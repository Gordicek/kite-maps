package app.kite

import app.kite.core.LatLng
import app.kite.core.Route
import app.kite.core.TravelMode
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class RouteBadgesTests {
    private val start = LatLng(49.5900, 17.2500)
    private val split = LatLng(49.6120, 17.2500)
    private val merge = LatLng(49.6200, 17.2500)
    private val end = LatLng(49.6550, 17.2500)
    private val bounds = RouteBadges.Bounds(12.0, 60.0, 320.0, 250.0)

    private fun path(vararg points: LatLng) =
        Route(TravelMode.DRIVE, points.toList(), 9000.0, 500.0, null, null, mutableListOf(), source = "test")

    @Test fun labelsFavorTheSeparatedBranchesOfMostlyIdenticalRoutes() {
        val west = path(start, split, LatLng(49.616, 17.245), merge, end)
        val east = path(start, split, LatLng(49.616, 17.257), merge, end)
        val (westMarker, eastMarker) = RouteBadges.anchors(listOf(west, east)).map { it.first() }
        assertTrue(westMarker.lng < split.lng && westMarker.lat in split.lat..merge.lat)
        assertTrue(eastMarker.lng > split.lng && eastMarker.lat in split.lat..merge.lat)
    }

    @Test fun aRouteWithNoAlternativeUsesTheMiddleInsteadOfItsStart() {
        val midpoint = RouteBadges.anchors(listOf(path(start, end))).single().first()
        assertTrue(midpoint.lat in 49.617..49.630)
    }

    @Test fun movesToAnotherPointOnTheRouteBeforeOffsettingFromIt() {
        val positions = listOf(
            listOf(RouteBadges.Tag(70.0, 110.0, 70.0, 36.0)),
            listOf(RouteBadges.Tag(76.0, 116.0, 70.0, 36.0), RouteBadges.Tag(210.0, 135.0, 70.0, 36.0)),
        )
        val result = RouteBadges.place(positions, bounds, selected = 0)
        assertEquals(RouteBadges.Placement(1, RouteBadges.Offset()), result.last())
    }

    @Test fun coincidentTimesHaveDistinctTargetsInsideTheUsableMap() {
        val tag = RouteBadges.Tag(160.0, 108.0, 76.0, 36.0)
        val offsets = RouteBadges.place(List(3) { listOf(tag) }, bounds, selected = 2).map { it.offset }
        assertEquals(3, offsets.distinct().size)
        assertEquals(RouteBadges.Offset(), offsets[2])
        assertNotEquals(offsets[0], offsets[1])
        assertTrue(offsets.all { tag.y + it.y in 78.0..232.0 && tag.x + it.x in 50.0..282.0 })
    }
}
