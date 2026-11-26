package app.kite.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TransitRailGeometryTests {
    @Test fun olomoucTramDoesNotDetourThroughAnUnservedVertex() {
        val start = LatLng(49.592316, 17.277658)
        val stop = LatLng(49.59436, 17.27148)
        val spike = LatLng(49.59093, 17.27153)
        val after = LatLng(49.59465, 17.27030)
        val end = LatLng(49.59617, 17.26497)
        val path = listOf(start, stop, spike, after, end)
        val calls = listOf(start, stop, end).map { TransitCall(null, "", it, null, null) }
        assertEquals(listOf(start, stop, after, end), Transitous.withoutRailSpikes(path, calls))

        val served = TransitCall(null, "Stop at the end of the branch", spike, null, null)
        assertEquals(path, Transitous.withoutRailSpikes(path, calls + served))
    }

    @Test fun removesMultiVertexSpikeButKeepsAStopItServes() {
        val start = LatLng(49.5900, 17.2700)
        val a = LatLng(49.5921, 17.2703)
        val b = LatLng(49.5923, 17.2706)
        val end = LatLng(49.5900, 17.2714)
        val path = listOf(start, a, b, end)
        fun call(at: LatLng) = TransitCall(null, "", at, null, null)
        assertEquals(listOf(start, end), Transitous.withoutRailSpikes(path, listOf(call(start), call(end))))
        assertEquals(path, Transitous.withoutRailSpikes(path, listOf(call(start), call(a), call(end))))
    }

    @Test fun viewportOmitsOffscreenTrackWithoutJoiningTwoVisibleSections() {
        val points = listOf(
            LatLng(0.0, -2.0), LatLng(0.0, 0.0), LatLng(0.0, 2.0), LatLng(2.0, 2.0),
            LatLng(2.0, 0.0), LatLng(0.5, 0.0), LatLng(0.5, -2.0),
        )
        assertEquals(listOf(points.subList(0, 3), points.subList(4, 7)),
            Transitous.trackPieces(points, -1.0, -1.0, 1.0, 1.0))
        val crossing = listOf(LatLng(0.0, -3.0), LatLng(0.0, 3.0))
        assertEquals(listOf(crossing), Transitous.trackPieces(crossing, -1.0, -1.0, 1.0, 1.0))
    }

    @Test fun fastestWalkIncludesTransitWaitingTimeAndIgnoresCancelledTrips() {
        val now = 1_000_000L
        val ride = TransitLeg(false, "TRAM")
        val trip = TransitTrip(now + 4 * 60_000, now + 9 * 60_000, 300.0, 0, listOf(ride))
        assertTrue(Transitous.walkIsFaster(420.0, listOf(trip), now, arriveBy = false))
        assertFalse(Transitous.walkIsFaster(420.0, listOf(trip), now + 9 * 60_000, arriveBy = true))
        assertTrue(Transitous.walkIsFaster(960.0, listOf(trip.copy(legs = listOf(ride.copy(cancelled = true)))), now, arriveBy = false))
    }
}
