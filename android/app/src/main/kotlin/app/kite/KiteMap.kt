package app.kite

import android.content.Context
import android.graphics.Color
import android.graphics.PointF
import android.graphics.RectF
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.maplibre.android.MapLibre
import org.maplibre.android.WellKnownTileServer
import app.kite.core.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.expressions.Expression.*
import org.maplibre.android.style.layers.*
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.android.style.sources.TileSet
import org.maplibre.android.style.sources.VectorSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon
import kotlin.math.*

/**
 * The full-screen map: OpenFreeMap Liberty painted in Kite's colors, plus Kite's own layers
 * (result pins, routes with traffic, the navigation puck, live traffic, satellite). The same
 * layers, in the same order, as the iPhone app's map.
 */
class KiteMap(private val model: AppModel, private val context: Context) {
    companion object {
        const val INDOOR = "kite-indoor"
        const val PROFILE_PROBE = "kite-profile-probe"
        const val STREET_VIEW = "kite-street-view"
        const val CURRENT_POSITION = "kite-location"
        const val STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"
        /** How see-through cameras and reports off your route are while driving. */
        const val OFF_ROUTE_OPACITY = 0.3
        /** Reports that always keep their own icon, never joining a numbered bubble. */
        val NEVER_GROUPED = setOf(WazeAlert.Kind.POLICE, WazeAlert.Kind.ACCIDENT)
        const val GOOGLE_DOTS = "kite-google-dots"
        const val AMBIENT_DOT_MIN_ZOOM = 11.8
        const val AMBIENT_DOT_MAX_ZOOM = 14.45
        const val AMBIENT_DOT_FADE = """["interpolate",["linear"],["zoom"],11.8,["case",["==",["get","reviewed"],true],0.74,0.42],12.9,["case",["==",["get","reviewed"],true],0,0.52],14.45,0]"""
        fun ambientMarkerZoom(reviewed: Boolean) = if (reviewed) 12.65 else 13.85
        fun ambientLabel(reviewed: Boolean) = """["step",["zoom"],"",${if (reviewed) 13.05 else 14.35},["get","name"]]"""
        fun ambientIconSize(scale: Double) =
            """["interpolate",["linear"],["zoom"],12,${scale * 0.66},15,${scale * 0.83},19,$scale]"""
        const val GOOGLE_UNREVIEWED = "kite-google-unreviewed"
        const val TRANSIT_STOPS = "kite-transit-stops"
        internal fun routeWidth(standard: Double, google: Double = 6.5): String =
            """["case",["==",["get","googleDrive"],true],$google,$standard]"""
        internal fun transitPaint(dark: Boolean) = """["to-color",["get","color${if (dark) "Night" else ""}"]]"""
        internal const val ARROW_SCALE = """["coalesce",["get","scale"],1]"""
        internal const val ARROW_WIDTH = """["coalesce",["get","width"],10]"""
        internal const val ARROW_FILL = """["to-color",["coalesce",["get","fill"],"#FFFFFF"]]"""
        internal const val ARROW_EDGE = """["to-color",["coalesce",["get","edge"],"#0F766E"]]"""
        const val TRANSIT_STOPS_ZOOM = 12.5
        const val RIDE_STOPS = "kite-ride-stops"
        const val TRANSIT_STOP_RADIUS = """["case",["==",["get","selected"],true],10,5.5]"""
        const val TRANSIT_STOP_STROKE = """["case",["==",["get","selected"],true],3.5,2]"""
        const val TRANSIT_VEHICLES = "kite-transit-vehicles"
        private val classicHitSources = setOf("vela-mine", "vela-selected",
            "vela-results", "vela-cameras", "vela-livecams")
        private val STOCK_POI = listOf("poi_r1", "poi_r7", "poi_r20", "poi_transit", "airport")
        private fun c(hex: Long, a: Float = 1f) = Color.argb((a * 255).toInt(), ((hex shr 16) and 0xFF).toInt(), ((hex shr 8) and 0xFF).toInt(), (hex and 0xFF).toInt())
        private fun hex(l: Long) = String.format("#%06X", l and 0xFFFFFF)
    }

    var scenery: MapboxScenery? = null
    private var syncingClassic = false
    private var lastClassicCamera = 0L

    private fun driveCamera(camera: CameraPosition, synchronize: Boolean) {
        val native = scenery?.takeIf { it.ready }
        if (synchronize) {
            map?.cancelTransitions()
            view?.removeCallbacks(verifyFollowingCamera)
            verifyingFollowing = false
            followingMisses = 0
            model.glide.markCameraVerified(false)
        }
        if (native == null) {
            syncFromScenery(camera, force = synchronize)
        } else {
            native.followCamera(camera, synchronize)
            val time = android.os.SystemClock.elapsedRealtime()
            if (synchronize || time - lastClassicCamera >= 950L) {
                lastClassicCamera = time
                syncFromScenery(camera, force = synchronize)
                queueViewportRefresh()
                native.refreshLamps()
            }
        }
        noteVisibleHeading(camera.bearing)
        if (synchronize) {
            val actual = if (native != null) native.cameraPosition() else map?.cameraPosition
            if (actual != null && NavGlide.matchesViewport(actual, camera))
                model.glide.markCameraVerified(arrowOnScreen(native, camera))
        }
        watchFollowingCamera()
    }

    private var followingMisses = 0
    private var verifyingFollowing = false
    private val verifyFollowingCamera: Runnable = object : Runnable {
        override fun run() {
            verifyingFollowing = false
            val surface = view ?: return
            val expected = model.glide.followingCamera ?: return
            if (!model.glide.mapTouched) {
                val native = scenery?.takeIf { it.ready }
                val actual = if (native != null) native.cameraPosition() else map?.cameraPosition
                if (actual != null && NavGlide.matchesViewport(actual, expected) && arrowOnScreen(native, expected)) {
                    model.glide.markCameraVerified(true)
                    followingMisses = 0
                } else if (surface.width > 0 && surface.height > 0) {
                    followingMisses++
                    model.glide.markCameraVerified(false)
                    if (followingMisses >= 2 && model.glide.recoverWithCenteredViewport()) return
                    // Retry the current GPS camera even if there is no second fix yet.
                    map?.cancelTransitions()
                    native?.followCamera(expected, takeover = true)
                    syncFromScenery(expected, force = true)
                }
            }
            if (view === surface) watchFollowingCamera()
        }
    }

    private fun arrowOnScreen(native: MapboxScenery?, camera: CameraPosition): Boolean {
        if (native != null) return native.arrowOnScreen(camera)
        val surface = view ?: return false
        if (surface.width == 0 || surface.height == 0) return false
        val target = camera.target ?: return false
        val pixel = map?.projection?.toScreenLocation(target) ?: return false
        return NavGlide.matchesArrowPosition(pixel.x.toDouble(), pixel.y.toDouble(), surface.width, surface.height,
            camera.padding, 12.0 * surface.resources.displayMetrics.density)
    }

    fun watchFollowingCamera() {
        val surface = view ?: return
        if (verifyingFollowing || model.glide.followingCamera == null) return
        verifyingFollowing = surface.postDelayed(verifyFollowingCamera, 180)
    }

    val viewportMoving: Boolean get() = model.glide.mapTouched || classicMoving || scenery?.viewportMoving == true
    private var classicMoving = false
    private val viewportRefresh = Runnable {
        val m = map ?: return@Runnable
        if (viewportMoving) return@Runnable
        loadCameras(m)
        loadWazeCameras(m)
        loadSurveillance(m)
        loadWaze(m)
        loadLiveCams(m)
        loadRail(m)
        loadGooglePlaces(m)
        loadOutdoor(m)
        if (railPublishPending) {
            railPublishPending = false
            if (model.settings.transitLines) setShape(m.style, "vela-rail", railFeatures)
        }
        render()
    }

    fun queueViewportRefresh(delayMillis: Long = 115) {
        val target = view ?: return
        target.removeCallbacks(viewportRefresh)
        target.postDelayed(viewportRefresh, delayMillis)
    }

    fun syncFromScenery(camera: CameraPosition, force: Boolean = false) {
        val m = map ?: return
        if (!force && m.cameraPosition == camera) return
        syncingClassic = true
        try {
            m.moveCamera(org.maplibre.android.camera.CameraUpdateFactory.newCameraPosition(camera))
        } finally { syncingClassic = false }
    }

    fun refreshProfileProbe() {
        val style = map?.style?.takeIf { styleReady }
        setShape(style, PROFILE_PROBE, listOfNotNull((model.profileProbe ?: model.stationEntrance)?.let { point(it, emptyMap()) }))
    }

    fun refreshIndoor() {
        val style = map?.style?.takeIf { styleReady }
        val view = model.indoorView
        for (id in listOf("building-3d", "kite-mapbox-buildings", "building-extrusion")) {
            style?.let { visible(it, id, view == null) }
        }
        val features = if (view == null) emptyList() else buildList {
            fun ring(points: List<LatLng>) = points.map { Point.fromLngLat(it.lng, it.lat) }
            fun polygon(points: List<LatLng>, holes: List<List<LatLng>>, kind: String, selected: Boolean) {
                add(Feature.fromGeometry(Polygon.fromLngLats(listOf(ring(points)) + holes.map(::ring))).apply {
                    addStringProperty("role", "area")
                    addStringProperty("kind", kind)
                    addBooleanProperty("picked", selected)
                })
            }
            view.plan.building?.let { polygon(it, emptyList(), "footprint", false) }
            val floor = view.plan.features.filter { view.floor in it.floors }
            for (item in floor.sortedBy { when (it.kind) { "level" -> 0; "area", "corridor" -> 1; else -> 2 } }) {
                if (item.polygon) polygon(item.geometry, item.holes, item.kind, item == view.feature)
                else if (item.geometry.size > 1) add(polyline(item.geometry, mapOf("role" to "area", "kind" to item.kind)))
                val label = item.name ?: continue
                val icon = when (item.kind) {
                    "toilets" -> "osm-toilets"
                    "elevator" -> "indoor-elevator"
                    "stairs" -> "indoor-stairs"
                    else -> if (item.category in listOf("cafe", "restaurant", "food_court")) "cat-food" else "cat-shop"
                }
                add(point(item.center, mapOf("role" to "label", "label" to label, "icon" to icon, "picked" to (item == view.feature))))
            }
            if (view.guide.size >= 2) add(polyline(view.guide, mapOf("role" to "guide")))
        }
        setShape(style, INDOOR, features)
    }

    fun refreshStreetView() {
        val style = map?.style?.takeIf { styleReady }
        val frame = model.streetViewFrame
        val features = if (frame == null) emptyList() else buildList {
            val sweep = (-30..30 step 10).map { angle ->
                Geo.destination(frame.at, frame.heading + angle.toDouble(), 38.0)
            }
            val ring = (listOf(frame.at) + sweep + frame.at).map { Point.fromLngLat(it.lng, it.lat) }
            add(Feature.fromGeometry(Polygon.fromLngLats(listOf(ring))))
            add(point(frame.at, mapOf("role" to "position")))
            frame.links.forEach { add(point(it, mapOf("role" to "link"))) }
        }
        setShape(style, STREET_VIEW, features)
    }

    fun pointAt(x: Float, y: Float): LatLng? = map?.projection?.fromScreenLocation(PointF(x, y))?.kite()
    fun centerPoint(): LatLng? = view?.let { pointAt(it.width / 2f, it.height / 2f) }

    fun sceneTap(point: LatLng, collapseResults: Boolean = true): Boolean =
        map?.let { tapped(it, point.ml(), collapseResults) } ?: false
    fun openWazeAlert(id: String?): Boolean {
        val alert = model.nav?.routeWaze?.firstOrNull { it.alert.id == id }?.alert
            ?: model.nav?.liveAlerts?.firstOrNull { it.id == id } ?: wazeAlerts.firstOrNull { it.id == id } ?: model.routeReports.firstOrNull { it.id == id } ?: return false
        if (!(if (model.nav == null) AppSettings.shows(alert) else AppSettings.showsOnRoad(alert))) return false
        model.wazeInfo = alert
        return true
    }

    fun scenePress(point: LatLng) { map?.let { pressed(it, point.ml()) } }

    fun refreshScenery() {
        routeContext = ""
        lastRoadNameKey = ""
        transitStopsKey = null
        vehicleKey = ""
        layerOptions = null
        policeHeatKey = null
        classicBadges.clear()
        lastRouteBadges = null
        lastResultsKey = ""; lastRouteKey = ""; lastMineKey = ""; lastCameraKey = ""
        lastWazeKey = null; lastLiveKey = ""; lastSignalsKey = ""; lastHeadKey = ""; lastNextRoadKey = ""; lastPuckKey = ""
        shownStopPins = null
        shownMeasurement = null
        shownGooglePlaces = null
        shownTransitStops = null
        map?.let(::loadGooglePlaces)
        map?.style?.let { setShape(it, "vela-rail", railFeatures) }
        if (model.streetViewFrame != null) refreshStreetView()
        refreshProfileProbe()
        refreshIndoor()
        render()
    }

    fun openSearchResult(id: String?): Boolean {
        val place = model.visibleResults.firstOrNull { it.id == id } ?: return false
        model.select(place)
        return true
    }

    fun googlePlace(id: String?): Place? = googlePlaces.firstOrNull { it.id == id }

    fun openGooglePlace(id: String?): Boolean {
        val place = googlePlaces.firstOrNull { it.id == id } ?: return false
        model.select(place)
        return true
    }

    var dark = true
        private set
    private var map: MapLibreMap? = null
    private var view: MapView? = null
    private val scope get() = model.scope
    private var styleReady = false
    private var mapPalette = MapPalette.default(true)
    private var selectedStyle: Basemap? = null
    private var loadingStyle: Basemap? = null
    var showingMapbox by mutableStateOf(false)
        private set
    private val overlayFont get() = arrayOf(if (showingMapbox) "Open Sans Bold" else "Noto Sans Bold")

    private data class Basemap(val url: String, val token: String? = null, val json: String? = null)
    private var movedToUser = false
    private var pinDroppedAt = 0L
    private val originalLabels = mutableMapOf<String, Triple<PropertyValue<*>, PropertyValue<*>, PropertyValue<*>>>()
    private var lastResultsKey = ""
    private var lastRouteKey = ""
    private var lastMineKey = ""
    private var lastPuckKey = ""
    private var lastHeadKey = ""
    private var lastNextRoadKey = ""
    private var lastRoadNameKey = ""
    private var styledDriving: Boolean? = null
    private var styledGuidance: Boolean? = null
    private var lastCameraKey = ""
    private var lastSignalsKey = ""
    private val cameras = mutableMapOf<Long, RoadCamera>()
    private val cameraCells = mutableSetOf<String>()
    private var cameraFetch: Job? = null
    private var wazeCameraFetch: Job? = null
    private var surveillanceFetch: Job? = null
    private var surveillanceWasOn = false
    private val surveillanceCells = mutableSetOf<String>()
    private val surveillance = mutableMapOf<Long, SurveillanceCamera>()
    private var railFetch: Job? = null
    private var railRetryAt = 0L
    private var wazeAlerts: List<WazeAlert> = emptyList()
    /** Screenshot runs only: made-up reports stay put instead of being replaced by live ones. */
    private var fakeWaze = false

    fun showDemoWaze(list: List<WazeAlert>) {
        fakeWaze = true
        wazeAlerts = list
        wazeAt = now()
        model.wazeLiveCount = list.size
        model.updateAreaRisk(list)
        lastWazeKey = null
        render()
    }
    private var wazeBox: Box? = null
    private var wazeAt = 0L
    private var wazeFetch: Job? = null
    private var wazeFailedAt = 0L
    private var wazeAskedAt = 0L
    val heatTiles = HighwayHeat(model.scope)
    private var wazeRetryQueued = false
    private var lastWazeKey: List<Any?>? = null
    private var googlePlaces = emptyList<Place>()
    private var shownGooglePlaces: List<Place>? = null
    private var shownTransitStops: List<Place>? = null
    private var layerOptions: List<Any>? = null
    private var railFeatures = emptyList<Feature>()
    private var transitStops = emptyList<Place>()
    private var transitStopsJob: Job? = null
    private data class ViewRequest(val center: LatLng, val at: Long)
    private var stopRequest: ViewRequest? = null
    private var stopRequestWide = false
    private var shownStopPins: Pair<List<StopCandidate>, String?>? = null
    private var googlePlacesJob: Job? = null
    private data class PlaceArea(val center: LatLng, val terms: List<String>, val driving: Boolean)
    private data class PlaceBatch(val places: List<Place>, val expiresAt: Long)
    private var googleArea: PlaceArea? = null
    private var googleRetryAt = 0L
    private val googlePlacesCache = object : LinkedHashMap<PlaceArea, PlaceBatch>(12, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<PlaceArea, PlaceBatch>?) = size > 12
    }
    private data class RailBox(val s: Double, val w: Double, val n: Double, val e: Double, val z: Int)
    private var railBox: RailBox? = null
    private var positionKey: Triple<LatLng?, Double?, Boolean>? = null

    private fun now() = System.currentTimeMillis()

    fun attach(v: MapView) {
        view = v
        // Pause frame-by-frame camera writes before the SDK has recognized the gesture.
        v.setOnTouchListener { _, event ->
            model.glide.mapTouched = when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> true
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> false
                else -> model.glide.mapTouched
            }
            false
        }
        v.getMapAsync { m ->
            if (view !== v) return@getMapAsync
            map = m
            model.map = m
            model.glide.cameraOutput = ::driveCamera
            model.glide.onMapPuck = { feature -> scenery?.updatePositionOverlay(NavGlide.PUCK, feature?.toJson()) }
            model.refreshPosition = ::refreshPosition
            model.glide.mapTouched = false
            model.nativeCamera = { camera, duration ->
                model.mapNavigator?.following == true ||
                    scenery?.takeIf { it.ready }?.animateCamera(camera, duration) == true
            }
            model.visibleCamera = { scenery?.takeIf { it.ready }?.cameraPosition() ?: m.cameraPosition }
            m.uiSettings.isCompassEnabled = false
            m.uiSettings.isLogoEnabled = false
            m.uiSettings.setAttributionMargins(24, 0, 0, 24)
            m.uiSettings.isAttributionEnabled = true
            if (model.glide.followingCamera != null) {
                model.glide.replayPuck()
                model.glide.restoreFollowingCamera()
            }
            else m.cameraPosition = CameraPosition.Builder().target(org.maplibre.android.geometry.LatLng(51.5074, -0.1278))
                .zoom(11.0).tilt(if (model.browseLook == MapLook.TILTED) 48.0 else 0.0).build()
            v.addOnDidFailLoadingMapListener {
                if (loadingStyle?.token != null) {
                    Toast.makeText(context, "Mapbox map unavailable. Using OpenFreeMap.", Toast.LENGTH_LONG).show()
                    loadBasemap(m, Basemap(STYLE_URL))
                }
            }
            val initial = selectedStyle ?: Basemap(STYLE_URL)
            selectedStyle = initial
            loadBasemap(m, initial)
            m.addOnMapClickListener { tapped(m, it) }
            m.addOnMapLongClickListener { pressed(m, it); true }
            var searchMapDragged = false
            fun stopFollowing() {
                if (!model.glide.mapTouched || syncingClassic) return
                searchMapDragged = true
                model.onUserMapGesture()
                model.mapNavigator?.following = false
                model.transitGuidance?.following = false
                model.stopTransitOverview()
            }
            // Off a route in 3D or 2D, dragging or turning the map stops following; zooming doesn't.
            m.addOnMoveListener(object : MapLibreMap.OnMoveListener {
                override fun onMoveBegin(detector: org.maplibre.android.gestures.MoveGestureDetector) {
                    stopFollowing()
                    if (model.nav == null) model.browseFollowing = false
                }
                override fun onMove(detector: org.maplibre.android.gestures.MoveGestureDetector) {}
                override fun onMoveEnd(detector: org.maplibre.android.gestures.MoveGestureDetector) {}
            })
            m.addOnRotateListener(object : MapLibreMap.OnRotateListener {
                override fun onRotateBegin(detector: org.maplibre.android.gestures.RotateGestureDetector) {
                    stopFollowing()
                    if (model.nav == null) model.browseFollowing = false
                }
                override fun onRotate(detector: org.maplibre.android.gestures.RotateGestureDetector) {}
                override fun onRotateEnd(detector: org.maplibre.android.gestures.RotateGestureDetector) {}
            })
            m.addOnScaleListener(object : MapLibreMap.OnScaleListener {
                override fun onScaleBegin(detector: org.maplibre.android.gestures.StandardScaleGestureDetector) { stopFollowing() }
                override fun onScale(detector: org.maplibre.android.gestures.StandardScaleGestureDetector) {}
                override fun onScaleEnd(detector: org.maplibre.android.gestures.StandardScaleGestureDetector) {}
            })
            m.addOnShoveListener(object : MapLibreMap.OnShoveListener {
                override fun onShoveBegin(detector: org.maplibre.android.gestures.ShoveGestureDetector) { stopFollowing() }
                override fun onShove(detector: org.maplibre.android.gestures.ShoveGestureDetector) {}
                override fun onShoveEnd(detector: org.maplibre.android.gestures.ShoveGestureDetector) {}
            })
            var lastFollowingRefresh = 0L
            m.addOnCameraMoveStartedListener {
                if (scenery?.ready != true) {
                    classicMoving = true
                    v.removeCallbacks(viewportRefresh)
                }
            }
            m.addOnCameraIdleListener {
                classicMoving = false
                if (scenery?.ready == true) return@addOnCameraIdleListener
                watchFollowingCamera()
                if (searchMapDragged) {
                    searchMapDragged = false
                    val camera = m.cameraPosition
                    camera.target?.let { model.noteSearchArea(it.kite(), camera.zoom) }
                }
                // Camera writes during following can fire idle every frame. Layer queries and
                // network scheduling should run at a much lower rate during those updates.
                if (model.mapNavigator?.following == true) {
                    val at = android.os.SystemClock.elapsedRealtime()
                    if (at - lastFollowingRefresh < 1_400L) return@addOnCameraIdleListener
                    lastFollowingRefresh = at
                }
                noteHeading(m)
                if (scenery?.ready != true) refreshRouteBadges()
                queueViewportRefresh()
            }
            m.addOnCameraMoveListener {
                if (!syncingClassic) {
                    noteHeading(m)
                    if (model.mapNavigator?.following != true || scenery?.ready != true) scenery?.syncFromKite(m.cameraPosition)
                }
            }
            WazeHistory.onChange = { render() }
            LiveCamStore.onChange = { render() }
            LocationService.last?.let(::pushFix)
        }
    }

    fun detach(v: MapView) {
        if (view !== v) return
        googlePlacesJob?.cancel()
        badgeAnchorJob?.cancel()
        badgeRoutes = emptyList()
        transitStopsJob?.cancel()
        railFetch?.cancel()
        railFetch = null
        v.removeCallbacks(viewportRefresh)
        v.removeCallbacks(verifyFollowingCamera)
        verifyingFollowing = false
        followingMisses = 0
        classicMoving = false
        renderPosted = false
        loadingStyle = null
        if (model.map === map) {
            model.glide.cameraOutput = null
            model.glide.onMapPuck = null
            model.refreshPosition = null
            model.nativeCamera = null
            model.visibleCamera = null
            model.map = null
        }
        map = null
        view = null
        styleReady = false
    }

    fun refreshWaze() {
        val m = map ?: return
        loadWaze(m)
        loadWazeCameras(m)
        render()
    }

    // Style

    fun applyBasemap(dark: Boolean, satellite: Boolean, token: String?, palette: MapPalette) {
        val appearanceChanged = this.dark != dark || mapPalette != palette
        this.dark = dark
        mapPalette = palette
        val id = if (dark) "dark-v11" else "streets-v12"
        val wanted = when {
            satellite -> Basemap("satellite", json = SatelliteStyle.json(mapbox = false))
            token == null -> Basemap(STYLE_URL)
            else -> Basemap("mapbox://styles/mapbox/$id", token)
        }
        if (wanted != selectedStyle) {
            selectedStyle = wanted
            map?.let { loadBasemap(it, wanted) }
        } else if (appearanceChanged) {
            repaint()
            render()
        }
    }

    private fun loadBasemap(m: MapLibreMap, basemap: Basemap) {
        val pending = basemap.copy()
        loadingStyle = pending
        styleReady = false
        if (basemap.token != null) MapLibre.getInstance(context, basemap.token, WellKnownTileServer.Mapbox)
        val builder = Style.Builder().apply {
            if (basemap.json == null) fromUri(basemap.url) else fromJson(basemap.json)
        }
        m.setStyle(builder) { style ->
            if (map !== m || loadingStyle !== pending) return@setStyle
            loadingStyle = null
            showingMapbox = basemap.token != null
            try {
                styleLoaded(m, style)
            } catch (e: RuntimeException) {
                styleReady = false
                android.util.Log.w("KiteMap", "Could not install map overlays", e)
                if (basemap.url != STYLE_URL) loadBasemap(m, Basemap(STYLE_URL))
            }
        }
    }

    private fun styleLoaded(m: MapLibreMap, style: Style) {
        originalLabels.clear()
        for (l in style.layers) if (l is SymbolLayer) originalLabels[l.id] = Triple(l.textColor, l.textHaloColor, l.textHaloWidth)
        MapStyler.reset()
        MapIcons.register(style, dark)
        if (showingMapbox) addMapboxLayers(style)
        addVelaLayers(style)
        styleReady = true
        if (model.streetViewFrame != null) refreshStreetView()
        refreshProfileProbe()
        refreshIndoor()
        applyPinSizes(style)
        heatTiles.styleReloaded()
        styledDriving = null
        styledGuidance = null
        repaint()
        routeContext = ""
        lastRoadNameKey = ""
        transitStopsKey = null
        vehicleKey = ""
        layerOptions = null
        policeHeatKey = null
        classicBadges.clear()
        lastRouteBadges = null
        lastResultsKey = ""; lastRouteKey = ""; lastMineKey = ""; lastPuckKey = ""; lastHeadKey = ""; lastNextRoadKey = ""; lastCameraKey = ""; lastSignalsKey = ""; lastWazeKey = null; lastLiveKey = ""
        positionKey = null
        refreshPosition()
        model.glide.replayPuck()
        model.glide.restoreFollowingCamera()
        shownStopPins = null
        shownMeasurement = null
        shownGooglePlaces = null
        shownTransitStops = null
        layerOptions = null
        setShape(style, "vela-rail", railFeatures)
        render()
        loadCameras(m); loadWazeCameras(m); loadWaze(m); loadLiveCams(m); updateHeat(m)
        loadGooglePlaces(m)
        loadOutdoor(m)
    }

    fun refreshPosition() {
        val fix = LocationService.last.takeIf { LocationService.authorized }
        val onMap = model.mapNavigator == null && model.cruise == null && model.streetView == null
        val at = fix?.coord
        val heading = model.phoneHeading.takeIf { model.currentCompass && fix != null }
        val coneAt = if (heading == null || at == null) null else model.glide.visualPosition ?: at
        val key = Triple(if (onMap) at else coneAt, heading, onMap)
        if (key == positionKey) return
        positionKey = key
        val shapes = buildList {
            if (at != null && onMap) add(point(at, mapOf("role" to "position")))
            if (heading != null) coneAt?.let {
                add(point(it, mapOf("role" to "heading", "bearing" to heading)))
            }
        }
        val collection = FeatureCollection.fromFeatures(shapes)
        val style = map?.style?.takeIf { styleReady }
        (style?.getSource(CURRENT_POSITION) as? GeoJsonSource)?.setGeoJson(collection)
        scenery?.updatePositionOverlay(CURRENT_POSITION, collection.toJson())
    }

    /** Preserve each fix even if either renderer's style is still loading. */
    fun pushFix(f: Fix) {
        refreshPosition()
        moveToUserOnce(f)
    }

    /** Screenshot runs: the demo has put the map somewhere, so the first fix mustn't move it back. */
    fun keepCamera() { movedToUser = true }

    private fun moveToUserOnce(f: Fix) {
        val m = map ?: return
        if (movedToUser) return
        movedToUser = true
        if (model.streetView != null || model.isGuiding || model.results.isNotEmpty() || model.selected != null || model.directions != null) return
        val camera = CameraPosition.Builder(m.cameraPosition).target(f.coord.ml()).zoom(15.0).build()
        MapCam.animate(m, camera, ms = 0, native = model.nativeCamera)
    }

    private fun repaint() {
        val style = map?.style ?: return
        if (!styleReady) return
        if (!dark) {
            for (l in style.layers) if (l is SymbolLayer) originalLabels[l.id]?.let { l.setProperties(it.first, it.second, it.third) }
        }
        if (!showingMapbox && style.getSource("kite-imagery") == null) MapStyler.apply(style, dark, mapPalette)
        val language = AppSettings.language.locale.language.takeIf { it.matches(Regex("[a-z]{2,3}")) }
        if (language != null) {
            for (layer in style.layers.filterIsInstance<SymbolLayer>()) {
                if (layer.sourceLayer in setOf("place", "water_name", "transportation_name", "park")) {
                    layer.setProperties(textField(raw("""["coalesce",["get","name:$language"],["get","name_$language"],["get","name"]]""")))
                }
            }
        }
        if (!showingMapbox) MapStyler.guidanceLabels(style, model.isGuiding || model.cruise != null, dark)
        MapIcons.swapPuck(style, dark)
        (style.getLayer("vela-transit-ride") as? LineLayer)?.setProperties(lineColor(raw(transitPaint(dark))))
        (style.getLayer(RIDE_STOPS) as? CircleLayer)?.setProperties(circleColor(raw(transitPaint(dark))))
        (style.getLayer("vela-transit-walk") as? LineLayer)?.setProperties(lineColor(c(if (dark) 0xC8D6E6 else 0x5F6368)))
        (style.getLayer("vela-transit-edge") as? LineLayer)?.setProperties(lineColor(c(if (dark) 0x0B121C else 0xFFFFFF, 0.93f)))
        (style.getLayer("vela-rail-case") as? LineLayer)?.setProperties(lineColor(if (dark) c(0x000000, 0.9f) else c(0xFFFFFF, 0.9f)))
        val text = if (dark) c(0xE8EAED) else c(0x202124)
        val halo = if (dark) c(0x0B1526) else Color.WHITE
        for (id in listOf("kite-google-places", GOOGLE_UNREVIEWED, OpenMapStyle.POIS, TRANSIT_STOPS)) {
            (style.getLayer(id) as? SymbolLayer)?.setProperties(textColor(text), textHaloColor(halo),
                textHaloWidth(1.2f), textHaloBlur(0.28f), textSize(raw("""["interpolate",["linear"],["zoom"],11,11.5,15,13,18,14.5]""")))
        }
        for (id in listOf("vela-mine", "vela-results", "vela-selected")) {
            (style.getLayer(id) as? SymbolLayer)?.setProperties(textColor(text), textHaloColor(halo), textHaloWidth(1.5f))
        }
        (style.getLayer("$INDOOR-fill") as? FillLayer)?.setProperties(fillColor(raw(indoorFill(dark))))
        (style.getLayer("$INDOOR-labels") as? SymbolLayer)?.setProperties(textColor(text), textHaloColor(halo))
        (style.getLayer(OpenMapStyle.WALK_PATHS) as? LineLayer)?.setProperties(
            lineColor(c(if (dark) 0x75E8AD else 0x188858)), lineOpacity(if (dark) 0.99f else 0.82f),
            lineWidth(interpolate(linear(), zoom(), stop(14, 1.7f), stop(19, 3.8f))))
        (style.getLayer(OpenMapStyle.BIKE_PATHS) as? LineLayer)?.setProperties(
            lineColor(c(if (dark) 0x78CFFF else 0x3276C6)), lineOpacity(if (dark) 0.99f else 0.82f))
        for (id in OpenMapStyle.trailLayers.keys) {
            (style.getLayer(id) as? RasterLayer)?.setProperties(
                rasterOpacity(if (dark) 1.0f else 0.9f),
                rasterBrightnessMin(OpenMapStyle.trailBrightness(dark).toFloat()),
                rasterSaturation(if (dark) 0.14f else 0f))
        }
        val routeColor = mapPalette[MapColor.ROUTE]
        (style.getLayer("vela-route-line") as? LineLayer)?.setProperties(lineColor(raw(
            """["match",["get","level"],1,"#F59E0B",2,"#EF4444",3,"#B91C1C","$routeColor"]""")))
        val rgb = Color.parseColor(routeColor)
        (style.getLayer("vela-route-edge") as? LineLayer)?.setProperties(lineColor(
            Color.rgb(Color.red(rgb) / 3, Color.green(rgb) / 3, Color.blue(rgb) / 3)))
        lastResultsKey = ""
        lastRouteKey = ""
    }

    private fun firstSymbolLayer(style: Style): String? = style.layers.firstOrNull { it is SymbolLayer }?.id

    private fun line(id: String, src: String, color: Int, width: Float) = LineLayer(id, src).withProperties(
        lineColor(color), lineWidth(width), lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND))

    /** Icons close together join into one bubble up to zoom 13 (a whole town in view); closer in, every icon shows. */
    private fun Style.groupedSource(id: String) {
        if (getSource(id) == null) addSource(GeoJsonSource(id, FeatureCollection.fromFeatures(emptyList()),
            GeoJsonOptions().withCluster(true).withClusterRadius(44).withClusterMaxZoom(13)))
    }

    /** The numbered bubble for a group: a dark circle with a colored ring, bigger for more. */
    private fun groupBubbles(style: Style, id: String, ring: Int, from: Float = 11f) {
        val count = get("point_count")
        style.addLayer(CircleLayer("$id-bubble", id).withProperties(
            circleColor(Color.rgb(0x0F, 0x17, 0x2A)), circleOpacity(0.92f),
            circleStrokeColor(Color.rgb(ring shr 16 and 0xFF, ring shr 8 and 0xFF, ring and 0xFF)), circleStrokeWidth(3f),
            circleRadius(step(count, literal(14f), stop(10, 17f), stop(50, 21f)))).apply { minZoom = from; setFilter(has("point_count")) })
        style.addLayer(SymbolLayer("$id-count", id).withProperties(
            textField(Expression.toString(count)), textFont(overlayFont), textSize(13f),
            textColor(Color.WHITE), textAllowOverlap(true), textIgnorePlacement(true)).apply { minZoom = from; setFilter(has("point_count")) })
    }

    private fun mapboxTrafficLayers(style: Style) =
        style.layers.filterIsInstance<LineLayer>().filter { it.sourceLayer == "traffic" }

    private fun addMapboxLayers(style: Style) {
        val labels = firstSymbolLayer(style)
        val building = style.layers.filterIsInstance<FillLayer>().firstOrNull { it.sourceLayer == "building" }
        val extrusions = style.layers.filterIsInstance<FillExtrusionLayer>().filter { it.sourceLayer == "building" }
        if (!AppSettings.satellite && dark) {
            // Slate ground and lighter blue roofs make the building forms read above the roads.
            style.layers.filterIsInstance<BackgroundLayer>().forEach { it.setProperties(backgroundColor(c(0x263348))) }
            extrusions.forEach { it.setProperties(fillExtrusionColor(c(0x5B7191))) }
        }
        if (!AppSettings.satellite && building != null && extrusions.isEmpty()) {
            val extrusion = FillExtrusionLayer("kite-mapbox-buildings", building.sourceId).withSourceLayer("building")
                .withFilter(eq(get("extrude"), literal("true")))
                .withProperties(
                    fillExtrusionColor(c(if (dark) 0x5B7191 else 0xD8DFE7)),
                    fillExtrusionHeight(toNumber(get("height"))),
                    fillExtrusionBase(toNumber(get("min_height"))),
                )
            extrusion.minZoom = 15f
            if (labels == null) style.addLayer(extrusion) else style.addLayerBelow(extrusion, labels)
        }


        val trafficLayers = mapboxTrafficLayers(style)
        for (layer in trafficLayers) layer.setProperties(
            lineWidth(interpolate(linear(), zoom(), stop(9, 1.8f), stop(13, 2.9f), stop(17, 5.3f), stop(20, 7f))))
        if (trafficLayers.isEmpty()) {
            val id = "kite-mapbox-traffic"
            style.addSource(VectorSource(id, "mapbox://mapbox.mapbox-traffic-v1"))
            val traffic = LineLayer(id, id).withSourceLayer("traffic")
                .withFilter(neq(get("congestion"), literal("low")))
                .withProperties(
                    lineColor(match(get("congestion"), color(Color.TRANSPARENT),
                        stop("moderate", color(c(0xF59E0B))), stop("heavy", color(c(0xEF4444))),
                        stop("severe", color(c(0xB91C1C))))),
                    lineWidth(interpolate(linear(), zoom(), stop(8, 1.5f), stop(16, 3.5f))),
                    lineOffset(interpolate(linear(), zoom(), stop(8, 0.5f), stop(16, 2.5f))),
                    visibility(Property.NONE),
                )
            traffic.minZoom = 8f
            if (labels == null) style.addLayer(traffic) else style.addLayerBelow(traffic, labels)
        }
    }

    private fun googlePlacesEnabled() = !AppSettings.noGoogle && AppSettings.welcomed &&
        (AppSettings.googleMapPlaces || OpenMapStyle.googleNavigationActive(model))

    private fun showGooglePlaces(style: Style) {
        val s = model.settings
        val browsing = !model.isGuiding && model.cruise == null &&
            model.directions == null && model.tab == AppTab.MAP
        val driving = OpenMapStyle.googleNavigationActive(model) && model.stopSearch == null && !model.placingStop
        val googleVisible = when {
            !googlePlacesEnabled() -> emptyList()
            driving -> googlePlaces.filter { OpenMapStyle.groupFor(it) in s.navigationPlaceGroups }
            browsing && s.googleMapPlaces -> googlePlaces
            else -> emptyList()
        }
        val native = scenery?.ready == true
        val groups = OpenMapStyle.visibleGroups(model)
        val searchActive = model.searching || model.results.isNotEmpty()
        val googleLayer = driving || browsing && s.googleMapPlaces && !searchActive
        val config = listOf(groups, s.hikingTrails, s.bikeTrails, s.footpaths, s.cycleways, native, googleLayer)
        if (layerOptions != config) {
            layerOptions = config
            for (id in STOCK_POI) visible(style, id, false)
            visible(style, OpenMapStyle.POIS, !native && groups.isNotEmpty())
            (style.getLayer(OpenMapStyle.POIS) as? SymbolLayer)?.setFilter(raw(OpenMapStyle.filter(groups)))
            visible(style, OpenMapStyle.HIKING, !native && s.hikingTrails)
            visible(style, OpenMapStyle.CYCLING, !native && s.bikeTrails)
            visible(style, OpenMapStyle.WALK_PATHS, !native && s.footpaths)
            visible(style, OpenMapStyle.BIKE_PATHS, !native && s.cycleways)
            for (id in listOf(GOOGLE_DOTS, GOOGLE_UNREVIEWED, "kite-google-places")) visible(style, id, !native && googleLayer)
            scenery?.showAmbientPlaces(googleLayer)
            scenery?.applyOutdoorLayers()
        }
        fun features(places: List<Place>) = places.map { place ->
            val icon = when {
                place.id.startsWith("transit:") -> "osm-transit"
                OutdoorData.kind(place.category.orEmpty()) == OutdoorData.WATER -> "osm-drinking_water"
                OutdoorData.kind(place.category.orEmpty()) == "bicycle_parking" -> "osm-bicycle_parking"
                else -> MapIcons.ambientName(place)
            }
            point(place.location, mapOf("id" to place.id, "icon" to icon, "name" to place.name,
                "color" to MapIcons.categoryTint(place), "category_en" to place.category, "reviewed" to AmbientPlaces.reviewed(place),
                "priority" to AmbientPlaces.rank(place)))
        }
        if (shownGooglePlaces != googleVisible) {
            shownGooglePlaces = googleVisible
            setShape(style, "kite-google-places", features(googleVisible))
        }
        val stops = if (!model.isGuiding && s.transitLines) transitStops else emptyList()
        if (shownTransitStops != stops) {
            shownTransitStops = stops
            setShape(style, TRANSIT_STOPS, features(stops))
        }
    }

    fun refreshMarkerTheme() {
        val style = map?.style?.takeIf { styleReady } ?: return
        MapIcons.register(style, dark)
        MapIcons.registerMapPlaces(style, selectedStyle?.json != null)
    }

    fun refreshPinSizes() { map?.style?.let(::applyPinSizes) }

    private fun applyPinSizes(style: Style) {
        val waze = AppSettings.wazeMapPercent.coerceIn(60, 160) / 100f
        for (id in listOf("vela-waze", "vela-waze-group")) {
            (style.getLayer(id) as? SymbolLayer)?.setProperties(iconSize(
                interpolate(linear(), zoom(), stop(11, 0.62f * waze), stop(15, 0.95f * waze))))
        }
        (style.getLayer("vela-cameras") as? SymbolLayer)?.setProperties(iconSize(
            interpolate(linear(), zoom(), stop(6, 0.6f * waze), stop(9, 0.7f * waze), stop(13, 0.9f * waze), stop(16, 1.05f * waze))))
        val sizes = MapPinSizes.read()
        for (id in listOf("kite-google-places", GOOGLE_UNREVIEWED))
            (style.getLayer(id) as? SymbolLayer)?.setProperties(iconSize(raw(ambientIconSize(sizes.google))))
        (style.getLayer(OpenMapStyle.POIS) as? SymbolLayer)?.setProperties(iconSize((0.55 * sizes.osm).toFloat()))
        (style.getLayer("vela-results") as? SymbolLayer)?.setProperties(iconSize((0.72 * sizes.search).toFloat()),
            textOffset(arrayOf((1.45 * sizes.search).toFloat(), 0f)))
        (style.getLayer("kite-search-hit") as? CircleLayer)?.setProperties(circleRadius((14 * sizes.search).toFloat()))
    }

    fun seedRoadAhead(nav: Navigator) {
        val reports = wazeAlerts.takeIf { wazeAt > 0 && now() - wazeAt <= 180_000 && !fakeWaze }.orEmpty()
        nav.seedWazeReports(reports)
    }

    fun refreshGooglePlaces() {
        val m = map ?: return
        m.style?.let(::showGooglePlaces)
        loadGooglePlaces(m)
        loadOutdoor(m)
        loadRail(m)
    }

    fun openOutdoorPlace(id: String?): Boolean {
        val place = transitStops.firstOrNull { it.id == id } ?: return false
        model.select(place)
        return true
    }

    private fun loadOutdoor(m: MapLibreMap) {
        val s = model.settings
        if (viewportMoving || model.tab != AppTab.MAP || model.isGuiding) return
        val camera = model.visibleCamera?.invoke() ?: m.cameraPosition
        val center = camera.target?.kite() ?: return
        val time = now()
        fun due(request: ViewRequest?, distance: Double) =
            request == null || (time - request.at >= 1_200 &&
                (request.center.distanceTo(center) > distance || time - request.at >= 240_000))
        val wide = camera.zoom < 13.5
        if (s.transitLines && camera.zoom >= TRANSIT_STOPS_ZOOM && transitStopsJob?.isActive != true &&
            (wide != stopRequestWide || due(stopRequest, if (wide) 1_700.0 else 850.0))) {
            val request = ViewRequest(center, time)
            stopRequest = request
            stopRequestWide = wide
            transitStopsJob = scope.launch {
                delay(300)
                val found = withContext(Dispatchers.Default) {
                    Transitous.stops(OutdoorData.bounds(center, if (wide) 4_000.0 else 2_200.0), grouped = true)
                        ?.sortedBy { it.at.distanceTo(center) }?.distinctBy { it.parentId ?: it.id }
                        ?.take(if (wide) 65 else 100)?.map {
                            Place("transit:${it.parentId ?: it.id}", it.name, it.at, "transitous", category = "Transit stop")
                        }
                }
                if (map !== m || stopRequest != request) return@launch
                if (found != null) transitStops = found else stopRequest = request.copy(at = now() - 230_000)
                if (viewportMoving) queueViewportRefresh() else render()
            }
        }
    }

    private fun loadGooglePlaces(m: MapLibreMap) {
        if (viewportMoving) return
        val driving = OpenMapStyle.googleNavigationActive(model) && model.stopSearch == null && !model.placingStop
        val browsing = !model.isGuiding && model.cruise == null && AppSettings.googleMapPlaces && model.directions == null
            && !model.searching && model.results.isEmpty()
        val camera = model.visibleCamera?.invoke() ?: m.cameraPosition
        if (!googlePlacesEnabled() || camera.zoom < AMBIENT_DOT_MIN_ZOOM || !(driving || browsing) || model.tab != AppTab.MAP) {
            if (googlePlacesJob?.isActive == true) {
                googlePlacesJob?.cancel()
                googleArea = null
            }
            return
        }
        val terms = if (driving) OpenMapStyle.navigationGoogleQueries() else listOf("places", "restaurants", "coffee", "shops", "gas stations")
        if (terms.isEmpty()) return
        val center = camera.target?.kite() ?: return
        val request = PlaceArea(center, terms, driving)
        val previous = googleArea
        val distance = if (driving) 1_200.0 else 850.0
        val radius = if (driving) 4_200.0 else 3_600.0
        val time = now()
        fun compatible(area: PlaceArea) = area.driving == driving && area.terms == terms
        fun nearby(area: PlaceArea) = compatible(area) && area.center.distanceTo(center) < distance
        if (previous?.let(::nearby) == true && (googlePlacesJob?.isActive == true || time < googleRetryAt)) return

        googlePlacesJob?.cancel()
        googleArea = request
        val key = googlePlacesCache.keys.filter(::nearby).minByOrNull { it.center.distanceTo(center) }
        val cached = googlePlacesCache[key]
        val old = googlePlaces.takeIf { previous?.let(::compatible) == true }.orEmpty()
        googlePlacesJob = scope.launch {
            suspend fun publish(retained: List<Place>, fresh: List<Place>): List<Place> {
                val visible = withContext(Dispatchers.Default) { AmbientPlaces.merge(retained, fresh, center, radius, 120) }
                if (map === m && googleArea == request && googlePlaces != visible) {
                    googlePlaces = visible
                    // Native GeoJSON is queued during a gesture and becomes visible on idle.
                    if (viewportMoving) queueViewportRefresh() else render()
                }
                return visible
            }
            if (cached != null && cached.expiresAt > time) {
                publish(old, cached.places)
                if (map === m && googleArea == request) googleRetryAt = cached.expiresAt
                return@launch
            }
            val found = ArrayList<Place>()
            val batches = Channel<List<Place>>(terms.size)
            val gate = Semaphore(3)
            for (term in terms) launch(Dispatchers.IO) {
                val places = try {
                    gate.withPermit { GoogleClient.shared.search(term, center, if (driving) 5_500.0 else 5_000.0, pages = 1) }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    emptyList()
                }
                batches.send(places)
            }
            var shown = publish(old, cached?.places.orEmpty())
            var lastPublish = 0L
            repeat(terms.size) { batch ->
                found += batches.receive()
                val finished = batch == terms.lastIndex
                val at = now()
                if (!finished && (found.isEmpty() || at - lastPublish < 135)) return@repeat
                shown = publish(shown, found)
                lastPublish = at
            }
            if (map !== m || googleArea != request) return@launch
            googleRetryAt = now() + if (found.isEmpty()) 15_000 else 240_000
            if (found.isNotEmpty()) googlePlacesCache[request] = PlaceBatch(shown, googleRetryAt)
        }
    }

    private fun addVelaLayers(style: Style) {
        fun grouped(id: String) = style.groupedSource(id)
        fun src(id: String, metrics: Boolean = false) {
            if (style.getSource(id) == null) style.addSource(GeoJsonSource(id, FeatureCollection.fromFeatures(emptyList()),
                GeoJsonOptions().withLineMetrics(metrics)))
        }
        MapIcons.registerMapPlaces(style, AppSettings.satellite)
        style.addSource(GeoJsonSource("kite-google-places", FeatureCollection.fromFeatures(emptyList()), GeoJsonOptions().withMaxZoom(18)))
        style.addLayer(CircleLayer(GOOGLE_DOTS, "kite-google-places").withProperties(
            circleRadius(2.7f), circleColor(toColor(get("color"))),
            circleOpacity(raw(AMBIENT_DOT_FADE)), circleStrokeWidth(0f)).apply {
                minZoom = AMBIENT_DOT_MIN_ZOOM.toFloat(); maxZoom = AMBIENT_DOT_MAX_ZOOM.toFloat()
            })
        fun googleMarkers(id: String, reviewed: Boolean) = SymbolLayer(id, "kite-google-places").withProperties(
            iconImage(get("icon")), iconSize(raw(ambientIconSize(MapPinSizes.read().google))),
            symbolSortKey(get("priority")), iconPadding(2f), textPadding(2f), textMaxWidth(13f),
            textOptional(true), textField(raw(ambientLabel(reviewed))), textFont(overlayFont),
            textSize(12f), textColor(c(if (dark) 0xE8EAED else 0x202124)),
            textHaloColor(if (dark) c(0x101A29) else Color.WHITE), textHaloWidth(0.85f), textHaloBlur(0.55f),
            textAnchor(Property.TEXT_ANCHOR_TOP), textOffset(arrayOf(0f, 0.95f)),
        ).also { it.minZoom = ambientMarkerZoom(reviewed).toFloat(); it.setFilter(eq(get("reviewed"), literal(reviewed))) }
        style.addLayer(googleMarkers(GOOGLE_UNREVIEWED, false))
        src(RIDE_STOPS)
        style.addLayer(CircleLayer(RIDE_STOPS, RIDE_STOPS).withProperties(
            circleRadius(raw(TRANSIT_STOP_RADIUS)), circleColor(toColor(get("color"))),
            circleStrokeColor(Color.WHITE), circleStrokeWidth(raw(TRANSIT_STOP_STROKE))))
        src(TRANSIT_VEHICLES)
        style.addLayer(CircleLayer(TRANSIT_VEHICLES, TRANSIT_VEHICLES).withProperties(
            circleRadius(13f), circleColor(toColor(get("color"))), circleStrokeWidth(2f), circleStrokeColor(Color.WHITE)))
        style.addLayer(SymbolLayer("$TRANSIT_VEHICLES-label", TRANSIT_VEHICLES).withProperties(
            textField(get("line")), textFont(overlayFont), textSize(11f), textColor(Color.WHITE), textAllowOverlap(true)))
        src(TRANSIT_STOPS)
        style.addLayer(SymbolLayer(TRANSIT_STOPS, TRANSIT_STOPS).withProperties(
            iconImage(get("icon")), iconSize(0.74f), textField(get("name")), textFont(overlayFont),
            textSize(raw("""["interpolate",["linear"],["zoom"],12.5,10,16,12]""")), textOptional(true),
            textColor(if (dark) Color.WHITE else 0xFF22314A.toInt()),
            textHaloColor(if (dark) 0xFF172338.toInt() else Color.WHITE), textHaloWidth(1.3f),
            textAnchor(Property.TEXT_ANCHOR_TOP), textOffset(arrayOf(0f, 1.0f))
        ).also { it.minZoom = TRANSIT_STOPS_ZOOM.toFloat() })
        val openSource = if (style.getSource("openmaptiles") != null) "openmaptiles" else OpenMapStyle.SOURCE.also {
            style.addSource(VectorSource(it, OpenMapStyle.TILEJSON))
        }
        style.addLayer(SymbolLayer(OpenMapStyle.POIS, openSource).withSourceLayer("poi").withProperties(
            iconImage(raw(OpenMapStyle.icon)), iconSize(0.73f), iconPadding(4f), textOptional(true),
            textField(step(zoom(), literal(""), stop(15, coalesce(get("name"), literal(""))))),
            textFont(overlayFont), textSize(11f), textAnchor(Property.TEXT_ANCHOR_TOP), textOffset(arrayOf(0f, 1f))
        ).also { it.minZoom = 13f })
        style.addLayer(googleMarkers("kite-google-places", true))
        for ((id, kind) in OpenMapStyle.trailLayers) {
            style.addSource(RasterSource(id, TileSet("tiles", OpenMapStyle.trailTile(kind)).apply {
                minZoom = 6f; maxZoom = 18f; attribution = OpenMapStyle.ATTRIBUTION
            }, 256))
            val layer = RasterLayer(id, id).withProperties(rasterOpacity(0.9f), visibility(Property.NONE))
            firstSymbolLayer(style)?.let { style.addLayerBelow(layer, it) } ?: style.addLayer(layer)
        }
        for ((id, definition) in OpenMapStyle.pathLayers) {
            val (filter, tint) = definition
            val path = LineLayer(id, openSource).withSourceLayer("transportation")
                .withFilter(raw(filter)).withProperties(
                    lineColor(tint.toInt()), lineWidth(interpolate(linear(), zoom(), stop(14, 1.4f), stop(19, 3.2f))),
                    lineOpacity(0.82f), lineCap(Property.LINE_CAP_ROUND), visibility(Property.NONE),
                )
            path.minZoom = 14f
            firstSymbolLayer(style)?.let { style.addLayerBelow(path, it) } ?: style.addLayer(path)
        }
        style.addSource(RasterSource("vela-terrain", TileSet("tiles", "https://services.arcgisonline.com/ArcGIS/rest/services/Elevation/World_Hillshade/MapServer/tile/{z}/{y}/{x}")
            .apply { maxZoom = 16f; attribution = "Hillshade © Esri" }, 256))
        val hill = RasterLayer("vela-terrain", "vela-terrain").withProperties(rasterOpacity(if (dark) 0.22f else 0.32f), visibility(Property.NONE))
        style.layers.firstOrNull { it.id.startsWith("tunnel_") || it.id.startsWith("road_") || (it is LineLayer && it.sourceLayer == "road") }?.let { style.addLayerBelow(hill, it.id) } ?: style.addLayer(hill)

        // Google's live traffic tiles, the same keyless overlay Vela draws.
        val trafficURL = "https://www.google.com/maps/vt/pb=!1m4!1m3!1i{z}!2i{x}!3i{y}!2m9!1e2!2straffic!3i999999" +
            "!4m2!1sincidents!2s1!4m2!1sincidents_text!2s1!3m8!2sen!3sus!5e1105!12m4!1e68!2m2!1sset!2sRoadmap!4e0!5m1!1e0"
        style.addSource(RasterSource("vela-traffic", TileSet("tiles", trafficURL).apply { maxZoom = 16f }, 256))
        val trafficLayer = RasterLayer("vela-traffic", "vela-traffic").withProperties(rasterOpacity(0.88f), rasterFadeDuration(380f), visibility(Property.NONE))
        (style.getLayer("building-3d") ?: style.getLayer("building"))?.let { style.addLayerAbove(trafficLayer, it.id) } ?: style.addLayer(trafficLayer)

        val labels = if (showingMapbox) null else firstSymbolLayer(style)
        fun insert(l: Layer) { if (labels != null) style.addLayerBelow(l, labels) else style.addLayer(l) }
        src("vela-route-alt"); src("vela-route", metrics = true); src("vela-route-traffic")

        val altEdge = line("vela-route-alt-edge", "vela-route-alt", c(0x115E59), 11f).withProperties(lineOpacity(0.6f))
        val altFill = line("vela-route-alt", "vela-route-alt", c(0x5EEAD4), 7f).withProperties(lineOpacity(0.6f))
        // The light look: one plain teal line, no darker edge and no chevrons along it.
        val edge = line("vela-route-edge", "vela-route", c(if (showingMapbox) 0x075964 else 0x0F766E), 15f)
            .withProperties(lineOpacity(if (showingMapbox) 0.95f else 0f), lineWidth(raw(routeWidth(15.0, 9.0))))
        val fill = line("vela-route-line", "vela-route-traffic", c(0x2DD4BF), 10f)
            .withProperties(lineColor(raw("""["match",["get","level"],1,"#F59E0B",2,"#EF4444",3,"#B91C1C","#2DD4BF"]""")),
                lineCap(Property.LINE_CAP_BUTT), lineWidth(raw(routeWidth(10.0))))
        val chevrons = SymbolLayer("vela-route-chevrons", "vela-route").withProperties(
            symbolPlacement(Property.SYMBOL_PLACEMENT_LINE), symbolSpacing(70f), iconImage("route-chevron"),
            iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP), iconAllowOverlap(true), iconIgnorePlacement(true),
            iconKeepUpright(false), iconOpacity(0.55f), visibility(Property.NONE)).apply { minZoom = 13f }
        listOf(altEdge, altFill, edge, fill, chevrons).forEach { insert(it) }
        src("kite-path-section")
        listOf(line("kite-path-section-edge", "kite-path-section", Color.WHITE, 16f),
            line("kite-path-section-fill", "kite-path-section", c(0xE56C16), 10f)).forEach { insert(it) }

        src("vela-transit")
        val walk = line("vela-transit-walk", "vela-transit", c(if (dark) 0xC8D6E6 else 0x5F6368), 5f).withProperties(lineDasharray(arrayOf(0.1f, 1.8f)))
        walk.setFilter(eq(get("walk"), literal(1)))
        val rideEdge = line("vela-transit-edge", "vela-transit", c(if (dark) 0x0B121C else 0xFFFFFF, 0.93f), 11f)
        rideEdge.setFilter(eq(get("walk"), literal(0)))
        val ride = line("vela-transit-ride", "vela-transit", c(0x1F6FEB), 7f).withProperties(lineColor(raw(transitPaint(dark))))
        ride.setFilter(eq(get("walk"), literal(0)))
        listOf(walk, rideEdge, ride).forEach { insert(it) }
        src("vela-rail")
        val railCase = line("vela-rail-case", "vela-rail", if (dark) c(0x000000, 0.9f) else c(0xFFFFFF, 0.9f), 5.5f)
        val rail = line("vela-rail", "vela-rail", Color.GRAY, 3.2f).withProperties(lineColor(toColor(get("color"))))
        for (l in listOf(railCase, rail)) { l.minZoom = 10f; l.setProperties(visibility(Property.NONE)); insert(l) }

        src("kite-measure")
        style.addLayer(line("kite-measure", "kite-measure", c(0x8C61EF), 3.3f).withProperties(lineDasharray(arrayOf(2f, 1.4f))))
        style.addLayer(CircleLayer("kite-measure-points", "kite-measure").withFilter(eq(geometryType(), literal("Point")))
            .withProperties(circleColor(c(0x8C61EF)), circleRadius(5f), circleStrokeColor(Color.WHITE), circleStrokeWidth(2f)))

        src("vela-signals")
        style.addLayer(SymbolLayer("vela-signals", "vela-signals").withProperties(
            iconImage(get("icon")), iconAllowOverlap(true), iconAnchor(Property.ICON_ANCHOR_BOTTOM),
            iconSize(interpolate(linear(), zoom(), stop(14, 1.05f), stop(17, 1.25f)))).apply { minZoom = 14f })

        // Live camera photos, under the speed cameras: Windy webcams from zoom 6 (a region), road
        // cameras from zoom 9 (a city). Zoomed out over a busy city (central London) they join into
        // numbered blue bubbles, like the speed cameras (Matteo, 2026-11-13).
        grouped("vela-livecams")
        style.addLayer(SymbolLayer("vela-livecams", "vela-livecams").withProperties(
            iconImage(get("icon")), iconAllowOverlap(false), iconPadding(2f),
            iconSize(interpolate(linear(), zoom(), stop(6, 0.6f), stop(9, 0.7f), stop(13, 0.9f), stop(16, 1.05f))))
            .apply { minZoom = LiveCams.WEBCAM_MIN_ZOOM.toFloat(); setFilter(not(has("point_count"))) })
        groupBubbles(style, "vela-livecams", 0x3B82F6, LiveCams.WEBCAM_MIN_ZOOM.toFloat())

        // Zoomed out over a busy city, cameras close together join into one bubble with a number.
        grouped("vela-cameras")
        val big = eq(get("big"), literal(1))
        style.addLayer(SymbolLayer("vela-cameras", "vela-cameras").withProperties(
            iconImage(get("icon")), iconAllowOverlap(true),
            iconSize(interpolate(linear(), zoom(),
                stop(11, switchCase(big, literal(1.0), literal(0.7))),
                stop(14, switchCase(big, literal(1.33), literal(0.92))),
                stop(17, switchCase(big, literal(1.65), literal(1.15))))),
            // While driving, cameras off your route fade back (see renderCameras).
            iconOpacity(coalesce(get("op"), literal(1.0)))).apply { minZoom = 11f; setFilter(not(has("point_count"))) })
        groupBubbles(style, "vela-cameras", 0xFF8A00)

        src("vela-waze-heat")
        val heat = HeatmapLayer("vela-waze-heat", "vela-waze-heat").withProperties(
            visibility(Property.NONE), heatmapRadius(interpolate(linear(), zoom(), stop(5, 13f), stop(13, 32f), stop(17, 42f))),
            heatmapIntensity(1.3f), heatmapOpacity(0.7f),
            heatmapColor(interpolate(linear(), heatmapDensity(), stop(0, rgba(66, 98, 240, 0f)),
                stop(0.2, rgb(83, 116, 244)), stop(0.55, rgb(146, 89, 236)), stop(1, rgb(239, 118, 217)))))
            .apply { minZoom = 5f }
        style.getLayer("vela-route-alt-edge")?.let { style.addLayerBelow(heat, "vela-route-alt-edge") } ?: insert(heat)
        src("vela-waze")
        style.addLayer(SymbolLayer("vela-waze", "vela-waze").withProperties(
            iconImage(get("icon")), iconAllowOverlap(true),
            iconSize(interpolate(linear(), zoom(), stop(11, 0.62f), stop(15, 0.95f))),
            iconOpacity(get("op"))).apply { minZoom = 11f })
        // Every other report (closures, hazards, jams) joins into numbered bubbles when zoomed
        // out, so a busy city stays readable. Police and crashes above always stay on their own.
        grouped("vela-waze-group")
        style.addLayerBelow(SymbolLayer("vela-waze-group", "vela-waze-group").withProperties(
            iconImage(get("icon")), iconAllowOverlap(true),
            iconSize(interpolate(linear(), zoom(), stop(11, 0.62f), stop(15, 0.95f))),
            iconOpacity(get("op"))).apply { minZoom = 11f; setFilter(not(has("point_count"))) }, "vela-waze")
        groupBubbles(style, "vela-waze-group", 0xEF4444)

        src("vela-mine")
        style.addLayer(SymbolLayer("vela-mine", "vela-mine").withProperties(
            iconImage(get("icon")), iconAllowOverlap(true), textField(get("label")), textFont(overlayFont),
            textSize(11f), textAnchor(Property.TEXT_ANCHOR_TOP), textOffset(arrayOf(0f, 1.1f)), textOptional(true),
            textMaxWidth(8f)).apply { minZoom = 9f })

        src("vela-results")
        style.addLayer(CircleLayer("kite-search-hit", "vela-results").withProperties(circleRadius(14f),
            circleColor(c(0x164B9F, 0.12f))))
        style.addLayer(SymbolLayer("vela-results", "vela-results").withProperties(
            iconImage(get("icon")), iconSize(0.72f), iconAllowOverlap(true), textField(get("label")), textFont(overlayFont),
            textSize(12.5f), textAnchor(Property.TEXT_ANCHOR_LEFT), textOffset(arrayOf(1.45f, 0f)), textOptional(true),
            textMaxWidth(8f), textJustify(Property.TEXT_JUSTIFY_LEFT), symbolSortKey(get("rank"))))

        src(StopSearch.PINS)
        style.addLayer(SymbolLayer(StopSearch.PINS, StopSearch.PINS).withProperties(
            iconImage(get("icon")), iconAllowOverlap(true), iconIgnorePlacement(true),
            iconPadding(3f), symbolSortKey(get("priority"))))

        src(INDOOR)
        style.addLayer(FillLayer("$INDOOR-fill", INDOOR).withProperties(
            fillColor(raw("""["case",["==",["get","picked"],true],"#B1DEDB",["match",["get","kind"],"footprint","#F4F2EF","level","#F4F2EF","corridor","#FFFFFF","toilets","#D9DEF6","#E1EBEA"]]""")),
            fillOpacity(0.99f), fillOutlineColor(0xFF69807E.toInt())))
        style.addLayer(LineLayer("$INDOOR-lines", INDOOR).withFilter(eq(get("role"), literal("area"))).withProperties(
            lineColor(0xFF706384.toInt()), lineWidth(1.6f)))
        style.addLayer(line("$INDOOR-guide", INDOOR, c(0x197EED), 6f).withFilter(eq(get("role"), literal("guide"))))
        style.addLayer(SymbolLayer("$INDOOR-labels", INDOOR).withFilter(eq(get("role"), literal("label"))).withProperties(
            iconImage(get("icon")), iconSize(raw("""["case",["==",["get","picked"],true],1.22,0.98]""")),
            textAnchor(Property.TEXT_ANCHOR_TOP), textOffset(arrayOf(0f, 1.15f)),
            textOptional(true), textField(get("label")), textFont(overlayFont), textSize(12f), textColor(0xFF263B3A.toInt()),
            textHaloColor(Color.WHITE), textHaloWidth(1.3f)))
        src(PROFILE_PROBE)
        style.addLayer(CircleLayer(PROFILE_PROBE, PROFILE_PROBE).withProperties(
            circleRadius(8f), circleColor(0xFF7C3AED.toInt()), circleStrokeColor(Color.WHITE), circleStrokeWidth(3f)))
        src(STREET_VIEW)
        style.addLayer(FillLayer("$STREET_VIEW-cone", STREET_VIEW).withProperties(
            fillColor(c(0x0EA5A4)), fillOpacity(0.35f), fillOutlineColor(c(0x15807C))))
        style.addLayer(CircleLayer("$STREET_VIEW-walk", STREET_VIEW).withFilter(eq(get("role"), literal("link"))).withProperties(
            circleRadius(5f), circleColor(c(0x0EA5A4)), circleStrokeColor(Color.WHITE), circleStrokeWidth(1.7f)))
        style.addLayer(CircleLayer("$STREET_VIEW-position", STREET_VIEW).withFilter(eq(get("role"), literal("position"))).withProperties(
            circleRadius(8f), circleColor(c(0x127A79)), circleStrokeColor(Color.WHITE), circleStrokeWidth(2.4f)))
        src("vela-selected")
        style.addLayer(SymbolLayer("vela-selected", "vela-selected").withProperties(
            iconImage(get("icon")), iconAnchor(get("anchor")), iconAllowOverlap(true), textAllowOverlap(false),
            textField(get("label")), textFont(overlayFont), textSize(13f), textAnchor(Property.TEXT_ANCHOR_TOP),
            textOffset(arrayOf(0f, 0.4f)), textOptional(true)))

        src("vela-route-bubbles")
        style.addLayer(SymbolLayer("vela-route-bubble", "vela-route-bubbles").withProperties(
            iconImage(get("icon")), iconAllowOverlap(true), iconIgnorePlacement(true),
            iconOffset(get("offset")), iconPadding(5f), symbolSortKey(get("priority"))))

        src("vela-turn-arrow")
        val shaft = eq(get("part"), literal("shaft"))
        val arrowShadow = line("vela-turn-arrow-shadow", "vela-turn-arrow", Color.BLACK, 22f).withProperties(
            lineOpacity(0.24f), lineBlur(3f), lineTranslate(arrayOf(0f, 2f)),
            lineWidth(raw("""["+",${ARROW_WIDTH},5]""")))
        arrowShadow.setFilter(shaft)
        // A big white arrow with a teal edge, like Waze's, so it's clear at a glance.
        val arrowEdge = line("vela-turn-arrow-edge", "vela-turn-arrow", c(0x0F766E), 18f)
            .withProperties(lineColor(raw(ARROW_EDGE)), lineWidth(raw("""["+",${ARROW_WIDTH},4]""")))
        arrowEdge.setFilter(shaft)
        val arrowLine = line("vela-turn-arrow", "vela-turn-arrow", Color.WHITE, 12f)
            .withProperties(lineColor(raw(ARROW_FILL)), lineWidth(raw(ARROW_WIDTH)))
        arrowLine.setFilter(shaft)
        // The head has a source of its own, updated only when the turn changes: the map fades a symbol
        // in afresh each time its source changes, which would keep the head see-through.
        src("vela-turn-head")
        val head = SymbolLayer("vela-turn-arrow-head", "vela-turn-head").withProperties(
            iconImage(get("icon")), iconSize(raw(ARROW_SCALE)), iconRotate(get("bearing")), iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
            iconAllowOverlap(true), iconIgnorePlacement(true))
        listOf(arrowShadow, arrowEdge, arrowLine, head).forEach { style.addLayer(it) }

        // The name of the road you're about to turn onto, lit up in a teal pill on the road itself.
        // Its own source, updated only when the turn changes, like the head.
        src("vela-next-road")
        style.addLayer(SymbolLayer("vela-next-road", "vela-next-road").withProperties(
            iconImage("road-pill"), iconTextFit(Property.ICON_TEXT_FIT_BOTH), iconTextFitPadding(arrayOf(5f, 11f, 5f, 11f)),
            textField(get("label")), textFont(overlayFont), textSize(16f), textColor(c(0x06201C)),
            textPitchAlignment(Property.TEXT_PITCH_ALIGNMENT_VIEWPORT), iconAllowOverlap(true), textAllowOverlap(true)))

        // The road you're on, written on the road just ahead of the car like a painted marking
        // (Matteo's pick over a box on the screen). Lies flat and follows the road's bends.
        // The map's own named roads, drawn too faintly to see: read back in the driving view with no
        // route, so the name follows the road you're on round its bends.
        if (style.getSource("openmaptiles") != null) {
            style.addLayer(LineLayer("vela-road-query", "openmaptiles").apply {
                sourceLayer = "transportation_name"
                // Not quite 0: a layer that draws nothing at all can't be asked what's under it.
                setProperties(lineOpacity(0.01f), lineWidth(3f))
            })
        }
        src("vela-road-name")
        style.addLayer(SymbolLayer("vela-road-name", "vela-road-name").withProperties(
            textField(get("label")), symbolPlacement(Property.SYMBOL_PLACEMENT_LINE_CENTER),
            textPitchAlignment(Property.TEXT_PITCH_ALIGNMENT_MAP), textRotationAlignment(Property.TEXT_ROTATION_ALIGNMENT_MAP),
            textKeepUpright(true), textFont(arrayOf("Noto Sans Bold")),
            textSize(interpolate(linear(), zoom(), stop(13, 15f), stop(15, 18f), stop(17, 22f), stop(18, 26f))),
            textLetterSpacing(0.08f), textTransform(Property.TEXT_TRANSFORM_UPPERCASE), textColor(c(0x0E3B36)),
            textHaloColor("rgba(255,255,255,0.95)"), textHaloWidth(2f), textAllowOverlap(true), textIgnorePlacement(true)))

        src(CURRENT_POSITION)
        style.addLayer(SymbolLayer("$CURRENT_POSITION-heading", CURRENT_POSITION).withFilter(eq(get("role"), literal("heading"))).withProperties(
            iconImage("heading-cone"), iconRotate(get("bearing")), iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
            iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_VIEWPORT), iconAllowOverlap(true), iconIgnorePlacement(true)))
        style.addLayer(CircleLayer(CURRENT_POSITION, CURRENT_POSITION).withFilter(eq(get("role"), literal("position"))).withProperties(
            circleRadius(7f), circleColor(c(0x00A8C5)), circleStrokeColor(Color.WHITE), circleStrokeWidth(2.5f),
            circlePitchAlignment(Property.CIRCLE_PITCH_ALIGNMENT_VIEWPORT), circlePitchScale(Property.CIRCLE_PITCH_SCALE_VIEWPORT)))
        src(NavGlide.PUCK)
        style.addLayer(SymbolLayer(NavGlide.PUCK, NavGlide.PUCK).withProperties(
            iconImage(coalesce(get("icon"), literal("puck"))), iconRotate(get("bearing")), iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
            iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_VIEWPORT), iconAllowOverlap(true), iconIgnorePlacement(true)))
    }

    // Render

    /** The road a turn goes onto, for the pill on the map: "Stoneham Way" from "Turn left onto Stoneham Way". */
    private fun nextRoadName(m: Maneuver): String? {
        val text = SpokenText.forScreen(m.instruction)
        val at = text.lastIndexOf(" onto ")
        val road = m.road
        val name = when {
            at >= 0 -> text.substring(at + 6)
            !road.isNullOrEmpty() && text.contains(road) -> SpokenText.forScreen(road)
            else -> null
        }?.trim()?.trimEnd('.')?.trim()
        return name?.takeIf { it.isNotEmpty() && it.length <= 40 }
    }

    private fun setShape(style: Style?, id: String, features: List<Feature>) {
        val collection = FeatureCollection.fromFeatures(features)
        val native = scenery
        if (native?.ready != true || !native.renders(id) || id in classicHitSources)
            (style?.getSource(id) as? GeoJsonSource)?.setGeoJson(collection)
        native?.updateOverlay(id) { collection.toJson() }
    }

    private fun point(p: LatLng, props: Map<String, Any?> = emptyMap()) = Feature.fromGeometry(Point.fromLngLat(p.lng, p.lat)).also { f ->
        props.forEach { (k, v) -> when (v) {
            is String -> f.addStringProperty(k, v)
            is Number -> f.addNumberProperty(k, v)
            is Boolean -> f.addBooleanProperty(k, v)
        } }
    }

    fun polyline(pts: List<LatLng>, props: Map<String, Any?> = emptyMap()) =
        Feature.fromGeometry(LineString.fromLngLats(pts.map { Point.fromLngLat(it.lng, it.lat) })).also { f ->
            props.forEach { (k, v) -> when (v) { is String -> f.addStringProperty(k, v); is Number -> f.addNumberProperty(k, v); is Boolean -> f.addBooleanProperty(k, v) } }
        }

    private fun visible(style: Style, id: String, on: Boolean) {
        val layer = style.getLayer(id) ?: return
        val value = if (on) Property.VISIBLE else Property.NONE
        if (layer.visibility.value != value) layer.setProperties(visibility(value))
    }

    private var renderPosted = false
    fun render() {
        val target = view ?: return
        if (renderPosted) return
        renderPosted = true
        target.postOnAnimation {
            renderPosted = false
            if (target === view) renderNow()
        }
    }

    private fun renderNow() {
        val m = map ?: return
        val style = if (styleReady) m.style else null
        if (style == null) {
            refreshPosition()
            if (scenery?.ready == true) {
                renderResults(null)
                renderStopPins(null)
                renderRoutes(null)
                renderSignals(null)
                renderRoadName(null)
            }
            return
        }
        val s = model.settings
        val navigating = model.isGuiding
        val driving = model.nav?.route?.mode == TravelMode.DRIVE || model.cruise != null
        refreshPosition()
        // Not before the welcome screen's choice: "Open data only" means nothing ever went to Google.
        visible(style, "vela-traffic", !showingMapbox && s.traffic && !navigating && !s.noGoogle && s.welcomed)
        if (showingMapbox) {
            mapboxTrafficLayers(style).forEach { visible(style, it.id, s.traffic && !navigating) }
        }
        // The Surveillance layer just switched on: fetch it now, not at the next pan (only on the
        // switch, so a failed fetch keeps its 30-second pause).
        if (s.surveillanceCameras && !surveillanceWasOn) loadSurveillance(m)
        surveillanceWasOn = s.surveillanceCameras
        visible(style, "vela-terrain", s.terrain && !s.satellite)
        for (id in listOf("vela-rail", "vela-rail-case")) visible(style, id, s.transitLines && !navigating && scenery?.ready != true)
        val guidance = navigating || driving
        if (styledGuidance != guidance) {
            if (!showingMapbox) repaint()
            styledGuidance = guidance
        }
        if (styledDriving != driving) {
            if (!showingMapbox) MapStyler.drive(style, driving)
            styledDriving = driving
            (style.getLayer("vela-route-line") as? LineLayer)?.setProperties(lineWidth(raw(routeWidth(if (navigating) 9.0 else 10.0))))
            (style.getLayer("vela-route-edge") as? LineLayer)?.setProperties(lineWidth(raw(routeWidth(if (navigating) 13.0 else 15.0, 9.0))))
            visible(style, "vela-route-chevrons", showingMapbox && navigating)
        }
        if (!showingMapbox) for (id in listOf("building", "building-3d")) visible(style, id, !s.satellite && !driving)
        renderResults(style)
        renderStopPins(style)
        renderMeasure(style)
        renderRoutes(style)
        renderPuck(style)
        if (!viewportMoving) {
            renderCameras(style)
            renderLiveCams(m, style)
            renderWaze(style)
            updateHeat(m)
        }
        renderSignals(style)
        renderRoadName(style)
        renderMine(style)
        showGooglePlaces(style)
    }

    /**
     * The road's name on the road ahead: 40 to 220 m along the route while navigating (left off
     * when the next turn is close, where the turn arrow and the new road's name need the room),
     * or straight ahead along your heading in the driving view with no route.
     */
    private var cruiseRoadAt = 0L
    private fun renderRoadName(style: Style?) {
        var line: List<LatLng> = emptyList()
        var name: String? = null
        val n = model.nav
        val c = model.cruise
        if (n != null) {
            val p = n.progress
            val road = n.currentRoad
            val next = n.nextManeuver
            if (n.following && !n.arrived && p != null && road != null &&
                (next == null || next.alongMeters - p.alongMeters > 260 || next.type == ManeuverType.STRAIGHT)) {
                val cum = if (routeGeometry === n.route.polyline) routeDistances else Geo.cumulative(n.route.polyline)
                val total = cum.last()
                val a = p.alongMeters + 40; val b = minOf(p.alongMeters + 220, total)
                if (b - a > 80) { line = Geo.slice(n.route.polyline, a, b, cum); name = road }
            }
        } else if (c != null && model.browseFollowing) {
            val time = android.os.SystemClock.elapsedRealtime()
            if (time - cruiseRoadAt < 1_300L) return
            cruiseRoadAt = time
            val f = c.fix
            val road = c.road ?: c.ref
            val m = map
            if (f != null && f.courseKnown && f.speed > 2 && road != null && m != null) {
                // Along the map's own line for the road you're on, so it stays on the road round a bend;
                // nothing when the map doesn't have it here (never floating off over a field).
                val pt = m.projection.toScreenLocation(org.maplibre.android.geometry.LatLng(f.lat, f.lng))
                val density = context.resources.displayMetrics.density
                val box = RectF(pt.x - 300 * density, pt.y - 450 * density, pt.x + 300 * density, pt.y + 250 * density)
                val match = any(eq(get("name"), c.road ?: ""), eq(get("ref"), c.ref ?: ""))
                val lines = mutableListOf<List<LatLng>>()
                for (feat in m.queryRenderedFeatures(box, match, "vela-road-query")) {
                    when (val g = feat.geometry()) {
                        is LineString -> lines.add(g.coordinates().map { LatLng(it.latitude(), it.longitude()) })
                        is org.maplibre.geojson.MultiLineString -> g.coordinates().forEach { l -> lines.add(l.map { LatLng(it.latitude(), it.longitude()) }) }
                    }
                }
                RoadAhead.path(lines, f.coord, f.course)?.let { line = it; name = road }
            }
        }
        val key = (name ?: "") + (line.firstOrNull()?.let { "${(it.lat * 20_000).toInt()},${(it.lng * 20_000).toInt()}" } ?: "")
        if (key == lastRoadNameKey) return
        lastRoadNameKey = key
        setShape(style, "vela-road-name", if (name != null && line.size >= 2) listOf(polyline(line, mapOf("label" to name))) else emptyList())
    }

    private fun renderMine(style: Style) {
        val s = model.settings
        val hidden = model.isGuiding || model.directions != null
        val items = mutableListOf<Triple<Place, String, String>>()
        if (!hidden) {
            s.home?.let { items.add(Triple(it, "mine-home", "Home")) }
            s.work?.let { items.add(Triple(it, "mine-work", "Work")) }
            s.parking?.let { items.add(Triple(it, "mine-parking", "Parked car")) }
            for (p in s.saved.take(200)) if (items.none { it.first.id == p.id }) items.add(Triple(p, "mine-saved", p.name))
        }
        val selected = model.selected
        // The label too, so a renamed place shows its new name.
        val key = items.joinToString(",") { it.first.id + it.second + it.third } + "|" + (selected?.id ?: "")
        if (key == lastMineKey) return
        lastMineKey = key
        setShape(style, "vela-mine", items.filter { selected == null || !it.first.matchesNearby(selected) }.map { (p, icon, label) ->
            point(p.location, mapOf("id" to p.id, "icon" to icon, "label" to label))
        })
    }

    private fun renderCameras(style: Style) {
        val s = model.settings
        val line = model.nav?.route?.polyline ?: model.directions?.route?.polyline ?: emptyList()
        val waze = s.speedCameras && s.wazeOn
        // While driving, only what's on your way stands out: everything off the route fades back.
        val driving = model.nav?.route?.mode == TravelMode.DRIVE
        val onRouteCameras = model.nav?.routeCameras.orEmpty()
        val key = "${s.speedCameras}${s.plateCameras}${s.aheadCameras}${onRouteCameras.hashCode()}${s.surveillanceCameras}${surveillance.size}${cameras.size}${line.size}${line.firstOrNull()?.lat ?: 0}$waze${WazeCameras.shared.version}$driving"
        if (key == lastCameraKey) return
        lastCameraKey = key
        val mapped = (cameras.values + onRouteCameras.map { it.camera }).distinctBy { it.id }
            .filter { if (it.kind == RoadCamera.Kind.SPEED) s.speedCameras else s.plateCameras }
        // Waze's speed cameras join OpenStreetMap's; one both maps hold shows once.
        val shown = if (waze) WazeCameras.merge(mapped, WazeCameras.shared.known()) else mapped
        val routeIds = onRouteCameras.mapTo(HashSet()) { it.camera.id }
        val previewDistances = if (!driving && line.size >= 2) Geo.cumulative(line) else null
        val features = shown.mapNotNull { cam ->
            val onRoute = cam.id in routeIds ||
                (previewDistances != null && Geo.project(cam.location, line, previewDistances).offset < 40)
            if (driving && !s.aheadCameras && onRoute) return@mapNotNull null
            point(cam.location, mapOf("icon" to if (cam.kind == RoadCamera.Kind.SPEED) "cam-speed" else "cam-plate",
                "big" to if (onRoute) 1 else 0, "op" to if (driving && !onRoute) OFF_ROUTE_OPACITY else 1.0,
                "surveillanceId" to if (cam.kind == RoadCamera.Kind.PLATE) cam.id.toString() else ""))
        }.toMutableList()
        if (s.surveillanceCameras) {
            for (camera in surveillance.values) {
                if (camera.isALPR && s.plateCameras && cameras[camera.id] != null) continue
                features.add(point(camera.location, mapOf("icon" to if (camera.isALPR) "cam-plate" else "cam-surveillance", "big" to 0,
                    "op" to if (driving) OFF_ROUTE_OPACITY else 1.0, "surveillanceId" to camera.id.toString())))
            }
        }
        setShape(style, "vela-cameras", features)
    }

    private fun renderSignals(style: Style?) {
        val nav = model.mapNavigator
        val from = nav?.progress?.alongMeters ?: 0.0
        val walking = nav?.route?.mode == TravelMode.WALK
        val range = if (walking) 650 else 5000
        val controls = nav?.roadControls.orEmpty().filter {
            (nav?.progress?.offsetMeters ?: 0.0) <= 120 && (if (nav?.route?.mode == TravelMode.WALK) model.settings.showsWalkingControl(it.kind) else model.settings.showsControl(it.kind)) &&
                it.along in (from - 12)..(from + range) && (walking || it.kind != Overpass.ControlKind.CROSSING)
        }
        val key = "${nav?.route?.id}|${controls.hashCode()}"
        if (key == lastSignalsKey) return
        lastSignalsKey = key
        val line = nav?.route?.polyline.orEmpty()
        val cum = Geo.cumulative(line)
        val points = controls.map { it.kind to (it.at ?: Geo.point(line, it.along, cum)) }
        setShape(style, "vela-signals", points.map { (kind, at) ->
            val icon = when (kind) {
                Overpass.ControlKind.SIGNAL -> "traffic-light"
                Overpass.ControlKind.STOP -> "road-stop"
                Overpass.ControlKind.RAIL -> "road-crossing"
                Overpass.ControlKind.CROSSING -> "pedestrian-crossing"
            }
            point(at, mapOf("icon" to icon))
        })
    }

    private fun renderWaze(style: Style) {
        val s = model.settings
        val nav = model.nav
        val routeAlerts = nav?.routeWaze.orEmpty()
        val liveAlerts = nav?.liveAlerts.orEmpty()
        val previewAlerts = model.routeReports.takeIf { model.directions != null }.orEmpty()
        val driving = nav?.route?.mode == TravelMode.DRIVE
        val key = listOf(s.wazeOn, s.wazeOnMap, s.aheadWaze, s.wazeMaxAge, s.wazeKinds, s.roadWazeKinds,
            wazeAt, wazeAlerts, routeAlerts, liveAlerts, previewAlerts, nav != null, driving)
        if (key == lastWazeKey) return
        lastWazeKey = key
        val live = routeAlerts.map { it.alert } + liveAlerts + previewAlerts
        val ids = wazeAlerts.map { it.id }.toSet()
        val all = (wazeAlerts + live.filter { !ids.contains(it.id) }).distinctBy { it.id }
        val shown = if (!s.wazeOnMap) emptyList()
            else if (model.nav != null) all.filter(s::showsOnRoad) else all.filter(s::shows)
        val alertsOnRoute = routeAlerts.mapTo(HashSet()) { it.alert.id }
        fun feature(a: WazeAlert): Feature {
            val age = (now() - a.reportedAtMillis) / 1000.0
            var op = max(0.5, 1 - age / 14_400)
            if (driving && a.id !in alertsOnRoute) op = OFF_ROUTE_OPACITY
            return point(a.location, mapOf("id" to a.id, "icon" to "waze-${a.kind.key}", "op" to op))
        }
        // Police and crashes matter most, so they never get grouped into a bubble.
        val (alone, grouped) = shown.partition { it.kind in NEVER_GROUPED }
        setShape(style, "vela-waze", alone.map(::feature))
        setShape(style, "vela-waze-group", grouped.map(::feature))
    }

    /** Local Waze report density, plus Highway Radar's coverage on the classic map. */
    private var policeHeatKey: Pair<Boolean, List<WazeHistory.Sighting>>? = null
    fun updateHeat(m: MapLibreMap) {
        val s = model.settings
        val enabled = styleReady && s.wazeOn && s.wazeHeatmap && s.wazePolice
        if (scenery?.ready != true) heatTiles.update(m, enabled, "vela-waze-heat")
        val history = if (enabled) WazeHistory.all else emptyList()
        val key = enabled to history
        if (policeHeatKey == key) return
        policeHeatKey = key
        m.style?.let {
            visible(it, "vela-waze-heat", enabled && scenery?.ready != true)
            setShape(it, "vela-waze-heat", history.takeLast(2500).map { p -> point(LatLng(p.lat, p.lng)) })
        }
    }

    /** Live Waze reports for the view and a margin, asked again after two minutes or when the map leaves the box. */
    fun loadWaze(m: MapLibreMap) {
        val s = model.settings
        if (s.wazeOn && (s.wazeHeatmap || s.policeRisk)) {
            // Only around where you are: Waze is never asked about saved Home and Work.
            val places = listOfNotNull(LocationService.last?.coord)
            WazeHistory.sweepIfDue(places, scope)
        }
        if (fakeWaze || !s.wazeOn || !(s.wazeOnMap || s.wazeHeatmap) || m.cameraPosition.zoom < 10 || wazeFetch != null || now() - wazeFailedAt <= 60_000) return
        val waitMs = (if (model.nav != null) 90_000 else 5_000) - (now() - wazeAskedAt)
        if (waitMs > 0) {
            if (!wazeRetryQueued) {
                wazeRetryQueued = true
                scope.launch { delay(waitMs + 100); wazeRetryQueued = false; loadWaze(m) }
            }
            return
        }
        val b = m.projection.visibleRegion.latLngBounds
        wazeBox?.let { r ->
            if (b.latitudeSouth >= r.s && b.longitudeWest >= r.w && b.latitudeNorth <= r.n && b.longitudeEast <= r.e && now() - wazeAt < 120_000) return
        }
        val dLat = (b.latitudeNorth - b.latitudeSouth) * 0.3; val dLng = (b.longitudeEast - b.longitudeWest) * 0.3
        val box = Box(b.latitudeSouth - dLat, b.longitudeWest - dLng, b.latitudeNorth + dLat, b.longitudeEast + dLng)
        if (box.n - box.s >= 1.5 || box.e - box.w >= 2.5) return
        val mid = LatLng((b.latitudeNorth + b.latitudeSouth) / 2, (b.longitudeEast + b.longitudeWest) / 2)
        val hLat = min((box.n - box.s) / 2, 0.15); val hLng = min((box.e - box.w) / 2, 0.25)
        val liveBox = Box(mid.lat - hLat, mid.lng - hLng, mid.lat + hLat, mid.lng + hLng)
        val askLive = model.nav == null
        wazeAskedAt = now()
        wazeFetch = scope.launch {
            val closures = async { runCatching { Waze.alerts(box.s, box.w, box.n, box.e) }.getOrNull() }
            val live = if (askLive) runCatching { WazeLive.shared.alerts(listOf(liveBox), mid) }.getOrNull() else null
            val fromMap = closures.await()
            wazeFetch = null
            if (fakeWaze) return@launch
            if (fromMap == null && live == null) { Log.i("KiteTrace", "waze browse: both failed (Waze: ${Waze.lastStatus?.summary ?: "no answer"}; ${WazeWeb.note})"); wazeFailedAt = now(); return@launch }
            val merged = linkedMapOf<String, WazeAlert>()
            for (a in (fromMap ?: emptyList()) + (live ?: emptyList())) merged[a.id] = a
            val found = merged.values.toList()
            Log.i("KiteTrace", "waze browse: live map ${fromMap?.size ?: "failed"}, drivers' reports ${if (askLive) live?.size ?: "failed" else "not asked"}" +
                if (fromMap == null) " (Waze: ${Waze.lastStatus?.summary ?: "no answer"}; ${WazeWeb.note})" else "")
            wazeAlerts = found
            wazeBox = if (live != null) liveBox else box
            wazeAt = now()
            WazeHistory.record(found)
            model.wazeLiveCount = found.count { model.settings.shows(it) }
            model.updateAreaRisk(found)
            render()
        }
    }

    /** Fetch and publish a bounded rail overlay after camera motion settles. */
    fun loadRail(m: MapLibreMap) {
        if (!model.settings.transitLines || model.isGuiding || viewportMoving || railFetch?.isActive == true || now() < railRetryAt) return
        val camera = m.cameraPosition
        if (camera.zoom < 11.2) return
        val center = camera.target?.kite() ?: return
        val b = m.projection.visibleRegion.latLngBounds
        val z = camera.zoom.toInt().coerceIn(11, 14)
        val latMargin = ((b.latitudeNorth - b.latitudeSouth) * 0.85).coerceIn(0.012, 0.10)
        val lonMargin = ((b.longitudeEast - b.longitudeWest) * 0.85).coerceIn(0.016, 0.19)
        railBox?.let { box ->
            if (center.lat in (box.s + latMargin * 0.18)..(box.n - latMargin * 0.18) &&
                center.lng in (box.w + lonMargin * 0.18)..(box.e - lonMargin * 0.18) && abs(z - box.z) < 3) return
        }
        val box = RailBox((center.lat - latMargin).coerceAtLeast(-85.0), (center.lng - lonMargin).coerceAtLeast(-180.0),
            (center.lat + latMargin).coerceAtMost(85.0), (center.lng + lonMargin).coerceAtMost(180.0), z)
        if (box.s >= box.n || box.w >= box.e) return
        railFetch = scope.launch {
            val features = withContext(Dispatchers.Default) {
                val lines = Transitous.lines(box.s, box.w, box.n, box.e, z) ?: return@withContext null
                var budget = if (z <= 12) 3_000 else 5_000
                buildList<Feature> {
                    for (line in lines) {
                        for (piece in Transitous.trackPieces(line.points, box.s, box.w, box.n, box.e)) {
                            if (budget < 2 || size >= 140) return@buildList
                            val points = Overpass.thinTo(piece, min(budget, if (z <= 12) 42 else 80))
                            budget -= points.size
                            if (points.size >= 2) add(polyline(points, mapOf("color" to hex(line.color))))
                        }
                    }
                }
            }
            if (map !== m || !model.settings.transitLines) return@launch
            railRetryAt = now() + if (features == null) 15_000 else 1_500
            if (features != null) {
                railBox = box
                railFeatures = features
                if (viewportMoving) railPublishPending = true else setShape(m.style, "vela-rail", features)
            }
        }
    }

    private var railPublishPending = false

    private fun cells(m: MapLibreMap, done: Set<String>): List<Pair<Int, Int>>? {
        val b = m.projection.visibleRegion.latLngBounds; val cell = 0.1
        val y0 = floor(b.latitudeSouth / cell).toInt(); val y1 = floor(b.latitudeNorth / cell).toInt()
        val x0 = floor(b.longitudeWest / cell).toInt(); val x1 = floor(b.longitudeEast / cell).toInt()
        if (y1 < y0 || x1 < x0 || (y1 - y0 + 1) * (x1 - x0 + 1) > 12) return null
        val missing = mutableListOf<Pair<Int, Int>>()
        for (y in y0..y1) for (x in x0..x1) if (!done.contains("$y,$x")) missing.add(y to x)
        return missing.ifEmpty { null }
    }

    /** Separate OSM surveillance layer, opt-in; bounded to the same cells as camera lookups. */
    fun loadSurveillance(m: MapLibreMap) {
        if (!model.settings.surveillanceCameras || m.cameraPosition.zoom < 12 || surveillanceFetch != null) return
        val missing = cells(m, surveillanceCells) ?: return
        val ys = missing.map { it.first }; val xs = missing.map { it.second }; val cell = 0.1
        surveillanceFetch = scope.launch {
            val found = runCatching { SurveillanceCameras.inBox(ys.min() * cell, xs.min() * cell, (ys.max() + 1) * cell, (xs.max() + 1) * cell) }.getOrNull()
            surveillanceFetch = null
            if (found == null) { delay(30_000); loadSurveillance(m); return@launch }
            missing.forEach { surveillanceCells.add("${it.first},${it.second}") }
            found.forEach { surveillance[it.id] = it }
            render(); loadSurveillance(m)
        }
    }

    /** Ask Overpass for the cameras in view, a few 0.1° cells at a time, each cell once. */
    fun loadCameras(m: MapLibreMap) {
        val s = model.settings
        if (!(s.speedCameras || s.plateCameras) || m.cameraPosition.zoom < 12 || cameraFetch != null) return
        val missing = cells(m, cameraCells) ?: return
        val ys = missing.map { it.first }; val xs = missing.map { it.second }; val cell = 0.1
        cameraFetch = scope.launch {
            val found = runCatching { Overpass.cameras(ys.min() * cell, xs.min() * cell, (ys.max() + 1) * cell, (xs.max() + 1) * cell, true, true) }.getOrNull()
            cameraFetch = null
            if (found == null) { delay(30_000); loadCameras(m); return@launch }
            missing.forEach { cameraCells.add("${it.first},${it.second}") }
            found.forEach { cameras[it.id] = it }
            render(); loadCameras(m)
        }
    }

    /** Waze's speed cameras for what's on screen, once zoomed in to a town; each piece of its map once a week. */
    fun loadWazeCameras(m: MapLibreMap) {
        val s = model.settings
        WazeCameras.enabled = s.wazeOn
        if (!s.speedCameras || !s.wazeOn || m.cameraPosition.zoom < 13 || wazeCameraFetch != null) return
        val b = m.projection.visibleRegion.latLngBounds
        wazeCameraFetch = scope.launch {
            val fresh = runCatching { WazeCameras.load(b.latitudeSouth, b.longitudeWest, b.latitudeNorth, b.longitudeEast) }.getOrDefault(false)
            wazeCameraFetch = null
            if (fresh) render()
        }
    }

    private var shownMeasurement: Pair<LatLng, LatLng>? = null
    private fun renderMeasure(style: Style) {
        val measure = model.measurement
        if (measure == shownMeasurement) return
        shownMeasurement = measure
        setShape(style, "kite-measure", measure?.let { (a, b) -> listOf(polyline(listOf(a, b)), point(a), point(b)) }.orEmpty())
    }

    private var badgeColors = MapIcons.BadgeColors.FALLBACK
    private val mapBadges = LinkedHashMap<String, android.graphics.Bitmap>()
    private val classicBadges = mutableSetOf<String>()

    fun setBadgeColors(colors: MapIcons.BadgeColors) {
        if (badgeColors == colors) return
        badgeColors = colors
        shownStopPins = null
        lastRouteBadges = null
        lastResultsKey = ""
        lastRouteKey = ""
        render()
    }

    private fun badge(style: Style?, label: String, selected: Boolean, numbered: Boolean): String {
        val id = "kite-badge-${badgeColors.hashCode()}-${label.hashCode()}-$selected-$numbered"
        val image = mapBadges.getOrPut(id) {
            if (mapBadges.size >= 300) mapBadges.remove(mapBadges.keys.first())
            MapIcons.mapBadge(label, selected, badgeColors, numbered)
        }
        if (style != null && classicBadges.add(id)) style.addImage(id, image)
        scenery?.addBadge(id, image)
        return id
    }

    private fun renderStopPins(style: Style?) {
        val search = model.stopSearch
        val results = search?.results.orEmpty()
        val selected = search?.selected?.place?.id
        val key = results to selected
        if (shownStopPins == key) return
        shownStopPins = key
        val features = results.mapIndexed { index, (place) ->
            val active = place.id == selected || (selected == null && index == 0)
            point(place.location, mapOf("id" to place.id, "rank" to index + 1, "priority" to if (active) results.size else -index,
                "selected" to active, "icon" to badge(style, (index + 1).toString(), active, numbered = true)))
        }
        setShape(style, StopSearch.PINS, features)
    }

    private fun renderResults(style: Style?) {
        val showList = model.directions == null && !model.isGuiding
        val list = if (showList) model.visibleResults else emptyList()
        val directions = model.directions.takeIf { model.nav == null }
        val plan = directions?.orderedPlaces()
        val here = model.userLatLng
        val target = model.mapNavigator?.destination ?: model.transitGuidance?.destination ?:
            if (plan != null) plan.last() else model.selected
        val destination = target?.location ?: here.takeIf { plan != null }
        val origin = plan?.let { it.first()?.location ?: here.takeUnless { directions.missingOrigin } }
        val stops = model.nav?.stops ?: plan?.drop(1)?.dropLast(1)?.filterNotNull().orEmpty()
        val pickedPlace = plan == null && !model.isGuiding && target != null && target.id == model.selected?.id
        fun cell(at: LatLng?) = at?.let { "${(it.lat * 7000).toInt()},${(it.lng * 7000).toInt()}" }
        val destinationKey = "${target?.id}:${cell(destination)}:$pickedPlace"
        val numbered = OutdoorData.kind(model.query) == "fuel"
        val key = "$numbered|${list.joinToString(",") { it.id }}|$destinationKey|${model.streetView != null}|${cell(origin)}|${stops.joinToString(",") { it.id }}"
        if (key == lastResultsKey) return
        lastResultsKey = key
        setShape(style, "vela-results", list.mapIndexedNotNull { i, p ->
            val active = pickedPlace && model.streetView == null && p.id == target.id
            if (active && !numbered) return@mapIndexedNotNull null
            val rating = p.rating?.let { String.format(java.util.Locale.US, "%.1f", it) }
            val highlighted = active || !pickedPlace && i == 0
            val icon = if (numbered) badge(style, (i + 1).toString(), highlighted, numbered = true)
                else MapIcons.searchName(p)
            val rank = if (!numbered) i else if (highlighted) list.size else -i
            point(p.location, mapOf("id" to p.id, "icon" to icon, "label" to listOfNotNull(p.name, rating).joinToString("\n"), "rank" to rank))
        })
        val sel = mutableListOf<Feature>()
        if (destination != null && model.streetView == null && !(numbered && pickedPlace && list.any { it.id == target?.id })) {
            sel.add(point(destination, mapOf("id" to (target?.id ?: "destination"), "icon" to "pin-selected",
                "anchor" to "bottom", "label" to (target?.name ?: "Your location"))))
        }
        stops.forEachIndexed { i, stop -> sel.add(point(stop.location, mapOf("id" to "stop$i", "icon" to "pin-stop", "anchor" to "center", "label" to stop.name))) }
        origin?.let { sel.add(point(it, mapOf("id" to "origin", "icon" to "pin-origin", "anchor" to "center", "label" to ""))) }
        setShape(style, "vela-selected", sel)
    }

    private var badgeRoutes: List<Route> = emptyList()
    private var badgeAnchors = emptyList<List<LatLng>>()
    private var badgeAnchorJob: Job? = null
    private var badgeLabels = emptyList<String>()
    private var badgeWidths = emptyList<Float>()
    private data class BadgeLayout(val id: String, val selected: Int, val colors: MapIcons.BadgeColors, val placements: List<RouteBadges.Placement>)
    private var lastRouteBadges: BadgeLayout? = null
    private var lastBadgeLayoutAt = 0L

    fun refreshRouteBadges() {
        val plan = model.directions?.takeIf { it.route != null && !model.isGuiding } ?: return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastBadgeLayoutAt < 90) return
        lastBadgeLayoutAt = now
        renderRouteBadges(map?.style?.takeIf { styleReady }, plan)
    }

    private fun renderRouteBadges(style: Style?, plan: DirectionsState) {
        val routes = plan.current
        val visible = view ?: return
        if (badgeRoutes !== routes) {
            badgeRoutes = routes
            badgeAnchors = emptyList()
            badgeLabels = routes.map { Fmt.duration(it.durationSeconds) }
            badgeWidths = badgeLabels.map(MapIcons::badgeWidth)
            lastRouteBadges = null
            setShape(style, "vela-route-bubbles", emptyList())
            badgeAnchorJob?.cancel()
            badgeAnchorJob = scope.launch {
                val anchors = withContext(Dispatchers.Default) { RouteBadges.anchors(routes) }
                if (badgeRoutes !== routes || model.directions?.current !== routes) return@launch
                badgeAnchors = anchors
                render()
            }
        }
        if (badgeAnchors.isEmpty() || viewportMoving) return
        val density = context.resources.displayMetrics.density
        val height = visible.height / density.toDouble()
        val width = visible.width / density.toDouble()
        val top = min(150.0, height * 0.24)
        val bottom = height - when (model.detent) {
            SheetDetent.PEEK -> min(app.kite.ui.tripPeek(plan).value.toDouble() + 10.0, height * 0.57)
            SheetDetent.HALF -> height * 0.46 + 10.0
            SheetDetent.FULL -> height * 0.46 + 10.0
        }
        val bounds = RouteBadges.Bounds(12.0, top, width - 12.0, max(bottom, top + 44.0))
        val native = scenery?.takeIf { it.ready }
        val tags = badgeAnchors.mapIndexed { i, anchors ->
            anchors.map { at ->
                val (x, y) = native?.screenPoint(at) ?: map?.projection?.toScreenLocation(at.ml())
                    ?.let { it.x.toDouble() to it.y.toDouble() } ?: (0.0 to 0.0)
                RouteBadges.Tag(x / density, y / density, badgeWidths[i].toDouble(), 36.0)
            }
        }
        val placements = RouteBadges.place(tags, bounds, plan.selected)
        val key = BadgeLayout(routes.joinToString { it.id }, plan.selected, badgeColors, placements)
        if (lastRouteBadges == key) return
        lastRouteBadges = key
        setShape(style, "vela-route-bubbles", routes.mapIndexed { i, route ->
            val selected = route.id == plan.route?.id
            val (anchor, offset) = placements[i]
            point(badgeAnchors[i][anchor], mapOf("icon" to badge(style, badgeLabels[i], selected, numbered = false),
                "priority" to if (selected) routes.size else i, "idx" to i)).apply {
                addProperty("offset", com.google.gson.JsonArray().apply { add(offset.x); add(offset.y) })
            }
        })
    }

    private var routeGeometry: List<LatLng>? = null
    private var routeDistances = DoubleArray(0)
    private var routeContext = ""
    private var renderedTransitPreview: TransitStepPreview? = null
    private var transitStopsKey: List<Any?>? = null
    private var visiblePathSection: Pair<String, Valhalla.PathSection>? = null

    private var vehicleKey = ""
    private fun renderRoutes(style: Style?) {
        var alts = emptyList<Route>()
        var main: Route? = null
        var from = 0.0
        val n = model.mapNavigator
        val d = model.directions
        val session = model.transitGuidance
        val overview = session?.overviewing == true
        if (n != null && !overview) { main = n.route; from = n.progress?.alongMeters ?: 0.0 }
        else if (d != null && session == null) { main = d.route; alts = d.current.filter { it.id != main?.id } }
        val trip = if (session != null) session.trip else if (n == null) d?.pickedTrip ?: model.departurePreview else null
        val transitPreview = model.transitStepPreview?.takeIf {
            it.tripId == trip?.id && (session == null || overview)
        }
        val vehicleKeyNow = "${trip?.id}|${model.transitLive.hashCode()}|${model.departureEstimate}"
        if (vehicleKey != vehicleKeyNow) {
            vehicleKey = vehicleKeyNow
            setShape(style, TRANSIT_VEHICLES, trip?.rides.orEmpty().distinctBy { it.tripId }.mapNotNull { leg ->
                val report = model.transitLive[leg.tripId]
                if (report?.cancelled == true) return@mapNotNull null
                val at = report?.position ?: model.departureEstimate?.takeIf { trip?.id == model.departurePreview?.id }
                    ?: return@mapNotNull null
                val label = (if (report?.gps == true) "" else "≈") + leg.line.take(4)
                point(at, mapOf("color" to hex(leg.mapColor), "line" to label))
            })
        }
        val keyForStops = listOf(trip?.id, session?.legIndex, session?.phase, overview, transitPreview)
        if (transitStopsKey != keyForStops) {
            transitStopsKey = keyForStops
            val rideLegs = when {
                transitPreview != null -> listOf(transitPreview.leg).filterNot { it.isWalk }
                overview || session == null -> trip?.rides.orEmpty()
                else -> listOfNotNull(session.leg?.takeUnless { it.isWalk })
            }
            val pins = rideLegs.flatMap { leg ->
                val calls = if (transitPreview != null || model.departurePreview?.id == trip?.id) leg.calls
                    else if (overview || session == null) listOfNotNull(leg.calls.firstOrNull(), leg.calls.lastOrNull()) else leg.calls
                calls.map { it to leg.mapColor }
            }.distinctBy { it.first.at }
            setShape(style, RIDE_STOPS, pins.map { (stop, tint) ->
                point(stop.at, mapOf("name" to stop.name, "color" to hex(tint),
                    "colorNight" to hex(NavigationDrawing.nightTransit(tint)), "selected" to (stop.at == transitPreview?.stop)))
            })
        }
        val traffic = n?.roadTraffic ?: main?.trafficSpans.orEmpty().takeIf { AppSettings.traffic }.orEmpty()
        val context = "${trip?.id}|${session?.legIndex}|$overview|${main?.id}|${alts.joinToString { it.id }}"
        val follows = n?.following == true
        val highlight = model.pathSectionHighlight.takeIf { main != null && it?.first == main.id }
        if (visiblePathSection != highlight || routeContext.isEmpty()) {
            visiblePathSection = highlight
            val points = highlight?.second?.let { section -> main?.let { Geo.slice(it.polyline, section.from, section.to) } }.orEmpty()
            setShape(style, "kite-path-section", if (points.size >= 2) listOf(polyline(points)) else emptyList())
        }
        val turnStyle = AppSettings.routeArrowStyle
        val key = "$turnStyle|$context|${model.plannedStep?.alongMeters}|${model.navigatingStep?.alongMeters}|${(from / if (n?.route?.mode == TravelMode.WALK) 4 else 6).toInt()}|$follows|" +
            "${n?.nextManeuver?.alongMeters}|${traffic.hashCode()}"
        if (key == lastRouteKey && renderedTransitPreview == transitPreview) {
            if (n == null && d?.route != null && !viewportMoving) renderRouteBadges(style, d)
            return
        }
        lastRouteKey = key
        if (routeContext != context || renderedTransitPreview != transitPreview) {
            routeContext = context
            renderedTransitPreview = transitPreview
            setShape(style, "vela-route-alt", alts.map { polyline(it.polyline, mapOf("idx" to d?.current?.indexOf(it))) })
            val legs = when {
                transitPreview != null -> listOf(transitPreview.leg to transitPreview.path)
                session != null && !overview -> trip?.legs?.drop(session.legIndex).orEmpty().map { it to it.path }
                else -> trip?.legs.orEmpty().map { it to it.path }
            }
            setShape(style, "vela-transit", legs.filter { it.second.size >= 2 }.map { (leg, path) ->
                polyline(path, mapOf("walk" to if (leg.isWalk) 1 else 0, "color" to hex(leg.mapColor),
                    "colorNight" to hex(NavigationDrawing.nightTransit(leg.mapColor))))
            })
        }
        val r = main
        if (r == null) {
            lastRouteBadges = null
            for (id in listOf("vela-route", "vela-route-traffic", "vela-route-bubbles", "vela-turn-arrow", "vela-turn-head", "vela-next-road")) setShape(style, id, emptyList())
            lastHeadKey = ""; lastNextRoadKey = ""
            return
        }
        if (routeGeometry !== r.polyline) {
            routeGeometry = r.polyline
            routeDistances = Geo.cumulative(r.polyline)
        }
        val cum = routeDistances
        val total = cum.lastOrNull() ?: return
        val to = if (follows) min(total, from + 16_000.0) else total
        val ahead = if (from > 1 || to < total) Geo.slice(r.polyline, from, to, cum) else r.polyline
        val googleDrive = n != null && r.source == "google" && r.mode == TravelMode.DRIVE
        setShape(style, "vela-route", if (ahead.size >= 2) listOf(polyline(ahead, mapOf("googleDrive" to googleDrive))) else emptyList())
        setShape(style, "vela-route-traffic", RouteTrafficPaint.ranges(from, to, traffic).mapNotNull { range ->
            val piece = Geo.slice(r.polyline, range.from, range.to, cum)
            if (piece.size >= 2) polyline(piece, mapOf("level" to range.level, "googleDrive" to googleDrive)) else null
        })
        // Start on the approach and leave the arrowhead on the road the driver should take.
        val preview = if (n != null) model.navigatingStep else model.plannedStep
        val m = preview ?: n?.nextManeuver
        val p = n?.progress
        var arrow = emptyList<Feature>()
        var head: Pair<LatLng, Double>? = null
        var nextRoad: Pair<LatLng, String>? = null
        val arrowRange = if (m != null && NavigationDrawing.isExitApproach(m)) 950 else 600
        if (m != null && m.type != ManeuverType.ARRIVE && m.type != ManeuverType.DEPART &&
            m.type != ManeuverType.STRAIGHT && (preview != null || p != null &&
                m.alongMeters - p.alongMeters < arrowRange)) {
            var end = m.alongMeters + 40
            if (m.type == ManeuverType.ROUNDABOUT) {
                r.maneuvers.firstOrNull { it.leavesRoundabout && it.alongMeters > m.alongMeters }?.let {
                    if (it.alongMeters - m.alongMeters < 250) end = it.alongMeters + 40
                }
            }
            val approach = NavigationDrawing.arrowStart(m, r.laneApproaches, n?.speedMps ?: 0.0)
            val a = max(approach, if (preview == null) from else 0.0)
            val b = min(end, total)
            val shaft = Geo.slice(r.polyline, a, b, cum)
            if (shaft.size >= 2) {
                val tip = shaft.last(); val prev = Geo.point(r.polyline, max(a, b - 4), cum)
                arrow = listOf(polyline(shaft, mapOf("part" to "shaft", "width" to (turnStyle.width * 0.68),
                    "fill" to turnStyle.fill, "edge" to turnStyle.edge)))
                head = tip to Geo.bearing(prev, tip)
            }
            // The new road's name, a little way into it, clear of the arrow head.
            nextRoadName(m)?.let { nextRoad = Geo.point(r.polyline, min(end + 55, total), cum) to it }
        }
        setShape(style, "vela-turn-arrow", arrow)
        val headKey = head?.let { (t, b) -> String.format(java.util.Locale.US, "%.6f,%.6f,%.0f,%s", t.lat, t.lng, b, turnStyle) } ?: ""
        if (headKey != lastHeadKey) {
            lastHeadKey = headKey
            setShape(style, "vela-turn-head", head?.let { (t, b) -> listOf(point(t, mapOf("bearing" to b, "icon" to turnStyle.sprite, "scale" to NavigationDrawing.TURN_HEAD_SCALE))) } ?: emptyList())
        }
        val nextRoadKey = nextRoad?.let { (at, name) -> String.format(java.util.Locale.US, "%.6f,%.6f,%s", at.lat, at.lng, name) } ?: ""
        if (nextRoadKey != lastNextRoadKey) {
            lastNextRoadKey = nextRoadKey
            setShape(style, "vela-next-road", nextRoad?.let { (at, name) -> listOf(point(at, mapOf("label" to name))) } ?: emptyList())
        }
        if (n != null || d == null) {
            lastRouteBadges = null
            setShape(style, "vela-route-bubbles", emptyList())
        } else renderRouteBadges(style, d)
    }

    private fun renderPuck(style: Style) {
        if (model.mapNavigator != null) {
            if (lastPuckKey.startsWith("cruise:")) setShape(style, NavGlide.PUCK, emptyList())
            lastPuckKey = "nav"
            return
        }
        val fix = LocationService.last.takeIf { model.cruise != null }
        val marker = AppSettings.driveMarker.mapSprite
        val key = fix?.let { "cruise:${it.timeMillis}:$marker" }.orEmpty()
        if (key == lastPuckKey) return
        val points = fix?.let { listOf(point(it.coord, mapOf("icon" to marker, "bearing" to it.course.coerceAtLeast(0.0)))) }
        setShape(style, NavGlide.PUCK, points.orEmpty())
        lastPuckKey = key
    }

    // Gestures

    private fun box(m: MapLibreMap, p: org.maplibre.android.geometry.LatLng): RectF {
        val pt: PointF = m.projection.toScreenLocation(p)
        val r = 22 * context.resources.displayMetrics.density
        return RectF(pt.x - r, pt.y - r, pt.x + r, pt.y + r)
    }

    private var lastLiveKey = ""
    /** The box the drawn live camera dots cover, so panning inside it redraws nothing. */
    private var liveCamBox: Box? = null

    /** Live camera dots for what's on screen (plus a margin), never while driving. */
    private fun renderLiveCams(m: MapLibreMap, style: Style) {
        val s = model.settings
        // Road cameras only from a city's zoom (there are thousands); webcams from a region's.
        val zoom = m.cameraPosition.zoom
        val roadNow = s.showsRoadCams && zoom >= LiveCams.ROAD_MIN_ZOOM
        val webNow = s.showsWebcams && zoom >= LiveCams.WEBCAM_MIN_ZOOM
        val on = model.nav == null && (roadNow || webNow)
        val key = "$on$roadNow$webNow${LiveCamStore.version}"
        if (!on) {
            if (lastLiveKey != key) {
                lastLiveKey = key
                liveCamBox = null
                setShape(style, "vela-livecams", emptyList())
            }
            return
        }
        val b = m.projection.visibleRegion.latLngBounds
        val dLat = (b.latitudeNorth - b.latitudeSouth) * 0.5
        val dLng = (b.longitudeEast - b.longitudeWest) * 0.5
        val inside = liveCamBox?.let { b.latitudeSouth >= it.s && b.latitudeNorth <= it.n && b.longitudeWest >= it.w && b.longitudeEast <= it.e } ?: false
        // Switched on from Layers or Settings: ask for the cameras now, not only after the next pan.
        loadLiveCams(m)
        if (key == lastLiveKey && inside) return
        lastLiveKey = key
        val expanded = Box(b.latitudeSouth - dLat, b.longitudeWest - dLng, b.latitudeNorth + dLat, b.longitudeEast + dLng)
        liveCamBox = expanded
        val features = LiveCamStore.cams(expanded.s, expanded.w, expanded.n, expanded.e, roadNow, webNow).map { cam ->
            point(cam.location, mapOf("id" to cam.id, "icon" to if (cam.source.isRoad) "livecam-road" else "livecam-web"))
        }
        setShape(style, "vela-livecams", features)
    }

    /** The road camera list (once a day) and Windy's webcams around what's on screen. */
    fun loadLiveCams(m: MapLibreMap) {
        val s = model.settings
        if (model.nav != null || !s.liveCams) return
        if (s.showsRoadCams || s.liveCamsOnRoute) LiveCamStore.ensureLoaded()
        if (!s.showsWebcams || m.cameraPosition.zoom < LiveCams.WEBCAM_MIN_ZOOM) return
        val b = m.projection.visibleRegion.latLngBounds
        val c = LatLng((b.latitudeSouth + b.latitudeNorth) / 2, (b.longitudeWest + b.longitudeEast) / 2)
        val span = LatLng(b.latitudeSouth, b.longitudeWest).distanceTo(LatLng(b.latitudeNorth, b.longitudeEast)) / 1000
        LiveCamStore.loadWindy(c, span)
    }

    private fun str(f: Feature, k: String): String? = f.getProperty(k)?.takeIf { it.isJsonPrimitive }?.asString

    private fun tapped(m: MapLibreMap, at: org.maplibre.android.geometry.LatLng, collapseResults: Boolean = true): Boolean {
        if (model.selectIndoor(at.kite()) || model.streetViewMapTapped(at.kite()) || model.positionEndpointPin(at.kite())) return true
        if (model.stopSearch != null) {
            val pin = m.queryRenderedFeatures(box(m, at), StopSearch.PINS).firstOrNull()
            model.selectStopPin(pin?.let { str(it, "id") })
            return true
        }
        if (model.placingStop || now() - pinDroppedAt < 1000) return false
        val box = box(m, at)
        if (scenery?.ready != true && AppSettings.wazeOn && AppSettings.wazeOnMap) {
            val point = at.kite()
            val reports = m.queryRenderedFeatures(box, "vela-waze", "vela-waze-group")
                .mapNotNull { feature ->
                    val location = feature.geometry() as? Point ?: return@mapNotNull null
                    val id = str(feature, "id") ?: return@mapNotNull null
                    id to point.distanceTo(LatLng(location.latitude(), location.longitude()))
                }.sortedBy { it.second }
            if (reports.any { openWazeAlert(it.first) }) {
                view?.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                return true
            }
        }
        if (model.nav != null) {
            model.showNavigationPlace(navigationPlaceAt(m, at))
            return true
        }
        if (model.directions != null) {
            val pixel = m.projection.toScreenLocation(at)
            val bubble = m.queryRenderedFeatures(pixel, "vela-route-bubble").firstOrNull()
            if (bubble != null && bubble.hasProperty("idx")) {
                model.selectRoute(bubble.getNumberProperty("idx").toInt())
                return true
            }
            val hits = m.queryRenderedFeatures(box, "vela-route-alt", "vela-route-alt-edge")
            hits.firstOrNull { it.hasProperty("idx") }?.let { model.selectRoute(it.getNumberProperty("idx").toInt()); return true }
            val d = model.directions ?: return true
            if (hits.isNotEmpty()) {
                val tappedAt = at.kite()
                val i = d.current.indexOfFirst { r -> r.id != d.route?.id && r.polyline.any { it.distanceTo(tappedAt) < 300 } }
                if (i >= 0) { model.selectRoute(i); return true }
            }
            // A tap on the picked route itself: offer to avoid that road (GitHub idea, 0.7.17).
            if (m.queryRenderedFeatures(box, "vela-route-line", "vela-route-edge").isNotEmpty()) model.askAvoid(at.kite())
            return true
        }
        // A numbered bubble: zoom in until it splits into its icons.
        m.queryRenderedFeatures(box, "vela-waze-group-bubble", "vela-cameras-bubble", "vela-livecams-bubble").firstOrNull()?.let { f ->
            (f.geometry() as? Point)?.let { p ->
                m.animateCamera(org.maplibre.android.camera.CameraUpdateFactory.newLatLngZoom(
                    org.maplibre.android.geometry.LatLng(p.latitude(), p.longitude()), min(m.cameraPosition.zoom + 2, 15.0)))
                return true
            }
        }
        m.queryRenderedFeatures(box, "vela-livecams").firstOrNull()?.let { f ->
            str(f, "id")?.let { LiveCamStore.cam(it) }?.let {
                view?.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                model.liveCamRoute = emptyList()
                model.liveCam = it
                return true
            }
        }
        m.queryRenderedFeatures(box, "vela-cameras").firstOrNull()?.let { f ->
            str(f, "surveillanceId")?.toLongOrNull()?.let { id -> surveillance[id]?.let { model.surveillanceInfo = it; return true } }
        }
        m.queryRenderedFeatures(box, "vela-mine").firstOrNull()?.let { f ->
            str(f, "id")?.let { model.myPlace(it) }?.let { model.select(it); return true }
        }
        m.queryRenderedFeatures(box, "vela-results", "kite-search-hit").firstOrNull()?.let { f ->
            val id = str(f, "id")
            model.results.firstOrNull { it.id == id }?.let { model.select(it); return true }
        }
        val selectedId = model.selected?.id
        if (selectedId != null && m.queryRenderedFeatures(box, "vela-selected")
                .any { str(it, "id") == selectedId }) return true
        val transitHit = m.queryRenderedFeatures(box, TRANSIT_STOPS).firstOrNull()
        if (transitHit != null && openOutdoorPlace(str(transitHit, "id"))) return true
        val googleHit = m.queryRenderedFeatures(box, "kite-google-places", GOOGLE_UNREVIEWED).firstOrNull()
        if (googleHit != null && openGooglePlace(str(googleHit, "id"))) return true
        val poiLayers = arrayOf(OpenMapStyle.POIS)
        m.queryRenderedFeatures(box, *poiLayers).firstOrNull()?.let { poi ->
            val name = OpenMapStyle.title(str(poi, "name") ?: str(poi, "name:en"), str(poi, "subclass"), str(poi, "class"))
            val g = poi.geometry() as? Point
            model.select(Place("osm:${g?.latitude()},${g?.longitude()}:$name", name,
                g?.let { LatLng(it.latitude(), it.longitude()) } ?: at.kite(), "osm",
                category = str(poi, "subclass") ?: str(poi, "class")))
            return true
        }
        if (model.selected != null) model.closePlace(restoreViewport = false)
        else if (collapseResults && model.results.isNotEmpty()) model.detent = SheetDetent.PEEK
        return true
    }

    private fun navigationPlaceAt(m: MapLibreMap, at: org.maplibre.android.geometry.LatLng): Place? {
        if (!AppSettings.navigationPlaces) return null
        val hits = m.queryRenderedFeatures(box(m, at), "kite-google-places", GOOGLE_UNREVIEWED, OpenMapStyle.POIS)
        for (hit in hits) {
            googlePlace(str(hit, "id"))?.let { return it }
            val group = str(hit, "class")
            val subclass = str(hit, "subclass")
            if (group == null && subclass == null) continue
            val point = hit.geometry() as? Point ?: continue
            val name = OpenMapStyle.title(str(hit, "name") ?: str(hit, "name:en"), subclass, group)
            return Place("osm:${point.latitude()},${point.longitude()}:$name", name,
                LatLng(point.latitude(), point.longitude()), "osm", category = subclass ?: group)
        }
        return null
    }

    private fun pressed(m: MapLibreMap, at: org.maplibre.android.geometry.LatLng) {
        if (model.streetView != null || model.stopSearch != null || model.placingStop) return
        if (model.positionEndpointPin(at.kite())) return
        pinDroppedAt = now()
        if (model.nav != null) {
            val place = navigationPlaceAt(m, at) ?: if (AppSettings.navigationHoldAnywhere) model.navigationPin(at.kite()) else return
            view?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            model.addNavigationPlace(place)
            return
        }
        if (model.isGuiding || model.directions != null) return
        view?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        model.dropPin(at.kite())
    }

    private fun noteHeading(m: MapLibreMap) {
        if (scenery?.ready != true) noteVisibleHeading(m.cameraPosition.bearing)
    }

    fun noteVisibleHeading(bearing: Double) {
        val heading = ((bearing % 360) + 360) % 360
        if (Geo.diff(heading, model.mapHeading) > 2 || (heading == 0.0) != (model.mapHeading == 0.0)) {
            model.mapHeading = heading
        }
    }
}
