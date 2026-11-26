package app.kite.core

import kotlinx.coroutines.*
import kotlin.math.*

/**
 * The FOSSGIS OSRM servers (routing.openstreetmap.de), which Vela uses for turn-by-turn steps.
 * Here they're the open-data router: used when Google gives no route.
 */
object OSRM {
    const val userAgent = "KiteMaps-Android/0.7 (+https://github.com/matteofx1/kite-maps)"
    private val client = Http.client(20)

    private suspend fun fetch(url: String): ByteArray = Http.get(url, mapOf("User-Agent" to userAgent), client).body

    suspend fun route(a: LatLng, b: LatLng, mode: TravelMode, via: List<LatLng> = emptyList(), heading: Double? = null): List<Route> =
        parse(fetch(routeURL(a, b, mode, via, heading)), mode)

    /** With [heading] (the way the car is moving), the route starts off that way: never a U-turn back down the road. */
    fun routeURL(a: LatLng, b: LatLng, mode: TravelMode, via: List<LatLng> = emptyList(), heading: Double? = null): String {
        val pts = listOf(a) + via + listOf(b)
        val coords = pts.joinToString(";") { "${it.lng.plain()},${it.lat.plain()}" }
        var s = "https://routing.openstreetmap.de/${mode.osrmBackend}/route/v1/${mode.osrmProfile}/$coords" +
            "?overview=full&geometries=polyline6&steps=true&alternatives=${mode == TravelMode.DRIVE && via.isEmpty()}"
        if (heading != null) s += "&bearings=${heading.roundToInt() % 360},60" + ";".repeat(pts.size - 1)
        return s
    }

    /** The open router's turn-by-turn for exactly [line] (Google's route), by map matching. */
    suspend fun match(line: List<LatLng>, mode: TravelMode): Route? {
        if (line.size < 2) return null
        val url = matchURL(line, mode)
        val root = Json.obj(fetch(url)) ?: return null
        val matchings = root["matchings"].asMaps() ?: return null
        if (matchings.size != 1) return null
        val m = parseRoot(mapOf("routes" to matchings), mode).firstOrNull() ?: return null
        val total = Geo.length(line)
        return if (abs(m.distanceMeters - total) < max(150.0, total * 0.04)) m else null
    }

    fun matchURL(line: List<LatLng>, mode: TravelMode): String {
        val cum = Geo.cumulative(line)
        val total = cum.last()
        val n = min(99, max(1, (total / 150).toInt()))
        val pts = (0..n).map { Geo.point(line, total * it / n, cum) }
        val coords = pts.joinToString(";") { String.format(java.util.Locale.US, "%.6f,%.6f", it.lng, it.lat) }
        val radii = List(pts.size) { "25" }.joinToString(";")
        return "https://routing.openstreetmap.de/${mode.osrmBackend}/match/v1/${mode.osrmProfile}/$coords" +
            "?overview=full&geometries=polyline6&steps=true&tidy=true&gaps=ignore&radiuses=$radii&waypoints=0;${pts.size - 1}"
    }

    /** The open router forced through points sampled off [line] (Google's route), so its steps follow the same roads. */
    /**
     * How much longer the drive gets by stopping at each of [places] (GitHub #60, "+3 min"): from [here]
     * to the place, then back to the route at [rejoin] (the same index), against driving straight to
     * [rejoin]. One question to the open router for all of them. Seconds, null for a place it can't reach;
     * null overall when the router didn't answer.
     */
    suspend fun detours(here: LatLng, places: List<LatLng>, rejoin: List<LatLng>): List<Double?>? {
        val n = places.size
        if (n == 0 || rejoin.size != n) return emptyList()
        val coords = (listOf(here) + places + rejoin).joinToString(";") { String.format(java.util.Locale.US, "%.6f,%.6f", it.lng, it.lat) }
        val sources = (0..n).joinToString(";")
        val destinations = (1..2 * n).joinToString(";")
        val url = "https://routing.openstreetmap.de/routed-car/table/v1/driving/$coords?sources=$sources&destinations=$destinations&annotations=duration"
        return try {
            val r = Http.get(url, mapOf("User-Agent" to userAgent))
            if (r.code == 200) parseDetours(r.body, n) else null
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { null }
    }

    fun parseDetours(data: ByteArray, n: Int): List<Double?>? {
        val root = Json.obj(data) ?: return null
        if (root["code"] != "Ok") return null
        val d = root["durations"].asList()?.map { it.asList() ?: return null } ?: return null
        if (d.size != n + 1 || d.any { it.size != 2 * n }) return null
        fun t(i: Int, j: Int) = d[i][j].num()
        return (0 until n).map { i ->
            val toPlace = t(0, i); val back = t(i + 1, n + i); val direct = t(0, n + i)
            if (toPlace == null || back == null || direct == null) null else maxOf(0.0, toPlace + back - direct)
        }
    }

    suspend fun routeVia(line: List<LatLng>, mode: TravelMode, spacing: Double = 1_500.0, maxVias: Int = 12): Route? {
        if (line.size < 2) return null
        val cum = Geo.cumulative(line)
        val total = cum.last()
        val vias = min(maxVias, (total / spacing).toInt())
        val pts = mutableListOf(line[0])
        val bearings = mutableListOf(bearing(line, cum, 0.0))
        for (i in 1..vias) {
            val m = total * i / (vias + 1)
            pts.add(Geo.point(line, m, cum))
            bearings.add(bearing(line, cum, m))
        }
        pts.add(line[line.size - 1])
        bearings.add(null)
        val coords = pts.joinToString(";") { String.format(java.util.Locale.US, "%.6f,%.6f", it.lng, it.lat) }
        val b = bearings.joinToString(";") { it?.let { v -> "${v.toInt()},45" } ?: "" }
        val radii = pts.indices.joinToString(";") { if (it == 0 || it == pts.size - 1) "unlimited" else "30" }
        val url = "https://routing.openstreetmap.de/${mode.osrmBackend}/route/v1/${mode.osrmProfile}/$coords" +
            "?overview=full&geometries=polyline6&steps=true&continue_straight=true&bearings=$b&radiuses=$radii"
        return parse(fetch(url), mode).firstOrNull()
    }

    private fun bearing(line: List<LatLng>, cum: DoubleArray, m: Double): Double? {
        val a = Geo.point(line, max(0.0, m - 10), cum)
        val b = Geo.point(line, min(cum.last(), m + 10), cum)
        return if (a.distanceTo(b) > 1) Geo.bearing(a, b) else null
    }

    fun parse(data: ByteArray, mode: TravelMode): List<Route> = Json.obj(data)?.let { parseRoot(it, mode) } ?: emptyList()

    private fun loc(x: Any?): LatLng? = x.asList()?.let { if (it.size == 2) LatLng(it[1].num() ?: return null, it[0].num() ?: return null) else null }

    fun parseRoot(root: Map<String, Any?>, mode: TravelMode, source: String = "osm"): List<Route> {
        val routes = root["routes"].asMaps() ?: return emptyList()
        return routes.mapNotNull { r ->
            val geo = r["geometry"] as? String ?: return@mapNotNull null
            val distance = r["distance"].num() ?: return@mapNotNull null
            val duration = r["duration"].num() ?: return@mapNotNull null
            val line = Geo.decodePolyline(geo, 1e6)
            if (line.size < 2) return@mapNotNull null
            val cum = Geo.cumulative(line)
            var maneuvers = mutableListOf<Maneuver>()
            val mergeSides = mutableMapOf<Int, String>()
            val nameOnly = mutableSetOf<Int>()
            var along = 0.0
            val roads = LinkedHashMap<String, Double>()
            var leftMotorway: String? = null
            val legs = r["legs"].asMaps() ?: emptyList()
            val controls = mutableListOf<Overpass.RoadsideNode>()
            val approaches = mutableListOf<LaneApproach>()
            fun projectStep(at: LatLng, from: Double, to: Double): Projection {
                val start = (from - 65).coerceIn(0.0, cum.last())
                val end = (to + 65).coerceIn(start, cum.last())
                val hit = Geo.project(at, Geo.slice(line, start, end, cum))
                return hit.copy(along = hit.along + start)
            }
            for ((li, leg) in legs.withIndex()) {
                val steps = leg["steps"].asMaps() ?: emptyList()
                for ((si, step) in steps.withIndex()) {
                    val dist = step["distance"].num() ?: 0.0
                    val intersections = step["intersections"].asMaps().orEmpty()
                    for ((ii, intersection) in intersections.withIndex()) {
                        val at = loc(intersection["location"]) ?: continue
                        if (mode == TravelMode.DRIVE && ii > 0) {
                            val lanes = intersectionLanes(intersection)
                            if (lanes.isNotEmpty()) {
                                val hit = projectStep(at, along, along + dist)
                                if (hit.offset <= 12 && hit.along > along - 8 && hit.along <= along + dist + 8 &&
                                    hit.along > 6 && hit.along < cum.last() - 6)
                                    approaches += LaneApproach(hit.along, lanes)
                            }
                        }
                        if (source == "mapbox") {
                            if (intersection["traffic_signal"] == true) controls += Overpass.RoadsideNode(Overpass.ControlKind.SIGNAL, at)
                            if (intersection["stop_sign"] == true) controls += Overpass.RoadsideNode(Overpass.ControlKind.STOP, at)
                            if (intersection["railway_crossing"] == true) controls += Overpass.RoadsideNode(Overpass.ControlKind.RAIL, at)
                        }
                    }
                    val m = step["maneuver"].asMap() ?: emptyMap()
                    val type = m["type"] as? String ?: ""
                    if ((type == "arrive" && li < legs.size - 1) || (type == "depart" && li > 0)) {
                        maneuvers.lastOrNull()?.let { it.distanceMeters += dist }
                        along += dist
                        continue
                    }
                    val modifier = m["modifier"] as? String
                    val ref0 = (step["ref"] as? String)?.ifEmpty { null }
                    val ref = ref0?.split(";")?.firstOrNull()?.trim()
                    val motorway = ref?.takeIf { Regex("^(M\\d+|A\\d+\\(M\\))$").containsMatchIn(it) }
                    val name = motorway ?: (step["name"] as? String)?.ifEmpty { null }
                    // The link road off a motorway roundabout often still carries the motorway's number (M3 J6
                    // into Basingstoke): having just left the M3, that exit isn't "onto M3".
                    val roundaboutStep = type in setOf("roundabout", "rotary", "roundabout turn", "exit roundabout", "exit rotary")
                    // The fork and merge further up that link road carry the number too: still not the M3.
                    val before = maneuvers.lastOrNull { it.road != null && it.type != ManeuverType.ROUNDABOUT }?.road
                    if (motorway == null || motorway != leftMotorway) leftMotorway = null
                    if (roundaboutStep && motorway != null && motorway == before) leftMotorway = motorway
                    val stepRoad = if (leftMotorway != null) null else name ?: ref
                    (name ?: ref)?.let { roads[it] = (roads[it] ?: 0.0) + dist }
                    val at = loc(m["location"]) ?: Geo.point(line, along, cum)
                    val mt = maneuverType(type, modifier, Geo.drivesOnLeft(at))
                    val signs = Signs(step["destinations"] as? String, step["exits"] as? String, maneuvers.lastOrNull()?.road)
                    val supplied = (m["instruction"] as? String)?.takeIf { source == "mapbox" && it.isNotBlank() }
                    val exit = if (roundaboutStep) {
                        (m["exit"].int() ?: step["exit"].int())?.takeIf { it > 0 }
                            ?: supplied?.let(DirectionsParser::exitNumber)
                    } else null
                    val text = supplied
                        ?: instruction(type, modifier, stepRoad, exit, mode, signs)
                    val estimate = min(along, cum.last())
                    val projected = projectStep(at, along, along)
                    val progress = projected.along.takeIf {
                        projected.offset <= 12 && abs(it - estimate) <= 55 &&
                            it >= (maneuvers.lastOrNull()?.alongMeters ?: 0.0)
                    } ?: estimate.coerceAtLeast(maneuvers.lastOrNull()?.alongMeters ?: 0.0)
                    val man = Maneuver(mt, text, at, progress, dist, stepRoad)
                    man.lanes = lanes(step)
                    man.exit = exit
                    if (type == "roundabout" || type == "rotary") {
                        if (man.lanes.isEmpty() && si > 0) {
                            steps[si - 1]["intersections"].asMaps()?.lastOrNull()?.let {
                                man.lanes = approachLanes(it, at, modifier, Geo.drivesOnLeft(at))
                            }
                        }
                        val exitStep = steps.getOrNull(si + 1)?.takeIf {
                            it["maneuver"].asMap()?.get("type") in setOf("exit roundabout", "exit rotary")
                        }
                        man.roundabout = roundaboutSketch(step, exitStep, Geo.drivesOnLeft(at))
                    }
                    if (type == "exit roundabout" || type == "exit rotary") {
                        man.leavesRoundabout = true
                        man.lanes = emptyList()
                        val prev = maneuvers.lastOrNull()
                        if (prev != null && prev.type == ManeuverType.ROUNDABOUT && !prev.leavesRoundabout) {
                            if (prev.exit == null && man.exit != null) {
                                prev.exit = man.exit
                                prev.instruction = instruction("roundabout", null, prev.road ?: stepRoad, prev.exit, mode)
                            }
                            man.exit = prev.exit
                            man.roundabout = prev.roundabout
                        }
                    }
                    if (type == "merge" && modifier != null) mergeSides[maneuvers.size] = modifier
                    if ((type == "new name" || type == "continue") && (modifier ?: "straight") == "straight") nameOnly.add(maneuvers.size)
                    maneuvers.add(man)
                    along += dist
                }
            }
            for (i in nameOnly) if (i > 0 && maneuvers[i].lanes.isEmpty()) maneuvers[i].silent = true
            maneuvers = keepLaneThroughMerges(maneuvers, mergeSides)
            maneuvers.lastOrNull()?.alongMeters = cum.last()
            val via = roads.maxByOrNull { it.value }?.key
            if (source == "mapbox" && (maneuvers.firstOrNull()?.type != ManeuverType.DEPART ||
                    maneuvers.lastOrNull()?.type != ManeuverType.ARRIVE)) return@mapNotNull null
            val typical = r["duration_typical"].num()?.takeIf { source == "mapbox" && it > 0 }
            val route = Route(mode, line, distance, typical ?: duration, duration.takeIf { typical != null },
                via, maneuvers, source = source, laneApproaches = approaches.distinctBy { it.alongMeters }.sortedBy { it.alongMeters })
            // Mapbox annotations can describe a different number of segments than the overview.
            val annotationCount = legs.sumOf { it["annotation"].asMap()?.get("maxspeed").asList()?.size ?: 0 }
            if (source != "mapbox" || annotationCount == cum.lastIndex) route.speedLimits = speedLimits(legs, cum)
            if (source == "mapbox") route.trafficSpans = MapboxDirections.trafficSpans(legs, cum)
            if (controls.isEmpty()) route else route.copy(roadControls = Overpass.projectControls(controls.distinct(), line, fromRouter = true))
        }
    }

    /** The roundabout as far as the router knows it, placed around a circle fitted through the points. */
    fun roundaboutSketch(step: Map<String, Any?>, next: Map<String, Any?>?, clockwise: Boolean): RoundaboutShape? {
        val ints = step["intersections"].asMaps() ?: return null
        if (ints.isEmpty()) return null
        val exitInt = next?.get("intersections").asMaps()?.firstOrNull() ?: return null
        val entry = loc(ints[0]["location"]) ?: return null
        val out = loc(exitInt["location"]) ?: return null
        val passed = mutableListOf<LatLng>()
        val inOnly = mutableListOf<LatLng>()
        for (x in ints.drop(1)) {
            val p = loc(x["location"]) ?: continue
            val bearings = x["bearings"].asList() ?: continue
            val entries = x["entry"].asList()?.map { it == true } ?: emptyList()
            val into = x["in"].int()
            val onward = x["out"].int()
            for (k in bearings.indices) {
                if (k == into || k == onward) continue
                if (k < entries.size && entries[k]) passed.add(p) else inOnly.add(p)
            }
        }
        val exitsBefore = passed.distinct().filter { it != out && it != entry }
        val entriesOnly = inOnly.distinct().filter { it !in exitsBefore && it != out && it != entry }
        val ring = listOf(entry, out) + exitsBefore + entriesOnly
        val center = fitCircle(ring) ?: return null
        val e = Geo.bearing(center, entry)
        fun rel(p: LatLng): Double {
            val a = Geo.bearing(center, p) - e + 180
            return ((a % 360) + 360) % 360
        }
        val exits = exitsBefore.map { rel(it) } + listOf(rel(out))
        return RoundaboutShape(exits, exits.size - 1, entriesOnly.map { rel(it) }, clockwise, false)
    }

    /** The middle of the circle best through [pts] (least squares), or null when they don't make one. */
    fun fitCircle(pts: List<LatLng>): LatLng? {
        if (pts.size < 3) return null
        val o = pts[0]
        val k = cos(o.lat * PI / 180) * 111_320.0
        val xy = pts.map { Pair((it.lng - o.lng) * k, (it.lat - o.lat) * 110_540) }
        val a = Array(3) { DoubleArray(3) }
        val b = DoubleArray(3)
        for ((x, y) in xy) {
            val row = doubleArrayOf(x, y, 1.0)
            val z = -(x * x + y * y)
            for (i in 0 until 3) {
                for (j in 0 until 3) a[i][j] += row[i] * row[j]
                b[i] += row[i] * z
            }
        }
        fun det(m: Array<DoubleArray>) =
            m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1]) - m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0]) +
                m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0])
        val d = det(a)
        if (abs(d) <= 1e-6) return null
        fun col(c: Int) = Array(3) { i -> DoubleArray(3) { j -> if (j == c) b[i] else a[i][j] } }
        val dd = det(col(0)) / d
        val ee = det(col(1)) / d
        val cx = -dd / 2
        val cy = -ee / 2
        val r = xy.sumOf { (x, y) -> sqrt((x - cx) * (x - cx) + (y - cy) * (y - cy)) } / xy.size
        if (r <= 3 || r >= 300) return null
        return LatLng(o.lat + cy / 110_540, o.lng + cx / k)
    }

    /** The turn lanes at a step's maneuver point (its first intersection), left to right. */
    fun lanes(step: Map<String, Any?>): List<Lane> {
        val first = step["intersections"].asMaps()?.firstOrNull() ?: return emptyList()
        return intersectionLanes(first)
    }

    private fun intersectionLanes(intersection: Map<String, Any?>): List<Lane> {
        val raw = intersection["lanes"].asMaps() ?: return emptyList()
        if (raw.size <= 1) return emptyList()
        val lanes = raw.map { l ->
            Lane(l["indications"].asList()?.mapNotNull { it as? String } ?: listOf("none"), l["valid"] as? Boolean ?: false)
        }
        return if (lanes.any { it.valid } && lanes.any { !it.valid }) lanes else emptyList()
    }

    private val leftSide = setOf(ManeuverType.RAMP_LEFT, ManeuverType.FORK_LEFT, ManeuverType.KEEP_LEFT)
    private val rightSide = setOf(ManeuverType.RAMP_RIGHT, ManeuverType.FORK_RIGHT, ManeuverType.KEEP_RIGHT)

    /** A slip road that joins a main road and leaves again at the next exit on the same side: no merge, keep the lane. */
    fun keepLaneThroughMerges(steps: List<Maneuver>, mergeSides: Map<Int, String>): MutableList<Maneuver> {
        val out = mutableListOf<Maneuver>()
        var dropped = false
        for ((i, m0) in steps.withIndex()) {
            val side = mergeSides[i]
            if (side != null && i > 0) {
                val n = (i + 1 until steps.size).firstOrNull { !steps[it].silent }
                if (n != null) {
                    val next = steps[n]
                    val fromLeft = side.endsWith("right")
                    val exitSameSide = if (fromLeft) next.type in leftSide else next.type in rightSide
                    if (exitSameSide && next.alongMeters - m0.alongMeters < 1_000 && out.isNotEmpty()) {
                        out.last().distanceMeters += m0.distanceMeters
                        dropped = true
                        continue
                    }
                }
            }
            val m = m0.copy()
            if (dropped && m.silent) { out.add(m); continue }
            if (dropped && m.lanes.isEmpty()) {
                val left = m.type in leftSide
                val exit = Lane(listOf(if (left) "slight left" else "slight right"), true)
                val main = Lane(listOf("straight"), false)
                m.lanes = if (left) listOf(exit, main) else listOf(main, exit)
            }
            dropped = false
            out.add(m)
        }
        return out
    }

    /** Lanes mapped at a junction just before a roundabout, marked again for the way round it. */
    fun approachLanes(x: Map<String, Any?>, roundabout: LatLng, modifier: String?, leftTraffic: Boolean): List<Lane> {
        val raw = x["lanes"].asMaps() ?: return emptyList()
        if (raw.size <= 1) return emptyList()
        val at = loc(x["location"]) ?: return emptyList()
        if (at.distanceTo(roundabout) >= 200) return emptyList()
        val out = x["out"].int() ?: return emptyList()
        val entry = x["entry"].asList()?.map { it == true } ?: emptyList()
        val into = x["in"].int()
        if (entry.indices.any { it != out && it != into && entry[it] }) return emptyList()
        val m = modifier ?: "straight"
        val way = when {
            m.endsWith("left") -> "left"
            m.endsWith("right") -> "right"
            m == "uturn" -> if (leftTraffic) "right" else "left"
            else -> "straight"
        }
        val lanes = raw.map { r ->
            val ind = r["indications"].asList()?.mapNotNull { it as? String } ?: listOf("none")
            Lane(ind, ind.any { it == way || it.endsWith(" $way") })
        }
        val valid = lanes.count { it.valid }
        return if (valid == 0 || valid == lanes.size) emptyList() else lanes
    }

    /** Per-segment `maxspeed` annotations turned into limit changes along the line. */
    fun speedLimits(legs: List<Map<String, Any?>>, cum: DoubleArray): List<SpeedLimit> {
        val out = mutableListOf<SpeedLimit>()
        var seg = 0
        outer@ for (leg in legs) {
            val speeds = leg["annotation"].asMap()?.get("maxspeed").asMaps() ?: emptyList()
            for (sp in speeds) {
                if (seg >= cum.size) break@outer
                var kmh: Double? = null
                sp["speed"].num()?.let { v -> kmh = if (sp["unit"] == "mph") v * 1.609344 else v }
                if (out.lastOrNull()?.let { it.kmh != kmh } != false) out.add(SpeedLimit(cum[seg], kmh))
                seg++
            }
        }
        if (seg < cum.lastIndex && out.lastOrNull()?.kmh != null) out.add(SpeedLimit(cum[seg], null))
        return if (out.any { it.kmh != null }) out else emptyList()
    }

    fun maneuverType(type: String, modifier: String?, leftTraffic: Boolean = false): ManeuverType {
        when (type) {
            "depart" -> return ManeuverType.DEPART
            "arrive" -> return ManeuverType.ARRIVE
            "roundabout", "rotary", "roundabout turn", "exit roundabout", "exit rotary" -> return ManeuverType.ROUNDABOUT
            "merge" -> return ManeuverType.MERGE
            "on ramp", "off ramp" -> {
                val m = modifier ?: ""
                return if (m.contains("left")) ManeuverType.RAMP_LEFT else if (m.contains("right")) ManeuverType.RAMP_RIGHT
                else if (leftTraffic) ManeuverType.RAMP_LEFT else ManeuverType.RAMP_RIGHT
            }
            "fork" -> return if ((modifier ?: "").contains("left")) ManeuverType.FORK_LEFT else ManeuverType.FORK_RIGHT
        }
        return when (modifier) {
            "left" -> ManeuverType.TURN_LEFT
            "right" -> ManeuverType.TURN_RIGHT
            "slight left" -> if (type == "continue" || type == "new name") ManeuverType.KEEP_LEFT else ManeuverType.SLIGHT_LEFT
            "slight right" -> if (type == "continue" || type == "new name") ManeuverType.KEEP_RIGHT else ManeuverType.SLIGHT_RIGHT
            "sharp left" -> ManeuverType.SHARP_LEFT
            "sharp right" -> ManeuverType.SHARP_RIGHT
            "uturn" -> ManeuverType.UTURN
            else -> ManeuverType.STRAIGHT
        }
    }

    /** What the road signs at a junction say, and the road being left, for slip road wording. */
    data class Signs(val destinations: String? = null, val exits: String? = null, val from: String? = null) {
        /** "Eastleigh and Southampton" from "A335: Eastleigh, Southampton"; the road numbers if that's all there is. */
        val toward: String?
            get() {
                val d = destinations?.replace(";", ",")?.trim()
                if (d.isNullOrEmpty()) return null
                val parts = d.split(":", limit = 2).map { it.trim() }
                val names = (if (parts.size == 2) parts[1] else parts[0]).split(",")
                    .map { it.replace(Regex("\\([^)]*\\)"), "").trim() }.filter { it.isNotEmpty() }
                val refs = if (parts.size == 2) parts[0].split(",").map { it.trim() } else emptyList()
                var pick = names.take(2)
                if (pick.isEmpty()) pick = refs.take(2)
                if (pick.isEmpty()) return null
                return if (pick.size == 2) "${pick[0]} and ${pick[1]}" else pick[0]
            }

        /** Junction number from the sign, like "5" or "9A". */
        val number: String? get() = exits?.split(";")?.firstOrNull()?.trim()?.ifEmpty { null }
    }

    fun instruction(type: String, modifier: String?, road: String?, exit: Int?, mode: TravelMode, signs: Signs = Signs()): String {
        val onto = road?.let { " onto $it" } ?: ""
        val dir = modifier ?: "straight"
        val newRoad = road?.takeIf { it != signs.from }
        return when {
            type == "depart" -> {
                val verb = if (mode == TravelMode.DRIVE) "Drive" else if (mode == TravelMode.BIKE) "Ride" else "Walk"
                road?.let { "$verb along $it" } ?: "Head out"
            }
            type == "arrive" -> "You have arrived"
            type == "roundabout turn" && exit == null -> {
                val turn = if (dir.contains("left")) "turn left" else if (dir.contains("right")) "turn right" else "go straight on"
                "At the roundabout, $turn$onto"
            }
            type == "roundabout" || type == "rotary" || type == "roundabout turn" -> {
                if (road != null && road.lowercase().contains("roundabout")) {
                    exit?.let { "At $road, take the ${ordinal(it)} exit" } ?: "Follow $road"
                } else {
                    val n = exit?.let { "take the ${ordinal(it)} exit" } ?: "follow the roundabout"
                    "At the roundabout, $n$onto"
                }
            }
            type == "exit roundabout" || type == "exit rotary" -> "Exit the roundabout$onto"
            type == "merge" -> "Merge$onto"
            type == "on ramp" -> newRoad?.let { "Take the slip road onto $it" } ?: ("Take the slip road" + (signs.toward?.let { " toward $it" } ?: ""))
            type == "off ramp" -> {
                val base = signs.number?.let { "Take exit $it" } ?: "Take the exit"
                signs.toward?.let { "$base toward $it" } ?: (base + (newRoad?.let { " onto $it" } ?: ""))
            }
            type == "fork" -> {
                val toward = signs.toward?.let { " toward $it" } ?: ""
                "Keep ${if (dir.contains("left")) "left" else "right"} at the fork" + (newRoad?.let { " onto $it" } ?: toward)
            }
            type == "end of road" -> "At the end of the road, turn ${dir.replace("slight ", "")}$onto"
            type == "continue" || type == "new name" -> {
                val stay = road?.let { " to stay on $it" } ?: ""
                when {
                    dir == "uturn" -> "Make a U-turn" + (road?.let { " on $it" } ?: "")
                    dir.contains("slight") -> "Keep ${if (dir.contains("left")) "left" else "right"}" + (if (type == "continue") stay else onto)
                    dir.contains("left") || dir.contains("right") -> "Turn $dir" + (if (type == "continue") stay else onto)
                    else -> "Continue" + (road?.let { " on $it" } ?: "")
                }
            }
            else -> when (dir) {
                "uturn" -> "Make a U-turn$onto"
                "straight" -> "Continue straight$onto"
                else -> "Turn $dir$onto"
            }
        }
    }

    fun ordinal(n: Int): String = when (n) {
        1 -> "1st"
        2 -> "2nd"
        3 -> "3rd"
        else -> "${n}th"
    }
}

/** What the app asks for routes: Google (traffic, alternates), else the open router. */
object Directions {
    data class Result(val routes: List<Route>, val error: String? = null)

    /**
     * A drive that keeps off the roads tapped away ([avoid], a point on each): the open router's line
     * round them, with turn-by-turn that follows it. Null when there's no way round.
     */
    suspend fun avoiding(from: LatLng, to: LatLng, via: List<LatLng> = emptyList(), avoid: List<LatLng>): Route? {
        val line = Valhalla.lineAvoiding(from, to, via, avoid) ?: return null
        val found = runCatching { OSRM.match(line, TravelMode.DRIVE) }.getOrNull()
            ?: runCatching { OSRM.routeVia(line, TravelMode.DRIVE, spacing = 500.0, maxVias = 40) }.getOrNull()
            ?: return null
        // The open router can pull a way back onto the road at a corner: check it really stays off.
        return if (touches(found.polyline, avoid)) null else found
    }

    /** Whether [line] goes along any of the avoided roads (within 20 m of a point tapped on one). */
    fun touches(line: List<LatLng>, avoid: List<LatLng>): Boolean =
        line.size >= 2 && avoid.any { Geo.project(it, line).offset <= 20 }

    suspend fun routes(
        a: LatLng, b: LatLng, mode: TravelMode,
        avoidHighways: Boolean = false, avoidTolls: Boolean = false,
        useGoogle: Boolean = true, via: List<LatLng> = emptyList(), heading: Double? = null,
        alternatives: Boolean = false, mapboxToken: String? = null, engine: RouteEngine = RouteEngine.AUTO,
    ): Result = withContext(Dispatchers.Default) {
        if (engine == RouteEngine.GOOGLE && !useGoogle) return@withContext Result(emptyList(), "Google routing is disabled.")
        suspend fun mapbox(): List<Route> {
            val token = mapboxToken ?: return emptyList()
            return MapboxDirections.routes(a, b, via, heading, avoidHighways, avoidTolls, alternatives, token, mode)
                .filter { reaches(it.polyline, a, b) }
        }
        fun finish(routes: List<Route>) = routes.map { StepMerge.tidy(it.maneuvers); Roundabouts.settle(it) }
        if (engine == RouteEngine.MAPBOX) {
            if (mapboxToken == null) return@withContext Result(emptyList(), "Mapbox routing needs a map token.")
            val primary = finish(mapbox())
            return@withContext Result(primary, "Mapbox did not find a route.".takeIf { primary.isEmpty() })
        }
        if (engine == RouteEngine.OPENSTREETMAP) {
            val routes = OSRM.route(a, b, mode, via, heading).filter { reaches(it.polyline, a, b) }
            return@withContext Result(routes.map { StepMerge.tidy(it.maneuvers); Roundabouts.settle(it) },
                "OpenStreetMap did not find a route.".takeIf { routes.isEmpty() })
        }
        // Off the main thread: fitting the open router's steps onto a long route (a drive across the
        // country) is millions of sums, enough to freeze the screen for seconds.
        // Ask both at once: Google's route knows traffic, OSRM's steps read better.
        val osrmTask = async(Dispatchers.IO) { runCatching { OSRM.route(a, b, mode, via, heading) } }
        if (engine == RouteEngine.AUTO && mapboxToken != null && mode == TravelMode.DRIVE) {
            val primary = mapbox()
            if (primary.isNotEmpty()) {
                // Keep the chosen Mapbox route. Supplement gaps only at matching approaches.
                val open = deadline(1.2) { osrmTask.await().getOrNull() }.orEmpty()
                osrmTask.cancel()
                return@withContext Result(finish(primary.map { addMatchingLanes(it, open) }))
            }
        }
        if (mode == TravelMode.WALK && engine == RouteEngine.AUTO) {
            val walk = osrmTask.await().getOrNull().orEmpty().filter {
                it.polyline.firstOrNull()?.distanceTo(a)?.let { d -> d <= 180 } == true &&
                    it.polyline.last().distanceTo(b) <= 180
            }
            val fallback = if (walk.isEmpty() && useGoogle) try {
                GoogleClient.shared.directions(a, b, mode, avoidHighways, avoidTolls, via).filter { reaches(it.polyline, a, b) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            } else emptyList()
            val found = walk.ifEmpty { fallback }
            found.forEach { StepMerge.tidy(it.maneuvers) }
            return@withContext Result(found, if (found.isEmpty()) "No walking route found." else null)
        }
        var err: String? = null
        if (useGoogle) {
            try {
                var g = GoogleClient.shared.directions(a, b, mode, avoidHighways, avoidTolls, via)
                    .filter { reaches(it.polyline, a, b) }.toMutableList()
                if (heading != null) {
                    var ahead = g.filter { !startsBehind(it.polyline, heading) }
                    if (ahead.isEmpty() && (avoidHighways || avoidTolls)) ahead = g
                    g = (if (alternatives) ahead else ahead.take(1)).toMutableList()
                }
                if (g.isNotEmpty()) {
                    if (!alternatives && g.size > 1) g = g.take(1).toMutableList()
                    val viaTasks = g.map { route ->
                        async(Dispatchers.IO) { runCatching { OSRM.routeVia(route.polyline, mode, spacing = 550.0) }.getOrNull() }
                    }
                    val plain = deadline(4.0) { osrmTask.await().getOrNull() }.orEmpty()
                    val vias = deadline(2.0) { viaTasks.awaitAll() }.orEmpty()
                    viaTasks.forEach { it.cancel() }
                    for (i in g.indices) {
                        val candidates = listOfNotNull(vias.getOrNull(i)) + plain
                        if (mode == TravelMode.DRIVE) {
                            val refined = candidates.firstNotNullOfOrNull { roadGeometry(g[i], it) }
                            if (refined != null) {
                                g[i] = refined
                                continue
                            }
                        }
                        // Google's step positions are only trustworthy when its steps cover the whole line.
                        val complete = g[i].stepsComplete
                        val steps = StepMerge.adopt(candidates, g[i].polyline)
                            ?: if (complete) StepMerge.adoptPiecewise(candidates, g[i].maneuvers, g[i].polyline) else null
                        if (steps != null) {
                            StepMerge.dropFalseUTurns(steps, g[i].polyline)
                            if (complete) {
                                StepMerge.addGoogleLaneHints(steps, g[i].maneuvers)
                                StepMerge.addGoogleExits(steps, g[i].maneuvers)
                                StepMerge.addGoogleKinds(steps, g[i].maneuvers, g[i].polyline)
                                StepMerge.addGoogleMissing(steps, g[i].maneuvers, g[i].polyline)
                                StepMerge.addGoogleSigns(steps, g[i].maneuvers)
                            }
                            g[i].maneuvers = steps
                            g[i].stepsComplete = true
                        }
                        g[i] = addMatchingLanes(g[i], candidates)
                    }
                    for (i in g.indices) {
                        for (m in g[i].maneuvers) if (m.type == ManeuverType.ROUNDABOUT && m.exit == null) m.exit = DirectionsParser.exitNumber(m.instruction)
                        StepMerge.tidy(g[i].maneuvers)
                        g[i] = Roundabouts.settle(g[i])
                        // Britain and Ireland ("slip road" is British).
                        if (a.lat in 49.8..61.0 && a.lng in -11.0..2.0) for (m in g[i].maneuvers) m.instruction = British.words(m.instruction)
                    }
                    osrmTask.cancel()
                    return@withContext Result(g)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                err = e.message ?: "Google didn't answer."
            }
        }
        if (engine == RouteEngine.GOOGLE && mode == TravelMode.DRIVE && mapboxToken != null) {
            val fallback = finish(mapbox())
            if (fallback.isNotEmpty()) {
                osrmTask.cancel()
                return@withContext Result(fallback)
            }
        }
        if (engine == RouteEngine.GOOGLE && (mode != TravelMode.DRIVE || avoidHighways || avoidTolls)) {
            osrmTask.cancel()
            return@withContext Result(emptyList(), err ?: "Google Maps did not find a route.")
        }
        val o = osrmTask.await()
        // The open router only knows Europe: asked for somewhere it has no roads to (California, an
        // island with no ferry), it snaps the end to its nearest road, and Southampton to Davis came
        // back as 1,280 miles to Cabo da Roca in Portugal. A route that stops miles short isn't one.
        val open = o.getOrNull()
        open?.filter { reaches(it.polyline, a, b) }?.let { if (it.isNotEmpty()) return@withContext Result(it.map { r -> StepMerge.tidy(r.maneuvers); Roundabouts.settle(r) }) }
        o.exceptionOrNull()?.let { err = it.message }
        Result(emptyList(), noRouteReason(a, b, mode, open) ?: err ?: "No route found.")
    }

    /** Use the detailed road shape only when both providers chose the same directed route. */
    internal fun roadGeometry(google: Route, roads: Route): Route? {
        if (!roads.stepsComplete || roads.maneuvers.firstOrNull()?.type != ManeuverType.DEPART ||
            roads.maneuvers.lastOrNull()?.type != ManeuverType.ARRIVE ||
            !sameGuidedRoads(google.polyline, roads.polyline)) return null
        val old = Geo.cumulative(google.polyline)
        val shape = NearLine(roads.polyline)
        fun relocate(distance: Double): Double? {
            if (distance <= 0) return 0.0
            if (distance >= old.last()) return shape.cum.last()
            val hit = shape.project(Geo.point(google.polyline, distance, old))
            return hit.along.takeIf { hit.offset <= 22 && abs(it - distance) <= 55 }
        }
        val traffic = google.trafficSpans.mapNotNull { span ->
            val start = relocate(span.startMeters) ?: return@mapNotNull null
            val end = relocate(span.startMeters + span.lengthMeters) ?: return@mapNotNull null
            if (end > start) TrafficSpan(span.level, start, end - start) else null
        }
        val steps = roads.maneuvers.map { it.copy() }.toMutableList()
        if (google.stepsComplete) {
            // The detailed router owns the turns. Keep corroborating text hints and road signs.
            val hints = google.maneuvers.mapNotNull { hint ->
                val along = relocate(hint.alongMeters) ?: return@mapNotNull null
                hint.copy(alongMeters = along, location = Geo.point(roads.polyline, along, shape.cum))
            }
            StepMerge.addGoogleLaneHints(steps, hints)
            StepMerge.addGoogleSigns(steps, hints)
        }
        return google.copy(polyline = roads.polyline, distanceMeters = roads.distanceMeters,
            maneuvers = steps, trafficSpans = traffic, speedLimits = roads.speedLimits,
            stepsComplete = true, roadControls = roads.roadControls, laneApproaches = roads.laneApproaches)
    }

    private fun sameGuidedRoads(a: List<LatLng>, b: List<LatLng>): Boolean {
        if (a.size < 2 || b.size < 2 || a.first().distanceTo(b.first()) > 22 || a.last().distanceTo(b.last()) > 22) return false
        if (listOf(a, b).any { line -> (1 until line.size).any { abs(line[it].lng - line[it - 1].lng) > 180 } }) return false
        val first = NearLine(a)
        val second = NearLine(b)
        if (abs(first.cum.last() - second.cum.last()) > max(35.0, first.cum.last() * 0.04)) return false
        fun follows(source: NearLine, target: NearLine): Boolean {
            val length = source.cum.last()
            val checks = ceil(length / 24).toInt().coerceIn(2, 4_000)
            var previous = 0.0
            for (i in 1 until checks) {
                val at = length * i / checks
                val hit = target.project(Geo.point(source.line, at, source.cum))
                if (hit.offset > 22 || hit.along < previous - 3 ||
                    abs(hit.along - at * target.cum.last() / length) > 55) return false
                val before = Geo.point(source.line, at - 9, source.cum)
                val after = Geo.point(source.line, at + 9, source.cum)
                val targetBefore = Geo.point(target.line, hit.along - 9, target.cum)
                val targetAfter = Geo.point(target.line, hit.along + 9, target.cum)
                if (Geo.diff(Geo.bearing(before, after), Geo.bearing(targetBefore, targetAfter)) > 68) return false
                previous = hit.along
            }
            return true
        }
        return follows(first, second) && follows(second, first)
    }

    private fun addMatchingLanes(route: Route, candidates: List<Route>): Route {
        val alternate = candidates.firstOrNull { other ->
            (other.laneApproaches.isNotEmpty() || other.maneuvers.any { it.lanes.isNotEmpty() }) &&
                sameGuidedRoads(route.polyline, other.polyline)
        } ?: return route
        val primary = NearLine(route.polyline)
        val secondary = Geo.cumulative(alternate.polyline)
        fun project(at: LatLng, along: Double): Double? {
            val hit = primary.project(at)
            return hit.along.takeIf {
                hit.offset <= 12 && StepMerge.sameJunction(alternate.polyline, secondary, along, route.polyline, primary.cum, it)
            }
        }
        val steps = route.maneuvers.map { it.copy() }.toMutableList()
        for (m in alternate.maneuvers.filter { it.lanes.isNotEmpty() }) {
            val along = project(m.location, m.alongMeters) ?: continue
            val step = steps.filter { it.type == m.type && it.leavesRoundabout == m.leavesRoundabout }
                .minByOrNull { abs(it.alongMeters - along) } ?: continue
            if (step.lanes.isEmpty() && abs(step.alongMeters - along) <= 15) step.lanes = m.lanes
        }
        val approaches = route.laneApproaches.toMutableList()
        for (lane in alternate.laneApproaches) {
            val along = project(Geo.point(alternate.polyline, lane.alongMeters, secondary), lane.alongMeters) ?: continue
            if (approaches.none { abs(it.alongMeters - along) < 20 }) approaches += lane.copy(alongMeters = along)
        }
        return route.copy(maneuvers = steps, laneApproaches = approaches.sortedBy { it.alongMeters })
    }

    /**
     * Why there's no route, when it can be told: too far for the way of travel, or roads that stop
     * short of the place (the open router found a way, but only to the nearest road it has: the sea,
     * or a ferry, is in the way). Null when it can't be told.
     */
    fun noRouteReason(a: LatLng, b: LatLng, mode: TravelMode, open: List<Route>?): String? {
        val km = a.distanceTo(b) / 1000
        return when {
            mode == TravelMode.WALK && km > 500 -> "Too far to walk."
            mode == TravelMode.BIKE && km > 1000 -> "Too far to cycle."
            km > 6000 -> "Too far to drive."
            // Thousands of km with no road found at all: almost always an ocean in between (London to New York).
            km > 3000 -> "No road goes all the way there. The sea may be in the way."
            // Only when the open router got close (within 50 km at both ends): far off means it simply has
            // no map there (it only knows Europe), not that the sea is in the way.
            open?.any { it.polyline.size > 1 && it.polyline.first().distanceTo(a) < 50_000 && it.polyline.last().distanceTo(b) < 50_000 } == true ->
                "No road goes all the way there. The sea or a ferry may be in the way."
            else -> null
        }
    }

    fun sameRoads(first: List<LatLng>, second: List<LatLng>): Boolean {
        if (first.size < 2 || second.size < 2) return false
        val firstDistances = Geo.cumulative(first)
        val secondDistances = Geo.cumulative(second)
        fun covered(line: List<LatLng>, distances: DoubleArray, reference: NearLine): Boolean {
            val length = distances.last()
            val samples = (length / 70).toInt().coerceIn(8, 320)
            return (1 until samples).all { i ->
                reference.project(Geo.point(line, length * i / samples, distances)).offset <= 35.0
            }
        }
        return covered(first, firstDistances, NearLine(second, secondDistances)) &&
            covered(second, secondDistances, NearLine(first, firstDistances))
    }

    /** Whether [line] starts and ends within 5 km of where it was asked to go from and to. */
    fun reaches(line: List<LatLng>, a: LatLng, b: LatLng): Boolean {
        val first = line.firstOrNull() ?: return false
        return first.distanceTo(a) < 5_000 && line.last().distanceTo(b) < 5_000
    }

    /** Whether [line] sets off more than a right angle away from [heading]: back the way the car came. */
    fun startsBehind(line: List<LatLng>, heading: Double): Boolean {
        if (line.size < 2) return false
        val cum = Geo.cumulative(line)
        val end = Geo.point(line, min(40.0, cum.last()), cum)
        if (line[0].distanceTo(end) <= 5) return false
        return Geo.diff(Geo.bearing(line[0], end), heading) > 100
    }
}

/** Puts an open router's steps on Google's route line, when they follow the same roads. */
object StepMerge {
    /** The steps of the candidate that best fits [line], placed on it; null if none fits well. */
    fun adopt(candidates: List<Route>, line: List<LatLng>): MutableList<Maneuver>? {
        if (line.size < 2) return null
        val cum = Geo.cumulative(line)
        val total = cum.last()
        var best: MutableList<Maneuver>? = null
        var bestScore = 0.0
        for (o in candidates) {
            if (!follows(o.polyline, line, cum) || !follows(line, o.polyline, Geo.cumulative(o.polyline))) continue
            val source = NearLine(o.polyline)
            val mids = o.maneuvers.filter { it.type != ManeuverType.DEPART && it.type != ManeuverType.ARRIVE }
            val placed = mutableListOf<Maneuver>()
            var last = 0.0
            for (m in mids) {
                val p = Geo.project(m.location, line, cum)
                if (!(p.offset < 40 && p.along >= last - 30 && p.along < total - 5)) continue
                if (!sameJunction(o.polyline, source.cum, source.project(m.location).along, line, cum, p.along)) continue
                last = max(last, p.along)
                placed.add(m.copy(alongMeters = last, location = Geo.point(line, last, cum)))
            }
            val score = if (mids.isEmpty()) (if (abs(Geo.length(o.polyline) - total) < 100) 1.0 else 0.0) else placed.size.toDouble() / mids.size
            if (score <= bestScore) continue
            bestScore = score
            val depart = (o.maneuvers.firstOrNull { it.type == ManeuverType.DEPART }?.copy()
                ?: Maneuver(ManeuverType.DEPART, "Head out", line[0], 0.0, 0.0)).apply { alongMeters = 0.0; location = line[0] }
            val arrive = (o.maneuvers.lastOrNull { it.type == ManeuverType.ARRIVE }?.copy()
                ?: Maneuver(ManeuverType.ARRIVE, "You have arrived", line.last(), total, 0.0)).apply { alongMeters = total; location = line.last() }
            val all = (listOf(depart) + placed + listOf(arrive)).toMutableList()
            for (i in 0 until all.size - 1) all[i].distanceMeters = all[i + 1].alongMeters - all[i].alongMeters
            all.last().distanceMeters = 0.0
            best = all
        }
        return if (bestScore >= 0.7) best else null
    }

    /** Whether [route] keeps within 30 m of [line] everywhere (checked every 50 m), ends aside. */
    fun follows(route: List<LatLng>, line: List<LatLng>, cum: DoubleArray): Boolean {
        if (route.size < 2) return false
        val rc = Geo.cumulative(route)
        val total = rc.last()
        var m = 60.0
        while (m < total - 60) {
            if (Geo.project(Geo.point(route, m, rc), line, cum).offset > 30) return false
            m += 50
        }
        return true
    }

    /** Google's exit count for each roundabout, where the open router has none or a different one. */
    fun addGoogleExits(steps: MutableList<Maneuver>, google: List<Maneuver>) {
        for (i in steps.indices) {
            val s = steps[i]
            if (s.type != ManeuverType.ROUNDABOUT || s.leavesRoundabout) continue
            val g = google.filter {
                it.type == ManeuverType.ROUNDABOUT && !it.leavesRoundabout && it.exit != null &&
                    abs(it.alongMeters - s.alongMeters) <= 45 && it.location.distanceTo(s.location) <= 35
            }.minByOrNull { abs(it.alongMeters - s.alongMeters) } ?: continue
            if (steps.any {
                it !== s && it.type == ManeuverType.ROUNDABOUT && !it.leavesRoundabout &&
                    abs(it.alongMeters - g.alongMeters) <= abs(s.alongMeters - g.alongMeters)
            }) continue
            val n = g.exit ?: continue
            if (n == s.exit) continue
            s.exit = n
            s.instruction = OSRM.instruction("roundabout", null, s.road, n, TravelMode.DRIVE)
            if (i + 1 < steps.size && steps[i + 1].leavesRoundabout) steps[i + 1].exit = n
        }
    }

    /** Google's own lane advice ("Use the right lane to merge onto Redbridge Rd") where the open map has no lanes. */
    fun addGoogleLaneHints(steps: MutableList<Maneuver>, google: List<Maneuver>) {
        // Each of Google's hints goes to one step only, and only when they're each other's nearest:
        // a left turn 80 m before a right one mustn't take the right turn's lanes.
        val open = steps.filter { !it.silent && it.lanes.isEmpty() && it.laneHint == null }
        val hints = google.filter { it.laneHint != null }
        for (g in hints) {
            val s = open.minByOrNull { abs(it.alongMeters - g.alongMeters) } ?: continue
            if (s.type != g.type || abs(s.alongMeters - g.alongMeters) >= 65 ||
                s.location.distanceTo(g.location) > 25) continue
            val back = hints.minByOrNull { abs(it.alongMeters - s.alongMeters) } ?: continue
            if (back.alongMeters == g.alongMeters) s.laneHint = g.laneHint
        }
    }
}

/** Google words its steps the American way even in Britain: "Take the ramp" is a slip road there. */
object British {
    fun words(text: String): String = text
        .replace(Regex("\\b(the|a) ramp\\b"), "$1 slip road")
        .replace(Regex("\\bramp\\b"), "slip road")
        .replace(Regex("\\bparking lot\\b"), "car park")
        .replace(Regex("\\bparking garage\\b"), "multi-storey car park")
        .replace(Regex("\\bfreeway\\b"), "motorway")
}
