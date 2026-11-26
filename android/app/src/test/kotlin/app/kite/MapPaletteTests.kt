package app.kite

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class MapPaletteTests {
    @Test fun previousNightPaletteSurvivesUpgrade() {
        val saved = "#11141B,#183041,#243832,#242C39,#454F60,#6F7787,#60D6CC"
        val restored = requireNotNull(MapPalette.decode(saved))
        assertFalse(restored.horizon)
        assertEquals(saved, restored.encode())
    }

    @Test fun expressiveMapUsesTheSystemAccentInLightAndDarkArt() {
        val light = lightColorScheme(primary = Color(0xFFAA4300), primaryContainer = Color(0xFFFFDBC8)).mapArtwork(false)
        val dark = darkColorScheme(primary = Color(0xFFFFB68C), primaryContainer = Color(0xFF74320C)).mapArtwork(true)
        assertFalse(light.horizon)
        assertFalse(dark.horizon)
        assertEquals("#AA4300", light[MapColor.ROUTE])
        assertEquals("#FFB68C", dark[MapColor.ROUTE])
        assertNotEquals(light[MapColor.HIGHWAYS], dark[MapColor.HIGHWAYS])
        assertEquals(dark, MapPalette.decode(dark.encode()))
        assertEquals(light, MapPalette.presets(false, light)[MapPalette.EXPRESSIVE_LABEL])
        assertTrue(MapPalette.default(dark = true).horizon)
    }

    @Test fun bothOldExpressivePreferencesMigrateToDynamicColor() {
        assertEquals(InterfacePalette.EXPRESSIVE, InterfacePalette.fromPreference("WALLPAPER"))
        assertEquals(InterfacePalette.EXPRESSIVE, InterfacePalette.fromPreference("EXPRESSIVE"))
        assertEquals(InterfacePalette.KITE, InterfacePalette.fromPreference("KITE"))
        val obsolete = MapPalette.decode("#FAF2F9,#ABD2EA,#C5DCCB,#DED2E5,#FFFBFF,#E6BAF8,#6D3AB4")
        assertTrue(requireNotNull(obsolete).wasFixedExpressive())
    }

    @Test fun editedHorizonPaletteKeepsItsBaseTheme() {
        val night = MapPalette.default(dark = true)
        val edited = night.change(MapColor.ROUTE, "#AABBCC")
        assertTrue(edited.horizon)
        assertEquals("#9FABBD", edited[MapColor.LAND])
        assertEquals("#EEF4FF", edited[MapColor.BUILDINGS])
        assertEquals(edited, MapPalette.decode(edited.encode()))
    }
}
