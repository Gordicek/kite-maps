package app.kite.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TransitRideTests {
    @Test fun followsStopsAndRejectsBadGps() {
        val a = LatLng(50.0, 14.0)
        val b = a.copy(lat = 50.004)
        val c = a.copy(lat = 50.008)
        val stops = listOf(TransitCall("a", "Start", a, 1000, 1100), TransitCall("b", "Middle", b, 5000, 5200),
            TransitCall("c", "Transfer", c, 16_000, null))
        val ride = TransitRide(TransitLeg(false, "TRAM", path = listOf(a, b, c), calls = stops))
        assertEquals("b", ride.next?.stopId)
        assertEquals("c", ride.scheduledNext(8000)?.stopId)
        assertFalse(ride.observe(c, 300.0, 6000).nextChanged)
        assertEquals("b", ride.next?.stopId)
        assertFalse(ride.observe(c.copy(lng = 14.03), 10.0, 6100).exitApproach)
        assertTrue(ride.observe(b, 10.0, 6300).nextChanged)
        assertEquals("c", ride.next?.stopId)
        assertFalse(ride.nearExit)
        assertTrue(ride.observe(c, 12.0, 15_000).exitApproach)
        assertTrue(ride.nearExit)
    }

    @Test fun keepsTransitousWalkingStepsAndPlatform() {
        val data = """{"itineraries":[{"legs":[
          {"mode":"WALK","from":{"lat":50.0,"lon":14.0},"to":{"lat":50.002,"lon":14.0},
           "steps":[{"relativeDirection":"DEPART","distance":20},{"relativeDirection":"ELEVATOR","distance":20}]},
          {"mode":"TRAM","from":{"lat":50.002,"lon":14.0,"track":"2"},"to":{"lat":50.005,"lon":14.0}}
          ]}]}"""
        val legs = Transitous.parse(data.toByteArray()).single().legs
        assertEquals("Take the elevator", legs.first().walkSteps[1].instruction)
        assertFalse(legs.first().walkSteps[1].silent)
        assertEquals("2", legs.last().calls.first().platform)
    }

    @Test fun metroUsesOperatorColorAndLiveIntermediateStopsWithoutGps() {
        val data = """{"legs":[{"mode":"SUBWAY","routeColor":"#00A562","tripId":"metro-1","realTime":true,
          "from":{"stopId":"a","name":"Board","lat":50.0,"lon":14.0,"departure":"2026-11-23T12:00:00Z"},
          "intermediateStops":[{"stopId":"b","name":"Next","lat":50.005,"lon":14.0,
            "arrival":"2026-11-23T12:07:00Z","scheduledArrival":"2026-11-23T12:03:00Z","arrivalTimestampReason":"PREDICTED"}],
          "to":{"stopId":"c","name":"Exit","lat":50.01,"lon":14.0,"arrival":"2026-11-23T12:12:00Z"}
          }]}"""
        val scheduled = data.replace("12:07:00Z", "12:03:00Z").replace("PREDICTED", "NO_RT_DATA")
        val plan = Transitous.parse("""{"itineraries":[$scheduled]}""".toByteArray()).single().rides.single()
        val now = checkNotNull(Transitous.date("2026-11-23T12:05:00Z"))
        val live = assertNotNull(Transitous.parseLive(data.toByteArray(), plan, now))
        val ride = TransitRide(plan)
        assertEquals(0x00A562L, plan.mapColor)
        assertEquals("c", ride.scheduledNext(now)?.stopId)
        val next = assertNotNull(ride.scheduledNext(now, live))
        assertEquals("b", next.stopId)
        assertEquals(4, live.matchCall(next)?.arrivalDelay)
        assertNull(live.exit?.arrivalDelay)
    }

    @Test fun projectsSignalsButNotDistantSideRoads() {
        val line = listOf(LatLng(50.0, 14.0), LatLng(50.02, 14.0))
        val raw = """{"elements":[
          {"type":"node","id":1,"lat":50.004,"lon":14.00003,"tags":{"highway":"traffic_signals"}},
          {"type":"node","id":2,"lat":50.009,"lon":14.0002,"tags":{"highway":"traffic_signals"}},
          {"type":"node","id":3,"lat":50.007,"lon":14.0001,"tags":{"highway":"stop"}},
          {"type":"node","id":4,"lat":50.011,"lon":14,"tags":{"railway":"level_crossing"}},
          {"type":"node","id":5,"lat":50.015,"lon":14.00016,"tags":{"railway":"level_crossing"}}
          ]}"""
        val projected = Overpass.projectControls(Overpass.parseRoadControls(raw.toByteArray()), line)
        assertEquals(listOf(Overpass.ControlKind.SIGNAL, Overpass.ControlKind.RAIL), projected.map { it.kind })
        assertEquals(LatLng(50.004, 14.0), projected.first().at)
    }

    @Test fun liveDetailAlignsWithTheSelectedStopsAndLabelsEstimatedPosition() {
        val now = checkNotNull(Transitous.date("2026-11-23T12:05:00Z"))
        val data = """{"legs":[{"mode":"BUS","tripId":"ride-1","realTime":true,
          "from":{"stopId":"a","name":"Board","lat":50.0,"lon":14.0,"departure":"2026-11-23T12:02:00Z",
            "scheduledDeparture":"2026-11-23T12:00:00Z","departureTimestampReason":"OBSERVED"},
          "to":{"stopId":"b","name":"Exit","lat":50.01,"lon":14.0,"arrival":"2026-11-23T12:08:00Z",
            "scheduledArrival":"2026-11-23T12:05:00Z","arrivalTimestampReason":"PREDICTED"},
          "alerts":[{"headerText":"Temporary route","descriptionText":"Use the replacement stop."}]}]}"""
        val plan = Transitous.parse("""{"itineraries":[$data]}""".toByteArray()).single().rides.single()
        val live = assertNotNull(Transitous.parseLive(data.toByteArray(), plan, now))
        assertEquals(2, live.board?.departureDelay)
        assertEquals(3, live.exit?.arrivalDelay)
        assertEquals("Temporary route", live.notices.single().title)
        assertEquals(50.005, assertNotNull(live.position).lat, 0.00001)
        assertFalse(live.gps)
        val scheduled = data.replace("OBSERVED", "NO_RT_DATA").replace("PREDICTED", "NO_RT_DATA")
        assertNull(Transitous.parseLive(scheduled.toByteArray(), plan, now)?.position)
        val cancelled = data.replace(""""mode":"BUS"""", """"mode":"BUS","cancelled":true""")
        val disrupted = assertNotNull(Transitous.parseLive(cancelled.toByteArray(), plan, now))
        assertTrue(disrupted.cancelled)
        assertNull(disrupted.position)
    }

    @Test fun vehicleFeedMatchesExactRideAndRejectsStalePositions() {
        val now = checkNotNull(Transitous.date("2026-11-23T12:01:00Z"))
        val data = """{"data":[{"attributes":{"latitude":42.35,"longitude":-71.06,
            "updated_at":"2026-11-23T12:00:40Z","occupancy_status":"FEW_SEATS_AVAILABLE"},
            "relationships":{"trip":{"data":{"type":"trip","id":"abc"}}}}]}""".toByteArray()
        val vehicle = assertNotNull(Transitous.parseMbtaVehicle(data, "abc", now))
        assertEquals(LatLng(42.35, -71.06), vehicle.at)
        assertEquals("Few seats available", vehicle.occupancy)
        assertNull(Transitous.parseMbtaVehicle(data, "other", now))
        assertNull(Transitous.parseMbtaVehicle(data, "abc", now + 90_000))
    }


    @Test fun adjacentSignalNeedsEvidenceFromTheRoadItBelongsTo() {
        val route = listOf(LatLng(50.0, 14.0), LatLng(50.01, 14.0))
        val at = LatLng(50.0045, 14.00009)
        val routeRoad = listOf(LatLng(50.004, 14.0), at, LatLng(50.005, 14.0))
        val sideRoad = listOf(at, LatLng(50.0045, 14.002))
        val node = Overpass.RoadsideNode(Overpass.ControlKind.SIGNAL, at)
        assertTrue(Overpass.projectControls(listOf(node.copy(roads = listOf(Overpass.ControlWay(sideRoad, 0)))), route).isEmpty())
        val signal = Overpass.projectControls(listOf(node.copy(roads = listOf(Overpass.ControlWay(routeRoad, 0)))), route).single()
        assertEquals(14.0, signal.at?.lng)
    }

}
