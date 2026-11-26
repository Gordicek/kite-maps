package app.kite

import app.kite.ui.shouldDismissSheet
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class MapSheetGestureTests {
    @Test fun mapCardStaysOpenForShortPullsAndDismissesWhenPulledBelowItsRestingPosition() {
        assertFalse(shouldDismissSheet(projectedHeight = 320f, lowestVisible = 330f))
        assertFalse(shouldDismissSheet(projectedHeight = 260f, lowestVisible = 330f))
        assertTrue(shouldDismissSheet(projectedHeight = 150f, lowestVisible = 330f))
        assertTrue(shouldDismissSheet(projectedHeight = 0f, lowestVisible = 330f))
    }
}
