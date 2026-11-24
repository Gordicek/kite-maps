package app.kite

import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.kite.core.*
import java.util.Locale
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

enum class MapStyleMode(val label: String) { SYSTEM("App"), LIGHT("Light"), DARK("Dark"), SUN("Sunrise & sunset") }
enum class MarkerTheme(val label: String, val resource: Int?) {
    CLASSIC("Classic", null), PORCELAIN("Porcelain", R.drawable.icon_porcelain),
    NIGHT("Neon", R.drawable.icon_night), TRAIL("Trail", R.drawable.icon_trail),
}
enum class RoadStripStyle(val label: String) { CLASSIC("Classic"), MINIMAL("Bare line"), CONTRAST("Event cards") }

enum class RoadStripPosition(val label: String) { BOTTOM("Bottom"), TOP("Top"), LEFT("Left"), RIGHT("Right") }

enum class DriveMarker(val label: String, val sprite: String, val top: Int? = null, val tilted: Int? = null) {
    ARROW("Arrow", "puck"),
    ALFA_147("Alfa 147", "puck-alfa", R.drawable.drive_alfa_147, R.drawable.drive_alfa_147_tilt),
    VAN("Van", "puck-van", R.drawable.drive_van, R.drawable.drive_van_tilt),
}

enum class NavigationPlaceSource(val label: String) { OPENSTREETMAP("OpenStreetMap"), GOOGLE("Google Maps") }

enum class ThemeMode(val label: String) { SYSTEM("System"), LIGHT("Light"), DARK("Dark") }

enum class UnitsMode(val label: String) {
    AUTO("Automatic"), METRIC("Kilometers"), IMPERIAL("Miles and feet"), YARDS("Miles and yards (UK)")
}

/** Everything in Settings, kept between launches. Each value is Compose state, so screens redraw when it changes. */
object AppSettings {
    private val d: SharedPreferences by lazy { KiteApp.app.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE) }

    private class Pref<T>(val key: String, val read: (SharedPreferences) -> T, val write: (SharedPreferences.Editor, T) -> Unit) :
        ReadWriteProperty<Any?, T> {
        private var state: androidx.compose.runtime.MutableState<T>? = null
        private fun s() = state ?: mutableStateOf(read(d)).also { state = it }
        override fun getValue(thisRef: Any?, property: KProperty<*>): T = s().value
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
            s().value = value
            d.edit().also { write(it, value) }.apply()
        }
    }

    private fun bool(key: String, def: () -> Boolean) =
        Pref(key, { if (it.contains(key)) it.getBoolean(key, false) else def() }, { e, v -> e.putBoolean(key, v) })
    private fun bool(key: String, def: Boolean = false) = bool(key) { def }
    private fun int(key: String, def: Int) = Pref(key, { it.getInt(key, def) }, { e, v -> e.putInt(key, v) })
    private fun place(key: String) = Pref(key, { p -> p.getString(key, null)?.let { PlaceCodec.decode(it) } },
        { e, v -> if (v == null) e.remove(key) else e.putString(key, PlaceCodec.encode(v)) })
    private fun places(key: String) = Pref(key, { p -> p.getString(key, null)?.let { PlaceCodec.decodeList(it) } ?: emptyList() },
        { e, v -> e.putString(key, PlaceCodec.encodeList(v)) })

    var theme: ThemeMode by Pref("theme", { p -> ThemeMode.entries.firstOrNull { it.name == p.getString("theme", null) } ?: ThemeMode.SYSTEM },
        { e, v -> e.putString("theme", v.name) })
    var units: UnitsMode by Pref("units", { p -> UnitsMode.entries.firstOrNull { it.name == p.getString("units", null) } ?: UnitsMode.AUTO },
        { e, v -> e.putString("units", v.name) })
    var voice by bool("voice", true)
    /** The voice button's middle setting, "Alerts only": remembered with `voice`, so the next drive starts the same. */
    var voiceAlertsOnly by bool("voiceAlertsOnly")
    var mapboxEnabled by bool("mapboxEnabled", true)
    var mapStyleMode by Pref("mapStyleMode", { p ->
        MapStyleMode.entries.firstOrNull { it.name == p.getString("mapStyleMode", null) } ?: MapStyleMode.SYSTEM
    }, { e, v -> e.putString("mapStyleMode", v.name) })
    var roadStripPosition by Pref("roadStripPosition", { p ->
        RoadStripPosition.entries.firstOrNull { it.name == p.getString("roadStripPosition", null) } ?: RoadStripPosition.BOTTOM
    }, { e, v -> e.putString("roadStripPosition", v.name) })
    var driveMarker by Pref("driveMarker", { p ->
        DriveMarker.entries.firstOrNull { it.name == p.getString("driveMarker", null) } ?: DriveMarker.ARROW
    }, { e, v -> e.putString("driveMarker", v.name) })
    var swapNavigationSides by bool("swapMapSides")
    var categoryMenu by bool("categoryMenu")
    var browseMapLook by Pref("browseMapLook", { p ->
        MapLook.entries.firstOrNull { it.name == p.getString("browseMapLook", null) } ?: MapLook.NORTH
    }, { e, v -> e.putString("browseMapLook", v.name) })
    var solidMapSearch by bool("solidMapSearch", true)
    var osmOnlySearch by bool("osmOnlySearch")
    var markerTheme by Pref("markerTheme",
        { p -> MarkerTheme.entries.firstOrNull { it.name == p.getString("markerTheme", null) } ?: MarkerTheme.CLASSIC },
        { e, v -> e.putString("markerTheme", v.name) })
    var roadStripStyle by Pref("roadStripStyle",
        { p -> RoadStripStyle.entries.firstOrNull { it.name == p.getString("roadStripStyle", null) } ?: RoadStripStyle.CLASSIC },
        { e, v -> e.putString("roadStripStyle", v.name) })
    var wazeMapPercent by int("wazeMapPercent", 100)
    var wazeStripPercent by int("wazeStripPercent", 100)
    var googleIconPercent by int("googleIconPercent", 100)
    var osmIconPercent by int("osmIconPercent", 100)
    var resultIconPercent by int("resultIconPercent", 100)
    private fun mapColor(key: String) = Pref<String?>(key,
        { it.getString(key, null)?.takeIf { value -> MapPalette.isColor(value) } },
        { e, value -> e.putString(key, value) })
    var mapUiAccent by mapColor("mapUiAccent")
    var mapUiDayPanel by mapColor("mapUiDayPanel")
    var mapUiNightPanel by mapColor("mapUiNightPanel")
    var standardMap by bool("standardMap", true)
    private fun palette(key: String, dark: Boolean) = Pref(key,
        { MapPalette.decode(it.getString(key, null)) ?: MapPalette.default(dark) },
        { e, value -> e.putString(key, value.colors.joinToString(",")) })
    var dayMapPalette by palette("dayMapPalette", false)
    var nightMapPalette by palette("nightMapPalette", true)
    var walkingAhead by bool("walkingAhead", true)
    var walkingAheadRight by bool("walkingAheadRight")
    var walkingAheadRange by int("walkingAheadRange", 600)
    var walkingSignals by bool("walkingSignals", true)
    var walkingStops by bool("walkingStops")
    var walkingRail by bool("walkingRail", true)
    var walkingCrossings by bool("walkingCrossings")
    var walkingWaze by bool("walkingWaze")
    var walkingCameras by bool("walkingCameras")

    fun showsWalkingControl(kind: Overpass.ControlKind): Boolean = when (kind) {
        Overpass.ControlKind.SIGNAL -> walkingSignals
        Overpass.ControlKind.STOP -> walkingStops
        Overpass.ControlKind.RAIL -> walkingRail
        Overpass.ControlKind.CROSSING -> walkingCrossings
    }

    var roadAheadStrip by bool("roadAheadStrip", true)
    var terrain3D by bool("terrain3D")
    var buildingShadows by bool("buildingShadows", true)
    var streetLamps by bool("streetLamps", true)
    var googleMapPlaces by bool("googleMapPlaces")
    var openMapPlaces by bool("openMapPlaces", true)
    var openPlaceGroups: Set<OpenPlaceGroup> by Pref("openPlaceGroups",
        { p -> p.getStringSet("openPlaceGroups", null)?.let { saved -> OpenPlaceGroup.entries.filter { it.name in saved }.toSet() }
            ?: OpenPlaceGroup.entries.toSet() },
        { e, v -> e.putStringSet("openPlaceGroups", v.map { it.name }.toSet()) })
    private fun routeEngine(key: String) = Pref(key,
        { p -> RouteEngine.entries.firstOrNull { it.name == p.getString(key, null) } ?: RouteEngine.AUTO },
        { e, value -> e.putString(key, value.name) })
    var driveEngine by routeEngine("driveEngine")
    var walkEngine by routeEngine("walkEngine")
    var bikeEngine by routeEngine("bikeEngine")
    fun routeEngine(mode: TravelMode): RouteEngine = when (mode) {
        TravelMode.DRIVE -> driveEngine
        TravelMode.WALK -> walkEngine
        TravelMode.BIKE -> bikeEngine
    }
    var navigationHoldAnywhere by bool("navigationHoldAnywhere")
    var portraitPip by bool("portraitPip")
    var driveNorthUp by bool("driveNorthUp")
    var browseNorthUp by bool("browseNorthUp", true)
    var preloadRouteProfile by bool("preloadRouteProfile", true)
    var navigationPlaces by bool("navigationPlaces")
    var navigationPlaceSource by Pref("navigationPlaceSource",
        { p -> NavigationPlaceSource.entries.firstOrNull { it.name == p.getString("navigationPlaceSource", null) }
            ?: NavigationPlaceSource.OPENSTREETMAP },
        { e, v -> e.putString("navigationPlaceSource", v.name) })
    var navigationPictureInPicture by bool("navigationPictureInPicture", true)
    var navigationPlaceGroups: Set<OpenPlaceGroup> by Pref("navigationPlaceGroups",
        { p -> p.getStringSet("navigationPlaceGroups", null)?.let { names -> OpenPlaceGroup.entries.filter { it.name in names }.toSet() }
            ?: setOf(OpenPlaceGroup.FUEL, OpenPlaceGroup.PARKING, OpenPlaceGroup.FOOD, OpenPlaceGroup.TOILETS) },
        { e, v -> e.putStringSet("navigationPlaceGroups", v.map { it.name }.toSet()) })
    var hikingTrails by bool("hikingTrails")
    var bikeTrails by bool("bikeTrails")
    var arrivalParking by bool("arrivalParking", true)
    var aheadSignals by bool("aheadSignals", true)
    var aheadStops by bool("aheadStops", true)
    var aheadRail by bool("aheadRail", true)
    var aheadCrossings by bool("aheadCrossings", true)
    var aheadWaze by bool("aheadWaze", true)
    var aheadCameras by bool("aheadCameras", true)
    var aheadTraffic by bool("aheadTraffic", true)

    fun showsControl(kind: Overpass.ControlKind) = when (kind) {
        Overpass.ControlKind.SIGNAL -> aheadSignals
        Overpass.ControlKind.STOP -> aheadStops
        Overpass.ControlKind.RAIL -> aheadRail
        Overpass.ControlKind.CROSSING -> aheadCrossings
    }
    var pidAccessToken by Pref("pidAccessToken", { it.getString("pidAccessToken", "").orEmpty() },
        { e, token -> e.putString("pidAccessToken", token) })
    var mapboxPublicToken by Pref("mapboxPublicToken", { it.getString("mapboxPublicToken", "").orEmpty() },
        { e, token -> e.putString("mapboxPublicToken", token) })
    val activeMapboxToken: String? get() = mapboxPublicToken.takeIf {
        mapboxEnabled && app.kite.core.MapboxDirections.isPublicToken(it)
    }
    var traffic by bool("traffic", true)
    var satellite by bool("satellite")
    /** Hill shading, and metro/rail lines in their colors. */
    var terrain by bool("terrain")
    var transitLines by bool("transitLines")
    var avoidTolls by bool("avoidTolls")
    var avoidHighways by bool("avoidHighways")
    /** "Use Kite without Google": search and routes from open data only. */
    var noGoogle by bool("noGoogle")
    var speedCameras by bool("speedCameras", true)
    var surveillanceCameras by bool("surveillanceCameras")
    var plateCameras by bool("plateCameras", true)
    /** Say "Speed camera ahead" while navigating. On for new users, except where it's against the law. */
    var cameraWarnings by bool("cameraWarnings") { !camerasRestricted }
    /** Say so when driving over the posted limit. Off by default, like Vela. */
    var speedingAlert by bool("speedingAlert")
    /** How close the driving map sits: Closer, Normal (as Kite always was) or Wider. */
    var mapZoom: MapZoom by Pref("mapZoom", { p -> MapZoom.of(p.getString("mapZoom", null)) }, { e, v -> e.putString("mapZoom", v.name) })
    /** The driving map's 3D, 2D or north-up choice from the map button, kept for the next drive. */
    var driveLook: MapLook by Pref("driveLook", { p -> MapLook.of(p.getString("driveLook", null)) }, { e, v -> e.putString("driveLook", v.name) })
    /** Live reports from Waze drivers. Off until the person says yes on the Waze screen. */
    var waze by bool("waze")
    var wazeAsked by bool("wazeAsked")
    var wazeKinds: Set<WazeAlert.Kind> by Pref("wazeKinds",
        { p -> p.getStringSet("wazeKinds", null)?.mapNotNull { k -> WazeAlert.Kind.entries.firstOrNull { it.key == k } }?.toSet()
            ?: setOf(WazeAlert.Kind.POLICE, WazeAlert.Kind.ACCIDENT, WazeAlert.Kind.HAZARD, WazeAlert.Kind.WEATHER) },
        { e, v -> e.putStringSet("wazeKinds", v.map { it.key }.toSet()) })
    var roadWazeKinds: Set<WazeAlert.Kind> by Pref("roadWazeKinds",
        { p -> p.getStringSet("roadWazeKinds", null)?.let { saved -> WazeAlert.Kind.entries.filter { it.key in saved }.toSet() }
            ?: Waze.shares },
        { e, v -> e.putStringSet("roadWazeKinds", v.map { it.key }.toSet()) })
    var wazeOnMap by bool("wazeOnMap", true)
    var wazeVoice by bool("wazeVoice", true)
    /** Reports older than this are ignored, in minutes; 0 keeps everything Waze still lists. */
    var wazeMaxAge by int("wazeMaxAge", 120)
    /** The police heat map: on from the start, browsing and driving (Matteo: "heat map always on"). */
    var wazeHeatmap by bool("wazeHeatmap", true)
    var policeRisk by bool("policeRisk", true)
    var policeAircraft by bool("policeAircraft")
    var parkingButton by bool("parkingButton", true)
    /** Switch to the driving view by itself when you set off with no route (and back once parked). */
    var autoDrive by bool("autoDrive", true)
    /** "My car meets the rules": clean air zones on a route aren't flagged. */
    var cleanCar by bool("cleanCar")
    /** The drive in progress, kept so it can be offered again if the app closes halfway. */
    var trip: SavedTrip? by Pref("trip", { p -> p.getString("trip", null)?.let { SavedTrip.decode(it) } },
        { e, v -> if (v == null) e.remove("trip") else e.putString("trip", v.encode()) })
    /** Live camera photos (GitHub #15): camera dots on the map, and the cameras along a route before you go. */
    var liveCams by bool("liveCams", true)
    /** UK road traffic cameras (National Highways, TfL, Traffic Scotland, Traffic Wales, TrafficWatch NI). */
    var liveRoadCams by bool("liveRoadCams", true)
    /** Windy's scenic webcams, worldwide. */
    var liveWebcams by bool("liveWebcams", true)
    /** The row of camera photos along the route, before pressing Go. */
    var liveCamsOnRoute by bool("liveCamsOnRoute", true)
    var home: Place? by place("home")
    var work: Place? by place("work")
    var placeShortcuts: List<Place> by places("placeShortcuts")
    /** Where the car is parked, with when in `statusText`. */
    var parking: Place? by place("parking")
    var welcomed by bool("welcomed")
    var defaultMode: TravelMode by Pref("defaultMode", { p -> TravelMode.entries.firstOrNull { it.raw == p.getInt("defaultMode", 0) } ?: TravelMode.DRIVE },
        { e, v -> e.putInt("defaultMode", v.raw) })
    var savedPlans: List<SavedPlan> by Pref("savedPlans",
        { p -> p.getString("savedPlans", null)?.let { Json.parse(it).asMaps()?.mapNotNull(SavedPlan::fromMap) }.orEmpty() },
        { e, value -> e.putString("savedPlans", Json.write(value.map(SavedPlan::toMap))) })
    var learnTransitWalkingPace by bool("learnTransitWalkingPace", true)
    var transitWalkingPace by Pref("transitWalkingPace",
        { p -> p.getFloat("transitWalkingPace", 0f).toDouble() },
        { e, v -> e.putFloat("transitWalkingPace", v.toFloat()) })
    val learnedWalkingPace: Double? get() =
        transitWalkingPace.takeIf { learnTransitWalkingPace && it in 0.65..2.2 }
    var transitExitAlerts by bool("transitExitAlerts", true)
    var placeFolders: List<PlaceFolder> by Pref("placeFolders",
        { p -> p.getString("placeFolders", null)?.let { Json.parse(it).asMaps()?.mapNotNull(PlaceFolder::fromMap) }.orEmpty() },
        { e, value -> e.putString("placeFolders", Json.write(value.map(PlaceFolder::toMap))) })

    fun removeSaved(id: String) {
        saved = saved.filter { it.id != id }
        placeFolders = placeFolders.map { it.copy(members = it.members - id) }
    }

    fun toggleFolderMember(folderId: String, placeId: String) {
        placeFolders = placeFolders.map { folder ->
            if (folder.id != folderId) folder else folder.copy(members =
                if (placeId in folder.members) folder.members - placeId else folder.members + placeId)
        }
    }

    var saved: List<Place> by places("saved")
    var recents: List<String> by Pref("recents", { p -> p.getString("recents", null)?.let { (Json.parse(it) as? List<*>)?.filterIsInstance<String>() } ?: emptyList() },
        { e, v -> e.putString("recents", Json.write(v)) })
    /** Places you opened lately, newest first. */
    var recentPlaces: List<Place> by places("recentPlaces")

    /** Countries where apps may not warn of speed cameras (France, Germany, Switzerland and a few more). */
    val camerasRestricted: Boolean get() = Locale.getDefault().country in setOf("FR", "DE", "CH", "CY", "SK", "TR")

    /** Waze belongs to Google, so "Use Kite without Google" turns it off too. */
    val wazeOn: Boolean get() = waze && !noGoogle
    /** Ask about Waze once: not to someone who chose no Google, since Waze is Google's. */
    val needsWazeChoice: Boolean get() = !wazeAsked && !noGoogle
    val wazePolice: Boolean get() = Waze.shares.contains(WazeAlert.Kind.POLICE)

    /** Live camera dots on the map: road cameras, and Windy's webcams when Kite has a key. */
    val showsRoadCams: Boolean get() = liveCams && liveRoadCams
    val showsWebcams: Boolean get() = liveCams && liveWebcams && WindyKey.present

    fun wants(k: WazeAlert.Kind) = wazeOn && wazeKinds.contains(k)

    /** Whether this report should show and warn: a wanted kind, and not too old. */
    private fun recent(a: WazeAlert) = wazeMaxAge == 0 || System.currentTimeMillis() - a.reportedAtMillis <= wazeMaxAge * 60_000L

    fun shows(a: WazeAlert): Boolean = wants(a.kind) && recent(a)

    fun showsOnRoad(a: WazeAlert): Boolean = wazeOn && wazeOnMap && aheadWaze &&
        a.kind in roadWazeKinds && a.kind in Waze.shares && recent(a)

    fun savedEntry(p: Place) = saved.firstOrNull { it.matchesNearby(p) }

    fun isSaved(p: Place) = savedEntry(p) != null

    fun savePlace(p: Place, lists: Set<String>) {
        val existing = savedEntry(p)
        val id = existing?.id ?: p.id
        if (existing == null) saved = listOf(p) + saved
        placeFolders = placeFolders.map { folder ->
            folder.copy(members = if (folder.id in lists) folder.members + id else folder.members - id)
        }
    }

    fun toggleSaved(p: Place) {
        val existing = savedEntry(p)
        if (existing != null) removeSaved(existing.id) else savePlace(p, emptySet())
    }

    fun renameSaved(id: String, name: String) {
        val n = name.trim()
        if (n.isEmpty()) return
        saved = saved.map { if (it.id == id) it.copy(name = n) else it }
    }

    fun addRecentPlace(p: Place) {
        if (listOf("pin:", "link:", "parking", "tap:", "demo").any { p.id.startsWith(it) }) return
        val slim = p.copy(distanceMeters = null, photoUrls = p.photoUrls.take(1), about = emptyList(), featuredReview = null)
        recentPlaces = (listOf(slim) + recentPlaces.filter { it.id != p.id && it.name != p.name }).take(15)
    }

    fun addRecent(q: String) {
        recents = (listOf(q) + recents.filterNot { it.equals(q, ignoreCase = true) }).take(12)
    }
}

/** A drive in progress: where to, by way of where, and when it was last being driven. */
data class SavedTrip(val destination: Place, val stops: List<Place>, val mode: Int, val atMillis: Long) {
    fun encode() = Json.write(mapOf("destination" to PlaceCodec.toMap(destination), "stops" to stops.map { PlaceCodec.toMap(it) },
        "mode" to mode, "at" to atMillis))

    companion object {
        fun decode(s: String): SavedTrip? {
            val m = Json.obj(s) ?: return null
            val to = (m["destination"] as? Map<*, *>)?.let { d -> PlaceCodec.fromMap(d.entries.associate { it.key.toString() to it.value }) } ?: return null
            return SavedTrip(to, m["stops"].asMaps()?.mapNotNull { PlaceCodec.fromMap(it) } ?: emptyList(),
                m["mode"].int() ?: 0, m["at"].num()?.toLong() ?: 0)
        }
    }
}

/** Places to and from JSON for saving. */
object PlaceCodec {
    fun toMap(p: Place): Map<String, Any?> = linkedMapOf(
        "id" to p.id, "name" to p.name, "lat" to p.location.lat, "lng" to p.location.lng, "source" to p.source,
        "category" to p.category, "address" to p.address, "rating" to p.rating, "reviewCount" to p.reviewCount,
        "priceText" to p.priceText, "website" to p.website, "phone" to p.phone, "statusText" to p.statusText,
        "openNow" to p.openNow, "permanentlyClosed" to p.permanentlyClosed, "hours" to p.hours, "photoUrls" to p.photoUrls,
        "featuredReview" to p.featuredReview, "editorialSummary" to p.editorialSummary,
        "about" to p.about.map { mapOf("title" to it.title, "items" to it.items) },
        "featureId" to p.featureId, "placeId" to p.placeId, "distanceMeters" to p.distanceMeters,
        "streetViewId" to p.streetViewHint?.id, "streetViewYaw" to p.streetViewHint?.yaw,
    ).filterValues { it != null }

    fun fromMap(m: Map<String, Any?>): Place? {
        val lat = m["lat"].num() ?: return null
        val lng = m["lng"].num() ?: return null
        return Place(
            id = m["id"].text() ?: return null, name = m["name"].text() ?: "", location = LatLng(lat, lng),
            source = m["source"].text() ?: "osm", category = m["category"].text(), address = m["address"].text(),
            rating = m["rating"].num(), reviewCount = m["reviewCount"].int(), priceText = m["priceText"].text(),
            website = m["website"].text(), phone = m["phone"].text(), statusText = m["statusText"].text(),
            openNow = m["openNow"] as? Boolean, permanentlyClosed = m["permanentlyClosed"] as? Boolean ?: false,
            hours = m["hours"].asList()?.filterIsInstance<String>() ?: emptyList(),
            photoUrls = m["photoUrls"].asList()?.filterIsInstance<String>() ?: emptyList(),
            featuredReview = m["featuredReview"].text(), editorialSummary = m["editorialSummary"].text(),
            about = m["about"].asMaps()?.map { AboutSection(it["title"].text() ?: "", it["items"].asList()?.filterIsInstance<String>() ?: emptyList()) } ?: emptyList(),
            featureId = m["featureId"].text(), placeId = m["placeId"].text(), distanceMeters = m["distanceMeters"].num(),
            streetViewHint = m["streetViewId"].text()?.takeIf(StreetView::validId)?.let { StreetView.Hint(it, m["streetViewYaw"].num()) },
        )
    }

    fun encode(p: Place) = Json.write(toMap(p))
    fun decode(s: String) = Json.obj(s)?.let { fromMap(it) }
    fun encodeList(l: List<Place>) = Json.write(l.map { toMap(it) })
    fun decodeList(s: String) = Json.parse(s).asMaps()?.mapNotNull { fromMap(it) } ?: emptyList()
}

internal data class MapPinSizes(val google: Double, val osm: Double, val search: Double) {
    companion object {
        fun read() = MapPinSizes(
            AppSettings.googleIconPercent.coerceIn(60, 160) / 100.0,
            AppSettings.osmIconPercent.coerceIn(60, 160) / 100.0,
            AppSettings.resultIconPercent.coerceIn(60, 160) / 100.0,
        )
    }
}
