package app.kite

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CompassHeadingTests {
    @Test fun flatDeviceUsesItsTopEdge() {
        assertEquals(90.0, assertNotNull(PhoneCompass.displayAzimuth(1f, 0f, 0f, 0f)), 0.001)
        assertEquals(0.0, assertNotNull(PhoneCompass.displayAzimuth(0f, 1f, 0f, 0f)), 0.001)
    }

    @Test fun uprightDeviceUsesTheDirectionThroughTheBackOfThePhone() {
        assertEquals(-90.0, assertNotNull(PhoneCompass.displayAzimuth(0.02f, 0.01f, -1f, 0f)), 0.001)
        assertEquals(0.0, assertNotNull(PhoneCompass.displayAzimuth(0f, -0.02f, 0f, 1f)), 0.001)
        assertNull(PhoneCompass.displayAzimuth(0f, 0f, 0f, 0f))
    }
}
