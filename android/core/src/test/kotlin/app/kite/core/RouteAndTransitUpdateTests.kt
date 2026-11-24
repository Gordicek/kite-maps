package app.kite.core

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class RouteAndTransitUpdateTests {
    @Test fun splitsTheCenterlineAtCongestionBoundaries() {
        val spans = listOf(TrafficSpan(1, 10.0, 40.0), TrafficSpan(3, 30.0, 30.0))
        assertEquals(
            listOf(
                RouteTrafficPaint.Segment(0.0, 10.0, 0), RouteTrafficPaint.Segment(10.0, 30.0, 1),
                RouteTrafficPaint.Segment(30.0, 60.0, 3), RouteTrafficPaint.Segment(60.0, 70.0, 0),
            ),
            RouteTrafficPaint.ranges(0.0, 70.0, spans),
        )
        assertEquals(listOf(RouteTrafficPaint.Segment(35.0, 37.0, 3)), RouteTrafficPaint.ranges(35.0, 37.0, spans))
    }

    @Test fun transferUsesLiveArrivalAndSubtractsTheWalk() {
        val at = LatLng(50.0, 14.0)
        val rides = listOf(
            TransitLeg(false, "BUS", toName = "Transfer station", arrive = 600_000, tripId = "incoming"),
            TransitLeg(true, "WALK", durationSeconds = 180.0, distanceMeters = 234.0),
            TransitLeg(false, "TRAM", depart = 900_000, tripId = "outgoing"),
        )
        val trip = TransitTrip(0, 1_000_000, 1000.0, 1, rides)
        assertEquals(120, trip.transferWarnings(emptyMap()).single().bufferSeconds)
        val personalized = trip.transferWarnings(emptyMap(), walkingPace = 0.75).single()
        assertEquals(-12, personalized.bufferSeconds)
        assertEquals(TransferTiming.Kind.MISSED, personalized.kind)
        val updated = TransitLive(null, TransitCall("a", "Transfer station", at, 733_000, null), false, emptyList())
        val warning = trip.transferWarnings(mapOf("incoming" to updated)).single()
        assertEquals(-13, warning.bufferSeconds)
        assertEquals(TransferTiming.Kind.MISSED, warning.kind)
    }

    @Test fun pidPositionRequiresTheExactTripAndAFreshReport() {
        val now = Instant.parse("2026-01-08T14:00:30Z").toEpochMilli()
        val payload = """{"geometry":{"type":"Point","coordinates":[14.4,50.1]},
          "properties":{"trip":{"gtfs":{"trip_id":"31_24"}},"last_position":{"origin_timestamp":"2026-01-08T14:00:00Z"}}}""".toByteArray()
        assertEquals(LatLng(50.1, 14.4), assertNotNull(PidRealtime.parseVehicle(payload, "31_24", now)).position)
        assertNull(PidRealtime.parseVehicle(payload, "31_25", now))
        assertNull(PidRealtime.parseVehicle(payload, "31_24", now + 5 * 60_000))
    }

    @Test fun pidDepartureUsesTheReportFromTheSelectedTrip() {
        val time = Instant.parse("2026-01-08T14:00:00Z").toEpochMilli()
        val stop = TransitCall("official", "Stop", LatLng(50.1, 14.4), null, time)
        val payload = """[[{"trip":{"id":"31_24"},"departure":{"timestamp_scheduled":"2026-01-08T15:00:00+01:00",
           "timestamp_predicted":"2026-01-08T15:02:06+01:00","delay_seconds":126}}]]""".toByteArray()
        val report = assertNotNull(PidRealtime.parseBoard(payload, "31_24", stop))
        assertEquals(time + 126_000, report.departure)
        assertEquals(2, report.departureDelay)
        assertNull(PidRealtime.parseBoard(payload, "31_25", stop))
    }
}
