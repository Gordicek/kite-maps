package app.kite

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import android.util.Log
import app.kite.core.*
import kotlinx.coroutines.*
import kotlin.math.*

data class CameraAlert(val kind: RoadCamera.Kind, val meters: Double, val startMeters: Double, val limitKmh: Double?)
data class WazeAhead(val alert: WazeAlert, val meters: Double, val startMeters: Double)
data class AverageCheck(val limitKmh: Double?, val averageKmh: Double, val metersLeft: Double)
/** A faster way found mid-drive: how much it saves, which road it takes, and when it's taken by itself if nothing is tapped. */
class FasterOffer(
    val minutes: Int, val via: String?, val untilMillis: Long,
    /** How much further the new way is than what's left of yours (less than 0 when it's shorter). */
    val extraMeters: Double = 0.0,
    /** Pay-later road charges on the new way that yours didn't have, like the Dartford Crossing. */
    val newCharges: List<String> = emptyList(),
    val congestionAhead: Boolean = false,
)

/** A lower speed limit coming up: what it is, and how far. */
data class LimitDrop(val kmh: Double, val meters: Double)

/** How long a faster route offer stays visible. */
const val FASTER_WAIT_MS = 10_000L

/** The speaker button's three choices, like Waze. */
enum class VoiceMode(val title: String, val detail: String) {
    ALL("Voice on", "Turns and warnings"),
    ALERTS("Alerts only", "Cameras and police, no turns"),
    OFF("Voice off", "Nothing spoken");

    val next: VoiceMode get() = when (this) { ALL -> ALERTS; ALERTS -> OFF; OFF -> ALL }
}

data class AircraftNear(val aircraft: PoliceAircraft, val meters: Double)

private fun now() = System.currentTimeMillis()
private fun since(t: Long) = (now() - t) / 1000.0
private fun String.lowercasedFirst() = if (isEmpty()) this else substring(0, 1).lowercase() + substring(1)

/**
 * One drive, walk or ride: follows the user along the route, speaks the turns, and reroutes
 * when they leave it. The same rules as the iPhone app, line for line.
 */
class Navigator(route0: Route, destination0: Place, stops0: List<Place>, private val scope: CoroutineScope, val transitWalk: Boolean = false, rejoinAtStart: Boolean = false) {
    var destination by mutableStateOf(destination0); private set
    var originalParkingDestination by mutableStateOf<Place?>(null); private set
    var parkingOfferDismissed by mutableStateOf(false); private set
    val offerParking: Boolean
        get() = AppSettings.arrivalParking && !parkingOfferDismissed && !arrived && !rerouting && stops.isEmpty() &&
            route.mode == TravelMode.DRIVE && progress?.let { !it.offRoute && it.remainingMeters in 1.0..1000.0 } == true

    val roadTraffic: List<TrafficSpan>
        get() {
            if (!AppSettings.traffic) return emptyList()
            val measured = currentTraffic ?: route.trafficSpans
            if (!AppSettings.aheadWaze) return measured
            // A point report identifies an approximate spot, so keep its mark short.
            val reported = routeWaze.filter { it.alert.kind == WazeAlert.Kind.JAM && AppSettings.showsInStrip(it.alert) }
                .map { TrafficSpan(if (it.alert.subtype == "JAM_STAND_STILL_TRAFFIC") 3 else 2, (it.along - 70).coerceAtLeast(0.0), 140.0) }
            return measured + reported
        }

    fun dismissParkingOffer() { parkingOfferDismissed = true }
    fun parkAt(place: Place) {
        val fix = lastFix ?: return
        if (!arrived) reroute(fix, quiet = true, parking = place)
    }
    var route by mutableStateOf(route0); private set
    var progress by mutableStateOf<RouteTracker.Progress?>(null); private set
    private var currentTraffic by mutableStateOf<List<TrafficSpan>?>(null)
    var rerouting by mutableStateOf(false); private set
    var arrived by mutableStateOf(false); private set
    private var voiceState = mutableStateOf(
        if (!AppSettings.voice) VoiceMode.OFF else if (AppSettings.voiceAlertsOnly) VoiceMode.ALERTS else VoiceMode.ALL)
    /** What the voice says: everything, only warnings (cameras, police, hazards), or nothing. */
    var voiceMode: VoiceMode
        get() = voiceState.value
        set(v) {
            voiceState.value = v
            if (v == VoiceMode.OFF || (v == VoiceMode.ALERTS && !speakingAlert)) Voice.stop()
            // Remembered, so the next trip starts on the same choice instead of full voice again.
            AppSettings.voice = v != VoiceMode.OFF
            AppSettings.voiceAlertsOnly = v == VoiceMode.ALERTS
        }
    val muted: Boolean get() = voiceMode == VoiceMode.OFF
    /** Whether what's being said now is a warning, so switching to "Alerts only" doesn't cut it off. */
    private var speakingAlert = false
    /** The camera follows the user until they pan the map. */
    var following by mutableStateOf(true)
    var speedMps by mutableStateOf(0.0); private set
    var lastFix by mutableStateOf<Fix?>(null); private set
    /** No GPS fix for a while (a tunnel, a car park): the screen says so instead of looking live. */
    var gpsLost by mutableStateOf(false); private set
    /**
     * GPS has gone (a tunnel) and Kite is carrying on along the route at the speed the car went in,
     * like Waze and Google do, so the arrow, the distance and the voice don't freeze.
     */
    var estimating by mutableStateOf(false); private set
    /** When the last real GPS fix came in, by the phone's running timer. */
    private var realFixMs = android.os.SystemClock.elapsedRealtime()
    /** Where along the route, how fast and when the estimate started from (the last real fix). */
    private class EstimateFrom(val along: Double, val speed: Double, val atMs: Long)
    private var estimateFrom: EstimateFrom? = null
    /** An estimated position for the app to show, in place of the fix GPS didn't send. */
    var onEstimate: ((Fix) -> Unit)? = null
    /** A clearly faster way found on the way, offered on a card before it's taken. */
    var fasterOffer by mutableStateOf<FasterOffer?>(null); private set
    private var fasterRoute: Route? = null
    var cameraAhead by mutableStateOf<CameraAlert?>(null); private set
    /** How far along the route the card's camera is. */
    private var cameraAheadAt = 0.0
    var wazeAhead by mutableStateOf<WazeAhead?>(null); private set
    var wazeWarned by mutableIntStateOf(0); private set
    var policeRisk by mutableStateOf<Int?>(null); private set
    var aircraftNear by mutableStateOf<AircraftNear?>(null); private set
    var averageCheck by mutableStateOf<AverageCheck?>(null); private set
    var roadControls by mutableStateOf<List<Overpass.RoadControl>>(emptyList()); private set
    private var preparedControls: Deferred<List<Overpass.RoadControl>?>? = null
    private var nearRoadControls = emptyList<Overpass.RoadControl>()
    private var nearRoadControlsCoverage = 1.0..0.0
    private var allRoadControls = emptyList<Overpass.RoadControl>()
    private var roadControlsJob: Job? = null
    private var roadControlsAt = 0L
    private var roadControlsAlong = -10_000.0

    private fun publishRoadControls() {
        roadControls = Overpass.mergeRoadControls(
            route.roadControls, nearRoadControls,
            allRoadControls.filter { it.along !in nearRoadControlsCoverage },
        )
    }

    fun seedWazeReports(reports: List<WazeAlert>) {
        if (route.mode != TravelMode.DRIVE || reports.isEmpty() || !AppSettings.wazeOn) return
        val current = route
        scope.launch {
            val alerts = withContext(Dispatchers.Default) { Waze.project(reports, current.polyline) }
            if (stopped || route.id != current.id || routeWaze.isNotEmpty() || wazeUpdated || alerts.isEmpty()) return@launch
            routeWaze = alerts
            wazeSeedExpires = now() + 180_000
        }
    }

    fun usePreparedControls(prepared: Deferred<List<Overpass.RoadControl>?>?) {
        preparedControls = prepared
    }

    private fun refreshRoadControls(along: Double) {
        val walking = route.mode == TravelMode.WALK
        if ((!walking && route.mode != TravelMode.DRIVE) || roadControlsJob?.isActive == true ||
            (since(roadControlsAt) < 75 && abs(along - roadControlsAlong) < if (walking) 320 else 950)) return
        roadControlsAt = now()
        roadControlsAlong = along
        val current = route
        val prepared = preparedControls.takeIf { along < 200 }
        preparedControls = null
        val from = if (prepared != null) 0.0 else (along - 35).coerceAtLeast(0.0)
        roadControlsJob = scope.launch {
            val local = prepared?.await() ?: withContext(Dispatchers.Default) {
                val line = Geo.slice(current.polyline, from, along + if (walking) 850.0 else 3_600.0)
                Overpass.controlsOnRoute(line, walking)
            }
            if (stopped || route.id != current.id) return@launch
            if (local == null) {
                roadControlsAt = now() - 63_000
                return@launch
            }
            nearRoadControls = local.map { it.copy(along = it.along + from) }
            nearRoadControlsCoverage = from..(along + if (walking) 850.0 else 3_600.0)
            publishRoadControls()
        }
    }
    var routeWaze by mutableStateOf<List<PlacedAlert>>(emptyList()); private set
    private var wazeUpdated = false
    private var wazeSeedExpires = 0L
    /** Stops still ahead, in trip order; each is dropped once reached. */
    var stops by mutableStateOf(stops0); private set
    /** Drivers' reports from Waze's app servers, asked every 90 seconds. */
    var liveAlerts by mutableStateOf<List<WazeAlert>>(emptyList()); private set
    /** Bumped when the route's cameras change, so the map redraws them. */
    var camerasTick by mutableIntStateOf(0); private set

    private var tracker = RouteTracker(route0)
    private var rerouteSpokenAt = 0L
    private var toldNoSignal = false
    private var spokenStage = mutableMapOf<Int, Int>()
    private var stoppedNearEnd: Long? = null
    private var trafficScale = 1.0
    private var trafficAt = now() - if (route0.trafficSpans.isEmpty()) 100_000 else 0
    private var trafficLoading = false
    private var cameraSpokenAt = 0L
    private var cameraSpokenLimit: Double? = null
    private var joinedRoute = false
    private var rejoiningStart = rejoinAtStart
    private var fullRoadData = false
    private var matchedLimits = false
    private var providerLimits = false
    private var matchedWays: List<Overpass.LimitWay> = emptyList()
    /** Merges on this route that are really a lane gain (where along the route), said nothing. */
    private var quietMerges: Set<Double> = emptySet()
    /** The road matcher's lane counts into each roundabout on this route. */
    private var entryLanes: List<RoundaboutEntry> = emptyList()
    var onFinished: (() -> Unit)? = null
    private var toldContinue = mutableSetOf<Int>()
    private var lastReroute = 0L
    private var rerouteRequest = 0
    private var stopped = false
    /** Roads tapped away on the plan (a point on each): reroutes and faster ways keep off them too. */
    var avoid: List<LatLng> = emptyList()
    private val avoidHighways = AppSettings.avoidHighways
    private val avoidTolls = AppSettings.avoidTolls
    var routeCameras by mutableStateOf<List<PlacedCamera>>(emptyList()); private set
    private val spokenCameras = mutableSetOf<Long>()
    private var zones: List<AverageSpeedZone> = emptyList()
    /** The average speed check being driven through: its stretch of the route, and when and where it began. */
    private data class ZoneEntry(val start: Double, val end: Double, val time: Long, val along: Double, val kmh: Double?)
    private var zoneEntry: ZoneEntry? = null
    /**
     * Rerouted (or a faster way taken) inside an average speed check: when it began, how far was
     * driven in it, and the rest of its road from the old route, so the same check carries on on
     * the new route instead of starting again from nothing. Dropped once the car leaves that road.
     */
    private data class ZoneCarry(val time: Long, val driven: Double, val rest: List<LatLng>, val at: Long, val kmh: Double?)
    private var zoneCarry: ZoneCarry? = null
    private val speeding = SpeedingAlerts()
    private val spokenWaze = mutableSetOf<String>()
    private var wazeAt = 0L
    private var wazeLoading = false
    private var liveAt = 0L
    private var closureAlerts: List<WazeAlert> = emptyList()
    private var riskAt = 0L
    private var riskPast: List<PoliceRisk.Report>? = null
    private var riskSpokenAt = 0L
    private var aircraftAt = 0L
    /** When the aircraft service last answered: an old sighting isn't "nearby" for long. */
    private var aircraftSeenAt = 0L
    private var aircraftLoading = false
    private val spokenAircraft = mutableSetOf<String>()
    private var tracedAt = 0L
    private val recentFixes = mutableListOf<Fix>()

    /** "left" or "right": which side of the road the place is, at the end of the route. */
    private val destinationSide: String? get() = if (route.mode == TravelMode.DRIVE) Geo.sideAtEnd(destination.location, route.polyline) else null

    private fun onOurSide(cams: List<PlacedCamera>): List<PlacedCamera> {
        if (matchedWays.isEmpty()) return cams
        return cams.filter { c -> matchedWays.any { w -> w.line.size >= 2 && Geo.project(c.camera.location, w.line).offset < 15 } }
    }

    private val gpsWatch = scope.launch {
        while (true) {
            delay(1_000)
            if (lastFix == null || demoAlerts) continue
            val gap = (android.os.SystemClock.elapsedRealtime() - realFixMs) / 1000.0
            val lost = gap > 8
            estimate(gap)
            if (lost != gpsLost) gpsLost = lost
        }
    }

    /**
     * No fix for a few seconds while driving on the route: carry on along it at the speed the car
     * had, once a second, for up to three minutes (the longest road tunnels in Britain take about
     * two). Not near the end of the route, where stopping matters, and never off the route.
     */
    private fun estimate(gap: Double) {
        val p = progress ?: return
        val last = lastFix ?: return
        if (arrived || stopped || route.mode != TravelMode.DRIVE || gap <= 3 || rerouting) return
        if (estimateFrom == null) {
            if (p.offRoute || p.offsetMeters >= 30 || speedMps < 5 || p.remainingMeters <= 400) return
            // Started again after a new route was taken mid-tunnel: from where the estimate had got to, now.
            estimateFrom = EstimateFrom(p.alongMeters, speedMps, if (estimating) android.os.SystemClock.elapsedRealtime() else realFixMs)
        }
        val e = estimateFrom ?: return
        val t = (android.os.SystemClock.elapsedRealtime() - e.atMs) / 1000.0
        val along = e.along + e.speed * t
        if (t >= 180 || along >= Geo.length(route.polyline) - 300) {
            // Too long without GPS to keep guessing: stop, and the screen says the signal is lost.
            if (estimating) estimating = false
            return
        }
        val at = Geo.point(route.polyline, along)
        val ahead = Geo.point(route.polyline, along + 15)
        val fix = Fix(at.lat, at.lng, accuracy = 10.0, speed = e.speed, course = if (at == ahead) max(last.course, 0.0) else Geo.bearing(at, ahead),
            altitude = last.altitude, timeMillis = now())
        if (!estimating) estimating = true
        onEstimate?.invoke(fix)
    }

    init {
        Voice.warmUp()
        val first = route0.maneuvers.firstOrNull { it.type != ManeuverType.DEPART } ?: route0.maneuvers.firstOrNull()
        loadCameras()
        loadWaze()
        loadRoundabouts()
        // The first step with how far it is ("In 300 yards, turn left"), so a turn a little way off
        // doesn't sound like it's right here.
        val firstWords = first?.let { f ->
            if (f.alongMeters < 40 || f.type == ManeuverType.ARRIVE) "${f.spokenWords}."
            else announcedIn(f.alongMeters, f.spokenWords.lowercasedFirst()) + "."
        } ?: ""
        speak(kt("Starting route to ${destination.name}. $firstWords", "Zahajuji navigaci do cíle ${destination.name}. $firstWords"))
        // That was the heads-up for the first step: not again a moment later, but still the turn itself.
        if (first != null && first.alongMeters < 1500) {
            val i = route0.maneuvers.indexOf(first)
            if (i >= 0) spokenStage[i] = if (first.alongMeters < 40) 3 else if (first.alongMeters < 300) 2 else 1
        }
    }

    fun stop() {
        stopped = true
        gpsWatch.cancel()
        roadControlsJob?.cancel()
        preparedControls?.cancel()
        Voice.stop()
    }

    /** The posted limit where the user is now, in km/h. */
    val speedLimitKmh: Double?
        get() = demoLimitKmh ?: progress?.takeUnless { it.offRoute || gpsLost && !estimating }?.let {
            route.speedLimit(it.alongMeters)
        }

    /**
     * A lower limit coming up, about 15 seconds ahead at your speed: shown small above the speed sign
     * so there's time to ease off. Only from the map's own limits, never guessed; gone once it starts.
     */
    val limitDropAhead: LimitDrop?
        get() {
            demoLimitDrop?.let { return it }
            val p = progress ?: return null
            if (route.mode != TravelMode.DRIVE || arrived || p.offRoute || gpsLost && !estimating) return null
            val next = route.nextLimitDrop(p.alongMeters, NavZoom.limitHeadsUp(speedMps)) ?: return null
            val kmh = next.kmh ?: return null
            val meters = next.startMeters - p.alongMeters
            return if (meters > 15) LimitDrop(kmh, meters) else null
        }

    val nextManeuver: Maneuver?
        get() {
            demoManeuver?.let { return it }
            val i = progress?.nextIndex
            if (i == null || i >= route.maneuvers.size) return route.maneuvers.firstOrNull()
            return route.maneuvers[i]
        }

    var nearbyRoadName by mutableStateOf<String?>(null)

    /** The road being driven now, for the name under the arrow. */
    val currentRoad: String?
        get() {
            val along = progress?.alongMeters ?: 0.0
            return nearbyRoadName ?: route.maneuvers.asReversed().firstNotNullOfOrNull { step ->
                if (step.alongMeters !in (along - 650.0)..(along + 3.0) || step.type == ManeuverType.ARRIVE) null
                else step.road?.trim()?.takeIf(String::isNotEmpty)
            }
        }

    /**
     * ", then turn right onto King Street" when the step after [i] comes so soon after it (70 m)
     * there's no time to be told on its own, the way Waze says both together. Not for leaving a
     * roundabout (said with the roundabout) or arriving.
     */
    private fun thenWords(i: Int): String {
        if (route.mode != TravelMode.DRIVE || i >= route.maneuvers.size) return ""
        val j = (i + 1 until route.maneuvers.size).firstOrNull { !route.maneuvers[it].silent } ?: return ""
        val a = route.maneuvers[i]; val b = route.maneuvers[j]
        if (b.leavesRoundabout || b.type == ManeuverType.ARRIVE || b.alongMeters - a.alongMeters >= 70) return ""
        return kt(", then ", ", poté ") + b.spokenWords.lowercasedFirst()
    }

    /** The one after next, for the "Then" line when two turns come close together. */
    val thenManeuver: Maneuver?
        get() {
            val i = progress?.nextIndex ?: return null
            if (i >= route.maneuvers.size) return null
            val j = (i + 1 until route.maneuvers.size).firstOrNull { !route.maneuvers[it].silent } ?: return null
            val a = route.maneuvers[i]; val b = route.maneuvers[j]
            if (b.leavesRoundabout) return null
            return if (b.alongMeters - a.alongMeters < 300) b else null
        }

    /** How far after the next turn the "Then" one comes, for "Then ↰ after 40 yds". */
    val thenGap: Double?
        get() {
            val b = thenManeuver ?: return null
            val a = nextManeuver ?: return null
            val gap = b.alongMeters - a.alongMeters
            return if (gap > 0) gap else null
        }

    private fun update(fix: Fix, heading: Double? = null): RouteTracker.Progress {
        val p = tracker.update(fix.coord, max(fix.accuracy, 5.0), heading)
        return p.copy(remainingSeconds = p.remainingSeconds * trafficScale)
    }

    private var transitArrival: Long? = null

    /** [estimated]: a position worked out along the route while GPS is gone, not a real fix. */
    fun handle(loc: Fix, estimated: Boolean = false) {
        if (arrived) return
        if (!estimated) {
            realFixMs = android.os.SystemClock.elapsedRealtime()
            estimateFrom = null
            if (estimating) estimating = false
        }
        lastFix = loc
        if (!demoAlerts) speedMps = max(0.0, loc.speed)
        val course = heading(loc)
        if (!estimated) noteFix(loc)
        val p = update(loc, course)
        progress = p
        val toPin = loc.coord.distanceTo(destination.location)
        // Near the pin only counts near the end of the route too: a route can pass right by the
        // place (or by it on the way to a stop) before looping round to its way in.
        // And only once every stop is done: a round trip starts right by its own destination.
        val nearPin = p.remainingMeters < 400 && stops.isEmpty()
        if (loc.speedKnown && loc.speed < 1 && (p.remainingMeters < 150 || toPin < 150)) {
            if (stoppedNearEnd == null) stoppedNearEnd = loc.timeMillis
        } else stoppedNearEnd = null
        val offRoad = p.offsetMeters > 20 || p.remainingMeters < 40 || (toPin < 60 && nearPin)
        // Parked by the pin with the route still going round (a way in the map doesn't have): three minutes.
        val parked = stoppedNearEnd?.let {
            (loc.timeMillis - it) / 1000.0 > (if (p.remainingMeters >= 150 && !nearPin) 180 else if (offRoad) 20 else 90)
        } ?: false
        val atStop = if (transitWalk) {
            val near = !estimated && loc.ageMs in 0..15_000 && loc.accuracy in 0.0..50.0 &&
                toPin <= 40 && (p.remainingMeters <= 80 || toPin <= 18)
            if (!near) transitArrival = null
            val first = transitArrival
            if (near && first == null) transitArrival = loc.elapsedMs
            near && first != null && loc.elapsedMs - first >= 900
        } else p.arrived || parked || (route.mode == TravelMode.DRIVE && toPin < 40 && nearPin)
        if (atStop) {
            arrived = true
            fasterOffer = null
            fasterRoute = null
            estimating = false
            speak(kt("You have arrived at ${destination.name}" + (destinationSide?.let { ", on the $it." } ?: "."), "Jste v cíli. ${destination.name}."))
            scope.launch {
                delay(180_000)
                if (arrived && !stopped) onFinished?.invoke()
            }
            return
        }
        if (p.offsetMeters < 40) {
            joinedRoute = true
            rejoiningStart = false
        }
        if (p.offRoute && !estimated && !rerouting && (toPin > 150 || !nearPin) &&
            (joinedRoute || p.offsetMeters > 400 || rejoiningStart && loc.accuracy <= 55) && since(lastReroute) > 8) {
            reroute(loc)
            return
        }
        if (since(tracedAt) >= 3) {
            tracedAt = now()
            Log.i("KiteTrace", String.format(java.util.Locale.US, "at %.5f,%.5f along %d off %d next %d in %d offRoute %b speed %d",
                loc.lat, loc.lng, p.alongMeters.toInt(), p.offsetMeters.toInt(), p.nextIndex ?: -1, p.distanceToNext.toInt(), p.offRoute, speedMps.toInt()))
        }
        announce(p)
        checkTraffic(p, loc)
        if (p.offsetMeters <= 120) refreshRoadControls(p.alongMeters)
        checkCameras(p)
        checkWaze(p)
        checkRisk(p)
        checkAircraft(loc)
        checkZones(p)
        checkStops(p)
        if (route.mode == TravelMode.DRIVE && AppSettings.speedingAlert &&
            speeding.update(if (loc.speedKnown) loc.speed * 3.6 else null, speedLimitKmh, loc.timeMillis / 1000.0)) {
            speak(kt("You're over the speed limit.", "Překračujete povolenou rychlost."), alert = true)
        }
    }

    /** A stop picked on the way (search along the route): go there first, then on. */
    fun addStop(p: Place, index: Int = 0) {
        if (stops.size >= 8 || stops.any(p::matchesNearby)) return
        stops = stops.toMutableList().apply { add(index.coerceIn(0, size), p) }
        speak(kt("Adding a stop at ${p.name}.", "Přidávám zastávku ${p.name}."))
        lastFix?.let { reroute(it, quiet = true, adding = p) }
    }

    fun removeNextStop() {
        val next = stops.firstOrNull() ?: return
        val fix = lastFix ?: return
        if (!rerouting && !arrived) reroute(fix, quiet = true, removing = next)
    }

    private fun checkStops(p: RouteTracker.Progress) {
        val next = stops.firstOrNull() ?: return
        val at = Geo.project(next.location, route.polyline)
        if (at.offset >= 150 || p.alongMeters < at.along - 40) return
        stops = stops.drop(1)
        val following = stops.firstOrNull()?.name ?: destination.name
        speak(kt("You've reached ${next.name}. Continuing to $following.", "Dorazili jste na zastávku ${next.name}. Pokračujte směr $following."))
    }

    /** The real shape of each roundabout ahead, for the picture in the banner. */
    private fun loadRoundabouts() {
        if (route.mode == TravelMode.WALK) return
        val start = route
        quietMerges = emptySet()
        // "Merge onto…" only where a lane really ends: a slip road that becomes a lane of its own is said nothing.
        if (route.mode == TravelMode.DRIVE && start.maneuvers.any { it.type == ManeuverType.MERGE && !it.silent }) {
            scope.launch {
                val gains = Merges.laneGains(start.maneuvers.map { it.copy() }, start.polyline) ?: return@launch
                if (route.id != start.id) return@launch
                quietMerges = gains
                useSteps(route.maneuvers)
            }
        }
        scope.launch {
            if (start.maneuvers.any { it.type == ManeuverType.ROUNDABOUT }) {
                Roundabouts.apply(start.deep())?.let { drawn ->
                    if (route.id != start.id || stopped) return@launch
                    val byStep = drawn.maneuvers.filter { it.type == ManeuverType.ROUNDABOUT }
                        .associateBy { it.alongMeters to it.leavesRoundabout }
                    val steps = route.maneuvers.map { m ->
                        val actual = byStep[m.alongMeters to m.leavesRoundabout]
                            ?.takeIf { m.type == ManeuverType.ROUNDABOUT } ?: return@map m
                        m.copy(roundabout = actual.roundabout, exit = actual.exit, instruction = actual.instruction)
                    }.toMutableList()
                    // Geometry and step indices didn't change. Keep the tracker on the current leg.
                    route = route.copy(maneuvers = steps)
                    Roundabouts.withEntryLanes(route.maneuvers, entryLanes)?.let { route = route.copy(maneuvers = it) }
                }
            }
            if (route.id != start.id || stopped) return@launch
            Roundabouts.addMinis(route.deep())?.let { withMinis ->
                if (route.id == start.id && !stopped) useSteps(withMinis.maneuvers)
            }
        }
    }

    /** New steps on the same route line: what was already said stays said, step by step. */
    private fun useSteps(steps0: List<Maneuver>) {
        val steps = steps0.map { if (it.type == ManeuverType.MERGE && it.alongMeters in quietMerges) it.copy(silent = true) else it }
        val old = route.maneuvers
        fun moved(i: Int): Int? {
            if (i >= old.size) return null
            return steps.indexOfFirst { it.alongMeters == old[i].alongMeters }.takeIf { it >= 0 }
        }
        val stages = mutableMapOf<Int, Int>()
        spokenStage.forEach { (k, v) -> moved(k)?.let { stages[it] = max(stages[it] ?: 0, v) } }
        spokenStage = stages
        toldContinue = toldContinue.mapNotNull { moved(it) }.toMutableSet()
        route = route.copy(maneuvers = steps.toMutableList())
        tracker = RouteTracker(route)
        lastFix?.let { progress = update(it) }
    }

    private fun setLimits(l: List<SpeedLimit>) {
        if (l.any { it.kmh != null } && !providerLimits)
            route = route.copy(speedLimits = l)
    }

    private fun loadCameras() {
        routeCameras = emptyList()
        cameraAhead = null
        zones = emptyList()
        zoneEntry = null
        averageCheck = null
        roadControlsJob?.cancel()
        roadControlsAt = 0L
        roadControlsAlong = -10_000.0
        nearRoadControls = emptyList()
        nearRoadControlsCoverage = 1.0..0.0
        allRoadControls = emptyList()
        roadControls = route.roadControls
        camerasTick++
        val s = AppSettings
        if (route.mode != TravelMode.DRIVE) return
        val line = route.polyline; val id = route.id
        val speed = s.speedCameras; val plate = s.plateCameras
        // Waze's speed cameras too, while Waze is switched on.
        WazeCameras.enabled = s.wazeOn
        fullRoadData = false
        matchedLimits = false
        providerLimits = route.source == "mapbox" && route.speedLimits.any { it.kmh != null }
        matchedWays = emptyList()
        entryLanes = emptyList()
        val matcher = scope.async {
            val found = Valhalla.speedLimits(line) { partial ->
                withContext(Dispatchers.Main) {
                    if (route.id == id && partial.isNotEmpty()) { matchedLimits = true; setLimits(partial) }
                }
            }
            if (found == null || route.id != id || found.limits.isEmpty()) {
                // A long drive's later miles didn't come back (the server timed out on one piece): the
                // first miles were already shown, but the rest must still come from the road data below,
                // or the sign stays blank past the first 25 km for the whole drive.
                if (route.id == id) matchedLimits = false
                return@async false
            }
            matchedLimits = true
            setLimits(found.limits)
            matchedWays = found.ways
            // How many lanes go into each roundabout, in case OpenStreetMap's own answer doesn't come.
            entryLanes = found.entries
            Roundabouts.withEntryLanes(route.maneuvers, found.entries)?.let { route = route.copy(maneuvers = it) }
            routeCameras = onOurSide(routeCameras)
            camerasTick++
            withContext(Dispatchers.IO) { RoadCache.shared.add(found.ways.filter { it.kmh != null }, emptyList(), emptyList()) }
            true
        }
        suspend fun useKnown() {
            val known = withContext(Dispatchers.Default) {
                val k = RoadCache.shared.along(line)
                val cams = CameraAlerts.project(k.cameras.filter { if (it.kind == RoadCamera.Kind.SPEED) speed else plate }, line)
                cams to if (k.ways.isEmpty()) emptyList() else Overpass.speedLimits(k.ways, line)
            }
            if (route.id != id || fullRoadData) return
            if (known.second.isNotEmpty() && !matchedLimits) setLimits(known.second)
            routeCameras = onOurSide(known.first)
            zones = CameraAlerts.averageZones(known.first)
            camerasTick++
        }
        scope.launch { useKnown() }
        val cum = Geo.cumulative(line)
        if (cum.last() > 8000) {
            val start = Geo.slice(line, 0.0, 6000.0, cum)
            scope.launch {
                val limits = !matcher.await()
                val first = Overpass.alongRoute(start, speed, plate, limits) ?: return@launch
                if (!stopped && route.id == id && !fullRoadData) {
                    allRoadControls = first.controls
                    publishRoadControls()
                }
                useKnown()
            }
        }
        scope.launch {
            val limits = !matcher.await()
            var result = Overpass.alongRoute(line, speed, plate, limits)
            repeat(2) {
                if (result == null && route.id == id) {
                    delay(20_000)
                    result = Overpass.alongRoute(line, speed, plate, limits)
                }
            }
            // OpenStreetMap never answered: Waze's cameras, read in the meantime, still show.
            val found = result ?: run { if (route.id == id) useKnown(); return@launch }
            if (route.id != id) return@launch
            val (cameras, postedLimits) = withContext(Dispatchers.Default) {
                CameraAlerts.project(found.cameras, line) to Overpass.speedLimits(found.ways, line)
            }
            if (route.id != id || stopped) return@launch
            fullRoadData = true
            routeCameras = onOurSide(cameras)
            if (!matchedLimits) setLimits(postedLimits)
            zones = CameraAlerts.merge(found.zones, CameraAlerts.averageZones(routeCameras))
            allRoadControls = found.controls
            publishRoadControls()
            camerasTick++
        }
    }

    private fun checkCameras(p: RouteTracker.Progress) {
        if (demoAlerts) return
        val meters = routeCameras.map { it.along }
        // Keep the card up, counting down, until its own camera is passed.
        cameraAhead?.let { ahead ->
            val left = cameraAheadAt - p.alongMeters
            cameraAhead = if (left > 0 && left < CameraAlerts.maxLead) ahead.copy(meters = left) else null
        }
        val spoken = routeCameras.indices.filter { spokenCameras.contains(routeCameras[it].camera.id) }.toSet()
        val i = CameraAlerts.due(meters, p.alongMeters, speedMps, spoken) ?: return
        val cam = routeCameras[i].camera
        // A speed camera's card stays up until it's passed: a plate camera just after it waits its turn.
        if (cam.kind != RoadCamera.Kind.SPEED && cameraAhead?.kind == RoadCamera.Kind.SPEED) return
        if (cam.quiet) spokenCameras.add(cam.id)
        // Only the same kind, though: a number plate camera just before a speed camera mustn't use up its warning.
        else meters.forEachIndexed { j, m ->
            if (m >= meters[i] && m - meters[i] < 200 && routeCameras[j].camera.kind == cam.kind) spokenCameras.add(routeCameras[j].camera.id)
        }
        val ahead = meters[i] - p.alongMeters
        val limit = if (cam.kind == RoadCamera.Kind.SPEED) (cam.maxspeedKmh ?: route.speedLimit(meters[i])) else null
        cameraAhead = CameraAlert(cam.kind, ahead, max(ahead, 1.0), limit)
        cameraAheadAt = meters[i]
        val repeatRun = since(cameraSpokenAt) < 150 && limit == cameraSpokenLimit
        if (AppSettings.cameraWarnings && cam.kind == RoadCamera.Kind.SPEED && !cam.quiet) {
            chime()
            if (!repeatRun) {
                val warning = if (cam.average) kt("Average speed camera ahead", "Pozor, úsekové měření rychlosti") else kt("Speed camera ahead", "Pozor, radar")
                speak(warning + (limit?.let { kt(", limit ", ", limit je ") + Fmt.spokenSpeed(it) + "." } ?: "."), alert = true)
            }
            cameraSpokenAt = now()
            cameraSpokenLimit = limit
        }
    }

    /** Live Waze reports along the route; asked again every 90 seconds, since they come and go. */
    private fun loadWaze() {
        if (demoAlerts) return
        if (route.mode != TravelMode.DRIVE || !AppSettings.wazeOn || wazeLoading) return
        wazeLoading = true
        wazeAt = now()
        val line = route.polyline; val id = route.id
        val at = progress?.alongMeters ?: 0.0
        val window = Geo.slice(line, max(at - 200, 0.0), at + 60_000)
        val here = progress?.let { Geo.point(line, it.alongMeters) } ?: line[0]
        val ahead = Geo.slice(line, max(at - 200, 0.0), at + 40_000)
        scope.launch {
            val closures = async { runCatching { Waze.alongRoute(if (window.size >= 2) window else line) }.getOrNull() }
            val live = runCatching { WazeLive.shared.alongRoute(if (ahead.size >= 2) ahead else line, here) }.getOrNull()
            val found = closures.await()
            wazeLoading = false
            if (route.id != id) return@launch
            if (found != null) { WazeHistory.record(found); closureAlerts = found }
            if (live != null) {
                WazeHistory.record(live)
                liveAlerts = live
                liveAt = now()
            } else if (liveAlerts.isNotEmpty() && since(liveAt) > 300) {
                liveAlerts = emptyList()
            }
            val merged = linkedMapOf<String, WazeAlert>()
            for (a in closureAlerts + liveAlerts) merged[a.id] = a
            val projected = withContext(Dispatchers.Default) { Waze.project(merged.values.toList(), line) }
            if (route.id == id && !stopped && (found != null || live != null || now() >= wazeSeedExpires)) {
                if (found != null || live != null) wazeUpdated = true
                routeWaze = projected
            }
        }
    }

    private fun checkWaze(p: RouteTracker.Progress) {
        if (demoAlerts) return
        val s = AppSettings
        if (!s.wazeOn) {
            wazeAhead = null
            return
        }
        if (since(wazeAt) > 90) loadWaze()
        wazeAhead?.let { ahead ->
            val hit = routeWaze.firstOrNull { it.alert.id == ahead.alert.id && s.shows(it.alert) }
            wazeAhead = if (hit != null && hit.along > p.alongMeters - 10) {
                val a = ahead.copy(meters = max(hit.along - p.alongMeters, 0.0))
                if (a.meters > 0) a else null
            } else null
        }
        if (speedMps < CameraAlerts.movingFloor) return
        val lead = min(max(speedMps * 20, 250.0), 1200.0)
        val next = routeWaze.firstOrNull {
            !spokenWaze.contains(it.alert.id) && s.shows(it.alert) && it.along > p.alongMeters && it.along - p.alongMeters <= lead
        } ?: return
        // A police car or a crash is never folded into a lesser report next to it, though.
        val major = setOf(WazeAlert.Kind.POLICE, WazeAlert.Kind.ACCIDENT)
        for (w in routeWaze) if ((abs(w.along - next.along) < 60 && (w.alert.kind == next.alert.kind || w.alert.kind !in major)) ||
            (w.alert.kind == next.alert.kind && abs(w.along - next.along) < 400)) spokenWaze.add(w.alert.id)
        val ahead = next.along - p.alongMeters
        wazeAhead = WazeAhead(next.alert, ahead, max(ahead, 1.0))
        wazeWarned++
        Voice.buzz()
        if (s.wazeVoice && !Voice.onCall) {
            chime()
            speak(kt("${next.alert.spoken.dropLast(1)}, in ${Fmt.spokenDistance(ahead)}.",
                "Za ${Fmt.spokenDistance(ahead)}: ${localizeLabel(next.alert.title)}."), alert = true)
        }
    }

    /** The police risk for the next 3 km, worked out again every 20 seconds. */
    private fun checkRisk(p: RouteTracker.Progress) {
        if (demoAlerts) return
        val s = AppSettings
        if (route.mode != TravelMode.DRIVE || !s.wazeOn || !s.policeRisk || !s.wazePolice) { policeRisk = null; return }
        val to = p.alongMeters + 3000
        val live = routeWaze.count { it.alert.kind == WazeAlert.Kind.POLICE && s.shows(it.alert) && it.along > p.alongMeters && it.along < to }
        if (live == 0) { policeRisk = null; riskAt = 0L; return }
        if (since(riskAt) <= 20) return
        riskAt = now()
        if (riskPast == null) riskPast = WazeHistory.reports
        val ahead = Geo.slice(route.polyline, p.alongMeters, to)
        val score = PoliceRisk.score(ahead, riskPast ?: emptyList(), live)
        val was = policeRisk?.let { PoliceRisk.level(it) } ?: PoliceRisk.Level.LOW
        policeRisk = score
        if (PoliceRisk.level(score) == PoliceRisk.Level.HIGH && was != PoliceRisk.Level.HIGH && s.wazeVoice && !Voice.onCall &&
            since(riskSpokenAt) > 600 &&
            (PoliceRisk.level(PoliceRisk.score(ahead, riskPast ?: emptyList(), 0)) == PoliceRisk.Level.HIGH)) {
            riskSpokenAt = now()
            speak(kt("Police hotspot ahead.", "Před vámi je častý výskyt policejních hlídek."), alert = true)
        }
    }

    /** Police aircraft near the user, asked every 30 seconds while driving. */
    private fun checkAircraft(loc: Fix) {
        if (demoAlerts) return
        if (route.mode != TravelMode.DRIVE || !AppSettings.policeAircraft) { aircraftNear = null; return }
        val here = loc.coord
        aircraftNear?.let {
            val d = it.aircraft.location.distanceTo(here)
            // No answer for two minutes (no signal), or 10 km past where it was: it's no longer nearby.
            aircraftNear = if (d < 10_000 && since(aircraftSeenAt) < 120) it.copy(meters = d) else null
        }
        if (aircraftLoading || since(aircraftAt) <= 30) return
        aircraftLoading = true
        aircraftAt = now()
        scope.launch {
            val found = runCatching { Aircraft.police(here, 10) }.getOrNull()
            aircraftLoading = false
            if (found == null || stopped) return@launch
            aircraftSeenAt = now()
            val me = lastFix?.coord ?: here
            val nearest = found.map { it to it.location.distanceTo(me) }.filter { it.second < 10_000 }.minByOrNull { it.second }
            aircraftNear = nearest?.let { AircraftNear(it.first, it.second) }
            val a = nearest?.first ?: return@launch
            if (spokenAircraft.add(a.id)) {
                Voice.buzz()
                chime()
                if (!Voice.onCall) speak(kt("${a.title} nearby.", "V okolí: ${localizeLabel(a.title)}."), alert = true)
            }
        }
    }

    /** Average speed checks: say when one starts and ends, and keep the user's average on screen. */
    private fun checkZones(p: RouteTracker.Progress) {
        if (demoAlerts) return
        val at = p.alongMeters
        // How far along the carried check's road the car is now, while it's still on that road.
        var carried: Double? = null
        zoneCarry?.let { c ->
            val on = Geo.project(p.snapped, c.rest)
            if (c.rest.size >= 2 && on.offset < 40 && on.along < Geo.length(c.rest) - 10 && since(c.at) < 600) carried = c.driven + on.along
            // Off that road, past its end or long ago: a check from here on is a new one.
            else zoneCarry = null
        }
        val inside = zones.indexOfFirst { at >= it.startMeters && at <= it.endMeters }.takeIf { it >= 0 }
        zoneEntry?.let { entry ->
            // The list of checks is replaced as road data comes in: the same stretch of road is
            // the same check, not one ending and another starting ("ended" then "for 2 miles").
            val same = inside?.let { zones[it] }?.takeIf { it.startMeters <= entry.end && it.endMeters >= entry.start }
            if (same != null) {
                zoneEntry = entry.copy(start = same.startMeters, end = same.endMeters, kmh = same.kmh ?: entry.kmh)
            } else {
                zoneEntry = null
                averageCheck = null
                if (AppSettings.cameraWarnings) speak(kt("Average speed check ended.", "Konec úsekového měření rychlosti."), alert = true)
            }
        }
        val i = inside ?: return
        val z = zones[i]
        val limit = z.kmh ?: route.speedLimit(at)
        if (zoneEntry == null) {
            // Rerouted inside a check and still on its road: the same check, carried on quietly
            // with the average so far (every metre driven in it, the reroute included).
            val c = zoneCarry
            val driven = carried
            // A different limit is a different check, even on a road right next to it.
            val ck = c?.kmh; val zk = z.kmh
            if (c != null && driven != null && (ck == null || zk == null || abs(ck - zk) < 2)) {
                zoneEntry = ZoneEntry(z.startMeters, z.endMeters, c.time, at - driven, zk ?: ck)
            } else {
                zoneEntry = ZoneEntry(z.startMeters, z.endMeters, now(), at, zk)
                if (AppSettings.cameraWarnings) {
                    chime()
                    val length = Fmt.spokenDistance(z.endMeters - at)
                    speak(kt("Average speed check for $length", "Úsekové měření rychlosti. Délka úseku $length") +
                        (limit?.let { kt(", limit ", ", limit je ") + Fmt.spokenSpeed(it) + "." } ?: "."), alert = true)
                }
            }
            zoneCarry = null
        }
        val entry = zoneEntry ?: return
        val secs = since(entry.time)
        val avg = if (secs > 5) (at - entry.along) / secs * 3.6 else speedMps * 3.6
        averageCheck = AverageCheck(limit, avg, z.endMeters - at)
    }

    /** Tapping the Waze card puts it away; the report still won't be warned about again. */
    fun dismissWaze() { wazeAhead = null }

    // For the build's screenshots only: made-up warnings, so every run shows the same thing.
    private var demoAlerts = false
    private var demoLimitKmh: Double? = null
    private var demoLimitDrop: LimitDrop? = null

    /** For the build's screenshots: 60 now, dropping to 30 in 300 yards. */
    fun showDemoLimitDrop() {
        demoLimitDrop = LimitDrop(48.3, 274.0)
        showDemoSpeed(96.6, 52.0)
    }
    var demoManeuver by mutableStateOf<Maneuver?>(null); private set

    fun showDemoRoundabout() {
        demoManeuver = Maneuver(ManeuverType.ROUNDABOUT, "At the roundabout, take the 6th exit onto M27", LatLng(50.87, -1.27), 0.0, 400.0,
            road = "M27", exit = 6, roundabout = RoundaboutShape(exits = listOf(262.0, 300.0, 338.0, 20.0, 62.0, 108.0), taken = 5,
                entriesOnly = listOf(150.0), clockwise = true, complete = true))
    }

    fun showDemoAlerts() {
        demoAlerts = true
        cameraAhead = CameraAlert(RoadCamera.Kind.SPEED, 180.0, 400.0, 48.3)
        averageCheck = AverageCheck(80.5, 77.0, 2400.0)
        // 38 in a 30, so the screenshots show the speed sign gone red.
        showDemoSpeed(48.3, 38.0)
    }

    fun showDemoSpeed(limitKmh: Double, mph: Double) {
        demoAlerts = true
        demoLimitKmh = limitKmh
        speedMps = mph / 2.23694
    }

    /** For the build's screenshots: standstill traffic up the road, shown small over the trip bar. */
    fun showDemoTraffic() {
        demoAlerts = true
        val here = progress?.let { Geo.point(route.polyline, it.alongMeters + 130) } ?: route.polyline.firstOrNull() ?: LatLng(0.0, 0.0)
        val a = WazeAlert(id = "demo-jam", kind = WazeAlert.Kind.JAM, subtype = "JAM_STAND_STILL_TRAFFIC", location = here, street = "A335",
            reportedAtMillis = System.currentTimeMillis() - 4 * 60_000, thumbsUp = 1, reliability = 7)
        wazeAhead = WazeAhead(a, 130.0, 600.0)
    }

    fun showDemoWaze() {
        demoAlerts = true
        val here = progress?.let { Geo.point(route.polyline, it.alongMeters + 300) } ?: route.polyline.firstOrNull() ?: LatLng(0.0, 0.0)
        val a = WazeAlert(id = "demo-police", kind = WazeAlert.Kind.POLICE, subtype = "POLICE_HIDING", location = here, street = "A9",
            reportedAtMillis = System.currentTimeMillis() - 7 * 60_000, thumbsUp = 3, reliability = 8)
        wazeAhead = WazeAhead(a, 260.0, 600.0)
        policeRisk = 72
        aircraftNear = AircraftNear(PoliceAircraft(id = "demo-heli", callsign = "NPAS15", location = here, altitudeFeet = 1400.0, helicopter = true), 3200.0)
    }

    private fun chime() {
        if (muted || stopped || Voice.onCall) return
        Voice.chime()
    }

    private fun announce(p: RouteTracker.Progress) {
        if (rerouting) return
        val i = p.nextIndex ?: return
        if (i >= route.maneuvers.size) return
        val m = route.maneuvers[i]
        val d = p.distanceToNext
        val stage = spokenStage[i] ?: 0
        val drive = route.mode == TravelMode.DRIVE
        val nowD = if (drive) max(40.0, speedMps * 4) else 15.0
        val near = if (drive) max(250.0, speedMps * 12) else 60.0
        val far = if (drive) max(1000.0, speedMps * 40) else 250.0
        if (d <= nowD && stage < 3) {
            spokenStage[i] = 3
            if (m.type == ManeuverType.ARRIVE) return
            if (m.leavesRoundabout) {
                val r = route.maneuvers.subList(0, i).lastOrNull { it.type == ManeuverType.ROUNDABOUT }
                if (r != null && m.alongMeters - r.alongMeters < 150) return
            }
            speak(m.spokenWords + thenWords(i), urgent = true)
        } else if (m.leavesRoundabout) {
            return
        } else if (d <= near && d > nowD + 30 && stage < 2) {
            spokenStage[i] = 2
            val lane = LaneGuide.lanes(m, Geo.drivesOnLeft(m.location))?.let { g ->
                if (czechUi) LaneGuide.czechAdvice(g.first) else if (g.second.startsWith("Lane")) ". Use " + g.second.lowercase().replace("from left", "from the left")
                else ". Use the ${g.second.lowercase()}"
            } ?: ""
            val words = if (m.type == ManeuverType.ARRIVE) {
                val english = destinationSide?.let { "your destination is on the $it" } ?: "your destination is ahead"
                kt(english, when (destinationSide) { "left" -> "je cíl vlevo"; "right" -> "je cíl vpravo"; else -> "je cíl před vámi" })
            } else m.spokenWords.lowercasedFirst()
            speak(announcedIn(d, words) + (if (m.type == ManeuverType.ARRIVE) "" else thenWords(i)) + lane)
        } else if (d <= far && d > near + 150 && stage < 1 && m.type != ManeuverType.ARRIVE) {
            spokenStage[i] = 1
            if (Voice.onCall) return
            speak(announcedIn(d, m.spokenWords.lowercasedFirst()))
        } else if (drive && d > 3000 && stage < 1 && !toldContinue.contains(i) && justPassed(p)) {
            toldContinue.add(i)
            if (!Voice.onCall) speak(continueWords(d))
        }
    }

    /** Every few minutes on a drive, asks again for the way from here, like Waze. */
    private fun checkTraffic(p: RouteTracker.Progress, loc: Fix) {
        val mapboxToken = AppSettings.activeMapboxToken
        val useGoogle = !AppSettings.noGoogle
        val engine = AppSettings.driveEngine
        if (!AppSettings.traffic || route.mode != TravelMode.DRIVE || (!useGoogle && mapboxToken == null) ||
            rerouting || trafficLoading || fasterOffer != null || p.remainingMeters <= 250 || p.offRoute || since(trafficAt) <= 110) return
        trafficLoading = true
        trafficAt = now()
        val from = loc.coord
        val course = heading(loc)
        val currentRoute = route
        val id = currentRoute.id
        val planned = p.remainingSeconds / trafficScale
        val to = destination.location
        val via = stops.map { it.location }
        val previousTraffic = currentTraffic ?: currentRoute.trafficSpans
        scope.launch {
            val annotationOnly = engine == RouteEngine.OPENSTREETMAP || currentRoute.source == "osm"
            val fresh = try {
                if (annotationOnly && mapboxToken != null) withContext(Dispatchers.Default) {
                    val line = currentRoute.polyline
                    val cum = Geo.cumulative(line)
                    val end = min(cum.last(), p.alongMeters + 10_000)
                    if (end - p.alongMeters < 250) return@withContext null
                    val anchors = generateSequence(p.alongMeters + 900) { it + 900 }
                        .takeWhile { it < end - 80 }.map { Geo.point(line, it, cum) }.toList()
                    MapboxDirections.routes(Geo.point(line, p.alongMeters, cum), Geo.point(line, end, cum), anchors,
                        null, false, false, false, mapboxToken).firstOrNull()
                } else {
                    val provider = if (annotationOnly) RouteEngine.GOOGLE else engine
                    Directions.routes(from, to, TravelMode.DRIVE, avoidHighways, avoidTolls, useGoogle, via, course,
                        mapboxToken = mapboxToken, engine = provider).routes.firstOrNull()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            } finally { trafficLoading = false } ?: return@launch
            val nowP = progress ?: return@launch
            if (stopped || arrived || route.id != id || rerouting || planned <= 0 || fresh.trafficSeconds == null) return@launch
            if (annotationOnly) {
                val updated = withContext(Dispatchers.Default) {
                    if (fresh.trafficSpans.isEmpty() && fresh.source == "google") null
                    else MapboxDirections.refreshTraffic(currentRoute.polyline, previousTraffic, fresh, p.alongMeters)
                }
                if (updated != null && route.id == id && !stopped && !rerouting) currentTraffic = updated
                return@launch
            }
            val current = nowP.remainingSeconds
            val saving = current - fresh.durationSeconds
            val left = withContext(Dispatchers.Default) {
                Geo.slice(currentRoute.polyline, p.alongMeters, Geo.length(currentRoute.polyline))
            }
            val sameWay = withContext(Dispatchers.Default) { Directions.sameRoads(left, fresh.polyline) }
            if (route.id != id || stopped || arrived || rerouting || progress?.offRoute == true) return@launch
            // A faster way back along a road that was tapped away is no offer at all.
            if (!sameWay && saving > max(120.0, current * 0.08) && !Directions.touches(fresh.polyline, avoid)) {
                val congestionAhead = previousTraffic.any {
                    it.level >= 2 && it.startMeters + it.lengthMeters >= nowP.alongMeters
                }
                offerFaster(fresh, saving, nowP.remainingMeters, left, congestionAhead)
            } else if (sameWay) {
                trafficScale = min(3.0, max(0.5, fresh.durationSeconds / planned))
                if (fresh.source == "mapbox" || fresh.source == "google") {
                    val updated = withContext(Dispatchers.Default) {
                        if (fresh.trafficSpans.isEmpty() && fresh.source == "google" &&
                            fresh.durationSeconds > fresh.typicalSeconds * 1.12) null
                        else MapboxDirections.refreshTraffic(currentRoute.polyline, previousTraffic, fresh, p.alongMeters)
                    }
                    if (updated != null && route.id == id && !stopped && !rerouting) currentTraffic = updated
                }
            }
        }
    }

    /**
     * A faster way is offered on a card with two big buttons, like Waze, rather than the line on the
     * map just changing. The alternative expires unless the driver accepts it.
     */
    private fun offerFaster(fresh: Route, saving: Double, remainingMeters: Double,
                            remainingLine: List<LatLng>, congestionAhead: Boolean) {
        val minutes = max(1, (saving / 60).roundToInt())
        // What else changes, so it's an easy call: longer or shorter, and any pay-later charge it adds.
        val clean = AppSettings.cleanCar
        val had = (RoadCharges.on(remainingLine) + CleanAirZones.on(remainingLine, clean)).map { it.name }.toSet()
        val charges = (RoadCharges.on(fresh.polyline) + CleanAirZones.on(fresh.polyline, clean)).map { it.name }.filter { it !in had }
        val offer = FasterOffer(minutes, fresh.summary, now() + FASTER_WAIT_MS, fresh.distanceMeters - remainingMeters, charges, congestionAhead)
        fasterRoute = fresh
        fasterOffer = offer
        speak(kt("Found a faster route. It saves $minutes minutes.", "Našel jsem rychlejší trasu. Úspora: $minutes minut."))
        scope.launch {
            delay(FASTER_WAIT_MS)
            if (fasterOffer === offer) keepRoute()
        }
    }

    /** Adopt the alternate route after the driver accepts it. */
    fun takeFaster() {
        val fresh = fasterRoute
        fasterOffer = null
        fasterRoute = null
        if (fresh == null || stopped || arrived) return
        adopt(fresh)
    }

    /** "Keep mine": stay on this route, and don't offer another for a good while. */
    fun keepRoute() {
        fasterOffer = null
        fasterRoute = null
        trafficAt = now() + 600_000
    }

    /** For the build's screenshots: the faster route card. */
    fun showDemoFaster() {
        fasterOffer = FasterOffer(6, "A3024", now() + 3600_000, 3400.0, listOf("Dartford Crossing"))
    }

    private fun justPassed(p: RouteTracker.Progress): Boolean {
        val i = p.nextIndex ?: return false
        val last = route.maneuvers.subList(0, i).lastOrNull { !it.silent }?.alongMeters ?: 0.0
        return p.alongMeters - last < 500
    }

    private fun continueWords(meters: Double): String {
        var road = currentRoad
        val p = progress
        val next = p?.nextIndex
        if (p != null && next != null && route.maneuvers.subList(0, next).any { it.alongMeters > p.alongMeters && it.road != null && it.road != road }) road = null
        if (czechUi) return "Pokračujte" + (road?.let { " po silnici $it" } ?: "") + " ještě ${Fmt.spokenDistance(meters)}."
        return "Continue " + (road?.let { "on $it " } ?: "") + "for ${Fmt.spokenDistance(meters)}."
    }

    private fun sayNewRoute() {
        val p = progress ?: return
        val i = p.nextIndex ?: return
        if (i >= route.maneuvers.size || (spokenStage[i] ?: 0) != 0 || toldContinue.contains(i)) return
        val m = route.maneuvers[i]; val d = p.distanceToNext
        val nowD = max(40.0, speedMps * 4); val near = max(250.0, speedMps * 12)
        if (d <= nowD + 30 || m.type == ManeuverType.ARRIVE) return
        if (d > 3000) {
            toldContinue.add(i)
            speak(continueWords(d))
        } else {
            spokenStage[i] = if (d <= near) 2 else 1
            speak(announcedIn(d, m.spokenWords.lowercasedFirst()))
        }
    }

    /** The way the phone is moving, once it's fast enough for that to be trusted. */
    private fun heading(loc: Fix): Double? {
        if (loc.courseKnown && loc.speed >= 4) return loc.course
        if (loc.accuracy >= 20) return null
        val from = recentFixes.lastOrNull { loc.coord.distanceTo(it.coord) >= 20 && (loc.timeMillis - it.timeMillis) < 8000 } ?: return null
        return Geo.bearing(from.coord, loc.coord)
    }

    private fun noteFix(loc: Fix) {
        if (loc.accuracy >= 20) return
        recentFixes.add(loc)
        recentFixes.removeAll { loc.timeMillis - it.timeMillis > 20_000 }
    }

    private fun reroute(loc: Fix, quiet: Boolean = false, adding: Place? = null, parking: Place? = null, removing: Place? = null) {
        Log.i("KiteTrace", "reroute from ${loc.lat},${loc.lng}")
        rerouting = true
        lastReroute = now()
        if (!Connectivity.online) {
            if (!quiet && !toldNoSignal) {
                toldNoSignal = true
                speak(kt("No signal. Keep going, and Kite will find a new route when it's back.", "Bez připojení. Pokračujte, trasu přepočítám po obnovení připojení."))
            }
        } else if (!quiet && since(rerouteSpokenAt) > 45 && loc.coord.distanceTo(destination.location) > 150) {
            rerouteSpokenAt = now()
            speak(kt("Rerouting.", "Přepočítávám trasu."))
        }
        val from = loc.coord
        val course = if (route.mode == TravelMode.DRIVE) heading(loc) else null
        val mode = route.mode
        val to = parking?.location ?: destination.location
        rerouteRequest++
        val req = rerouteRequest
        val avoid = if (mode == TravelMode.DRIVE) this.avoid else emptyList()
        val previousStops = stops
        val waypoints = (if (removing != null) previousStops.drop(1) else previousStops).map { it.location }
        scope.launch {
            var r = runCatching {
                Directions.routes(from, to, mode, avoidHighways, avoidTolls, !AppSettings.noGoogle, waypoints, course,
                    mapboxToken = AppSettings.activeMapboxToken, engine = AppSettings.routeEngine(mode))
            }.getOrNull()
            // The new way goes back along a road tapped away on the plan: the way round it instead,
            // when there is one (otherwise the usual route, rather than none at all).
            val first = r?.routes?.firstOrNull()
            if (AppSettings.routeEngine(mode) == RouteEngine.AUTO && avoid.isNotEmpty() && first != null && Directions.touches(first.polyline, avoid) && req == rerouteRequest) {
                runCatching { Directions.avoiding(from, to, waypoints, avoid) }.getOrNull()?.let { r = r?.copy(routes = listOf(it)) }
            }
            if (stopped || req != rerouteRequest) return@launch
            rerouting = false
            // Parked at the destination while the new route was loading: the trip is over, keep it that way.
            if (arrived || removing != null && stops != previousStops) return@launch
            val fresh = r?.routes?.firstOrNull()
            if (fresh == null) {
                if (removing != null) speak(kt("Couldn't skip the next stop. Keeping your route.", "Zastávku se nepodařilo vynechat. Pokračujte po původní trase."))
                if (parking != null) {
                    parkingOfferDismissed = false
                    speak(kt("Couldn't route to that parking area. Continuing to ${destination.name}.", "Parkoviště není dostupné. Pokračujte směr ${destination.name}."))
                }
                // The stop just added couldn't be routed to: say so, and don't keep it as if it were on the way.
                val i = if (adding != null) stops.indexOfFirst { it.id == adding.id } else -1
                if (adding != null && i >= 0) {
                    // Only the one just added (at the front), not the same place planned earlier.
                    stops = stops.toMutableList().also { it.removeAt(i) }
                    speak(kt("Couldn't add a stop at ${adding.name}. Carrying on to ${stops.firstOrNull()?.name ?: destination.name}.", "Zastávku ${adding.name} se nepodařilo přidat. Pokračujte po původní trase."))
                }
                return@launch
            }
            toldNoSignal = false
            if (parking != null) {
                originalParkingDestination = destination
                destination = parking
                parkingOfferDismissed = true
                speak(kt("Routing to ${parking.name}.", "Přepočítávám trasu k parkovišti ${parking.name}."))
            }
            if (removing != null) {
                stops = previousStops.drop(1)
                speak(kt("Skipped ${removing.name}.", "Vynechávám zastávku ${removing.name}."))
            }
            adopt(fresh)
            if (!quiet) sayNewRoute()
        }
    }

    /** Drives [fresh] from here on: a reroute, or a faster way found on the way. */
    private fun adopt(fresh: Route) {
        // A reroute while the faster route card was up: that offer started from somewhere else.
        fasterOffer = null
        fasterRoute = null
        estimateFrom = null
        // Inside an average speed check: keep the rest of its road from the route being left.
        val e = zoneEntry; val p = progress
        // With none showing (a second reroute before the cameras came back), one already kept stays.
        if (e != null && p != null) zoneCarry = if (e.end - p.alongMeters > 20)
            ZoneCarry(e.time, max(0.0, p.alongMeters - e.along), Geo.slice(route.polyline, p.alongMeters, e.end), now(), e.kmh) else null
        currentTraffic = null
        route = fresh
        tracker = RouteTracker(fresh)
        spokenStage = mutableMapOf()
        toldContinue = mutableSetOf()
        trafficScale = 1.0
        trafficAt = now()
        loadCameras()
        loadRoundabouts()
        wazeAt = 0
        routeWaze = emptyList()
        wazeUpdated = false
        wazeSeedExpires = 0L
        wazeAhead = null
        loadWaze()
        lastFix?.let { handle(it, estimating) }
    }

    /** Something the app needs to tell the driver mid-drive, where a notice wouldn't be seen. */
    fun say(text: String) = speak(text)

    private fun speak(text: String, urgent: Boolean = false, alert: Boolean = false) {
        if (stopped || !(voiceMode == VoiceMode.ALL || (voiceMode == VoiceMode.ALERTS && alert))) return
        speakingAlert = alert
        Voice.say(text, urgent)
    }
}

/** Which lanes to use for a step, and the words for them. */
object LaneGuide {
    fun lanes(m: Maneuver, leftTraffic: Boolean): Pair<List<Lane>, String>? {
        if (m.lanes.size > 1) return named(m.lanes)
        val hint = m.laneHint
        if (hint != null && !hint.contains("any lane", ignoreCase = true) && m.type != ManeuverType.ROUNDABOUT) {
            val words = hint.replace("use the ", "", ignoreCase = true)
            return emptyList<Lane>() to words.capitalizedFirst()
        }
        if (m.type != ManeuverType.ROUNDABOUT || m.leavesRoundabout) return null
        val lanes = m.roundabout?.ruleLanes(leftTraffic, m.laneHint) ?: return null
        return named(lanes)
    }

    /** The lanes with words for the good ones, by the edge they touch. */
    fun czechAdvice(lanes: List<Lane>): String {
        val valid = lanes.indices.filter { lanes[it].valid }
        if (valid.isEmpty()) return ""
        val label = when {
            valid.size == 1 && valid.first() == 0 -> "levý pruh"
            valid.size == 1 && valid.last() == lanes.lastIndex -> "pravý pruh"
            valid.size == 1 -> "${valid.first() + 1}. pruh zleva"
            valid.first() == 0 && valid.last() == valid.size - 1 -> "${valid.size} pruhy vlevo"
            valid.last() == lanes.lastIndex && valid.first() == lanes.size - valid.size -> "${valid.size} pruhy vpravo"
            else -> return ""
        }
        return ". Použijte $label."
    }

    fun named(lanes: List<Lane>): Pair<List<Lane>, String>? {
        val valid = lanes.indices.filter { lanes[it].valid }
        val first = valid.firstOrNull() ?: return null
        val last = valid.last()
        if (valid.size >= lanes.size) return null
        val n = lanes.size; val count = valid.size
        val many = if (count > 1) "$count lanes" else "lane"
        val words = when {
            first == 0 -> "Left $many"
            last == n - 1 -> "Right $many"
            count == 1 && n > 3 -> "Lane ${first + 1} from left"
            else -> if (count > 1) "Middle lanes" else "Middle lane"
        }
        return lanes to words
    }
}

/**
 * What the voice says for this step: a roundabout's way off too, as the banner shows it, so it's
 * "take the 3rd exit onto West End Road" and not just "take the 3rd exit" (the open router's
 * roundabout steps often leave the road out of the words).
 */
val Maneuver.spokenWords: String
    get() {
        if (czechUi) return czechInstruction()
        val r = road?.trim()
        if (type != ManeuverType.ROUNDABOUT || leavesRoundabout || r.isNullOrEmpty() || instruction.contains(" onto ") || instruction.contains(" toward")) return instruction
        return "$instruction onto $r"
    }
