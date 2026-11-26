package app.kite

import app.kite.core.LatLng
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class NavCameraLayoutTests {
    @Test fun drivingCameraKeepsARenderableViewportOnShortScreens() {
        for (height in listOf(160f, 320f, 480f, 499f, 500f, 540f, 567f, 800f)) {
            val top = NavGlide.topInset(height)
            val bottom = NavGlide.bottomInset(height)
            assertTrue(height - top - bottom >= 48f, "No usable viewport at ${height}dp")
            assertEquals(top + (height - top - bottom) / 2f, NavGlide.carY(height), absoluteTolerance = 0.001f)
        }
    }

    @Test fun northUpKeepsTheVehicleAtTheCenterOfTheAdjustedViewport() {
        val height = 540f
        val bottom = NavGlide.bottomInset(height)
        assertEquals((height - bottom) / 2f, NavGlide.carY(height, north = 1f))
        assertTrue(NavGlide.carY(height, north = 1f) < NavGlide.carY(height))
    }

    @Test fun startupRejectsAnOldPreviewCenterOrZoom() {
        val fix = LatLng(49.592, 17.252)
        val routeOverview = LatLng(49.604, 17.285)
        assertTrue(NavGlide.needsCameraCorrection(routeOverview, fix, 17.0, 17.0))
        assertTrue(NavGlide.needsCameraCorrection(fix, fix, 12.0, 17.0))
        assertFalse(NavGlide.needsCameraCorrection(fix.copy(lat = 49.592003), fix, 17.02, 17.0))
    }

    @Test fun screenPuckRequiresTheActualViewportToMatchGuidance() {
        val fix = LatLng(49.592, 17.252)
        val insets = doubleArrayOf(0.0, 420.0, 0.0, 160.0)
        fun matches(zoom: Double, pitch: Double, bearing: Double, actualInsets: DoubleArray?) =
            NavGlide.matchesViewport(fix, fix, zoom, 17.0, pitch, 48.0, bearing, 0.0, actualInsets, insets)

        assertTrue(matches(17.02, 48.1, 359.2, insets))
        assertFalse(matches(12.0, 48.0, 0.0, insets))
        assertFalse(matches(17.0, 0.0, 0.0, insets))
        assertFalse(matches(17.0, 48.0, 45.0, insets))
        assertFalse(matches(17.0, 48.0, 0.0, null))
        assertFalse(matches(17.0, 48.0, 0.0, doubleArrayOf(0.0, 90.0, 0.0, 40.0)))
    }

    @Test fun nativeArrowMustProjectIntoThePaddedViewport() {
        val insets = doubleArrayOf(0.0, 450.0, 0.0, 150.0)
        assertTrue(NavGlide.matchesArrowPosition(200.0, 550.0, 400, 800, insets, 10.0))
        assertFalse(NavGlide.matchesArrowPosition(200.0, 400.0, 400, 800, insets, 10.0))
        assertFalse(NavGlide.matchesArrowPosition(-1.0, -1.0, 400, 800, insets, 10.0))
    }

    @Test fun freshGpsReanchorsTheArrowAfterStartingWithACachedLocation() {
        assertFalse(NavGlide.reliableFix(ageMs = 90_000, accuracy = 8.0))
        assertFalse(NavGlide.reliableFix(ageMs = 600, accuracy = 1_000.0))
        assertTrue(NavGlide.reliableFix(ageMs = 600, accuracy = 8.0))
        assertTrue(NavGlide.shouldReanchorAfterStartup(firstReliableFix = true, distance = 1_100.0, accuracy = 8.0))
        assertFalse(NavGlide.shouldReanchorAfterStartup(firstReliableFix = false, distance = 1_100.0, accuracy = 8.0))
        assertFalse(NavGlide.shouldReanchorAfterStartup(firstReliableFix = true, distance = 16.0, accuracy = 15.0))
    }

    @Test fun vehicleCanTurnRelativeToTheFollowingCameraAcrossNorth() {
        assertEquals(28.0, NavGlide.shortestTurn(350.0, 18.0))
        assertEquals(-28.0, NavGlide.shortestTurn(18.0, 350.0))
        assertEquals(0.0, NavGlide.shortestTurn(90.0, 90.0))
    }
    @Test fun recoveryCentersTheOverlayAndNativeCameraAtTheSamePoint() {
        for (height in listOf(160f, 520f, 800f)) {
            val center = height / 2
            assertEquals(center, NavGlide.carY(height, north = 0.3f, centered = true))
            assertEquals(center, NavGlide.carY(height, compact = true, centered = true))
            assertTrue(NavGlide.matchesArrowPosition(200.0, center.toDouble(), 400, height.toInt(), DoubleArray(4), 1.0))
        }
    }
}
