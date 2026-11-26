package app.kite

import app.kite.core.Lane
import app.kite.core.LaneApproach
import app.kite.core.LatLng
import app.kite.core.Maneuver
import app.kite.core.ManeuverType
import app.kite.ui.roadAheadGroups
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

class NavigationDrawingTests {
    private val point = LatLng(49.6, 17.25)
    private val ramp = Maneuver(ManeuverType.RAMP_RIGHT, "Take the exit", point, 1_000.0, 100.0)
    private val exitLanes = listOf(Lane(listOf("straight"), false), Lane(listOf("slight right"), true))

    @Test fun anExitArrowStartsOnTheApproachWhileACityTurnKeepsAShortArrow() {
        val lanes = listOf(
            LaneApproach(550.0, exitLanes),
            LaneApproach(710.0, exitLanes),
            LaneApproach(940.0, listOf(Lane(listOf("straight"), true))),
        )
        assertEquals(710.0, NavigationDrawing.arrowStart(ramp, lanes, 25.0))
        assertEquals(680.0, NavigationDrawing.arrowStart(ramp, emptyList(), 32.0))
        assertEquals(950.0, NavigationDrawing.arrowStart(ramp.copy(type = ManeuverType.TURN_RIGHT), lanes, 32.0))
    }

    @Test fun operatorColorsStayBrightEnoughAgainstTheNightMap() {
        val background = NavigationDrawing.luminance(0x1B2635L)
        val brightened = NavigationDrawing.nightTransit(0x0B3482L)
        assertTrue((NavigationDrawing.luminance(brightened) + 0.05) / (background + 0.05) >= 4.5)
        assertEquals(0xF5C242L, NavigationDrawing.nightTransit(0xF5C242L))
    }

    @Test fun groupedReportsRetainEveryItemInRouteOrder() {
        val groups = roadAheadGroups(listOf(70.0, 71.0, 160.0, 161.0, 620.0, 621.0), 120.0)
        assertEquals(listOf(listOf(0, 1, 2, 3), listOf(4, 5)), groups)
    }
}
