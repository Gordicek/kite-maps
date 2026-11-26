package app.kite

import app.kite.core.Box
import app.kite.core.Json
import app.kite.core.asList
import app.kite.core.num
import java.text.Collator
import kotlin.math.*

/** Catalogue bounds from Geofabrik extracts and Eurostat GISCO NUTS 2024 (Czech regions). */
internal object OfflineRegions {
    data class Region(val id: String, val parent: String?, val name: String, val box: Box) {
        val displayName: String get() {
            if (!czechUi) return name
            if (parent == null) return when (id) {
                "africa" -> "Afrika"
                "asia" -> "Asie"
                "australia-oceania" -> "Austrálie a Oceánie"
                "central-america" -> "Střední Amerika"
                "europe" -> "Evropa"
                "north-america" -> "Severní Amerika"
                "south-america" -> "Jižní Amerika"
                "russia" -> "Rusko"
                else -> name
            }
            return countryNames[name] ?: name
        }
        val maxZoom: Int get() = zoomFor(box)
        val estimate: Long get() = tileCount(box, maxZoom) * ESTIMATED_BYTES_PER_TILE
    }

    private val countryNames by lazy {
        java.util.Locale.getISOCountries().associate { country ->
            val locale = java.util.Locale.Builder().setRegion(country).build()
            locale.getDisplayCountry(java.util.Locale.ENGLISH) to locale.getDisplayCountry(java.util.Locale.forLanguageTag("cs-CZ"))
        } + mapOf("Czech Republic" to "Česko", "United States of America" to "Spojené státy",
            "Great Britain" to "Velká Británie")
    }

    val catalogue: List<Region> by lazy {
        val input = KiteApp.app.assets.open("offline-regions.json").use { it.readBytes() }
        val collator = Collator.getInstance(AppSettings.language.locale)
        Json.parse(input).asList().orEmpty().mapNotNull { item ->
            val columns = item.asList()?.takeIf { it.size == 7 } ?: return@mapNotNull null
            val id = columns[0] as? String ?: return@mapNotNull null
            val name = columns[2] as? String ?: return@mapNotNull null
            val (s, w, n, e) = columns.drop(3).mapNotNull { it.num() }.takeIf { it.size == 4 } ?: return@mapNotNull null
            Region(id, columns[1] as? String, name, Box(s, w, n, e))
        }.sortedWith { a, b -> collator.compare(a.displayName, b.displayName) }
    }

    const val VIEWPORT_ZOOM = 15
    const val MAX_VIEWPORT_TILES = 7_000L
    private const val ESTIMATED_BYTES_PER_TILE = 52_000L

    fun viewportEstimate(box: Box): Long = tileCount(box, VIEWPORT_ZOOM) * ESTIMATED_BYTES_PER_TILE

    fun tileCount(box: Box, maxZoom: Int): Long {
        fun mercator(lat: Double): Double = asinh(tan(lat.coerceIn(-83.0, 83.0) * PI / 180)) / (2 * PI)
        val width = (box.e - box.w) / 360
        val height = mercator(box.n) - mercator(box.s)
        if (width <= 0 || height <= 0) return 0
        return (0..maxZoom).sumOf { z ->
            val side = 1 shl z
            ((ceil(width * side) + 1) * (ceil(height * side) + 1)).toLong()
        }
    }

    private fun zoomFor(box: Box): Int =
        (15 downTo 4).firstOrNull { z -> tileCount(box, z) <= 5_000 } ?: 4
}
