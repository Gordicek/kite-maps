package app.kite

import android.Manifest
import android.app.PictureInPictureParams
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Rect
import android.util.Rational
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Alignment
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import app.kite.ui.*
import app.kite.core.PidRealtime
import app.kite.core.TransitLive
import app.kite.core.Transitous
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

class MainActivity : ComponentActivity() {
    var inPip by mutableStateOf(false)
        private set
    private var pipAvailable = false
    private var pipPortrait = false
    private val compass by lazy { PhoneCompass(this, model::headingChanged) }

    private fun pipParams(enterAutomatically: Boolean): PictureInPictureParams {
        val params = PictureInPictureParams.Builder().setAspectRatio(if (pipPortrait) Rational(10, 16) else Rational(16, 10))
        val bounds = Rect()
        if (window.decorView.getGlobalVisibleRect(bounds) && !bounds.isEmpty) params.setSourceRectHint(bounds)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            params.setAutoEnterEnabled(enterAutomatically).setSeamlessResizeEnabled(false)
        }
        return params.build()
    }

    fun offerPip(eligible: Boolean, portrait: Boolean) {
        val available = eligible && packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
        if (pipAvailable == available && pipPortrait == portrait) return
        pipAvailable = available
        pipPortrait = portrait
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) runCatching { setPictureInPictureParams(pipParams(available)) }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && pipAvailable && !isInPictureInPictureMode) {
            runCatching { enterPictureInPictureMode(pipParams(false)) }
        }
    }

    override fun onPictureInPictureModeChanged(active: Boolean, configuration: Configuration) {
        super.onPictureInPictureModeChanged(active, configuration)
        inPip = active
        if (active && model.nav != null) {
            model.addingStop = false
            model.closeStopSearch(restore = false)
            if (model.placingStop) model.finishStopPin()
        }
        if (active && model.mapNavigator?.following == false) model.recenterNav()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Connectivity.start(applicationContext)
        LocationService.refreshPermission()
        Voice.warmUp()
        WazeWeb.install(this)
        handle(intent)
        setContent { KiteTheme { KiteScreen(model, this) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onResume() {
        super.onResume()
        LocationService.visible = true
        LocationService.refreshPermission()
        compass.start()
    }

    /** Out of sight and not driving: GPS off to save the battery (back on at onResume). */
    override fun onStop() {
        super.onStop()
        LocationService.visible = false
        LocationService.stop()
        compass.stop()
    }

    override fun onDestroy() {
        WazeWeb.uninstall(this)
        super.onDestroy()
    }

    /** Links shared from other apps, map links opened in Kite, and the Home, Work and Car shortcuts. */
    private fun handle(i: Intent?) {
        i ?: return
        when (i.action) {
            Intent.ACTION_SEND -> i.getStringExtra(Intent.EXTRA_TEXT)?.let { model.openLink(it) }
            Intent.ACTION_VIEW -> i.dataString?.let { model.openLink(it) }
            "app.kite.HOME" -> model.perform(LaunchKind.HOME)
            "app.kite.WORK" -> model.perform(LaunchKind.WORK)
            "app.kite.CAR" -> model.perform(LaunchKind.CAR)
            TransitReminders.OPEN_PLAN -> {
                if (!model.isGuiding) AppSettings.savedPlans.firstOrNull {
                    it.id == i.getStringExtra(TransitReminders.PLAN)
                }?.let(model::openSavedPlan)
            }
        }
        // The screenshot scripts, for the build's emulator only: on a real phone another app could
        // otherwise start Kite in a demo that mutes the voice.
        if (onEmulator) i.getStringExtra("demo")?.let { DemoScript.start(model, it) }
    }

    private val onEmulator: Boolean
        get() = android.os.Build.HARDWARE in setOf("ranchu", "goldfish") || android.os.Build.PRODUCT.startsWith("sdk_")

    /** The screen stays on while driving, like every sat nav. */
    fun keepScreenOn(on: Boolean) {
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    companion object {
        /** One model for the app's life, so turning the phone or switching apps keeps everything as it was. */
        val model by lazy { AppModel() }
    }
}

@Composable
fun KiteScreen(model: AppModel, activity: MainActivity) {
    val ctx = activity
    val s = AppSettings
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val inPip = activity.inPip
    LaunchedEffect(s.placeShortcuts) {
        val places = s.placeShortcuts
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { QuickLaunch.sync(activity.applicationContext, places) }
        }
    }
    val kiteMap = remember { KiteMap(model, ctx.applicationContext).also { DemoScript.kite = it } }
    val dark = LocalDark.current
    SideEffect {
        val bars = androidx.core.view.WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        bars.isAppearanceLightStatusBars = !dark
        bars.isAppearanceLightNavigationBars = !dark
    }
    val scenery = remember(kiteMap) { MapboxScenery(model, kiteMap).also { kiteMap.scenery = it } }

    // The map's own location dot follows the same fixes the drive uses.
    DisposableEffect(Unit) {
        val modelUpdate = LocationService.onUpdate
        LocationService.onUpdate = { f -> modelUpdate?.invoke(f); kiteMap.pushFix(f) }
        model.glide.onMapPuck = { feature ->
            scenery.updateOverlay(NavGlide.PUCK) {
                feature?.toJson() ?: """{"type":"FeatureCollection","features":[]}"""
            }
        }
        onDispose {
            LocationService.onUpdate = modelUpdate
            model.glide.onMapPuck = null
            model.glide.cameraOutput = null
        }
    }
    val driving = model.nav?.route?.mode == app.kite.core.TravelMode.DRIVE || model.cruise != null
    LaunchedEffect(Unit) { OfflineMaps.load() }
    val offlineBasemap = model.offlineMapShown || !Connectivity.online && OfflineMaps.areas.values.any { it.complete }
    val mapboxToken = s.activeMapboxToken.takeUnless { offlineBasemap }
    val satellite = s.satellite && !offlineBasemap
    LaunchedEffect(model.offlineFocus) {
        val (at, zoom) = model.offlineFocus ?: return@LaunchedEffect
        delay(120)
        model.map?.let { map ->
            MapCam.animate(map, org.maplibre.android.camera.CameraPosition.Builder().target(at.ml()).zoom(zoom).build(), ms = 220)
        }
        model.offlineFocus = null
    }
    var clock by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(s.mapStyleMode) {
        while (s.mapStyleMode == MapStyleMode.SUN) {
            clock = System.currentTimeMillis()
            kotlinx.coroutines.delay(60_000)
        }
    }
    val mapDark = when (s.mapStyleMode) {
        MapStyleMode.SYSTEM -> dark
        MapStyleMode.LIGHT -> false
        MapStyleMode.DARK -> true
        MapStyleMode.SUN -> app.kite.core.Daylight.isDark(LocationService.last?.coord ?: model.viewport.first, clock)
    }
    val standardToken = mapboxToken.takeIf { s.standardMap && s.welcomed }
    LaunchedEffect(standardToken, satellite) { scenery.allowRetry() }
    val usableStandardToken = standardToken.takeUnless { scenery.failed }
    val standardReady = usableStandardToken != null && scenery.ready
    val showingMapbox = standardReady || kiteMap.showingMapbox
    val look = MapboxScenery.Look(mapDark, satellite, s.terrain3D && model.indoorView == null, s.terrain, s.buildingShadows && !driving, s.streetLamps, s.traffic && !driving,
        if (mapDark) s.nightMapPalette else s.dayMapPalette, driving, indoor = model.indoorView != null)
    LaunchedEffect(look) { scenery.setLook(look) }
    LaunchedEffect(mapDark, driving, satellite, mapboxToken, standardReady) {
        kiteMap.applyBasemap(mapDark, driving, satellite && !standardReady, mapboxToken.takeUnless { standardReady })
    }
    LaunchedEffect(s.markerTheme) { kiteMap.refreshMarkerTheme(); scenery.refreshMarkerTheme() }
    LaunchedEffect(s.googleIconPercent, s.osmIconPercent, s.resultIconPercent, s.wazeMapPercent) {
        kiteMap.refreshPinSizes()
        scenery.refreshPinSizes()
    }
    LaunchedEffect(s.googleMapPlaces, s.openMapPlaces, s.openPlaceGroups, s.navigationPlaces, s.navigationPlaceSource, s.navigationPlaceGroups, s.hikingTrails, s.bikeTrails, s.transitLines, s.noGoogle, model.tab, model.mapNavigator != null, model.transitGuidance != null, model.directions != null, model.selected != null, model.results.isEmpty(), model.cruise != null) {
        kiteMap.refreshGooglePlaces()
        scenery.showTransit(s.transitLines && model.nav == null && model.transitGuidance == null)
        LocationService.last?.let(scenery::updatePosition)
    }

    val journey = model.transitGuidance
    val transitTrip = journey?.trip ?: model.directions?.takeIf { it.transit }?.pickedTrip
    LaunchedEffect(transitTrip?.id, journey?.legIndex, journey?.phase, s.pidAccessToken) {
        model.transitLive = emptyMap()
        model.refreshedTransit = null
        val trip = transitTrip ?: return@LaunchedEffect
        if (journey?.phase == TransitGuidance.Phase.COMPLETE) return@LaunchedEffect
        val candidates = trip.legs.drop(journey?.legIndex ?: 0).filter { !it.isWalk }.distinctBy { it.tripId }
        if (candidates.isEmpty()) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var nextWholeRefresh = 0L
            while (true) {
                val now = System.currentTimeMillis()
                val visibleRides = candidates.filter { leg ->
                    val depart = leg.depart ?: return@filter false
                    now in (depart - 55 * 60_000)..((leg.arrive ?: depart) + 35 * 60_000)
                }.take(4)
                val details = coroutineScope {
                    val refreshed = async {
                        if (now < nextWholeRefresh || visibleRides.isEmpty()) null else {
                            nextWholeRefresh = now + 65_000
                            Transitous.refresh(trip)
                        }
                    }
                    val current = visibleRides.map { original ->
                        async {
                            val id = original.tripId ?: return@async null
                            val operator = async { PidRealtime.forLeg(original, s.pidAccessToken) }
                            val status = withTimeoutOrNull(11_000) { Transitous.live(original) }
                            val fromItinerary = (refreshed.await() ?: model.refreshedTransit)?.rides?.firstOrNull { it.tripId == id }
                            val scheduled = (fromItinerary ?: original).calls
                            val board = status?.board?.takeIf { it.departureDelay != null }
                                ?: scheduled.firstOrNull()?.takeIf { it.departureDelay != null } ?: status?.board ?: scheduled.firstOrNull()
                            val exit = status?.exit?.takeIf { it.arrivalDelay != null }
                                ?: scheduled.lastOrNull()?.takeIf { it.arrivalDelay != null } ?: status?.exit ?: scheduled.lastOrNull()
                            val pid = operator.await()
                            val regional = if (pid?.board == null && status?.cancelled != true && board?.departureDelay == null)
                                withTimeoutOrNull(7_500) { Transitous.localDeparture(original) } else null
                            val merged = (status ?: TransitLive(null, null, false, original.notices)).copy(
                                board = pid?.board ?: regional ?: board,
                                exit = exit,
                                position = pid?.position ?: status?.position,
                                gps = pid?.position != null || status?.gps == true,
                                calls = status?.calls?.takeIf { it.isNotEmpty() } ?: scheduled,
                                cancelled = pid?.cancelled == true || status?.cancelled == true || fromItinerary?.cancelled == true,
                                notices = (status?.notices.orEmpty() + fromItinerary?.notices.orEmpty() + original.notices).distinct(),
                            )
                            id to merged
                        }
                    }.awaitAll().filterNotNull().toMap()
                    refreshed.await() to current
                }
                details.first?.let { model.refreshedTransit = it }
                model.transitLive = details.second
                delay(35_000)
            }
        }
    }
    val previewRoute = model.directions?.route
    LaunchedEffect(previewRoute?.id) { model.prepareRoadControls(previewRoute) }
    val sceneryRoute = model.nav?.route ?: previewRoute
    val scenerySection = ((model.nav?.progress?.alongMeters ?: 0.0) / 1800.0).toInt()
    LaunchedEffect(sceneryRoute?.id, scenerySection, standardReady, satellite, s.streetLamps) {
        val route = sceneryRoute.takeIf { standardReady && !satellite }
        if (route != null) kotlinx.coroutines.delay(1_200)
        scenery.prepareRoute(route, scenerySection)
    }
    LaunchedEffect(model.nav) { model.nav?.let(kiteMap::seedRoadAhead) }

    // Redraw the map's layers whenever anything they show changes.
    LaunchedEffect(Unit) {
        snapshotFlow {
            listOf(model.mapTick, model.results, model.selected?.id, model.streetView != null, model.directions, model.pathSectionHighlight, model.mapNavigator, model.mapNavigator?.following, model.transitGuidance?.legIndex, model.transitGuidance?.overviewing, model.transitLive, model.stopSearch, model.measurement, model.nav?.routeWaze, model.mapNavigator?.roadControls, model.nav?.roadTraffic, model.openNowOnly, model.rating4Only,
                s.traffic, s.satellite, s.terrain, s.transitLines, s.speedCameras, s.plateCameras, s.wazeOnMap, s.wazeHeatmap,
                s.home, s.work, s.parking, s.saved, LocationService.authorized, model.tab,
                s.welcomed, s.noGoogle, s.surveillanceCameras, s.wazeOn, s.wazeKinds, s.wazeMaxAge,
                s.liveCams, s.liveRoadCams, s.liveWebcams, LiveCamStore.version, model.cruise, s.driveMarker, model.routeReports,
                s.aheadSignals, s.aheadStops, s.aheadRail, s.aheadCrossings, s.aheadWaze, s.aheadCameras, s.aheadTraffic, s.roadWazeKinds, s.navigationPlaces, s.navigationPlaceSource, s.navigationPlaceGroups)
        }.collect { kiteMap.render() }
    }
    // Kept on while driving; once arrived the phone goes in a pocket, so the screen may sleep (like the iPhone).
    val screenOn = model.transitGuidance?.let { it.phase != TransitGuidance.Phase.COMPLETE }
        ?: model.nav?.let { !it.arrived } ?: (model.cruise != null)
    val activeGuidance = model.nav?.let { !it.arrived } == true ||
        model.transitGuidance?.let { it.phase != TransitGuidance.Phase.COMPLETE } == true
    val autoPip = activeGuidance && s.navigationPictureInPicture
    LaunchedEffect(autoPip, s.portraitPip) { activity.offerPip(autoPip, s.portraitPip) }
    LaunchedEffect(screenOn, inPip) { activity.keepScreenOn(screenOn && !inPip) }
    LaunchedEffect(s.wazeOn, s.wazeOnMap, s.wazeHeatmap) {
        if (!s.wazeOn) WazeWeb.shutDown()
        kiteMap.refreshWaze()
    }

    val ready = s.welcomed && !s.needsWazeChoice
    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        LocationService.refreshPermission()
        if (r[Manifest.permission.ACCESS_FINE_LOCATION] == false && r[Manifest.permission.ACCESS_COARSE_LOCATION] == false) LocationService.denied = true
    }
    LaunchedEffect(ready) {
        if (!ready) return@LaunchedEffect
        LocationService.refreshPermission()
        if (!LocationService.authorized) {
            val list = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33) list += Manifest.permission.POST_NOTIFICATIONS
            perms.launch(list.toTypedArray())
        }
    }

    val activeProfileRoute = model.mapNavigator?.route
    LaunchedEffect(activeProfileRoute?.id, s.preloadRouteProfile) {
        if (s.preloadRouteProfile && activeProfileRoute != null) {
            delay(6500)
            model.routeProfiles.load(activeProfileRoute)
        }
    }
    LaunchedEffect(model.profileProbe, model.stationEntrance) { kiteMap.refreshProfileProbe() }
    LaunchedEffect(model.selected?.id, Connectivity.online) { model.probeIndoor(model.selected) }
    LaunchedEffect(model.indoorView) { kiteMap.refreshIndoor() }
    LaunchedEffect(model.streetViewFrame) { kiteMap.refreshStreetView() }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val panorama = model.streetView
        val mapPane = if (panorama == null) maxHeight else maxHeight * if (model.streetViewMapExpanded) 0.58f else 0.30f
        model.mapWidthDp = maxWidth.value
        model.mapHeightDp = mapPane.value
        model.glide.compactViewport = inPip
        model.glide.heightDp = maxHeight.value
        model.glide.widthDp = maxWidth.value
        AndroidView(
            modifier = Modifier.align(Alignment.BottomCenter).height(mapPane).fillMaxWidth(),
            factory = { c -> KiteMapSurface(c, lifecycle, kiteMap, scenery, model) },
            onReset = null,
            onRelease = { it.dispose() },
            update = { it.show(usableStandardToken, standardReady, look) },
        )

        val nav = model.nav
        CompositionLocalProvider(
            LocalMapboxAttribution provides if (standardReady) scenery::openAttribution else null,
        ) {
            when {
                inPip -> NavigationPictureInPicture(model, showingMapbox)
                panorama != null -> StreetViewScreen(model, panorama, mapPane, showingMapbox)
                model.indoorView != null -> IndoorFloorOverlay(model, requireNotNull(model.indoorView))
                model.profileRoute != null -> RouteProfileOverlay(model, requireNotNull(model.profileRoute))
                model.placingEndpoint != null -> EndpointPinOverlay(model, showingMapbox) { scenery.centerPoint() ?: kiteMap.centerPoint() }
                model.placingStop -> StopPinOverlay(model, showingMapbox) { scenery.centerPoint() ?: kiteMap.centerPoint() }
                model.stopSearch != null -> StopMapOverlay(model, showingMapbox)
                model.transitGuidance != null -> TransitNavigationOverlay(model, requireNotNull(model.transitGuidance), showingMapbox)
                nav != null -> NavigationOverlay(model, nav, showingMapbox)
                model.cruise != null -> model.cruise?.let { CruiseOverlay(model, it, showingMapbox) }
                model.directions != null -> DirectionsOverlay(model, showingMapbox)
                else -> when (model.tab) {
                    AppTab.MAP -> BrowseOverlay(model, showingMapbox)
                    AppTab.SAVED -> SavedPage(model)
                    AppTab.SETTINGS -> SettingsPage(model)
                }
            }
        }
        if (!inPip && panorama == null && model.measurement != null && !model.placingStop && model.stopSearch == null && model.placingEndpoint == null && model.tab == AppTab.MAP) {
            DistanceMeasure(model, Modifier.align(androidx.compose.ui.Alignment.CenterStart))
        }
        if (!inPip && nav != null && !nav.arrived && model.stopSearch == null && !model.addingStop && !model.placingStop) {
            model.navigationPlace?.let { place -> NavigationPlaceCard(model, place, Modifier.align(Alignment.BottomCenter)) }
        }
        val info = model.wazeInfo
        if (!inPip && panorama == null && info != null && model.tab == AppTab.MAP && model.stopSearch == null && !model.placingStop) {
            val placement = if (nav == null) Modifier else Modifier.align(androidx.compose.ui.Alignment.Center)
            WazeInfoToast(model, info, placement)
        }
        val cam = model.liveCam
        if (!inPip && panorama == null && cam != null && nav == null && model.tab == AppTab.MAP) {
            LiveCamCard(model, cam)
        }
    }

    if (inPip) return

    model.surveillanceInfo?.let { cam ->
        val details = listOfNotNull(cam.operatorName?.let { "Operator: $it" }, cam.manufacturer?.let { "Manufacturer: $it" },
            cam.surveillance?.let { "Watches: $it" }, cam.mount?.let { "Mount: $it" }, cam.direction?.let { "Direction: $it" }).joinToString("\n")
        AlertDialog(onDismissRequest = { model.surveillanceInfo = null },
            confirmButton = { TextButton({ model.surveillanceInfo = null }) { Text("OK") } },
            title = { Text(if (cam.isALPR) "Plate reader" else "Surveillance camera") },
            text = { Text(details.ifEmpty { "Details not mapped in OpenStreetMap." }) })
    }
    if (model.showLayers) LayersSheet(model.wazeLiveCount) { model.showLayers = false }
    if (model.addingStop) StopPicker(model)
    if (model.pickingOrigin || model.pickingDestination) EndpointPicker(model, model.pickingDestination)
    model.picking?.let { PlacePicker(model, it) }
    model.shortcutDraft?.let { ShortcutNameDialog(model, it) }
    if (!s.welcomed || s.needsWazeChoice) {
        if (s.welcomed) WazeIntroScreen() else WelcomeScreen()
    }
    if (DemoScript.step == "icons") DemoIcons()
}
