package app.kite.core

import kotlin.test.Test
import kotlin.test.assertEquals

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
}
