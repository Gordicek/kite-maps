package app.kite

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import app.kite.core.*
import kotlinx.coroutines.*
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import kotlin.math.*

/** The dock's pages. */
enum class AppTab { MAP, SAVED, SETTINGS }

/** Which of your places the "pick a place" sheet is choosing. */
enum class PickKind { HOME, WORK, SHORTCUT }

enum class SheetDetent { PEEK, HALF, FULL }

enum class SortMode(val label: String) { RELEVANCE("Relevance"), DISTANCE("Distance"), RATING("Rating") }

/** What a home-screen shortcut asked the app to do once it's open. */
enum class LaunchKind { HOME, WORK, CAR }

data class Category(val label: String, val icon: ImageVector, val query: String) {
    companion object {
        /** British words where units are British: petrol, supermarkets, chemists, cash machines. */
        val all: List<Category> get() = if (Fmt.yards) british else american

        val british = listOf(
            Category("Restaurants", Icons.Filled.Restaurant, "restaurants"),
            Category("Coffee", Icons.Filled.LocalCafe, "coffee"),
            Category("Drinking water", Icons.Filled.WaterDrop, "drinking water"),
            Category("Petrol", Icons.Filled.LocalGasStation, "petrol station"),
            Category("Supermarkets", Icons.Filled.ShoppingCart, "supermarket"),
            Category("Pubs", Icons.Filled.SportsBar, "pub"),
            Category("Hotels", Icons.Filled.Hotel, "hotels"),
            Category("Parking", Icons.Filled.LocalParking, "car park"),
            Category("EV charging", Icons.Filled.EvStation, "ev charging station"),
            Category("Chemists", Icons.Filled.LocalPharmacy, "pharmacy"),
            Category("Cash machines", Icons.Filled.LocalAtm, "atm"),
        )
        val american = listOf(
            Category("Restaurants", Icons.Filled.Restaurant, "restaurants"),
            Category("Coffee", Icons.Filled.LocalCafe, "coffee"),
            Category("Drinking water", Icons.Filled.WaterDrop, "drinking water"),
            Category("Gas", Icons.Filled.LocalGasStation, "gas stations"),
            Category("Groceries", Icons.Filled.ShoppingCart, "grocery stores"),
            Category("Hotels", Icons.Filled.Hotel, "hotels"),
            Category("Parking", Icons.Filled.LocalParking, "parking"),
            Category("Pharmacies", Icons.Filled.LocalPharmacy, "pharmacy"),
            Category("ATMs", Icons.Filled.LocalAtm, "atm"),
        )
    }
}

internal sealed interface RoadStart {
    data object WaitForLocation : RoadStart
    data object FindRoute : RoadStart
    data class ConnectToPlan(val destination: Place, val stops: List<Place>) : RoadStart
    data class Follow(val route: Route) : RoadStart
}

/** Directions to one place: routes for every mode, the one picked, and where from. */
data class DirectionsState(
    val destination: Place,
    /** True when the trip goes from the place back to you. */
    val reversed: Boolean = false,
    val mode: TravelMode,
    val routes: Map<TravelMode, List<Route>> = emptyMap(),
    val loading: Set<TravelMode> = emptySet(),
    val errors: Map<TravelMode, String> = emptyMap(),
    val selected: Int = 0,
    /** Places to stop at on the way, in order. */
    val stops: List<Place> = emptyList(),
    /** The Transit tab is showing (its trips come from Transitous, not the road routers). */
    val transit: Boolean = false,
    val trips: List<TransitTrip>? = null,
    val tripsError: String? = null,
    /** Null for "leave now". */
    val transitTime: Long? = null,
    val arriveBy: Boolean = false,
    val trip: Int = 0,
    val start: Place? = null,
    val useCurrentOrigin: Boolean = true,
    /** Spots on roads tapped to avoid, with the roads' names. */
    val avoid: List<LatLng> = emptyList(),
    val avoidNames: List<String> = emptyList(),
    /** The driving routes from before avoiding a road, for Undo. */
    val beforeAvoid: List<Route>? = null,
    /** The place as first picked, while the trip goes to its main entrance or a car park instead. */
    val original: Place? = null,
    /** What the trip ends at instead of the place itself: "Main entrance", or the car park's name. */
    val endNote: String? = null,
    val startRequested: Boolean = false,
    val waitingForLocation: Boolean = false,
    val preferredTransitLines: List<String> = emptyList(),
) {
    fun orderedPlaces(): List<Place?> =
        if (reversed) listOf(destination) + stops.asReversed() + listOf(null)
        else listOf(start) + stops + destination

    fun replacingDestination(place: Place): DirectionsState =
        copy(destination = place, start = if (reversed) destination else start,
            stops = if (reversed) stops.asReversed() else stops, reversed = false,
            original = null, endNote = null, startRequested = false)

    val missingOrigin: Boolean get() = start == null && !useCurrentOrigin && !reversed

    fun endpoints(current: LatLng?): Pair<LatLng?, LatLng?> =
        if (reversed) destination.location to current
        else (start?.location ?: current.takeIf { useCurrentOrigin }) to destination.location

    internal fun roadStart(here: LatLng?): RoadStart? {
        if (transit || missingOrigin) return null
        if (here == null) return RoadStart.WaitForLocation
        val chosen = route ?: return RoadStart.FindRoute
        val points = orderedPlaces()
        val distanceToStart = points.first()?.location?.distanceTo(here) ?: 0.0
        if (!reversed && distanceToStart <= 70) {
            return RoadStart.Follow(chosen)
        }
        val destination = points.last() ?: Place("waypoint:${here.lat},${here.lng}", "Your location", here, "pin")
        val prefix = points.first()?.takeIf { it.location.distanceTo(here) > 70 }
        return RoadStart.ConnectToPlan(destination, listOfNotNull(prefix) + points.drop(1).dropLast(1).filterNotNull())
    }

    val current: List<Route> get() = if (transit) emptyList() else routes[mode] ?: emptyList()
    val route: Route? get() = current.getOrNull(selected) ?: current.firstOrNull()
    val pickedTrip: TransitTrip? get() = if (transit) trips?.let { it.getOrNull(trip) ?: it.firstOrNull() } else null
}

/** A road tapped on the planned route, waiting for "Avoid" or "Cancel". */
data class AvoidAsk(val point: LatLng, val road: String?)

/** The whole app's state; the map and every sheet read it. */
class AppModel {
    val settings = AppSettings
    val location = LocationService
    val scope = MainScope()

    var query by mutableStateOf("")
    var searchPrompt by mutableIntStateOf(0)
        private set
    /** Autocomplete while typing: Google's, or OpenStreetMap's without Google. */
    fun consumeSearchPrompt(): Boolean {
        if (searchPrompt == 0) return false
        searchPrompt = 0
        return true
    }

    var suggestions by mutableStateOf(SuggestParser.Result())
    private var suggestTask: Job? = null
    private var suggestedQuery: String? = null
    var results by mutableStateOf<List<Place>>(emptyList())
    var resultsTitle by mutableStateOf("")
    var canSearchHere by mutableStateOf(false)
        private set
    private var submittedSearch: Pair<String, String?>? = null
    private var searchCenter: LatLng? = null
    private var searchZoom: Double? = null

    fun noteSearchArea(center: LatLng, zoom: Double) {
        val previous = searchCenter ?: return
        val changedZoom = searchZoom?.let { abs(it - zoom) >= 1.0 } == true
        canSearchHere = submittedSearch != null && (previous.distanceTo(center) >= 300 || changedZoom)
    }

    fun searchHere() {
        val (text, title) = submittedSearch ?: return
        search(text, title, fromMap = true)
    }
    var selected by mutableStateOf<Place?>(null)
    var searching by mutableStateOf(false)
    var notice by mutableStateOf<String?>(null)
    var detent by mutableStateOf(SheetDetent.HALF)
    var openNowOnly by mutableStateOf(false)
    var rating4Only by mutableStateOf(false)
    var sort by mutableStateOf(SortMode.RELEVANCE)
    var directions by mutableStateOf<DirectionsState?>(null)
    var nav by mutableStateOf<Navigator?>(null)
        private set
    var transitGuidance by mutableStateOf<TransitGuidance?>(null)
        private set
    val mapNavigator: Navigator? get() = nav ?: transitGuidance?.walking
    val isGuiding: Boolean get() = nav != null || transitGuidance != null
    private var transitWalkJob: Job? = null
    private var transitBoardJob: Job? = null
    private var transitClockJob: Job? = null
    private var transitDeparturesJob: Job? = null
    private var transitCameraAt = 0L
    var showLayers by mutableStateOf(false)
    var offlineMapShown by mutableStateOf(false)
    var offlineFocus by mutableStateOf<Pair<LatLng, Double>?>(null)
    var tab by mutableStateOf(AppTab.MAP)
    /** Live Waze reports in view, for the "Live from Waze" chip; null until Waze answers. */
    var wazeLiveCount by mutableStateOf<Int?>(null)
    /** The police risk around you (0 to 100), for the chip on the map. */
    var areaRisk by mutableStateOf<Int?>(null)
    /** A Waze report tapped on the map, shown in a small card. */
    var wazeInfo by mutableStateOf<WazeAlert?>(null)
    var surveillanceInfo by mutableStateOf<SurveillanceCamera?>(null)
    /** The live camera whose photo is open (tapped on the map or in a route's camera row). */
    var liveCam by mutableStateOf<LiveCam?>(null)
    /** The route's cameras in order, when the open one was picked from the route's row (for next/previous). */
    var liveCamRoute by mutableStateOf<List<LiveCam>>(emptyList())
    /** The "Add stop" search sheet. */
    var navigationPlace by mutableStateOf<Place?>(null)
        private set

    fun showNavigationPlace(place: Place?) {
        if (nav?.arrived == false) navigationPlace = place
    }

    fun navigationPin(at: LatLng) = Place("pin:" + at.lat + "," + at.lng, "Dropped pin", at, "pin")

    fun addNavigationPlace(place: Place) {
        val n = nav?.takeUnless { it.arrived } ?: return
        if (n.stops.size >= 8 || n.stops.any(place::matchesNearby) || n.destination.matchesNearby(place)) return
        navigationPlace = null
        n.addStop(place)
        if (!n.following) recenterNav()
    }

    var addingStop by mutableStateOf(false)
    var placingStop by mutableStateOf(false)
        private set
    var measurement by mutableStateOf<Pair<LatLng, LatLng>?>(null)
    fun beginStopPin() {
        addingStop = false
        stopPinWasFollowing = stopSearch?.wasFollowing ?: (nav?.following == true)
        closeStopSearch(restore = false)
        nav?.following = false
        browseFollowing = false
        placingStop = true
    }

    private var stopPinWasFollowing = false
    fun finishStopPin(at: LatLng? = null) {
        placingStop = false
        if (at != null) acceptStop(StopCandidate(Place("pin:${at.lat},${at.lng}", "Dropped pin", at, "pin"), null, 0.0))
        if (stopPinWasFollowing && nav != null) recenterNav()
        else if (at == null && nav == null) frameRoute()
        stopPinWasFollowing = false
    }
    var stopSearch by mutableStateOf<StopSearch?>(null)
        private set
    private var stopSearchJob: Job? = null
    var picking by mutableStateOf<PickKind?>(null)
    var shortcutDraft by mutableStateOf<Place?>(null)
    var showSteps by mutableStateOf(false)
    /** A drive that was under way when the app last closed, offered again on the home map. */
    var resumeOffer by mutableStateOf<SavedTrip?>(null)
    private var tripSavedAt = 0L
    /** Bumped when the map should redraw overlays that don't come from a state value. */
    var mapTick by mutableIntStateOf(0)
    /** Which way the map faces, for the compass button. */
    var mapHeading by mutableStateOf(0.0)
    /**
     * The browse map's 3D, 2D or north-up button. North up is the map as it always was; 3D and 2D
     * follow you round like the driving map, without a route. Starts north up each time Kite opens.
     */
    var browseLook by mutableStateOf(settings.browseMapLook)
    /** Whether the browse map is following you in 3D or 2D; dragging the map stops it. */
    var browseFollowing by mutableStateOf(false)
    /** Street View opened from a place. */
    var streetView by mutableStateOf<StreetViewRequest?>(null)
        private set
    var streetViewMapExpanded by mutableStateOf(false)
    var streetViewFrame by mutableStateOf<StreetViewFrame?>(null)
        private set
    var streetViewTap by mutableStateOf<Pair<LatLng, Long>?>(null)
        private set
    private var beforeStreetView: CameraPosition? = null
    private var streetViewTapNumber = 0L

    fun openStreetView(request: StreetViewRequest) {
        if (streetView == null) beforeStreetView = visibleCamera?.invoke() ?: map?.cameraPosition
        browseFollowing = false
        streetView = request
    }

    fun closeStreetView() {
        streetView = null
        streetViewFrame = null
        streetViewTap = null
        streetViewMapExpanded = false
        val camera = beforeStreetView
        beforeStreetView = null
        if (camera != null) scope.launch {
            // Let the shared map regain its full viewport before restoring the camera.
            delay(65)
            if (streetView == null) map?.let { MapCam.animate(it, camera, ms = 0, native = nativeCamera, keepPadding = true) }
        }
    }

    fun streetViewMapTapped(point: LatLng): Boolean {
        if (streetView == null) return false
        streetViewTap = point to ++streetViewTapNumber
        return true
    }

    fun updateStreetViewFrame(pano: StreetView.Panorama, heading: Double) {
        if (streetView == null) return
        streetViewFrame = StreetViewFrame(pano.location, (heading / 2.0).roundToInt() * 2, pano.links.map { it.at })
    }

    fun centerStreetView(at: LatLng, keepZoom: Boolean = true) {
        if (streetView == null) return
        val m = map ?: return
        val current = visibleCamera?.invoke() ?: m.cameraPosition
        val zoom = if (keepZoom) current.zoom.coerceAtLeast(16.0) else 17.4
        MapCam.animate(m, CameraPosition.Builder().target(at.ml()).zoom(zoom).tilt(0.0).bearing(0.0).build(),
            ms = 200, native = nativeCamera)
    }
    /** The driving view with no route, switched to by itself when you set off (null when not driving). */
    var cruise by mutableStateOf<Cruise?>(null)
    /** When fast driving with no route began, for switching to the driving view after a few seconds. */
    private var movingSince: Long? = null
    /** Closed with the ✕: stays closed until the car next stops for a while. */
    private var cruiseClosed = false
    private var cruiseClosedStill: Long? = null

    var map: MapLibreMap? = null
        set(v) { field = v; glide.map = v }
    var nativeCamera: ((CameraPosition, Int) -> Boolean)? = null
    var visibleCamera: (() -> CameraPosition?)? = null
    var refreshedTransit by mutableStateOf<TransitTrip?>(null)
    fun latestTransit(trip: TransitTrip?): TransitTrip? = refreshedTransit?.takeIf { it.id == trip?.id } ?: trip
    var transitLive by mutableStateOf<Map<String, TransitLive>>(emptyMap())
    /** The map view's size in dp, for framing. */
    var mapWidthDp = 400f
    var mapHeightDp = 800f
    /** Moves the nav arrow and camera every frame between GPS fixes. */
    val glide = NavGlide()
    private var routePlanJob: Job? = null
    private var preparedRoadControls: Pair<String, Deferred<List<Overpass.RoadControl>?>>? = null

    fun prepareRoadControls(route: Route?) {
        val old = preparedRoadControls
        if (route?.id == old?.first) return
        old?.second?.cancel()
        preparedRoadControls = null
        if (route == null || route.mode == TravelMode.BIKE) return
        preparedRoadControls = route.id to scope.async(Dispatchers.Default) {
            val walking = route.mode == TravelMode.WALK
            Overpass.controlsOnRoute(Geo.slice(route.polyline, 0.0, if (walking) 850.0 else 3_600.0), walking)
        }
    }
    private var transitPlanJob: Job? = null
    private var transitLoadedAt = 0L
    private var searchTask: Job? = null

    init {
        location.onUpdate = { locationChanged(it) }
        // A drive cut short (the app closed, the phone restarted): offer it again, for two hours.
        settings.trip?.let { t ->
            if (!DemoScript.active && System.currentTimeMillis() - t.atMillis < 2 * 3600_000L) resumeOffer = t else settings.trip = null
        }
    }

    /** "Carry on": the same place, stops and way of travelling, started as soon as the route arrives. */
    fun resumeTrip() {
        val t = resumeOffer ?: return
        if (isGuiding) return
        resetPlanRequests()
        location.last?.let(::centerForNavigationStart)
        resumeOffer = null
        settings.trip = null
        selected = null
        results = emptyList()
        directions = DirectionsState(destination = t.destination, mode = TravelMode.entries.firstOrNull { it.raw == t.mode } ?: TravelMode.DRIVE,
            stops = t.stops, startRequested = true)
        detent = SheetDetent.HALF
        loadPlan()
    }

    fun dismissResume() {
        resumeOffer = null
        settings.trip = null
    }

    private fun rememberTrip(n: Navigator) {
        tripSavedAt = System.currentTimeMillis()
        settings.trip = SavedTrip(n.destination, n.stops, n.route.mode.raw, tripSavedAt)
    }

    fun edit(f: (DirectionsState) -> DirectionsState) { directions?.let { directions = f(it) } }

    val userLatLng: LatLng? get() = location.last?.coord

    // Search

    val visibleResults: List<Place>
        get() {
            var r = results
            if (openNowOnly) r = r.filter { it.openNow == true }
            if (rating4Only) r = r.filter { (it.rating ?: 0.0) >= 4 }
            return when (sort) {
                SortMode.RELEVANCE -> r
                SortMode.DISTANCE -> r.sortedBy { it.distanceMeters ?: Double.MAX_VALUE }
                SortMode.RATING -> r.sortedByDescending { it.rating ?: 0.0 }
            }
        }

    val viewport: Pair<LatLng, Double?> get() = searchViewport()
    private fun searchViewport(fromMap: Boolean = false): Pair<LatLng, Double?> {
        val m = map ?: return Pair(userLatLng ?: LatLng(51.5074, -0.1278), null)
        val c = m.cameraPosition.target ?: return Pair(userLatLng ?: LatLng(51.5074, -0.1278), null)
        val b = m.projection.visibleRegion.latLngBounds
        val span = LatLng(b.latitudeSouth, b.longitudeWest).distanceTo(LatLng(b.latitudeNorth, b.longitudeEast)) / 1.4
        val center = c.kite()
        // A world view provides little local context for addresses or nearby businesses.
        val here = userLatLng
        if (!fromMap && here != null && (span > 120_000 || center == LatLng(51.5074, -0.1278))) return Pair(here, 20_000.0)
        return Pair(center, span.takeIf { it.isFinite() && it > 0 }?.coerceIn(1_500.0, 120_000.0))
    }

    fun search(text: String? = null, title: String? = null, fromMap: Boolean = false) {
        val q = (text ?: query).trim()
        if (q.isEmpty()) return
        if (title == null && MapLinks.looksLikeLink(q)) { openLink(q); return }
        query = title ?: q
        settings.addRecent(title ?: q)
        searchTask?.cancel()
        resetPlanRequests()
        selected = null
        directions = null
        searching = true
        notice = null
        if (!fromMap) {
            openNowOnly = false
            rating4Only = false
            sort = SortMode.RELEVANCE
        }
        val vp = searchViewport(fromMap)
        submittedSearch = q to title
        searchCenter = vp.first
        searchZoom = map?.cameraPosition?.zoom
        canSearchHere = false
        val osmOnly = settings.osmOnlySearch || settings.noGoogle
        val user = userLatLng
        val sameQuery = !fromMap && suggestedQuery == Place.fold(q)
        val pendingSuggestions = suggestTask.takeIf { sameQuery }
        val visibleSuggestions = suggestions.places.takeIf { sameQuery }.orEmpty()
        searchTask = scope.launch {
            val answer = if (osmOnly) PlaceSearch.Result(OpenPlaceSearch.search(q, vp.first, vp.second)) else coroutineScope {
                val full = async { PlaceSearch.search(q, vp.first, vp.second, useGoogle = true) }
                val local = async {
                    if (OutdoorData.classicKind(q) != null) return@async emptyList()
                    if (visibleSuggestions.isNotEmpty()) return@async visibleSuggestions
                    if (pendingSuggestions?.isActive == true) {
                        pendingSuggestions.join()
                        if (suggestedQuery == Place.fold(q)) return@async suggestions.places
                    }
                    try {
                        GoogleClient.shared.suggest(q, vp.first, vp.second ?: 20_000.0).places
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        emptyList()
                    }
                }
                val r = full.await()
                val found = PlaceSearch.forMap(q, vp.first, vp.second, r.places, local.await())
                val places = if (found.isNotEmpty() || OutdoorData.classicKind(q) != null) found
                    else PlaceSearch.forMap(q, vp.first, vp.second, PlaceSearch.namedNearby(q, vp.first, 24_000.0, useGoogle = true))
                PlaceSearch.Result(places, r.googleError)
            }
            var places = answer.places
            ensureActive()
            if (user != null) places = places.map { it.copy(distanceMeters = user.distanceTo(it.location)) }
            searching = false
            resultsTitle = title ?: q
            results = places
            if (places.isEmpty()) {
                notice = Connectivity.explain("No results for \"$q\" here. Try another word, or move the map.")
            } else if (answer.googleError != null) {
                notice = "Google didn't answer, so these results are from OpenStreetMap."
            }
            detent = SheetDetent.HALF
            if (places.isNotEmpty() && !fromMap) {
                val nearest = places.first().location
                frame(places.take(8).filter { nearest.distanceTo(it.location) <= 35_000 }.map { it.location })
            }
        }
    }

    fun typed(text: String) {
        suggestTask?.cancel()
        val q = text.trim()
        suggestedQuery = Place.fold(q)
        suggestions = SuggestParser.Result()
        if (q.length < 2 || MapLinks.looksLikeLink(q)) return
        val vp = viewport
        val osmOnly = settings.osmOnlySearch || settings.noGoogle
        val user = userLatLng
        suggestTask = scope.launch {
            delay(250)
            if (osmOnly) {
                val answer = OpenPlaceSearch.suggestions(q, vp.first)
                ensureActive()
                suggestions = if (user == null) answer else answer.copy(places = answer.places.map {
                    it.copy(distanceMeters = user.distanceTo(it.location))
                })
                return@launch
            }
            var places = emptyList<Place>()
            var queries = emptyList<String>()
            run {
                runCatching { GoogleClient.shared.suggest(q, vp.first, vp.second ?: 20_000.0) }.getOrNull()?.let {
                    places = it.places; queries = it.queries
                }
            }
            places = PlaceSearch.nearFirst(places, vp.first)
            if (places.isEmpty()) Postcodes.lookup(q)?.let { places = listOf(it) }
            if (PlaceSearch.needsNearbyFallback(places, vp.first)) {
                val open = runCatching { Photon.search(q, vp.first, 8) }.getOrDefault(emptyList())
                places = PlaceSearch.withNearbyFallback(places, open, vp.first)
            }
            ensureActive()
            if (user != null) places = places.map { it.copy(distanceMeters = user.distanceTo(it.location)) }
            suggestions = SuggestParser.Result(places, queries)
        }
    }

    fun openKiteLink(link: KiteLinks.Target) {
        tab = AppTab.MAP
        when (link) {
            KiteLinks.Target.Map -> Unit
            is KiteLinks.Target.Search -> {
                val text = link.query
                when {
                    nav != null && text != null -> searchStops(text, named = OutdoorData.kind(text) == null)
                    nav != null -> addingStop = true
                    !isGuiding && text != null -> search(text)
                    !isGuiding -> { directions = null; searchPrompt++ }
                }
            }
            is KiteLinks.Target.Place -> {
                val place = Place("pin:${link.at.lat},${link.at.lng}", link.name ?: "Dropped pin", link.at, "pin")
                if (nav != null) showNavigationPlace(place)
                else if (link.directions) openDirections(place)
                else if (!isGuiding) {
                    directions = null
                    dropPin(link.at, place.name)
                }
            }
            is KiteLinks.Target.Shortcut -> settings.placeShortcuts.firstOrNull { it.id == link.id }?.let {
                if (nav != null) showNavigationPlace(it) else openDirections(it)
            }
            is KiteLinks.Target.Saved -> perform(LaunchKind.valueOf(link.name))
        }
    }

    /** A pasted Google Maps, Waze or Apple Maps link (or coordinates): show the spot it points at. */
    fun openLink(text: String) {
        KiteLinks.parse(text)?.let { openKiteLink(it); return }
        if (Link(text.trim()).scheme.equals("kite", ignoreCase = true)) return
        searchTask?.cancel()
        suggestTask?.cancel()
        suggestedQuery = null
        suggestions = SuggestParser.Result()
        resetPlanRequests()
        selected = null
        directions = null
        results = emptyList()
        notice = null
        searching = true
        query = "Opening link…"
        searchTask = scope.launch {
            val target = runCatching { MapLinks.resolve(text) }.getOrNull()
            searching = false
            when (target) {
                is MapLinks.Target.Spot -> if (target.name != null) {
                    query = target.name!!
                    resolve(Place(id = "link:${target.at.lat},${target.at.lng}", name = target.name!!, location = target.at, source = "osm"))
                } else {
                    // Keep where it is in the box, not an empty "Where to?" as if nothing was found.
                    query = String.format(java.util.Locale.US, "%.5f, %.5f", target.at.lat, target.at.lng)
                    dropPin(target.at, "Shared location")
                }
                is MapLinks.Target.Search -> {
                    target.near?.let { n -> map?.moveCamera(CameraUpdateFactory.newLatLngZoom(org.maplibre.android.geometry.LatLng(n.lat, n.lng), 14.0)) }
                    search(target.text)
                }
                null -> {
                    query = ""
                    notice = Connectivity.explain("Couldn't find a location in that link.")
                }
            }
        }
    }

    /** A place picked from the suggestions: show it now, then fill in its details from Google. */
    fun openSuggestion(place: Place) {
        suggestTask?.cancel()
        searchTask?.cancel()
        searching = false
        notice = null
        settings.addRecent(place.name)
        query = place.name
        results = emptyList()
        directions = null
        resolve(place)
    }

    fun switchSearchSource(osmOnly: Boolean) {
        if (settings.noGoogle || settings.osmOnlySearch == osmOnly) return
        settings.osmOnlySearch = osmOnly
        searchTask?.cancel()
        searching = false
        typed(query)
    }

    fun backFromSearch() {
        if (selected != null && results.isNotEmpty()) closePlace() else clearSearch()
    }

    fun clearSearch() {
        submittedSearch = null
        searchCenter = null
        searchZoom = null
        canSearchHere = false
        searchTask?.cancel()
        suggestTask?.cancel()
        suggestedQuery = null
        suggestions = SuggestParser.Result()
        query = ""
        results = emptyList()
        resultsTitle = ""
        selected = null
        searching = false
        notice = null
    }

    fun select(place: Place, focus: Boolean = true) {
        var p = place
        settings.addRecentPlace(place)
        if (p.distanceMeters == null) userLatLng?.let { p = p.copy(distanceMeters = it.distanceTo(p.location)) }
        selected = p
        detent = SheetDetent.HALF
        if (!focus) return
        val m = map ?: return
        browseFollowing = false
        val camera = visibleCamera?.invoke() ?: m.cameraPosition
        val zoom = max(MapCam.zoomFor(900.0, p.location.lat, mapWidthDp), min(camera.zoom, 18.0).takeIf { it > 16 } ?: 0.0)
        MapCam.animate(m, CameraPosition.Builder().target(p.location.ml()).zoom(zoom)
            .tilt(browsePitch).bearing(if (browseLook == MapLook.NORTH) 0.0 else camera.bearing).build(),
            padding = intArrayOf(0, 120, 0, (mapHeightDp * 0.45f).toInt()), ms = 320, native = nativeCamera)
    }

    fun closePlace() {
        selected = null
        if (results.isNotEmpty()) {
            detent = SheetDetent.HALF
            val nearest = results.first().location
            frame(results.take(8).filter { nearest.distanceTo(it.location) <= 35_000 }.map { it.location })
        }
    }

    /** A tap on a place the basemap draws: look it up on Google by name at that spot. */
    fun resolveTapped(name: String, point: LatLng, category: String?, source: String) {
        resolve(Place(id = "tap:${point.lat},${point.lng}", name = name, location = point, source = source, category = category))
    }

    /** Show [placeholder] right away, then swap in Google's full place found at the same spot. */
    private fun resolve(placeholder0: Place) {
        val point = placeholder0.location
        val placeholder = placeholder0.copy(distanceMeters = userLatLng?.distanceTo(point))
        select(placeholder, focus = !placeholder.id.startsWith("tap:"))
        if (settings.noGoogle || settings.osmOnlySearch && placeholder.source == "osm") return
        val q = listOfNotNull(placeholder.name, placeholder.address).joinToString(" ")
        val pid = placeholder.id
        scope.launch {
            val found = runCatching { GoogleClient.shared.search(q, point, 1_000.0, pages = 1) }.getOrDefault(emptyList())
            if (selected?.id != pid) return@launch
            // The same place by name too, not just the nearest: a café next door mustn't take its place.
            val best = found.filter { it.location.distanceTo(point) < 150 && similarNames(it.name, placeholder.name) }
                .minByOrNull { it.location.distanceTo(point) } ?: return@launch
            var b = best
            if (pid.startsWith("tap:")) b = b.copy(location = point)
            b = b.copy(distanceMeters = userLatLng?.distanceTo(b.location))
            selected = b
            settings.addRecentPlace(b)
        }
    }

    /**
     * A saved place: its "Open · Closes 10 pm" was true the day it was saved, not now. Show it
     * without that, then fill in today's from Google (left out with no internet).
     */
    fun openSaved(saved: Place) {
        val p = saved.copy(statusText = null, openNow = null)
        select(p)
        if (settings.noGoogle) return
        val q = listOfNotNull(p.name, p.address).joinToString(" ")
        scope.launch {
            val found = runCatching { GoogleClient.shared.search(q, p.location, 1_000.0, pages = 1) }.getOrDefault(emptyList())
            val fresh = found.firstOrNull { it.location.distanceTo(p.location) < 150 && similarNames(it.name, p.name) } ?: return@launch
            if (selected?.id == p.id) selected = selected?.copy(statusText = fresh.statusText, openNow = fresh.openNow,
                hours = fresh.hours, permanentlyClosed = fresh.permanentlyClosed)
        }
    }

    /** "Costa" and "Costa Coffee", "St Mary's Church" and "St. Mary's": every word of one name is in the other. */
    private fun similarNames(a: String, b: String): Boolean {
        fun words(s: String) = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}"), "").split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
        val x = words(a); val y = words(b)
        if (x.isEmpty() || y.isEmpty()) return false
        // Whole words, so "Costa" isn't "Costas" and "Royal Hotel" isn't "Royal Cafe".
        return y.containsAll(x) || x.containsAll(y)
    }

    // My places

    /** A home-screen shortcut: directions home or to work, or back to the car. */
    fun perform(kind: LaunchKind) {
        scope.launch {
            repeat(20) { if (userLatLng == null) delay(250) }
            val target = when (kind) {
                LaunchKind.HOME -> settings.home
                LaunchKind.WORK -> settings.work
                LaunchKind.CAR -> settings.parking
            }
            if (target == null) {
                if (kind == LaunchKind.CAR) { nav?.say("No parking spot saved yet.") ?: run { notice = "No parking spot saved yet. Tap P on the map when you park." } }
                // Mid-drive, keep the trip going: a picker on top of it, cancelled, would lose it.
                else if (nav != null) nav?.say("No ${if (kind == LaunchKind.HOME) "home" else "work"} address saved yet. Set it in Saved after this drive.")
                else picking = if (kind == LaunchKind.HOME) PickKind.HOME else PickKind.WORK
                return@launch
            }
            // Only now that there's somewhere to go does the current drive end.
            if (isGuiding) endNavigation()
            openDirections(target, if (kind == LaunchKind.CAR) TravelMode.WALK else settings.defaultMode)
        }
    }

    /** Home, work, the parked car or a saved place, by id, for a tap on its map icon. */
    fun myPlace(id: String): Place? =
        listOfNotNull(settings.home, settings.work, settings.parking).firstOrNull { it.id == id } ?: settings.saved.firstOrNull { it.id == id }

    fun nameNewShortcut(place: Place) {
        shortcutDraft = place.copy(id = "shortcut:" + java.util.UUID.randomUUID())
    }

    fun setHome(p: Place?) {
        settings.home = p?.copy(distanceMeters = null)
        if (p != null) notice = "Saved as your home."
    }

    fun setWork(p: Place?) {
        settings.work = p?.copy(distanceMeters = null)
        if (p != null) notice = "Saved as your work."
    }

    /** Remember where the car is: here, now, with the street name when one is found. */
    fun saveParking() {
        val me = userLatLng ?: run {
            notice = if (location.denied) "Location is off for Kite. Turn it on in Settings to save where you parked."
            else "Still finding your location. Try again in a moment."
            return
        }
        val p = Place(id = "parking:${System.currentTimeMillis() / 1000}", name = "Parked car", location = me, source = "osm",
            category = "Parked at ${Fmt.time(java.util.Date())}")
        settings.parking = p
        notice = "Parking spot saved. Tap the P button to find your car."
        scope.launch {
            val found = Photon.reverse(me) ?: return@launch
            if (settings.parking?.id != p.id) return@launch
            settings.parking = settings.parking?.copy(address = listOfNotNull(found.name, found.address).joinToString(", "))
        }
    }

    fun clearParking() {
        if (selected?.id == settings.parking?.id) selected = null
        settings.parking = null
    }

    /** Long press: a dropped pin with the address there. */
    fun dropPin(point: LatLng, name: String = "Dropped pin") {
        val pin = Place(id = "pin:${point.lat},${point.lng}", name = name, location = point, source = "osm",
            category = String.format(java.util.Locale.US, "%.5f, %.5f", point.lat, point.lng))
        select(pin)
        scope.launch {
            val found = Photon.reverse(point) ?: return@launch
            if (selected?.id != pin.id) return@launch
            selected = pin.copy(address = listOfNotNull(found.name, found.address).joinToString(", "))
        }
    }

    // Directions

    fun openDirections(place: Place, mode: TravelMode = settings.defaultMode, startNow: Boolean = false) {
        if (isGuiding) return
        endCruise()
        closeStopSearch(restore = false)
        addingStop = false
        tab = AppTab.MAP
        if (resumeOffer != null) dismissResume()
        resetPlanRequests()
        val useHere = startNow || userLatLng?.distanceTo(place.location)?.let { it < 350_000.0 } != false
        directions = DirectionsState(destination = place, mode = mode, startRequested = startNow, useCurrentOrigin = useHere)
        detent = SheetDetent.HALF
        if (startNow) location.last?.let(::centerForNavigationStart)
        if (!useHere) frame(listOf(place.location))
        loadPlan()
    }

    fun closeDirections() {
        closeStopSearch(restore = false)
        resetPlanRequests()
        directions = null
        showSteps = false
        selected?.let { select(it) }
    }

    fun reverseDirections() {
        if (directions?.missingOrigin == true) {
            pickingOrigin = true
            return
        }
        directions?.let { reorderPlan(it.orderedPlaces().asReversed()) }
    }

    fun moveWaypoint(index: Int, step: Int) {
        val places = directions?.orderedPlaces()?.toMutableList() ?: return
        val target = index + step
        if (index !in places.indices || target !in places.indices) return
        val item = places.removeAt(index)
        places.add(target, item)
        reorderPlan(places)
    }

    private fun reorderPlan(places: List<Place?>) {
        val d = directions ?: return
        if (places.size < 2) return
        val middle = places.drop(1).dropLast(1)
        val snap = if (middle.any { it == null }) currentWaypoint() ?: return else null
        val orderedStops = middle.mapNotNull { it ?: snap }
        val from = places.first()
        val to = places.last()
        val updated = if (to == null && from != null) {
            d.copy(destination = from, reversed = true, start = null, stops = orderedStops.asReversed())
        } else if (to != null) {
            d.copy(destination = to, reversed = false, start = from, stops = orderedStops)
        } else return
        directions = updated
        reloadRoutes()
    }

    private fun currentWaypoint(): Place? = userLatLng?.let {
        Place("waypoint:${it.lat},${it.lng}", "Your location", it, "pin")
    }

    fun routePlan(name: String, trip: TransitTrip? = directions?.pickedTrip): SavedPlan? {
        val d = directions?.takeUnless { it.missingOrigin } ?: return null
        val points = d.orderedPlaces().map { it ?: currentWaypoint() ?: return null }
        return SavedPlan(name.trim().ifEmpty { "${points.first().name} → ${points.last().name}" }, points, d.mode, transit = d.transit,
            transitDeparture = trip?.depart, transitLines = trip?.savedLineKeys().orEmpty())
    }

    fun saveTransitPlan(trip: TransitTrip) {
        val plan = routePlan("", trip) ?: return
        if (settings.savedPlans.none {
            it.transit && it.points == plan.points && it.transitDeparture == plan.transitDeparture && it.transitLines == plan.transitLines
        }) settings.savedPlans = listOf(plan) + settings.savedPlans
    }

    fun openSavedPlan(plan: SavedPlan) {
        tab = AppTab.MAP
        clearSearch()
        closeStopSearch(restore = false)
        addingStop = false
        resetPlanRequests()
        val first = plan.points.first()
        val now = System.currentTimeMillis()
        val departure = plan.transitDeparture?.takeIf { it > now }
        directions = DirectionsState(plan.points.last(), mode = plan.mode,
            stops = plan.points.drop(1).dropLast(1), start = first, transit = plan.transit,
            transitTime = departure?.let { max(now, it - 60_000) },
            preferredTransitLines = plan.transitLines.takeIf { departure != null }.orEmpty())
        detent = SheetDetent.HALF
        loadPlan()
    }

    fun setMode(m: TravelMode) {
        val d = directions ?: return
        if (!d.transit && d.mode == m) return
        edit { it.copy(transit = false, mode = m, selected = 0, startRequested = false) }
        settings.defaultMode = m
        if (m !in d.routes && m !in d.loading) loadPlan()
        frameRoute()
    }

    private var stopDraftJob: Job? = null
    var stopDraft by mutableStateOf<StopSearch?>(null)
        private set

    fun clearStopDraft() {
        stopDraftJob?.cancel()
        stopDraftJob = null
        stopDraft = null
    }

    fun searchStops(query: String, named: Boolean = false, parking: Boolean = false, draft: Boolean = false) {
        if (!draft) navigationPlace = null
        val q = query.trim()
        if (q.isEmpty()) return
        val driving = nav
        val d = directions
        val route = driving?.route ?: d?.route
        val endpoints = d?.endpoints(userLatLng)
        val origin = endpoints?.first ?: userLatLng ?: viewport.first
        val line = route?.polyline?.takeIf { it.size >= 2 } ?: listOf(origin, endpoints?.second ?: origin)
        val cum = Geo.cumulative(line)
        val start = (driving?.progress?.alongMeters ?: 0.0).coerceIn(0.0, cum.last())
        val end = if (driving == null) cum.last() else min(cum.last(), start + 42_000.0)
        val span = end - start
        val existing = driving?.stops ?: d?.stops.orEmpty()
        val destinationId = (driving?.destination ?: d?.orderedPlaces()?.lastOrNull())?.id
        val useGoogle = settings.welcomed && !settings.noGoogle
        val search = StopSearch(q, wasFollowing = stopSearch?.wasFollowing ?: (driving?.following == true), parking = parking)
        clearStopDraft()
        if (draft) {
            stopDraft = search
        } else {
            stopSearchJob?.cancel()
            stopSearch = search
            addingStop = false
            driving?.following = false
            map?.cancelTransitions()
        }
        val job = scope.launch {
            val results = withContext(Dispatchers.Default) {
                if (parking && driving != null) {
                    val target = driving.destination.location
                    val places = coroutineScope {
                        val osm = async {
                            val nearby = ArrivalHelp.parking(target)?.map { park ->
                                Place("osm:parking:${park.id}", park.name ?: "Parking", park.location, "osm",
                                    category = "Parking", address = listOfNotNull(park.kind,
                                        park.fee?.let { if (it) "Paid parking" else "Free parking" },
                                        park.spaces?.let { "$it spaces" }).joinToString(" · ").ifBlank { null })
                            }.orEmpty()
                            nearby.ifEmpty { OutdoorData.find(OutdoorData.PARKING, target, 1400.0).orEmpty() }
                        }
                        val google = async { if (useGoogle) PlaceSearch.namedNearby("parking", target, 2800.0, true) else emptyList() }
                        OutdoorData.combine(google.await(), osm.await())
                    }
                    return@withContext places.map { StopCandidate(it, null, target.distanceTo(it.location)) }
                        .filter { it.offset <= 1500 && it.place.id != destinationId }.sortedBy { it.offset }.take(20)
                }
                val count = ceil(span / (if (driving == null) 27_000 else 14_000)).toInt()
                    .coerceIn(1, if (draft) 3 else if (driving == null) 72 else 3)
                val window = Geo.slice(line, start, end, cum)
                val windowCum = Geo.cumulative(window)
                val found = (0 until count).chunked(4).flatMap { batch ->
                    coroutineScope {
                        batch.map { i ->
                            async {
                                val center = if (span > 0) Geo.point(line, start + span * (i + 0.5) / count, cum) else origin
                                val width = max(4_000.0, span / count * 1.65)
                                if (named) PlaceSearch.namedNearby(q, center, width, useGoogle)
                                else PlaceSearch.search(q, center, width, useGoogle).places
                            }
                        }.awaitAll().flatten()
                    }
                }
                val candidates = found.mapNotNull { p ->
                    if (p.id == destinationId || existing.any(p::matchesNearby)) return@mapNotNull null
                    if (span <= 1 || window.size < 2) {
                        val distance = origin.distanceTo(p.location)
                        return@mapNotNull if (distance < 3500) StopCandidate(p, null, distance) else null
                    }
                    val at = Geo.project(p.location, window, windowCum)
                    val corridor = if (named) 5_000.0 else 2_200.0
                    if (at.offset > corridor || (driving != null && at.along < 30 && !named)) return@mapNotNull null
                    StopCandidate(p, at.along, at.offset, start + at.along)
                }
                val ranked = if (driving != null || span <= 42_000) candidates.sortedBy { (it.ahead ?: 0.0) + it.offset * 3 }
                    else {
                        val bands = candidates.groupBy { ((it.ahead ?: 0.0) / (span / 30).coerceAtLeast(28_000.0)).toInt() }
                        bands.values.flatMap { group -> group.sortedBy { it.offset }.take(3) }.sortedBy { it.ahead }
                    }
                buildList<StopCandidate> {
                    for (p in ranked) {
                        if (none { it.place.matchesNearby(p.place) }) add(p)
                        if (size == 100) break
                    }
                }
            }
            if (draft) {
                if (stopDraft === search && addingStop) stopDraft = search.copy(loading = false, results = results.take(14))
                return@launch
            }
            if (stopSearch !== search) return@launch
            if (nav !== driving || directions?.destination?.id != d?.destination?.id) {
                closeStopSearch(restore = false)
                return@launch
            }
            if (driving != null && driving.route.id != route?.id) {
                searchStops(q, named, parking)
                return@launch
            }
            stopSearch = search.copy(loading = false, results = results)
            if (results.isNotEmpty()) {
                val first = if (parking) driving?.destination?.location ?: origin
                    else if (span > 0) Geo.point(line, start, cum) else origin
                val visible = if (driving == null && span > 42_000) results else results.take(7)
                frame(listOf(first) + visible.map { it.place.location }, bottomFraction = 0.27f, top = 125)
            }
            if (!parking) launch {
                val prices = FuelPrices.ukQuotes(results.map { it.place })
                if (prices.isNotEmpty()) stopSearch?.takeIf { it.results === results }?.let {
                    stopSearch = it.copy(fuelQuotes = prices)
                }
            }
            if (!parking && route?.mode == TravelMode.DRIVE && span > 0) {
                val candidates = results.filter { it.routeMeters != null }.take(8)
                if (candidates.isNotEmpty()) {
                    val here = Geo.point(line, start, cum)
                    val rejoin = candidates.map { Geo.point(line, min(end, requireNotNull(it.routeMeters) + 1_200), cum) }
                    val detours = OSRM.detours(here, candidates.map { it.place.location }, rejoin)
                    ensureActive()
                    if (detours != null && nav === driving && (nav?.route ?: directions?.route)?.id == route.id) {
                        stopSearch?.takeIf { it.results === results }?.let { current ->
                            val times = candidates.zip(detours).mapNotNull { (candidate, seconds) ->
                                seconds?.let { candidate.place.id to it }
                            }.toMap()
                            stopSearch = current.copy(detours = times)
                        }
                    }
                }
            }
        }
        if (draft) stopDraftJob = job else stopSearchJob = job
    }

    fun selectStopPin(id: String?) {
        val search = stopSearch ?: return
        val selected = search.results.firstOrNull { it.place.id == id }
        if (search.selected != selected) stopSearch = search.copy(selected = selected)
    }

    fun showStopPin(candidate: StopCandidate) {
        selectStopPin(candidate.place.id)
        frame(listOf(candidate.place.location), bottomFraction = 0.27f, top = 125)
    }

    fun closeStopSearch(restore: Boolean = true) {
        val search = stopSearch ?: return
        stopSearchJob?.cancel()
        stopSearch = null
        if (restore) {
            if (search.wasFollowing && nav != null) recenterNav()
            else if (nav == null) frameRoute()
        }
    }

    fun acceptStop(candidate: StopCandidate) {
        val driving = nav
        if (driving != null && stopSearch?.parking == true) {
            closeStopSearch()
            driving.parkAt(candidate.place)
            return
        }
        val route = driving?.route ?: directions?.route
        val existing = driving?.stops ?: directions?.orderedPlaces()?.drop(1)?.dropLast(1)?.filterNotNull().orEmpty()
        val along = candidate.routeMeters
        val cum = route?.let { Geo.cumulative(it.polyline) }
        val idx = if (along == null || route == null || cum == null) (if (driving != null) 0 else existing.size) else {
            existing.indexOfFirst { Geo.project(it.location, route.polyline, cum).along > along }
                .takeIf { it >= 0 } ?: existing.size
        }
        closeStopSearch(restore = driving != null)
        if (driving != null) driving.addStop(candidate.place, idx) else addStop(candidate.place, idx)
        addingStop = false
    }

    fun addStop(p: Place, index: Int = directions?.stops?.size ?: 0) {
        val d = directions ?: return
        if (d.stops.size >= 8) return
        val insertion = (if (d.reversed) d.stops.size - index else index).coerceIn(0, d.stops.size)
        edit { it.copy(stops = it.stops.toMutableList().apply { add(insertion, p) }) }
        reloadRoutes()
    }

    fun removeStop(i: Int) {
        val d = directions ?: return
        if (i !in d.stops.indices) return
        val index = if (d.reversed) d.stops.lastIndex - i else i
        edit { it.copy(stops = it.stops.filterIndexed { j, _ -> j != index }) }
        reloadRoutes()
    }

    /** 0 leave now, 1 depart at, 2 arrive by. */
    fun setTransitTime(kind: Int, atMillis: Long) {
        edit { it.copy(transitTime = if (kind == 0) null else atMillis, arriveBy = kind == 2, preferredTransitLines = emptyList()) }
        loadTransit()
    }

    fun showTransit() {
        val d = directions ?: return
        if (d.transit) return
        edit { it.copy(transit = true, startRequested = false) }
        if (d.trips == null || d.transitTime == null && System.currentTimeMillis() - transitLoadedAt > 120_000) loadTransit()
        frameRoute()
    }

    fun selectTrip(i: Int) { edit { it.copy(trip = i) }; frameRoute() }

    fun selectRoute(i: Int) { edit { it.copy(selected = i) }; mapTick++ }

    fun reloadRoutes() {
        if (directions == null) return
        resetPlanRequests()
        edit { it.copy(routes = emptyMap(), loading = emptySet(), errors = emptyMap(), selected = 0,
            trips = null, tripsError = null, trip = 0, avoid = emptyList(), avoidNames = emptyList(), beforeAvoid = null) }
        loadPlan()
    }

    /**
     * End the trip at [p] instead of the place picked (its main entrance, or a car park near it),
     * with [note] saying so; null goes back to the place itself.
     */
    fun endAt(p: LatLng?, note: String?) {
        val d = directions ?: return
        val place = d.original ?: d.destination
        if (p != null) {
            val dest = place.copy(location = p, name = if (note != null && note != "Main entrance") note else place.name)
            edit { it.copy(original = place, destination = dest, endNote = note) }
        } else {
            edit { it.copy(destination = place, original = null, endNote = null) }
        }
        reloadRoutes()
    }

    /** Start the trip from [p] instead of here; null goes back to your location. */
    fun setOrigin(p: Place?) {
        if (directions == null) return
        edit { it.copy(start = p, reversed = false, useCurrentOrigin = true) }
        reloadRoutes()
    }

    /** "Avoid Bassett Avenue?" after tapping the planned route. */
    var avoidAsk by mutableStateOf<AvoidAsk?>(null)
    var avoidBusy by mutableStateOf(false)
    private var avoidJob: Job? = null
    var pickingOrigin by mutableStateOf(false)
    var pickingDestination by mutableStateOf(false)
    var placingEndpoint by mutableStateOf<Boolean?>(null)
        private set

    fun beginEndpointPin(destination: Boolean) {
        if (directions == null) return
        pickingOrigin = false
        pickingDestination = false
        placingEndpoint = destination
    }

    fun finishEndpointPin(at: LatLng? = null) {
        val destination = placingEndpoint ?: return
        placingEndpoint = null
        if (directions == null) return
        if (at == null) {
            if (destination) pickingDestination = true else pickingOrigin = true
            return
        }
        val point = Place("pin:${at.lat},${at.lng}", "Dropped pin", at, "pin")
        if (destination) setDestination(point) else setOrigin(point)
    }

    fun positionEndpointPin(at: LatLng): Boolean {
        if (placingEndpoint == null) return false
        val m = map ?: return true
        val camera = visibleCamera?.invoke() ?: m.cameraPosition
        MapCam.animate(m, CameraPosition.Builder(camera).target(at.ml()).build(), ms = 220, native = nativeCamera)
        return true
    }


    fun setDestination(place: Place) {
        val plan = directions ?: return
        directions = plan.replacingDestination(place)
        reloadRoutes()
    }
    /** Waze reports found along the route being planned, shown as icons on it before you go. */
    var routeReports by mutableStateOf<List<WazeAlert>>(emptyList())
    /** The place whose Reviews tab to open (its stars were tapped). */
    var showReviews by mutableStateOf<String?>(null)

    /** The road under a tap on the planned route, to ask whether to avoid it. */
    fun askAvoid(tap: LatLng) {
        val d = directions ?: return
        if (d.transit || d.mode != TravelMode.DRIVE) return
        val r = d.route ?: return
        val hit = Geo.project(tap, r.polyline)
        if (hit.offset >= 60) return
        val total = Geo.length(r.polyline)
        // Not the very start or end: the trip has to begin and finish somewhere.
        if (hit.along <= 150 || hit.along >= total - 150) return
        val at = Geo.point(r.polyline, hit.along)
        val road = r.maneuvers.lastOrNull { it.alongMeters <= hit.along + 1 }?.road
        avoidAsk = AvoidAsk(at, road)
    }

    /** A way round the road just tapped: the road map's router kept off it, with turn-by-turn from the open router. */
    fun avoidRoad() {
        val ask = avoidAsk ?: return
        avoidAsk = null
        val d = directions ?: return
        val r = d.route ?: return
        if (r.polyline.size < 2) return
        val name = ask.road ?: "that road"
        val points = d.avoid + ask.point
        val via = (if (d.reversed) d.stops.reversed() else d.stops).map { it.location }
        val from = r.polyline.first(); val to = r.polyline.last()
        val destId = d.destination.id
        avoidJob?.cancel()
        avoidBusy = true
        avoidJob = scope.launch {
            val found = Directions.avoiding(from, to, via, points)
            ensureActive()
            avoidBusy = false
            if (directions?.destination?.id != destId) return@launch
            if (found == null) {
                notice = "Couldn't find a way round $name."
                return@launch
            }
            if (directions?.mode != TravelMode.DRIVE) return@launch
            edit { it.copy(beforeAvoid = it.beforeAvoid ?: it.routes[TravelMode.DRIVE], avoid = points, avoidNames = it.avoidNames + name,
                routes = it.routes + (TravelMode.DRIVE to listOf(found)), selected = 0) }
            frameRoute()
        }
    }

    /** Back to the routes from before any road was avoided. */
    fun undoAvoid() {
        val before = directions?.beforeAvoid ?: return
        edit { it.copy(routes = it.routes + (TravelMode.DRIVE to before), avoid = emptyList(), avoidNames = emptyList(), beforeAvoid = null, selected = 0) }
        frameRoute()
    }

    private fun resetPlanRequests() {
        routePlanJob?.cancel()
        routePlanJob = null
        routeReports = emptyList()
        avoidAsk = null
        avoidJob?.cancel()
        avoidJob = null
        avoidBusy = false
        transitPlanJob?.cancel()
        transitPlanJob = null
        transitLoadedAt = 0L
    }

    private val navigationFix: Fix? get() = location.last?.takeIf { it.ageMs in 0..120_000 }

    private fun planEndpoints(): Pair<LatLng, LatLng>? {
        val d = directions?.takeUnless { it.missingOrigin } ?: return null
        var here = navigationFix?.coord
        if (here == null) {
            location.start()
            here = navigationFix?.coord
        }
        val (from, to) = d.endpoints(here)
        val waiting = from == null || to == null
        edit { it.copy(waitingForLocation = waiting) }
        return if (from != null && to != null) from to to else null
    }

    private fun loadPlan() {
        val d = directions ?: return
        if (d.transit) {
            loadTransit()
            return
        }
        val (from, to) = planEndpoints() ?: return
        val via = d.orderedPlaces().drop(1).dropLast(1).filterNotNull().map { it.location }
        val heading = navigationFix?.takeIf { !d.reversed && d.start == null && it.speed >= 4 && it.courseKnown }?.course
        val modes = if (d.startRequested) listOf(d.mode) else TravelMode.entries.filter { it !in d.routes }.sortedBy { it != d.mode }
        if (modes.isEmpty()) return
        routePlanJob?.cancel()
        val hw = settings.avoidHighways
        val tolls = settings.avoidTolls
        val google = !settings.noGoogle
        val mapboxToken = settings.activeMapboxToken
        edit { it.copy(loading = modes.toSet(), errors = it.errors - modes.toSet(), waitingForLocation = false) }
        routePlanJob = scope.launch {
            for (mode in modes) launch {
                val result = try {
                    val preferred = Directions.routes(from, to, mode, hw, tolls, google, via, heading.takeIf { mode == TravelMode.DRIVE },
                        alternatives = !d.startRequested, mapboxToken = mapboxToken, engine = settings.routeEngine(mode))
                    if (mode == TravelMode.DRIVE && d.avoid.isNotEmpty()) {
                        val clear = preferred.routes.filterNot { Directions.touches(it.polyline, d.avoid) }
                        if (clear.isNotEmpty()) Directions.Result(clear)
                        else if (settings.driveEngine == RouteEngine.AUTO) {
                            Directions.avoiding(from, to, via, d.avoid)?.let { Directions.Result(listOf(it)) }
                                ?: Directions.Result(emptyList(), "No route found.")
                        } else Directions.Result(emptyList(), "The selected engine did not find a route that skips those roads.")
                    } else preferred
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Directions.Result(emptyList(), e.message)
                }
                ensureActive()
                val routes = result.routes.filter { it.polyline.size >= 2 }.sortedBy { it.durationSeconds }
                val error = Connectivity.explain(result.error ?: "Couldn't get a route just now. Try again in a moment.")
                val shouldStart = directions?.let { !it.transit && it.mode == mode && it.startRequested } == true
                edit { it.copy(loading = it.loading - mode,
                    routes = if (routes.isEmpty()) it.routes else it.routes + (mode to routes),
                    errors = if (routes.isEmpty()) it.errors + (mode to error) else it.errors - mode,
                    startRequested = it.startRequested && !(shouldStart && routes.isEmpty())) }
                if (shouldStart && routes.isNotEmpty()) startNavigation()
                else if (directions?.let { !it.transit && it.mode == mode } == true) frameRoute()
            }
        }
    }

    /** Transit data belongs to the transit tab. Opening a road plan does not contact the transit router. */
    fun loadTransit() {
        val d = directions?.takeIf { it.transit } ?: return
        transitPlanJob?.cancel()
        val (from, to) = planEndpoints() ?: return
        if (d.stops.isNotEmpty()) {
            edit { it.copy(trips = emptyList(), tripsError = "Public transport can't go via stops. Remove the stops to see trains and buses.") }
            return
        }
        transitLoadedAt = System.currentTimeMillis()
        edit { it.copy(trips = null, tripsError = null, trip = 0) }
        transitPlanJob = scope.launch {
            try {
                val trips = Transitous.plan(from, to, d.transitTime, d.arriveBy)
                ensureActive()
                val preferred = trips.indexOfFirst { it.savedLineKeys() == d.preferredTransitLines }.coerceAtLeast(0)
                edit { it.copy(trips = trips, trip = preferred, tripsError = if (trips.isEmpty()) "No transit trips found here." else null) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                ensureActive()
                edit { it.copy(trips = emptyList(), tripsError = Connectivity.explain("Couldn't reach the transit planner.")) }
            }
            if (directions?.transit == true) frameRoute()
        }
    }

    fun frameRoute() {
        if (isGuiding) return
        val plan = directions ?: return
        if (plan.startRequested) return
        plan.pickedTrip?.let { trip ->
            if (trip.path.size >= 2) frame(trip.path, bottomFraction = 0.42f)
            mapTick++
            return
        }
        val route = plan.route ?: run { mapTick++; return }
        frame(plan.current.ifEmpty { listOf(route) }.flatMap { it.polyline },
            bottomFraction = 0.47f, top = 156, flat = true, marginZoom = 0.40)
    }

    /** Police risk within about a kilometre of you, from remembered and live Waze police reports. */
    fun updateAreaRisk(live: List<WazeAlert>) {
        val fix = location.last
        if (!settings.wazeOn || !settings.policeRisk || !settings.wazePolice || location.reducedAccuracy || fix == null ||
            fix.accuracy > 200 || fix.ageMs > 120_000) { areaRisk = null; return }
        val here = fix.coord
        val d = 0.0096 / max(cos(here.lat * PI / 180), 0.2) * 0.62
        val line = listOf(LatLng(here.lat, here.lng - d), LatLng(here.lat, here.lng + d))
        val near = live.count { it.kind == WazeAlert.Kind.POLICE && it.location.distanceTo(here) < 1_500 }
        areaRisk = PoliceRisk.score(line, WazeHistory.reports, near, radius = 900.0)
    }

    // Navigation

    private var navZoomSteps = 0
    private var navigationCameraUntil = 0L

    fun startTransitNavigation() {
        val d = directions ?: return
        val trip = d.pickedTrip?.takeIf { it.legs.isNotEmpty() } ?: return
        if (isGuiding) return
        val destination = d.orderedPlaces().last() ?: currentWaypoint() ?: return
        endCruise()
        resetPlanRequests()
        directions = null
        browseFollowing = false
        val session = TransitGuidance(trip, destination)
        transitGuidance = session
        location.last?.let { session.observe(it) }
        settings.trip = null
        resumeOffer = null
        location.setNavigating(true)
        DriveService.start(KiteApp.app, "Using transit with Kite")
        transitClockJob = scope.launch {
            while (transitGuidance === session && session.phase != TransitGuidance.Phase.COMPLETE) {
                delay(5_000)
                session.tick(System.currentTimeMillis(), transitLive[session.leg?.tripId])
            }
        }
        session.prepareLeg()
        setTransitLeg(session)
    }

    private fun setTransitLeg(session: TransitGuidance) {
        stopTransitOverview()
        transitWalkJob?.cancel()
        transitBoardJob?.cancel()
        transitDeparturesJob?.cancel()
        session.walking?.stop()
        session.walking = null
        glide.stop()
        navZoomSteps = 0
        transitCameraAt = 0
        val leg = session.leg
        if (leg == null) {
            location.setNavigating(false)
            DriveService.stop(KiteApp.app)
        } else when (session.phase) {
            TransitGuidance.Phase.WALK -> walkTransitLeg(session, location.last)
            TransitGuidance.Phase.BOARD -> {
                (leg.calls.firstOrNull()?.at ?: leg.path.firstOrNull())?.let {
                    frame(listOf(it), bottomFraction = 0.23f, top = 135)
                }
                DriveService.show(KiteApp.app, "Board ${leg.line} at ${leg.fromName}", leg.headsign ?: leg.toName)
                val first = leg.calls.firstOrNull()
                if (first?.stopId != null && leg.tripId != null) {
                    transitDeparturesJob = scope.launch {
                        while (transitGuidance === session && session.phase == TransitGuidance.Phase.BOARD) {
                            val board = withContext(Dispatchers.Default) {
                                Transitous.departures(first.at, stopId = first.stopId, stopName = first.name)
                            }
                            if (transitGuidance === session && session.leg === leg)
                                session.liveDeparture = board?.departures?.firstOrNull { it.tripId == leg.tripId }
                            delay(40_000)
                        }
                    }
                }
            }
            else -> {}
        }
        mapTick++
    }

    private fun walkTransitLeg(session: TransitGuidance, fix: Fix?) {
        val leg = session.leg ?: return
        if (!leg.isWalk || transitWalkJob?.isActive == true) return
        val destination = session.trip.legs.getOrNull(session.legIndex + 1)?.takeUnless { it.isWalk }?.let {
            it.calls.firstOrNull()?.at ?: it.path.firstOrNull()
        } ?: leg.path.lastOrNull()
        val freshFix = fix?.takeIf { it.ageMs in 0..15_000 && it.accuracy in 0.0..65.0 }
        val origin = freshFix?.coord ?: leg.path.firstOrNull()
        if (destination == null || origin == null) {
            session.walkingError = true
            return
        }
        val name = leg.toName.takeUnless { it.isBlank() || it == "END" } ?: session.destination.name
        if (freshFix != null && freshFix.accuracy <= 35 && origin.distanceTo(destination) < 23 && leg.distanceMeters < 80) {
            advanceTransitLeg()
            return
        }
        val end = Place("transit-walk:${session.legIndex}", name, destination, "transitous")
        val index = session.legIndex
        session.walkingError = false
        transitWalkJob = scope.launch {
            val route = if (settings.walkEngine == RouteEngine.AUTO && leg.walkSteps.isNotEmpty() &&
                leg.path.size >= 2 && origin.distanceTo(leg.path.first()) < 50) {
                Route(TravelMode.WALK, leg.path, leg.distanceMeters, leg.durationSeconds, null, null,
                    leg.walkSteps.map { it.copy() }.toMutableList(), source = "transitous")
            } else Directions.routes(origin, destination, TravelMode.WALK, useGoogle = !settings.noGoogle, mapboxToken = settings.activeMapboxToken,
                engine = settings.walkEngine).routes.firstOrNull()
            if (transitGuidance !== session || session.legIndex != index) return@launch
            if (route == null) {
                session.walkingError = true
                frame(leg.path, bottomFraction = 0.24f, top = 135)
                return@launch
            }
            val walking = Navigator(route, end, emptyList(), scope, transitWalk = true)
            session.walking = walking
            navigationFix?.let { now ->
                walking.handle(now)
                if (walking.arrived) { advanceTransitLeg(); return@launch }
                followCamera(walking, now)
            }
            mapTick++
        }
    }

    fun retryTransitWalk() {
        val session = transitGuidance ?: return
        walkTransitLeg(session, location.last)
    }

    fun boardTransit() {
        val session = transitGuidance ?: return
        val leg = session.leg ?: return
        if (session.phase != TransitGuidance.Phase.BOARD || session.boarding) return
        session.boarding = true
        transitBoardJob = scope.launch {
            val tracker = withContext(Dispatchers.Default) { TransitRide(leg) }
            if (transitGuidance !== session || session.leg !== leg) return@launch
            transitDeparturesJob?.cancel()
            stopTransitOverview()
            session.board(tracker)
            location.last?.let { session.observe(it) }
            frame(leg.path, bottomFraction = 0.24f, top = 148)
            DriveService.show(KiteApp.app, "Riding ${leg.line}", "Get off at ${leg.toName}")
            mapTick++
        }
    }

    fun advanceTransitLeg() {
        val session = transitGuidance ?: return
        session.advance()
        setTransitLeg(session)
    }

    fun replanTransit() {
        val destination = transitGuidance?.destination ?: return
        endNavigation()
        openDirections(destination)
        showTransit()
    }

    fun stopTransitOverview() {
        if (transitGuidance?.overviewing == true) map?.cancelTransitions()
        transitGuidance?.overviewing = false
    }

    fun previewTransitJourney() {
        val journey = transitGuidance ?: return
        val path = journey.trip.path
        if (path.isEmpty()) return
        stopTransitOverview()
        journey.overviewing = true
        journey.following = false
        journey.walking?.following = false
        frame(path, bottomFraction = 0.20f, top = 132, flat = true)
    }

    fun followTransit() {
        val session = transitGuidance ?: return
        stopTransitOverview()
        session.following = true
        session.walking?.let { walking ->
            glide.recenter()
            walking.following = true
            location.last?.let { followCamera(walking, it) }
            return
        }
        if (session.phase == TransitGuidance.Phase.BOARD) {
            val leg = session.leg
            (leg?.calls?.firstOrNull()?.at ?: leg?.path?.firstOrNull())?.let {
                frame(listOf(it), bottomFraction = 0.22f, top = 135)
            }
        } else location.last?.let {
            transitCameraAt = 0
            focusTransitRide(session, it)
        }
    }

    private fun focusTransitRide(session: TransitGuidance, f: Fix) {
        val m = map ?: return
        if (!session.following || glide.mapTouched || session.phase != TransitGuidance.Phase.RIDE || f.accuracy > 75) return
        val time = android.os.SystemClock.elapsedRealtime()
        if (time - transitCameraAt < 1200 || m.cameraPosition.target?.kite()?.distanceTo(f.coord)?.let { it < 24 } == true) return
        transitCameraAt = time
        MapCam.animate(m, CameraPosition.Builder().target(f.coord.ml()).zoom(15.4).tilt(0.0).bearing(0.0).build(), ms = 500)
    }

    fun startNavigation() {
        val d = directions ?: return
        if (d.transit || isGuiding) return
        if (d.missingOrigin) {
            pickingOrigin = true
            return
        }
        edit { it.copy(startRequested = true) }
        if (navigationFix == null) location.start()
        val fix = navigationFix
        val route = when (val start = d.roadStart(fix?.coord)) {
            RoadStart.WaitForLocation -> {
                edit { it.copy(waitingForLocation = true) }
                return
            }
            RoadStart.FindRoute -> {
                fix?.let(::centerForNavigationStart)
                if (d.mode !in d.loading) loadPlan()
                return
            }
            is RoadStart.ConnectToPlan -> {
                resetPlanRequests()
                directions = DirectionsState(start.destination, mode = d.mode, stops = start.stops, startRequested = true, avoid = d.avoid, avoidNames = d.avoidNames)
                fix?.let(::centerForNavigationStart)
                loadPlan()
                return
            }
            is RoadStart.Follow -> start.route
            null -> return
        }
        checkNotNull(fix)

        endCruise()
        resetPlanRequests()
        directions = null
        browseFollowing = false
        val n = Navigator(route, d.destination, d.stops, scope)
        preparedRoadControls?.let { (id, controls) ->
            if (id == route.id) n.usePreparedControls(controls) else controls.cancel()
        }
        preparedRoadControls = null
        if (d.mode == TravelMode.DRIVE) n.avoid = d.avoid
        navZoomSteps = 0
        navigationCameraUntil = android.os.SystemClock.elapsedRealtime() + 10_000L
        nav = n
        n.onFinished = { if (nav === n) endNavigation() }
        n.onEstimate = { estimate ->
            if (nav === n) {
                n.handle(estimate, estimated = true)
                followCamera(n, estimate)
                driveNote(n)
                mapTick++
            }
        }
        resumeOffer = null
        n.handle(fix)
        followCamera(n, fix)
        rememberTrip(n)
        location.setNavigating(true)
        DriveService.start(KiteApp.app)
        driveNote(n)
        mapTick++
    }

    private fun centerForNavigationStart(fix: Fix) {
        if (mapNavigator?.following == true) return
        val m = map ?: return
        browseFollowing = false
        val look = settings.driveLook
        val current = visibleCamera?.invoke() ?: m.cameraPosition
        MapCam.animate(m, CameraPosition.Builder().target(fix.coord.ml())
            .zoom(MapCam.zoomFor(900.0, fix.lat, mapWidthDp)).tilt(if (look == MapLook.TILTED) 45.0 else 0.0)
            .bearing(if (look == MapLook.NORTH) 0.0 else current.bearing).build(),
            padding = intArrayOf(0, 100, 0, (mapHeightDp * 0.36).toInt()), ms = 350, native = nativeCamera)
    }

    fun endNavigation() {
        resetPlanRequests()
        stopTransitOverview()
        transitWalkJob?.cancel()
        transitWalkJob = null
        transitBoardJob?.cancel()
        transitClockJob?.cancel()
        transitDeparturesJob?.cancel()
        transitGuidance?.walking?.stop()
        transitGuidance = null
        navigationPlace = null
        placingStop = false
        stopPinWasFollowing = false
        closeStopSearch(restore = false)
        addingStop = false
        nav?.stop()
        glide.stop()
        nav = null
        settings.trip = null
        location.setNavigating(false)
        DriveService.stop(KiteApp.app)
        directions = null
        showSteps = false
        browseFollowing = false
        resetCamera()
        mapTick++
    }

    fun recenterNav(closer: Boolean = false) {
        val n = mapNavigator ?: return
        if (closer) navZoomSteps = max(navZoomSteps, 5)
        if (transitGuidance?.walking != null) {
            followTransit()
            return
        }
        navigationCameraUntil = android.os.SystemClock.elapsedRealtime() + 10_000L
        n.following = true
        glide.recenter()
        location.last?.let { followCamera(n, it) }
    }

    fun zoomNavigation(step: Int) {
        val n = mapNavigator ?: return
        val m = map ?: return
        if (n.following) {
            navZoomSteps = (navZoomSteps + step).coerceIn(-4, 10)
            location.last?.let { followCamera(n, it) }
        } else {
            val camera = visibleCamera?.invoke() ?: m.cameraPosition
            MapCam.animate(m, CameraPosition.Builder(camera)
                .zoom((camera.zoom + step * 0.55).coerceIn(2.0, 21.5)).build(), ms = 220, native = nativeCamera, keepPadding = true)
        }
    }

    fun previewNavTurn(turn: Maneuver) {
        val n = mapNavigator ?: return
        val m = map ?: return
        val current = visibleCamera?.invoke() ?: m.cameraPosition
        n.following = false
        val preview = CameraPosition.Builder(current).target(turn.location.ml()).zoom(16.8)
            .tilt(if (settings.driveLook == MapLook.TILTED) 24.0 else 0.0).build()
        MapCam.animate(m, preview, padding = intArrayOf(0, 100, 0, 45), ms = 260, native = nativeCamera)
    }

    /** Show the whole remaining trip; the camera stops following until re-centered. */
    fun overviewNav() {
        val n = nav ?: return
        n.following = false
        val line = n.progress?.let { Geo.slice(n.route.polyline, it.alongMeters, Geo.length(n.route.polyline)) } ?: n.route.polyline
        frame(line, bottomFraction = 0.25f, top = 200)
    }

    private fun locationChanged(f: Fix) {
        if (location.reducedAccuracy || f.accuracy > 200) areaRisk = null
        val waiting = directions?.takeIf { it.waitingForLocation }
        if (waiting != null) {
            edit { it.copy(waitingForLocation = false) }
            if (waiting.startRequested) startNavigation() else loadPlan()
        }
        val transit = transitGuidance
        if (transit != null) {
            val walking = transit.walking
            if (transit.phase == TransitGuidance.Phase.WALK && walking == null && transit.walkingError &&
                f.ageMs in 0..15_000 && f.accuracy in 0.0..35.0 &&
                transit.leg?.path?.lastOrNull()?.distanceTo(f.coord)?.let { it <= 20 } == true) {
                advanceTransitLeg()
            } else if (transit.phase == TransitGuidance.Phase.WALK && walking != null) {
                transit.observe(f)
                walking.handle(f)
                if (walking.arrived) advanceTransitLeg()
                else {
                    if (settings.learnTransitWalkingPace) transit.sampleWalkingPace(f)?.let { sample ->
                        val previous = settings.learnedWalkingPace
                        settings.transitWalkingPace = if (previous == null) sample else previous * 0.8 + sample * 0.2
                    }
                    followCamera(walking, f)
                    driveNote(walking)
                    mapTick++
                }
            } else if (transit.observe(f)) advanceTransitLeg()
            else if (transit.shouldAutoBoard(f)) boardTransit()
            else focusTransitRide(transit, f)
            return
        }
        val n = nav ?: run { cruiseCheck(f); followBrowse(f, entering = false); return }
        n.handle(f)
        followCamera(n, f)
        tripCheck(n)
        driveNote(n)
        mapTick++
    }

    /**
     * Driving with no route: after a few seconds above about 15 mph with nothing open, switch to the
     * driving view (speed, limit, cameras, road name). Opening a place or a route, or parking, ends it.
     */
    private fun cruiseCheck(f: Fix) {
        cruise?.let { c ->
            if (directions != null || selected != null || results.isNotEmpty()) { endCruise(); return }
            c.handle(f)
            mapTick++
            return
        }
        // After the ✕, wait until the car has stood still a minute before offering it again.
        if (cruiseClosed) {
            if (f.speedKnown && f.speed < 1.5) {
                val t = cruiseClosedStill ?: f.timeMillis.also { cruiseClosedStill = it }
                if (f.timeMillis - t > 60_000) cruiseClosed = false
            } else cruiseClosedStill = null
            return
        }
        if (!settings.autoDrive || tab != AppTab.MAP || searching || query.isNotBlank() || directions != null || selected != null || results.isNotEmpty() || picking != null) {
            movingSince = null
            return
        }
        val (go, since) = Cruise.shouldStart(f, movingSince)
        movingSince = since
        if (go) startCruise(f)
    }

    private fun startCruise(f: Fix) {
        val c = Cruise(scope)
        cruise = c
        c.onParked = { if (cruise === c) endCruise() }
        movingSince = null
        location.setNavigating(true)
        browseFollowing = true
        showLayers = false
        c.handle(f)
        followBrowse(f, entering = true)
        mapTick++
    }

    /** Back to the map; [closed] is the ✕, which keeps it away until the next stop. */
    fun endCruise(closed: Boolean = false) {
        val c = cruise ?: return
        c.stop()
        cruise = null
        movingSince = null
        if (closed) { cruiseClosed = true; cruiseClosedStill = null }
        if (nav == null) {
            location.setNavigating(false)
            browseFollowing = false
            if (directions == null && selected == null) resetCamera()
        }
        mapTick++
    }

    fun recenterCruise() {
        if (cruise == null) return
        val f = location.last ?: return
        browseFollowing = true
        followBrowse(f, entering = true)
    }

    /** The next turn and arrival time on the drive's notification (Android only: the iPhone has no such thing to fill). */
    private fun driveNote(n: Navigator) {
        val p = n.progress ?: return
        if (n.arrived) {
            DriveService.show(KiteApp.app, "You have arrived", n.destination.name)
            return
        }
        val m = n.nextManeuver
        val turn = if (m == null || m.type == ManeuverType.ARRIVE) "Continue to ${n.destination.name}"
            else "${Fmt.distance(p.distanceToNext)} · ${SpokenText.forScreen(m.instruction)}"
        val arrow = when (m?.type) {
            ManeuverType.TURN_LEFT, ManeuverType.SLIGHT_LEFT, ManeuverType.SHARP_LEFT -> "↰"
            ManeuverType.TURN_RIGHT, ManeuverType.SLIGHT_RIGHT, ManeuverType.SHARP_RIGHT -> "↱"
            ManeuverType.ROUNDABOUT -> "⟳"
            ManeuverType.UTURN -> "↶"
            else -> "↑"
        }
        val distance = if (m == null || m.type == ManeuverType.ARRIVE) p.remainingMeters else p.distanceToNext
        val chip = "$arrow ${Fmt.distance(distance)}"
        val percent = (100 * p.alongMeters / (p.alongMeters + p.remainingMeters).coerceAtLeast(1.0)).toInt()
        val arrival = "Arrive ${Fmt.arrival(p.remainingSeconds)} · ${Fmt.distance(p.remainingMeters)} to go"
        val estimate = if (n.speedMps >= 2.0 && distance > 30 && m != null && m.type != ManeuverType.ARRIVE) {
            val seconds = (distance / n.speedMps).toInt()
            if (seconds < 60) "About ${(seconds / 5 * 5).coerceAtLeast(5)} sec to turn" else "About ${Fmt.duration(seconds.toDouble())} to turn"
        } else null
        DriveService.show(KiteApp.app, turn, listOfNotNull(estimate, arrival).joinToString(" · "), chip, percent)
    }

    private fun tripCheck(n: Navigator) {
        if (n.arrived) {
            if (settings.trip != null) settings.trip = null
        } else if (System.currentTimeMillis() - tripSavedAt > 60_000 || settings.trip?.stops?.size != n.stops.size ||
            settings.trip?.destination?.id != n.destination.id) {
            rememberTrip(n)
        }
    }

    private fun followCamera(n: Navigator, f: Fix) {
        val p = n.progress
        // Further out on long stretches, closer in and flatter just before a turn (see NavZoom).
        // Then the zoom setting (Closer, Normal, Wider) and the 3D, 2D or north-up button.
        val rawView = if (n.route.mode == TravelMode.WALK) NavZoom.View(135.0, 40.0)
            else NavZoom.view(f.speed, p?.distanceToNext, n.nextManeuver?.type, n.route.mode == TravelMode.DRIVE)
        val view = rawView.applying(settings.mapZoom, settings.driveLook)
        glide.look = if (settings.driveNorthUp) MapLook.NORTH else settings.driveLook
        val across = if (n.route.mode == TravelMode.DRIVE && android.os.SystemClock.elapsedRealtime() < navigationCameraUntil)
            min(view.across, 350.0 * settings.mapZoom.scale) else view.across
        glide.fix(n, f, across / 1.36.pow(navZoomSteps), view.pitch)
    }

    /** 2D or 3D perspective, saved for the next drive. */
    fun cycleDriveLook() {
        settings.driveLook = if (settings.driveLook == MapLook.TILTED) MapLook.FLAT else MapLook.TILTED
        browseLook = settings.driveLook
        settings.browseMapLook = browseLook
        val n = mapNavigator ?: return
        n.following = true
        glide.resumeFollowing()
        (navigationFix ?: location.last)?.let { followCamera(n, it) }
    }

    fun toggleDriveCompass() {
        settings.driveNorthUp = !settings.driveNorthUp
        val n = mapNavigator ?: return
        n.following = true
        glide.resumeFollowing()
        (navigationFix ?: location.last)?.let { followCamera(n, it) }
    }

    fun cycleBrowseLook(ctx: android.content.Context) {
        browseLook = if (browseLook == MapLook.TILTED) MapLook.FLAT else MapLook.TILTED
        settings.browseMapLook = browseLook
        settings.driveLook = browseLook
        val fix = location.last
        if (browseFollowing && fix != null) followBrowse(fix, entering = true)
        else resetCamera()
    }

    private var phoneHeading: Double? = null

    fun headingChanged(heading: Double) {
        phoneHeading = heading
        if (!settings.browseNorthUp && browseFollowing && !isGuiding) location.last?.let { followBrowse(it, entering = false) }
    }

    fun toggleBrowseCompass(ctx: android.content.Context) {
        settings.browseNorthUp = !settings.browseNorthUp
        if (settings.browseNorthUp) resetCamera() else centerOnUser(ctx)
    }

    private var lastNavCenteredAt: LatLng? = null
    fun tapNavigationLocation() {
        val coord = location.last?.coord
        val closer = mapNavigator?.following == true && coord != null &&
            lastNavCenteredAt?.distanceTo(coord)?.let { it < 18 } == true
        lastNavCenteredAt = coord
        recenterNav(closer)
    }

    /**
     * In 3D or 2D off a route, keeps you in the middle with the map facing the way you're going, as you
     * move. Pinching to zoom is kept; dragging the map, or opening a place, stops it until Centre on me.
     */
    private fun followBrowse(f: Fix, entering: Boolean, closer: Boolean = false) {
        val m = map ?: return
        if (!browseFollowing || nav != null) return
        if (!entering && (selected != null || results.isNotEmpty() || directions != null)) return
        val current = visibleCamera?.invoke() ?: m.cameraPosition
        val course = if (settings.browseNorthUp) 0.0 else phoneHeading ?: f.course.takeIf { f.courseKnown } ?: current.bearing
        val meters = if (closer) 72.0 else if (browseLook == MapLook.TILTED) 700.0 else 950.0
        val zoom = if (entering) max(current.zoom, MapCam.zoomFor(meters, f.lat, mapWidthDp)) else current.zoom
        if (!entering && current.target?.kite()?.distanceTo(f.coord)?.let { it < 9 } == true && Geo.diff(current.bearing, course) < 4) return
        val pos = CameraPosition.Builder().target(f.coord.ml()).zoom(zoom).tilt(browsePitch).bearing(course).build()
        MapCam.animate(m, pos, ms = if (entering) 300 else 260, native = nativeCamera)
    }

    // Map

    private val browsePitch: Double get() = if (browseLook == MapLook.TILTED) 48.0 else 0.0

    private var lastCenteredAt: LatLng? = null

    fun centerOnUser(ctx: android.content.Context, closer: Boolean = false) {
        val loc = location.last
        if (map == null || loc == null) {
            if (location.denied) LocationService.openSettings(ctx) else location.start()
            return
        }
        val close = closer || browseFollowing && lastCenteredAt?.distanceTo(loc.coord)?.let { it < 18 } == true
        lastCenteredAt = loc.coord
        browseFollowing = true
        followBrowse(loc, entering = true, closer = close)
    }

    private fun resetCamera() {
        val m = map ?: return
        val c = visibleCamera?.invoke() ?: m.cameraPosition
        MapCam.animate(m, CameraPosition.Builder(c).tilt(browsePitch)
            .bearing(if (settings.browseNorthUp) 0.0 else phoneHeading ?: c.bearing).build(), ms = 290, native = nativeCamera)
    }

    /** A spot on the map, close up (a report along the route). */
    fun showSpot(p: LatLng) {
        val m = map ?: return
        browseFollowing = false
        MapCam.animate(m, CameraPosition.Builder().target(p.ml()).zoom(15.0).build(), ms = 500)
    }

    data class IndoorView(val place: Place, val plan: IndoorMaps.Plan, val floor: String,
                          val feature: IndoorMaps.Feature? = null)
    var indoorView by mutableStateOf<IndoorView?>(null)
        private set
    var placeIndoorPlan by mutableStateOf<Pair<String, IndoorMaps.Plan>?>(null)
        private set
    private val knownIndoor = mutableListOf<IndoorMaps.Plan>()
    private val indoorMisses = mutableMapOf<String, Long>()

    suspend fun probeIndoor(place: Place?) {
        placeIndoorPlan = null
        if (place == null) return
        val cached = knownIndoor.firstOrNull { it.covers(place.location) }
        if (cached == null && !Connectivity.online) return
        val checked = indoorMisses[place.id] ?: 0L
        if (cached == null && System.currentTimeMillis() - checked < 5 * 60_000) return
        if (cached == null) delay(450)
        val plan = cached ?: IndoorMaps.plan(place.location)
        if (selected?.id != place.id) return
        if (plan == null) {
            if (indoorMisses.size >= 24) indoorMisses.remove(indoorMisses.keys.first())
            indoorMisses[place.id] = System.currentTimeMillis()
        } else {
            placeIndoorPlan = place.id to plan
            if (cached == null) {
                if (knownIndoor.size >= 6) knownIndoor.removeAt(0)
                knownIndoor.add(plan)
            }
        }
    }

    fun openIndoor(place: Place) {
        val plan = placeIndoorPlan?.takeIf { it.first == place.id }?.second ?: return
        val facility = plan.features.filter { it.name != null && place.name.equals(it.name, ignoreCase = true) }
            .minByOrNull { it.center.distanceTo(place.location) }
        val floor = facility?.floors?.firstOrNull() ?: plan.floors.firstOrNull { it == "0" } ?: plan.floors.lastOrNull() ?: return
        indoorView = IndoorView(place, plan, floor, facility)
        val points = plan.building ?: plan.features.filter { floor in it.floors }.flatMap { it.geometry }
        frame(points, bottomFraction = 0.18f, top = 108, flat = true)
    }

    fun setIndoorFloor(floor: String) {
        indoorView = indoorView?.copy(floor = floor, feature = null)
    }

    fun selectIndoor(at: LatLng): Boolean {
        val view = indoorView ?: return false
        val named = view.plan.features.asSequence().filter { view.floor in it.floors && it.name != null }
        val feature = named.filter { it.covers(at) || it.center.distanceTo(at) < 14 }
            .minByOrNull { it.center.distanceTo(at) }
        indoorView = view.copy(feature = feature)
        return true
    }

    fun closeIndoor() {
        indoorView = null
        resetCamera()
        selected?.let { frame(listOf(it.location), bottomFraction = 0.32f) }
    }

    fun openIndoorPlace(feature: IndoorMaps.Feature) {
        val name = feature.name ?: return
        indoorView = null
        select(Place("indoor:${feature.center.lat},${feature.center.lng}:$name", name,
            feature.center, "osm", category = feature.category ?: feature.kind))
    }

    fun showStationEntrance(at: LatLng) {
        mapNavigator?.following = false
        transitGuidance?.following = false
        val m = map ?: return
        if (!isGuiding) detent = SheetDetent.PEEK
        MapCam.animate(m, CameraPosition.Builder(m.cameraPosition).target(at.ml()).zoom(18.6).build(),
            padding = intArrayOf(0, 60, 0, (mapHeightDp * 0.30f).toInt()), ms = 230, native = nativeCamera)
        stationEntrance = at
        stationEntranceJob?.cancel()
        stationEntranceJob = scope.launch { delay(18_000); stationEntrance = null }
    }

    private var stationEntranceJob: Job? = null
    var stationEntrance by mutableStateOf<LatLng?>(null)
        private set

    var profileRoute by mutableStateOf<Route?>(null)
        private set
    var profileProbe by mutableStateOf<LatLng?>(null)
        private set
    val routeProfiles = RouteProfiles(scope)
    private var profileWasFollowing = false

    fun openProfile(route: Route) {
        profileWasFollowing = mapNavigator?.following == true
        profileRoute = route
    }

    fun closeProfile() {
        val restore = profileWasFollowing
        val probed = profileProbe != null || pathSectionHighlight?.first == profileRoute?.id
        profileRoute = null
        profileProbe = null
        pathSectionHighlight = null
        if (probed && restore) recenterNav()
        else if (probed && mapNavigator == null) frameRoute()
    }

    fun scrubProfile(at: LatLng) {
        val m = map ?: return
        profileProbe = at
        mapNavigator?.following = false
        browseFollowing = false
        val c = m.cameraPosition
        MapCam.animate(m, CameraPosition.Builder(c).target(at.ml()).zoom(c.zoom.coerceAtLeast(14.5)).build(),
            padding = intArrayOf(0, 40, 0, (mapHeightDp * 0.53f).toInt()), ms = 0, native = nativeCamera)
    }

    var pathSectionHighlight by mutableStateOf<Pair<String, Valhalla.PathSection>?>(null)

    fun framePathSection(route: Route, section: Valhalla.PathSection) {
        if (directions?.route?.id != route.id && mapNavigator?.route?.id != route.id) return
        val points = Geo.slice(route.polyline, section.from, section.to)
        if (points.size < 2) return
        pathSectionHighlight = route.id to section
        profileProbe = null
        mapNavigator?.following = false
        if (profileRoute == null) detent = SheetDetent.PEEK
        frame(points, bottomFraction = if (profileRoute == null) 0.30f else 0.52f, top = 58)
    }

    fun frame(points: List<LatLng>, bottomFraction: Float = 0.45f, top: Int = 130, flat: Boolean = false, marginZoom: Double = 0.0) {
        if (mapNavigator?.following == true) return
        val m = map ?: return
        val first = points.firstOrNull() ?: return
        // Showing a place or a route: stop following, or the next fix would pull the map away.
        browseFollowing = false
        val pitch = if (isGuiding || flat) 0.0 else browsePitch
        if (points.all { it == first }) {
            MapCam.animate(m, CameraPosition.Builder().target(first.ml()).zoom(MapCam.zoomFor(1200.0, first.lat, mapWidthDp))
                .tilt(pitch).bearing(0.0).build(), ms = 300, native = nativeCamera)
            return
        }
        val b = LatLngBounds.Builder().apply { points.forEach { include(it.ml()) } }.build()
        val topInset = top.coerceAtMost((mapHeightDp * 0.35f).toInt())
        val bottomInset = (mapHeightDp * bottomFraction + 20).toInt().coerceAtMost((mapHeightDp * 0.55f).toInt())
        MapCam.fit(m, b, intArrayOf(40, topInset, 40, bottomInset), ms = 360, native = nativeCamera, pitch = pitch, marginZoom = marginZoom)
    }

    companion object {
        const val LOCATION_OFF_TEXT = "Location is off for Kite. Turn it on in Settings to get directions from where you are."
    }
}

data class StopCandidate(val place: Place, val ahead: Double?, val offset: Double, val routeMeters: Double? = null)
data class StopSearch(
    val query: String,
    val wasFollowing: Boolean,
    val loading: Boolean = true,
    val parking: Boolean = false,
    val results: List<StopCandidate> = emptyList(),
    val selected: StopCandidate? = null,
    val detours: Map<String, Double> = emptyMap(),
    val fuelQuotes: Map<String, FuelPrices.Quote> = emptyMap(),
) {
    companion object { const val PINS = "kite-stop-candidates" }
}

/** Street View asked for from a place: where, and what to call it. */
data class StreetViewRequest(val name: String, val pano: StreetView.Panorama, val heading: Double)
data class StreetViewFrame(val at: LatLng, val heading: Int, val links: List<LatLng>)
