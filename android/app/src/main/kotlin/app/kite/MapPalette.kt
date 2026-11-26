package app.kite

enum class MapColor(val label: String, val styleKey: String?) {
    LAND("Land", "colorLand"),
    WATER("Water", "colorWater"),
    PARKS("Parks", "colorGreenspace"),
    BUILDINGS("Buildings", "colorBuildings"),
    STREETS("Streets", "colorRoads"),
    HIGHWAYS("Highways", "colorMotorways"),
    ROUTE("Route", null),
}

data class MapPalette(val colors: List<String>, val horizon: Boolean = false) {
    internal fun wasFixedExpressive(): Boolean = this == oldExpressiveDay || this == oldExpressiveNight

    fun encode(): String = (if (horizon) "horizon," else "") + colors.joinToString(",")

    operator fun get(field: MapColor): String = colors[field.ordinal]

    fun change(field: MapColor, color: String): MapPalette = copy(colors = colors.mapIndexed { index, old ->
        if (index == field.ordinal) color else old
    })

    companion object {
        private val colorPattern = Regex("#[0-9A-Fa-f]{6}")
        fun isColor(s: String) = colorPattern.matches(s)
        fun decode(s: String?): MapPalette? {
            val horizon = s?.startsWith("horizon,") == true
            val colors = s?.removePrefix("horizon,")?.split(',') ?: return null
            return if (colors.size == MapColor.entries.size && colors.all(::isColor)) MapPalette(colors, horizon) else null
        }
        private fun palette(vararg colors: String) = MapPalette(colors.map { "#$it" })
        private val oldExpressiveDay = palette("FAF2F9", "ABD2EA", "C5DCCB", "DED2E5", "FFFBFF", "E6BAF8", "6D3AB4")
        private val oldExpressiveNight = palette("2D2439", "152C48", "2C463D", "776484", "69607D", "946CB3", "D8BAFF")
        val day = linkedMapOf(
            "Kite Standard" to palette("F0EEE7", "A3CFE1", "BED7B5", "D9D3CB", "FFFFFF", "EFC581", "079A9A"),
            "Meadow" to palette("F3EEE2", "A5CCD1", "B0CBA4", "D7C8B5", "FFF8E8", "E7B477", "197665"),
            "Alpine" to palette("EDF0F4", "A0C4E8", "BBD2CC", "D3DBE7", "FFFFFF", "C6BBED", "4763C7"),
        )
        // The LUT supplies water, parks and roads at their Standard colors unless explicitly edited.
        val horizonNight = palette("9FABBD", "183041", "243832", "EEF4FF", "454F60", "6F7787", "60D6CC").copy(horizon = true)
        val night = linkedMapOf(
            "Kite Standard" to horizonNight,
            "Afterglow" to palette("11141B", "183041", "243832", "242C39", "454F60", "6F7787", "60D6CC"),
            "Moonlit" to palette("6C7F9C", "294363", "465C59", "C5CCE9", "70839C", "9C81B3", "67E5CB"),
            "Twilight" to palette("202B40", "132943", "24483F", "39445A", "48556E", "77658C", "57DDCA"),
            "Midnight" to palette("172330", "101F3D", "263D41", "2A3C56", "394E65", "576F9D", "75CFFF"),
            "Ember" to palette("302A35", "202635", "344841", "4E4048", "5C4B57", "946D5F", "FFC190"),
        )
        const val EXPRESSIVE_LABEL = "Material 3 Expressive"

        fun presets(dark: Boolean, wallpaper: MapPalette? = null): Map<String, MapPalette> {
            val base = if (dark) night else day
            return if (wallpaper == null) base else base + (EXPRESSIVE_LABEL to wallpaper)
        }

        fun default(dark: Boolean) = presets(dark).values.first()
    }
}

internal fun indoorFill(dark: Boolean) = if (dark) {
    """["case",["==",["get","picked"],true],"#285D57",["match",["get","kind"],"footprint","#161B23","level","#171C25","corridor","#2B3441","toilets","#303651","#242C39"]]"""
} else {
    """["case",["==",["get","picked"],true],"#B1DEDB",["match",["get","kind"],"footprint","#F4F2EF","level","#F4F2EF","corridor","#FFFFFF","toilets","#D9DEF6","#E1EBEA"]]"""
}
