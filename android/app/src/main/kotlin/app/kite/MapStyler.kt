package app.kite

import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.*
import org.maplibre.android.style.layers.PropertyFactory.*

/**
 * Paints OpenFreeMap Liberty in Kite's colors: a calm Waze-like night palette and the classic day
 * look. While driving, [drive] strips the map down to roads and names. The same as the iPhone app.
 */
object MapStyler {
    class Palette(
        val land: Int, val water: Int, val park: Int, val pitch: Int, val landuse: Int, val landuseOpacity: Float,
        val building: Int, val buildingOutline: Int,
        val minor: Int, val major: Int, val motorway: Int, val service: Int, val path: Int,
        val casing: Int, val motorwayCasing: Int, val rail: Int, val airport: Int, val runway: Int,
        val label: Int?, val halo: Int?, val roadLabel: Int, val roadHalo: Int, val border: Int,
    )

    private fun c(hex: Long) = (0xFF000000 or hex).toInt()

    // Night: near-black land with quiet roads, so the teal route and the warnings are what you see.
    // Motorways a soft blue and A roads a soft green, like British road signs; smaller roads grey.
    val dark = Palette(
        land = c(0x12161D), water = c(0x15273B), park = c(0x15291F), pitch = c(0x15291F), landuse = c(0x161B23), landuseOpacity = 0.5f,
        building = c(0x1A2029), buildingOutline = c(0x202733),
        minor = c(0x29313D), major = c(0x2F4C3D), motorway = c(0x34507C), service = c(0x1F2630), path = c(0x1C222B),
        casing = c(0x12161D), motorwayCasing = c(0x12161D), rail = c(0x252C37), airport = c(0x161B23), runway = c(0x29313D),
        label = c(0xAEB7C4), halo = c(0x12161D), roadLabel = c(0xD5DBE5), roadHalo = c(0x12161D), border = c(0x5F6977),
    )

    // Day: white streets, with motorways and A roads tinted blue and green like the road signs.
    val light = Palette(
        land = c(0xF2F1EE), water = c(0x90DAEE), park = c(0xCFECCD), pitch = c(0xD3F8E2), landuse = c(0xF2F1EE), landuseOpacity = 1f,
        building = c(0xDDE1E7), buildingOutline = c(0xC4C9D1),
        minor = c(0xFFFFFF), major = c(0xD3EDCF), motorway = c(0xA9C5EE), service = c(0xFFFFFF), path = c(0xDCDFE4),
        casing = c(0xDADDE2), motorwayCasing = c(0x86A9DD), rail = c(0xC4C9D1), airport = c(0xE6E8EC), runway = c(0xD0D4DA),
        label = null, halo = null, roadLabel = c(0x5F6368), roadHalo = c(0xFFFFFF), border = c(0x9AA0A6),
    )

    fun apply(style: Style, isDark: Boolean, colors: MapPalette) {
        val standard = if (isDark) dark else light
        val p = if (colors.horizon || colors == MapPalette.default(isDark)) standard else {
            fun color(field: MapColor) = android.graphics.Color.parseColor(colors[field])
            val land = color(MapColor.LAND)
            val street = color(MapColor.STREETS)
            val parks = color(MapColor.PARKS)
            val buildings = color(MapColor.BUILDINGS)
            Palette(land, color(MapColor.WATER), parks, parks, land, standard.landuseOpacity,
                buildings, buildings, street, street, color(MapColor.HIGHWAYS), street, standard.path,
                standard.casing, standard.motorwayCasing, standard.rail, land, street,
                standard.label, standard.halo, standard.roadLabel, standard.roadHalo, standard.border)
        }
        for (layer in style.layers) {
            val id = layer.id
            if (id.startsWith("vela") || id.startsWith("hr-heat")) continue
            if (id == "natural_earth" || id == "road_area_pattern") { layer.setProperties(visibility(Property.NONE)); continue }
            when (layer) {
                is BackgroundLayer -> layer.setProperties(backgroundColor(p.land))
                is FillLayer -> when (id) {
                    "water" -> layer.setProperties(fillColor(p.water))
                    "park" -> layer.setProperties(fillColor(p.park), fillOutlineColor(p.park), fillOpacity(1f))
                    "landcover_grass", "landcover_wood", "landcover_wetland" -> {
                        runCatching { layer.setProperties(PropertyValue<String?>("fill-pattern", null)) }
                        layer.setProperties(fillColor(p.park), fillOutlineColor(p.park), fillOpacity(0.9f))
                    }
                    "landuse_pitch", "landuse_track" -> layer.setProperties(fillColor(p.pitch))
                    "building" -> layer.setProperties(fillColor(p.building), fillOutlineColor(p.buildingOutline))
                    "aeroway_fill" -> layer.setProperties(fillColor(p.airport))
                    else -> if (id.startsWith("landuse") || id.startsWith("landcover")) layer.setProperties(fillColor(p.landuse), fillOpacity(p.landuseOpacity))
                }
                is FillExtrusionLayer -> if (id == "building-3d") layer.setProperties(fillExtrusionColor(p.building))
                is LineLayer -> {
                    when {
                        id.startsWith("waterway") -> layer.setProperties(lineColor(p.water))
                        id == "park_outline" || id.startsWith("landcover") || id.startsWith("park") -> layer.setProperties(visibility(Property.NONE))
                        id.startsWith("aeroway") -> layer.setProperties(lineColor(p.runway))
                        id.startsWith("boundary") -> layer.setProperties(lineColor(p.border))
                        id.contains("rail") -> layer.setProperties(lineColor(p.rail))
                        !(id.startsWith("road_") || id.startsWith("bridge_") || id.startsWith("tunnel_")) -> {}
                        id.contains("casing") -> layer.setProperties(lineColor(if (id.contains("motorway")) p.motorwayCasing else p.casing))
                        id.contains("motorway") -> layer.setProperties(lineColor(p.motorway))
                        id.contains("trunk_primary") -> layer.setProperties(lineColor(p.major))
                        id.contains("service_track") -> layer.setProperties(lineColor(p.service))
                        id.contains("path_pedestrian") -> layer.setProperties(lineColor(p.path))
                        else -> layer.setProperties(lineColor(p.minor))
                    }
                }
                is SymbolLayer -> {
                    if (id.startsWith("highway-name")) {
                        layer.setProperties(textColor(p.roadLabel), textHaloColor(p.roadHalo), textHaloWidth(1.6f))
                    } else if (p.label != null && p.halo != null) {
                        layer.setProperties(textColor(p.label), textHaloColor(p.halo), textHaloWidth(1.1f))
                    }
                }
            }
        }
    }

    private fun clutter(id: String) =
        id.startsWith("poi_") || id == "building" || id == "building-3d" || id.contains("path_pedestrian") ||
            id == "highway-name-path" || id.startsWith("road_one_way_arrow") || id.endsWith("_hatching") ||
            id in setOf("landuse_pitch", "landuse_track", "landuse_cemetery", "landuse_hospital", "landuse_school",
                "aeroway_fill", "aeroway_runway", "aeroway_taxiway", "airport")

    private val savedGuidanceLabels = mutableMapOf<String, List<PropertyValue<*>>>()
    private val savedShieldSpacing = mutableMapOf<String, PropertyValue<*>>()
    private val savedShieldPadding = mutableMapOf<String, PropertyValue<*>>()
    private val savedLineOpacity = mutableMapOf<String, PropertyValue<*>>()

    /** Side streets and service roads: a little quieter while driving, so the roads that matter stand out. */
    private fun sideRoad(id: String) =
        (id.startsWith("road_") || id.startsWith("bridge_") || id.startsWith("tunnel_")) && (id.contains("minor") || id.contains("service_track"))

    /** Forget saved values after a new style loads. */
    fun reset() { savedGuidanceLabels.clear(); savedShieldSpacing.clear(); savedShieldPadding.clear(); savedLineOpacity.clear() }

    /** Strips the map to roads, water, parks and big road names while navigating, and puts it back after. */
    fun drive(style: Style, on: Boolean) {
        for (layer in style.layers) {
            val id = layer.id
            if (id.startsWith("vela") || id.startsWith("hr-heat")) continue
            if (clutter(id)) { layer.setProperties(visibility(if (on) Property.NONE else Property.VISIBLE)); continue }
            if (layer is SymbolLayer && id.contains("shield")) {
                if (savedShieldSpacing[id] == null) {
                    savedShieldSpacing[id] = layer.symbolSpacing
                    savedShieldPadding[id] = layer.iconPadding
                }
                if (on) layer.setProperties(symbolSpacing(900f), iconPadding(30f))
                else { restore(layer, savedShieldSpacing[id]!!); restore(layer, savedShieldPadding[id]!!) }
                continue
            }
            if (layer is LineLayer && sideRoad(id)) {
                if (savedLineOpacity[id] == null) savedLineOpacity[id] = layer.lineOpacity
                if (on) layer.setProperties(lineOpacity(0.6f)) else restore(layer, savedLineOpacity[id]!!)
                continue
            }
        }
    }

    fun guidanceLabels(style: Style, on: Boolean, dark: Boolean) {
        if (!on) {
            savedGuidanceLabels.forEach { (id, properties) ->
                style.getLayer(id)?.let { layer -> properties.forEach { restore(layer, it) } }
            }
            savedGuidanceLabels.clear()
            return
        }
        for (layer in style.layers.filterIsInstance<SymbolLayer>()) {
            val place = layer.sourceLayer == "place"
            val road = layer.sourceLayer == "transportation_name" && !layer.id.contains("shield")
            if (!place && !road) continue
            savedGuidanceLabels.getOrPut(layer.id) { listOf(layer.textSize, layer.textHaloBlur) }
            val size = org.maplibre.android.style.expressions.Expression.raw(if (place)
                """["interpolate",["linear"],["zoom"],6,13,13,15,18,18]"""
            else """["interpolate",["linear"],["zoom"],11,11,15,14,18,16]""")
            layer.setProperties(textSize(size), textColor(c(if (dark) 0xF5F7FE else 0x233043)),
                textHaloColor(c(if (dark) 0x101826 else 0xF7F9FC)), textHaloWidth(if (place) 1.75f else 1.5f),
                textHaloBlur(0.3f))
        }
    }

    /**
     * Puts back a value read from the style. One the style gave as an expression (a size that grows
     * with zoom, say) comes back as JSON, which the map only takes once turned into an expression again.
     */
    private fun restore(layer: Layer, v: PropertyValue<*>) {
        val value = v.value
        runCatching {
            when (value) {
                null -> layer.setProperties(PropertyValue<Any?>(v.name, null))
                is Number, is String, is Boolean, is Array<*> -> layer.setProperties(v)
                else -> {
                    val json = value.toString()
                    if (json.startsWith("[")) layer.setProperties(PropertyValue(v.name, org.maplibre.android.style.expressions.Expression.raw(json)))
                    else json.toFloatOrNull()?.let { layer.setProperties(PropertyValue(v.name, it)) }
                }
            }
        }
    }
}
