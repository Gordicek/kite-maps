package app.kite.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

data class TransitCall(
    val stopId: String?, val name: String, val at: LatLng, val arrival: Long?, val departure: Long?,
    val platform: String? = null, val departureDelay: Int? = null, val arrivalDelay: Int? = null,
    val cancelled: Boolean = false,
)

data class TransitNotice(val title: String, val message: String, val url: String?)

data class TransitLive(
    val board: TransitCall?, val exit: TransitCall?, val cancelled: Boolean,
    val notices: List<TransitNotice>, val position: LatLng? = null, val gps: Boolean = false,
    val occupancy: String? = null,
    val calls: List<TransitCall> = emptyList(),
) {
    fun matchCall(call: TransitCall): TransitCall? =
        matchTransitCall(calls, call.stopId, call.departure ?: call.arrival) { it.departure ?: it.arrival }
}

private fun matchTransitCall(
    calls: List<TransitCall>, id: String?, time: Long?, event: (TransitCall) -> Long?,
): TransitCall? {
    if (id == null) return null
    // A circular line can visit the same stop twice.
    return calls.asSequence().filter { it.stopId == id }.minByOrNull {
        val expected = event(it)
        if (time == null || expected == null) Long.MAX_VALUE else abs(expected - time)
    }
}

/** One part of a transit trip: a walk, or a ride on one line between two stops. Times are epoch millis. */
data class TransitLeg(
    val isWalk: Boolean,
    val mode: String,
    var line: String = "",
    var color: Long? = null,
    var textColor: Long? = null,
    var headsign: String? = null,
    val fromName: String = "",
    val toName: String = "",
    val depart: Long? = null,
    val arrive: Long? = null,
    val durationSeconds: Double = 0.0,
    var distanceMeters: Double = 0.0,
    var stops: Int = 0,
    var delayMinutes: Int? = null,
    var path: List<LatLng> = emptyList(),
    var calls: List<TransitCall> = emptyList(),
    var tripId: String? = null,
    var walkSteps: List<Maneuver> = emptyList(),
    var notices: List<TransitNotice> = emptyList(),
    var cancelled: Boolean = false,
) {
    val vehicle: Vehicle get() = Vehicle.of(mode)
    val mapColor: Long get() = color ?: when (vehicle) {
        Vehicle.WALK -> 0x64748B
        Vehicle.BUS -> 0x1B58B8
        Vehicle.TRAM -> 0xB52B45
        Vehicle.METRO -> 0x107564
        Vehicle.TRAIN -> 0x6543A5
        Vehicle.FERRY -> 0x0B697F
        Vehicle.OTHER -> 0x576880
    }

    enum class Vehicle {
        WALK, BUS, METRO, TRAM, TRAIN, FERRY, OTHER;

        companion object {
            fun of(mode: String): Vehicle = when (mode) {
                "WALK", "BIKE", "CAR", "RENTAL" -> WALK
                "BUS", "COACH", "ODM", "FLEX" -> BUS
                "SUBWAY", "METRO" -> METRO
                "TRAM", "CABLE_CAR", "FUNICULAR", "AERIAL_LIFT" -> TRAM
                "FERRY" -> FERRY
                else -> if (mode.contains("RAIL") || mode == "SUBURBAN" || mode == "LONG_DISTANCE") TRAIN else OTHER
            }
        }
    }
}

/** One way to get there by public transit. */
data class TransitTrip(
    val depart: Long?,
    val arrive: Long?,
    val durationSeconds: Double,
    val transfers: Int,
    val legs: List<TransitLeg>,
    val id: String = UUID.randomUUID().toString(),
    val itineraryId: String? = null,
) {
    val rides: List<TransitLeg> get() = legs.filter { !it.isWalk }
    val walkSeconds: Double get() = legs.filter { it.isWalk }.sumOf { it.durationSeconds }
    val path: List<LatLng> get() = legs.flatMap { it.path }
}

/** Public transit directions from Transitous (transitous.org), keyless, with live delays where agencies send them. */
object Transitous {
    const val base = "https://api.transitous.org"
    private val client = Http.client(25)
    private val refreshClient = Http.client(8)
    private val headers = mapOf("User-Agent" to "Kite-Maps-Android (https://github.com/matteofx1/kite-maps)")

    fun planURL(from: LatLng, to: LatLng, max: Int = 5, timeMillis: Long? = null, arriveBy: Boolean = false): String {
        var s = "$base/api/v1/plan?fromPlace=${from.lat.plain()},${from.lng.plain()}&toPlace=${to.lat.plain()},${to.lng.plain()}&numItineraries=$max"
        if (timeMillis != null) {
            s += "&time=" + Instant.ofEpochMilli(timeMillis).toString().replace(Regex("\\.\\d+Z$"), "Z")
            if (arriveBy) s += "&arriveBy=true"
        }
        return s
    }

    /** Trips leaving now, or around [timeMillis]. Throws on a network error. */
    suspend fun plan(from: LatLng, to: LatLng, timeMillis: Long? = null, arriveBy: Boolean = false): List<TransitTrip> {
        val oldUrl = planURL(from, to, timeMillis = timeMillis, arriveBy = arriveBy)
        val modern = Http.getOrNull(oldUrl.replace("/api/v1/", "/api/v6/") + "&withFares=false", headers, client)
        if (modern?.ok == true) return withContext(Dispatchers.Default) { parse(modern.body) }
        val r = Http.get(oldUrl, headers, client)
        if (!r.ok) throw KiteError.http(r.code)
        return withContext(Dispatchers.Default) { parse(r.body) }
    }

    suspend fun selectedDeparture(departure: Departure): TransitTrip? {
        val id = departure.tripId ?: return null
        val response = Http.getOrNull("$base/api/v6/trip?tripId=${urlEnc(id)}&withScheduledSkippedStops=true",
            headers, refreshClient)?.takeIf { it.ok } ?: return null
        return withContext(Dispatchers.Default) {
            val row = Json.obj(response.body)?.get("legs").asMaps()?.firstOrNull { it["tripId"] == id }
                ?: return@withContext null
            val ride = leg(row).takeIf { !it.isWalk && it.path.size >= 2 } ?: return@withContext null
            TransitTrip(ride.depart, ride.arrive, ride.durationSeconds, 0, listOf(ride), id = "departure:$id")
        }
    }

    /** Refresh the itinerary in one request. Keep the chosen vehicle IDs and transfer order. */
    suspend fun refresh(trip: TransitTrip): TransitTrip? {
        val id = trip.itineraryId ?: return null
        val response = Http.getOrNull("$base/api/v6/refresh-itinerary?itineraryId=${urlEnc(id)}&withFares=false", headers, refreshClient)
            ?.takeIf { it.ok } ?: return null
        return withContext(Dispatchers.Default) {
            val current = Json.obj(response.body)?.let(::itinerary) ?: return@withContext null
            val was = trip.rides.map { it.tripId }
            val fresh = current.rides.map { it.tripId }
            if (was != fresh || was.any { it == null }) return@withContext null
            current.copy(id = trip.id, itineraryId = current.itineraryId ?: id)
        }
    }

    /** When a regional feed has live times on its departures but not on the trip endpoint. */
    suspend fun localDeparture(leg: TransitLeg): TransitCall? {
        val call = leg.calls.firstOrNull() ?: return null
        val departure = leg.depart ?: return null
        if (leg.tripId == null || call.stopId == null || abs(System.currentTimeMillis() - departure) > 60 * 60_000) return null
        val update = departures(call.at, stopId = call.stopId, stopName = call.name)?.departures
            ?.firstOrNull { it.tripId == leg.tripId && it.delayMinutes != null } ?: return null
        return call.copy(departure = update.time, departureDelay = update.delayMinutes, platform = update.platform ?: call.platform)
    }

    /** Refreshes the same train/bus, identified by its service-day trip ID, not another departure on the line. */
    suspend fun live(leg: TransitLeg): TransitLive? = coroutineScope {
        val id = leg.tripId ?: return@coroutineScope null
        val gps = async {
            val trip = mbtaTripId(id) ?: return@async null
            val response = Http.getOrNull("https://api-v3.mbta.com/vehicles?filter%5Btrip%5D=${urlEnc(trip)}", headers, client)
            response?.takeIf { it.ok }?.let { parseMbtaVehicle(it.body, trip, System.currentTimeMillis()) }
        }
        val response = Http.getOrNull("$base/api/v6/trip?tripId=${urlEnc(id)}&withScheduledSkippedStops=true", headers, client)
        val data = response?.takeIf { it.ok }?.body
        val status = data?.let { withContext(Dispatchers.Default) { parseLive(it, leg, System.currentTimeMillis()) } }
        val vehicle = gps.await()
        if (status?.cancelled == true) return@coroutineScope status
        if (vehicle != null) return@coroutineScope (status ?: TransitLive(null, null, false, leg.notices)).copy(
            position = vehicle.at, gps = true, occupancy = vehicle.occupancy
        )
        if (status?.position != null) return@coroutineScope status

        // The map endpoint returns timed segments; the dot is interpolated, not reported by the vehicle.
        val now = System.currentTimeMillis()
        val progress = estimateMapSegment(leg, now) ?: return@coroutineScope status
        (status ?: TransitLive(null, null, false, leg.notices)).copy(position = progress)
    }

    private suspend fun estimateMapSegment(leg: TransitLeg, now: Long): LatLng? = withContext(Dispatchers.Default) {
        val start = leg.depart ?: return@withContext null
        val end = leg.arrive ?: return@withContext null
        if (leg.path.size < 2 || now !in (start - 30_000)..(end + 8 * 60_000)) return@withContext null
        val fraction = ((now - start).toDouble() / (end - start).coerceAtLeast(1)).coerceIn(0.0, 1.0)
        val center = Geo.point(leg.path, Geo.length(leg.path) * fraction)
        val b = OutdoorData.bounds(center, 2500.0)
        if (b.w >= b.e) return@withContext null
        val earlier = java.time.Instant.ofEpochMilli(now - 45_000).toString()
        val later = java.time.Instant.ofEpochMilli(now + 45_000).toString()
        val response = Http.getOrNull("$base/api/v6/map/trips?min=${b.s.plain()},${b.w.plain()}&max=${b.n.plain()},${b.e.plain()}" +
            "&zoom=14&precision=5&startTime=${urlEnc(earlier)}&endTime=${urlEnc(later)}", headers, client)
        response?.takeIf { it.ok }?.let { parseMapSegment(it.body, leg.tripId, now) }
    }

    internal fun parseMapSegment(data: ByteArray, tripId: String?, now: Long): LatLng? {
        if (tripId == null) return null
        return Json.parse(data).asMaps().orEmpty().firstNotNullOfOrNull { segment ->
            if (segment["realTime"] != true ||
                segment["trips"].asMaps()?.none { it["tripId"] == tripId } != false) return@firstNotNullOfOrNull null
            val start = date(segment["departure"]) ?: return@firstNotNullOfOrNull null
            val end = date(segment["arrival"]) ?: return@firstNotNullOfOrNull null
            if (end <= start || now !in start..end) return@firstNotNullOfOrNull null
            val line = (segment["polyline"] as? String)?.let { Geo.decodePolyline(it, 1e5) }.orEmpty()
            if (line.size < 2) return@firstNotNullOfOrNull null
            Geo.point(line, Geo.length(line) * (now - start).toDouble() / (end - start))
        }
    }

    internal fun parseLive(data: ByteArray, selected: TransitLeg, now: Long): TransitLive? {
        val raw = Json.obj(data)?.get("legs").asMaps()?.firstOrNull { it["tripId"] == selected.tripId } ?: return null
        val current = leg(raw)
        val board = matchTransitCall(current.calls, selected.calls.firstOrNull()?.stopId, selected.depart, TransitCall::departure)
        val exit = matchTransitCall(current.calls, selected.calls.lastOrNull()?.stopId, selected.arrive, TransitCall::arrival)
        val cancelled = current.cancelled || board?.cancelled == true || exit?.cancelled == true
        val position = if (raw["realTime"] == true && !cancelled) estimateVehicle(current, now) else null
        return TransitLive(board, exit, cancelled, current.notices, position, calls = current.calls)
    }

    fun timetablePosition(leg: TransitLeg, now: Long): LatLng? =
        if (leg.cancelled) null else estimateVehicle(leg, now, requireUpdates = false)

    private fun estimateVehicle(leg: TransitLeg, now: Long, requireUpdates: Boolean = true): LatLng? {
        if (leg.path.size < 2) return null
        val stop = leg.calls.firstOrNull { call ->
            !call.cancelled && call.arrival != null && call.departure != null && now in call.arrival..call.departure &&
                (!requireUpdates || call.arrivalDelay != null || call.departureDelay != null)
        }
        if (stop != null) return stop.at
        val (a, b) = leg.calls.filterNot { it.cancelled }.zipWithNext().firstOrNull { (a, b) ->
            a.departure != null && b.arrival != null && b.arrival > a.departure && now in a.departure..b.arrival &&
                (!requireUpdates || a.departureDelay != null || b.arrivalDelay != null)
        } ?: return null
        val fromTime = a.departure ?: return null
        val toTime = b.arrival ?: return null
        val distances = Geo.cumulative(leg.path)
        val from = Geo.project(a.at, leg.path, distances)
        val to = Geo.project(b.at, leg.path, distances)
        if (from.offset > 150 || to.offset > 150 || to.along < from.along) return null
        val fraction = (now - fromTime).toDouble() / (toTime - fromTime)
        return Geo.point(leg.path, from.along + fraction * (to.along - from.along), distances)
    }

    internal data class VehicleReport(val at: LatLng, val occupancy: String?)

    // Transitous prefixes GTFS trip IDs with the service date, time and feed ID.
    private fun mbtaTripId(id: String): String? =
        Regex("""^\d{8}_\d{2,3}:\d{2}_us-ma-MBTA_(.+)$""").matchEntire(id)?.groupValues?.get(1)

    internal fun parseMbtaVehicle(data: ByteArray, tripId: String, now: Long): VehicleReport? =
        Json.obj(data)?.get("data").asMaps().orEmpty().firstNotNullOfOrNull { vehicle ->
            val trip = vehicle["relationships"].asMap()?.get("trip").asMap()?.get("data").asMap()
            if (trip?.get("id") != tripId) return@firstNotNullOfOrNull null
            val report = vehicle["attributes"].asMap() ?: return@firstNotNullOfOrNull null
            val updated = date(report["updated_at"]) ?: return@firstNotNullOfOrNull null
            if (now - updated !in -30_000L..90_000L) return@firstNotNullOfOrNull null
            val lat = report["latitude"].num()?.takeIf { it in -90.0..90.0 } ?: return@firstNotNullOfOrNull null
            val lng = report["longitude"].num()?.takeIf { it in -180.0..180.0 } ?: return@firstNotNullOfOrNull null
            val occupancy = when (report["occupancy_status"]) {
                "EMPTY" -> "Empty"
                "MANY_SEATS_AVAILABLE" -> "Many seats available"
                "FEW_SEATS_AVAILABLE" -> "Few seats available"
                "STANDING_ROOM_ONLY" -> "Standing room only"
                "CRUSHED_STANDING_ROOM_ONLY" -> "Very crowded"
                "FULL" -> "Full"
                "NOT_ACCEPTING_PASSENGERS" -> "Not accepting passengers"
                else -> null
            }
            VehicleReport(LatLng(lat, lng), occupancy)
        }

    data class Departure(
        val line: String, val mode: String, val color: Long?, val textColor: Long?, val headsign: String,
        val time: Long, val delayMinutes: Int?, val tripId: String? = null, val platform: String? = null,
    ) {
        val vehicle get() = TransitLeg.Vehicle.of(mode)
    }

    data class Board(val stop: String, val departures: List<Departure>)

    data class Stop(val id: String, val parentId: String?, val name: String, val at: LatLng)

    suspend fun stops(box: Box, grouped: Boolean = false): List<Stop>? {
        val r = Http.getOrNull("$base/api/v1/map/stops?min=${box.s.plain()},${box.w.plain()}&max=${box.n.plain()},${box.e.plain()}&grouped=$grouped",
            headers, client)?.takeIf { it.ok } ?: return null
        return Json.parse(r.body).asMaps()?.mapNotNull { s ->
            val id = s["stopId"] as? String ?: return@mapNotNull null
            val at = point(s) ?: return@mapNotNull null
            Stop(id, (s["parentId"] as? String)?.takeIf(String::isNotBlank), (s["name"] as? String).orEmpty(), at)
        }
    }

    /** Query the selected station directly, or find the nearest one for a place from another map. */
    suspend fun departures(point: LatLng, radius: Double = 150.0, stopId: String? = null, stopName: String = ""): Board? {
        val nearest = if (stopId != null) emptyList() else stops(OutdoorData.bounds(point, radius)).orEmpty()
            .sortedBy { it.at.distanceTo(point) }
        val first = nearest.firstOrNull()
        val name = first?.name ?: stopName
        val ids = if (stopId != null) listOf(stopId) else {
            first ?: return null
            val distance = first.at.distanceTo(point)
            nearest.filter { it.name == name || it.at.distanceTo(point) - distance < 25 }.map { it.parentId ?: it.id }.distinct().take(4)
        }
        val all = mutableListOf<Departure>()
        for (id in ids) {
            val args = "stopId=${urlEnc(id)}&n=30"
            val d = Http.getOrNull("$base/api/v6/stoptimes?$args", headers, client)?.takeIf { it.ok }
                ?: Http.getOrNull("$base/api/v5/stoptimes?$args", headers, client)?.takeIf { it.ok }
                ?: Http.getOrNull("$base/api/v1/stoptimes?$args", headers, client)?.takeIf { it.ok } ?: continue
            all += parseStopTimes(d.body, name)
        }
        if (all.isEmpty()) return null
        val seen = HashSet<String>()
        return Board(name, all.sortedBy { it.time }.filter { seen.add("${it.line}|${it.headsign}|${it.time}") })
    }

    fun parseStopTimes(data: ByteArray, stopName: String = ""): List<Departure> {
        val list = Json.obj(data)?.get("stopTimes").asMaps() ?: return emptyList()
        return list.mapNotNull { st ->
            val place = st["place"].asMap() ?: emptyMap()
            if (st["tripCancelled"] == true || place["cancelled"] == true) return@mapNotNull null
            val time = date(place["departure"]) ?: return@mapNotNull null
            var delay: Int? = null
            if (st["realTime"] == true) date(place["scheduledDeparture"])?.let { delay = ((time - it) / 60_000.0).roundToInt() }
            val line = listOf(st["routeShortName"], st["displayName"], st["routeLongName"]).mapNotNull { (it as? String)?.trim() }.firstOrNull { it.isNotEmpty() } ?: ""
            val end = st["tripTo"].asMap()?.get("name") as? String
            if (end != null && stopName.isNotEmpty() && end == stopName) return@mapNotNull null
            var headsign = st["headsign"] as? String ?: ""
            if (!end.isNullOrEmpty() && (headsign.isEmpty() || headsign == stopName)) headsign = end
            Departure(line, st["mode"] as? String ?: "", hex(st["routeColor"]), hex(st["routeTextColor"]), headsign, time, delay,
                st["tripId"] as? String, (place["track"] ?: place["scheduledTrack"]) as? String)
        }
    }

    /** A stretch of metro or rail track with its lines' color, for the transit map layer. */
    data class Line(val points: List<LatLng>, val color: Long, val metro: Boolean, val names: List<String>)

    /** How the last [lines] request went, for the build's log. */
    @Volatile var linesNote = ""

    suspend fun lines(south: Double, west: Double, north: Double, east: Double, zoom: Int): List<Line>? {
        val started = System.currentTimeMillis()
        val r = try { Http.get("$base/api/experimental/map/routes?min=${south.plain()},${west.plain()}&max=${north.plain()},${east.plain()}&zoom=$zoom", headers, client) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { linesNote = "${e.javaClass.simpleName}: ${e.message} after ${System.currentTimeMillis() - started} ms"; return null }
        linesNote = "HTTP ${r.code}, ${r.body.size} bytes in ${System.currentTimeMillis() - started} ms" +
            if (r.code != 200) ": " + r.text.take(120) else ""
        if (r.code != 200) return null
        return parseLines(r.body)
    }

    /** Keep consecutive in-view track segments, including a point across each edge so pans don't expose a cut. */
    fun trackPieces(points: List<LatLng>, south: Double, west: Double, north: Double, east: Double): List<List<LatLng>> = buildList {
        var start = -1
        for (index in 1 until points.size) {
            val a = points[index - 1]
            val b = points[index]
            val nearView = maxOf(a.lat, b.lat) >= south && minOf(a.lat, b.lat) <= north &&
                maxOf(a.lng, b.lng) >= west && minOf(a.lng, b.lng) <= east
            if (nearView) {
                if (start < 0) start = index - 1
            } else if (start >= 0) {
                add(points.subList(start, index))
                start = -1
            }
        }
        if (start >= 0) add(points.subList(start, points.size))
    }

    fun parseLines(data: ByteArray): List<Line> {
        val root = Json.obj(data) ?: return emptyList()
        val routes = root["routes"].asMaps() ?: return emptyList()
        val polys = root["polylines"].asMaps() ?: return emptyList()
        val out = mutableListOf<Line>()
        for (p in polys) {
            val rs = (p["routeIndexes"].asList() ?: emptyList()).mapNotNull { it.int()?.let { i -> routes.getOrNull(i) } }
            val rail = rs.filter { TransitLeg.Vehicle.of(it["mode"] as? String ?: "") in setOf(TransitLeg.Vehicle.METRO, TransitLeg.Vehicle.TRAM, TransitLeg.Vehicle.TRAIN) }
            if (rail.isEmpty()) continue
            val enc = p["polyline"].asMap() ?: continue
            val pts = enc["points"] as? String ?: continue
            val color = rail.firstNotNullOfOrNull { hex(it["transitRoutes"].asMaps()?.firstOrNull()?.get("color")) }
            val metro = rail.any { TransitLeg.Vehicle.of(it["mode"] as? String ?: "") != TransitLeg.Vehicle.TRAIN }
            val names = rail.mapNotNull { it["transitRoutes"].asMaps()?.firstOrNull()?.get("shortName") as? String }
            val precision = (enc["precision"].int() ?: 6).coerceIn(5, 7)
            val line = Geo.decodePolyline(pts, 10.0.pow(precision))
            if (line.size < 2) continue
            if (!metro && Geo.length(line) / (line.size - 1) > 700) continue
            out.add(Line(line, color ?: if (metro) 0x5F6368 else 0x8E8E93, metro, names.distinct().sorted()))
        }
        return out
    }

    fun parse(data: ByteArray): List<TransitTrip> {
        val its = Json.obj(data)?.get("itineraries").asMaps() ?: return emptyList()
        val trips = its.mapNotNull { itinerary(it) }
        val seen = HashSet<String>()
        return trips.filter { seen.add(it.rides.joinToString(",") { r -> r.line } + "${(it.depart ?: 0) / 1000}") }
    }

    private fun itinerary(it: Map<String, Any?>): TransitTrip? {
        val rawLegs = it["legs"].asMaps() ?: return null
        val legs = rawLegs.map { leg(it) }
        if (legs.none { !it.isWalk }) return null
        val start = date(it["startTime"])
        val end = date(it["endTime"])
        val dur = it["duration"].num() ?: if (start != null && end != null) (end - start) / 1000.0 else 0.0
        val transfers = it["transfers"].int() ?: maxOf(0, legs.count { !it.isWalk } - 1)
        return TransitTrip(start, end, dur, transfers, legs, itineraryId = ((it["id"] ?: it["itineraryId"]) as? String)?.takeIf(String::isNotBlank))
    }

    private fun leg(l: Map<String, Any?>): TransitLeg {
        val mode = l["mode"] as? String ?: ""
        val from = l["from"].asMap() ?: emptyMap()
        val to = l["to"].asMap() ?: emptyMap()
        val start = date(l["startTime"])
        val end = date(l["endTime"])
        val dur = l["duration"].num() ?: if (start != null && end != null) (end - start) / 1000.0 else 0.0
        val out = TransitLeg(TransitLeg.Vehicle.of(mode) == TransitLeg.Vehicle.WALK, mode, fromName = from["name"] as? String ?: "",
            toName = to["name"] as? String ?: "", depart = start, arrive = end, durationSeconds = dur,
            distanceMeters = l["distance"].num() ?: 0.0, path = path(l))
        if (out.path.size < 2) {
            val a = point(from)
            val b = point(to)
            if (a != null && b != null) out.path = listOf(a, b)
        }
        if (out.isWalk) {
            if (out.distanceMeters == 0.0) out.distanceMeters = Geo.length(out.path)
            out.walkSteps = walkSteps(l["steps"].asMaps().orEmpty(), out.path)
            return out
        }
        out.line = listOf(l["routeShortName"], l["displayName"], l["routeLongName"]).mapNotNull { (it as? String)?.trim() }.firstOrNull { it.isNotEmpty() } ?: ""
        out.color = hex(l["routeColor"])
        out.textColor = hex(l["routeTextColor"])
        out.headsign = (l["headsign"] as? String)?.ifEmpty { null }
        val intermediate = l["intermediateStops"].asMaps().orEmpty()
        out.stops = intermediate.size + 1
        out.tripId = l["tripId"] as? String
        out.cancelled = l["cancelled"] == true
        val stops = listOf(from) + intermediate + listOf(to)
        val realTime = l["realTime"] == true
        out.calls = stops.mapNotNull { stop ->
            val at = point(stop) ?: return@mapNotNull null
            val arrival = date(stop["arrival"])
            val departure = date(stop["departure"])
            TransitCall(stop["stopId"] as? String, (stop["name"] as? String).orEmpty(), at, arrival, departure,
                (stop["track"] ?: stop["scheduledTrack"]) as? String,
                delayMinutes(departure, stop["scheduledDeparture"], realTime, stop["departureTimestampReason"]),
                delayMinutes(arrival, stop["scheduledArrival"], realTime, stop["arrivalTimestampReason"]),
                stop["cancelled"] == true)
        }
        if (out.vehicle in setOf(TransitLeg.Vehicle.TRAM, TransitLeg.Vehicle.METRO, TransitLeg.Vehicle.TRAIN)) {
            out.path = withoutRailSpikes(out.path, out.calls)
        }
        if (out.distanceMeters == 0.0) out.distanceMeters = Geo.length(out.path)
        out.delayMinutes = out.calls.firstOrNull()?.departureDelay
            ?: delayMinutes(start, l["scheduledStartTime"], realTime, from["departureTimestampReason"])
        out.notices = notices(l) + stops.flatMap(::notices)
        out.notices = out.notices.distinct()
        return out
    }

    internal fun withoutRailSpikes(path: List<LatLng>, calls: List<TransitCall>): List<LatLng> {
        if (path.size < 3 || calls.size < 2) return path
        fun served(point: LatLng) = calls.any { it.at.distanceTo(point) <= 45 }
        val singleSpikes = path.filterIndexed { i, point ->
            if (i == 0 || i == path.lastIndex) return@filterIndexed true
            val before = path[i - 1]
            val after = path[i + 1]
            val shorterSide = minOf(before.distanceTo(point), point.distanceTo(after))
            shorterSide < 120 || before.distanceTo(after) >= 0.5 * shorterSide || served(point)
        }
        // Some bad shapes jump away and back through several adjacent points. Keep mapped stops
        // and regular turns. Replace only short vertex runs bounded by two long jumps.
        if (singleSpikes.size < 4) return singleSpikes
        val distances = Geo.cumulative(singleSpikes)
        return buildList {
            var start = 0
            while (start < singleSpikes.size) {
                val point = singleSpikes[start]
                add(point)
                var end = start + 1
                val last = minOf(start + 6, singleSpikes.lastIndex)
                if (last >= start + 2 && distances[start + 1] - distances[start] >= 100) {
                    for (candidate in last downTo start + 2) {
                        if (distances[candidate] - distances[candidate - 1] < 100) continue
                        val target = singleSpikes[candidate]
                        val chord = point.distanceTo(target)
                        val total = distances[candidate] - distances[start]
                        if (total - chord < 210 || total < chord * 1.95 || chord > 680) continue
                        val skipped = singleSpikes.subList(start + 1, candidate)
                        if (skipped.any(::served)) continue
                        val direct = listOf(point, target)
                        if (skipped.none { Geo.project(it, direct).offset >= 105 }) continue
                        end = candidate
                        break
                    }
                }
                start = end
            }
        }
    }

    /** Compare a direct walk with an itinerary, including the wait before a leave-now trip. */
    fun walkIsFaster(walkSeconds: Double, trips: List<TransitTrip>, at: Long, arriveBy: Boolean): Boolean {
        if (!walkSeconds.isFinite() || walkSeconds <= 0) return false
        val transitSeconds = trips.filter { trip -> trip.rides.none { it.cancelled } }.minOfOrNull { trip ->
            val total = if (arriveBy) trip.depart?.let { (at - it) / 1000.0 }
                else trip.arrive?.let { (it - at) / 1000.0 }
            maxOf(trip.durationSeconds, total ?: trip.durationSeconds)
        } ?: return true
        return walkSeconds + 30 < transitSeconds
    }

    private fun delayMinutes(actual: Long?, planned: Any?, live: Boolean, reason: Any?): Int? {
        if (!live || actual == null || reason == "NO_RT_DATA" || reason == "INCONSISTENT_RT") return null
        val scheduled = date(planned) ?: return null
        return ((actual - scheduled) / 60_000.0).roundToInt()
    }

    private fun notices(row: Map<String, Any?>): List<TransitNotice> = row["alerts"].asMaps().orEmpty().mapNotNull { item ->
        val title = (item["headerText"] as? String).orEmpty().trim()
        val message = (item["descriptionText"] as? String).orEmpty().trim()
        if (title.isBlank() && message.isBlank()) return@mapNotNull null
        val url = (item["url"] as? String)?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
        TransitNotice(title, message, url)
    }

    private fun walkSteps(rows: List<Map<String, Any?>>, line: List<LatLng>): List<Maneuver> {
        if (rows.isEmpty() || line.size < 2) return emptyList()
        val cum = Geo.cumulative(line)
        val end = cum.last()
        val distance = rows.sumOf { it["distance"].num()?.coerceAtLeast(0.0) ?: 0.0 }
        if (end <= 0 || distance <= 0) return emptyList()
        var along = 0.0
        val steps = rows.mapIndexed { i, row ->
            val step = ((row["distance"].num() ?: 0.0) * end / distance).coerceAtLeast(0.0)
            val dir = (row["relativeDirection"] as? String).orEmpty().uppercase().replace('-', '_')
            val road = (row["streetName"] as? String)?.takeUnless { it.isBlank() || it == "unnamed" }
            val type = when (dir) {
                "LEFT", "TURN_LEFT" -> ManeuverType.TURN_LEFT
                "RIGHT", "TURN_RIGHT" -> ManeuverType.TURN_RIGHT
                "SLIGHTLY_LEFT", "SLIGHT_LEFT" -> ManeuverType.SLIGHT_LEFT
                "SLIGHTLY_RIGHT", "SLIGHT_RIGHT" -> ManeuverType.SLIGHT_RIGHT
                "HARD_LEFT", "SHARP_LEFT" -> ManeuverType.SHARP_LEFT
                "HARD_RIGHT", "SHARP_RIGHT" -> ManeuverType.SHARP_RIGHT
                else -> if (i == 0) ManeuverType.DEPART else ManeuverType.STRAIGHT
            }
            val instruction = when {
                dir.contains("ELEVATOR") -> "Take the elevator"
                dir.contains("STAIR") -> "Take the stairs"
                dir.contains("ESCALATOR") -> "Take the escalator"
                type in setOf(ManeuverType.TURN_LEFT, ManeuverType.SLIGHT_LEFT, ManeuverType.SHARP_LEFT) -> "Turn left"
                type in setOf(ManeuverType.TURN_RIGHT, ManeuverType.SLIGHT_RIGHT, ManeuverType.SHARP_RIGHT) -> "Turn right"
                else -> "Continue"
            } + (road?.let { " on $it" } ?: "")
            Maneuver(type, instruction, Geo.point(line, along, cum), along, step, road,
                silent = i > 0 && type == ManeuverType.STRAIGHT && !dir.contains("STAIR") &&
                    !dir.contains("ELEVATOR") && !dir.contains("ESCALATOR")).also { along += step }
        }
        return steps + Maneuver(ManeuverType.ARRIVE, "You have arrived", line.last(), end, 0.0)
    }

    private fun path(l: Map<String, Any?>): List<LatLng> {
        val g = l["legGeometry"].asMap() ?: return emptyList()
        val pts = g["points"] as? String ?: return emptyList()
        if (pts.isEmpty()) return emptyList()
        val p = (g["precision"].int() ?: 6).coerceIn(5, 7)
        return Geo.decodePolyline(pts, 10.0.pow(p)).filter { it.lat in -90.0..90.0 && it.lng in -180.0..180.0 }
    }

    private fun point(o: Map<String, Any?>): LatLng? {
        val lat = o["lat"].num() ?: return null
        val lon = o["lon"].num() ?: return null
        return LatLng(lat, lon)
    }

    fun hex(v: Any?): Long? {
        var s = (v as? String)?.trim() ?: return null
        if (s.isEmpty()) return null
        if (s.startsWith("#")) s = s.drop(1)
        return if (s.length == 6) s.toLongOrNull(16) else null
    }

    fun date(v: Any?): Long? {
        val s = v as? String ?: return null
        if (s.isEmpty()) return null
        return try { OffsetDateTime.parse(s).toInstant().toEpochMilli() } catch (e: Exception) {
            try { Instant.parse(s).toEpochMilli() } catch (e2: Exception) { null }
        }
    }
}
