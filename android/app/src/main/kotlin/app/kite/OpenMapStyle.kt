package app.kite

import app.kite.core.FuelPrices
import app.kite.core.Place

enum class OpenPlaceGroup(val label: String, val icon: String, vararg val tags: String) {
    WATER("Drinking water", "osm-drinking_water", "drinking_water", "water_point", "water_well", "water_tap"),
    PARKING("Parking", "poi-parking", "parking", "parking_entrance", "parking_space"),
    TOILETS("Toilets", "osm-toilets", "toilets", "shower"),
    BIKE("Bicycle facilities", "osm-bicycle_parking", "bicycle", "bicycle_parking", "bicycle_rental", "bicycle_repair_station"),
    OUTDOORS("Benches, shelters and viewpoints", "poi-park", "bench", "shelter", "viewpoint", "picnic_site", "picnic_table", "park", "camp_site", "playground"),
    FOOD("Food and cafés", "poi-food", "restaurant", "cafe", "ice_cream", "food_court", "fast_food", "pub", "bar"),
    SHOP("Shops", "poi-shop", "shop", "supermarket", "bakery", "convenience", "mall", "clothes"),
    FUEL("Fuel and charging", "poi-gas", "fuel", "charging_station", "charging-station"),
    MEDICAL("Pharmacies and health", "poi-health", "pharmacy", "hospital", "clinic", "doctors", "dentist"),
    TRANSIT("Transit stops", "osm-transit", "bus", "bus_stop", "bus_station", "tram_stop", "rail", "railway", "station", "subway", "halt"),
    MORE("Other mapped places", "poi-default"),
}

object OpenMapStyle {
    const val TILEJSON = "https://tiles.openfreemap.org/planet"
    const val POIS = "kite-osm-pois"
    const val SOURCE = "kite-osm-tiles"
    const val HIKING = "kite-hiking"
    const val CYCLING = "kite-cycling"
    const val WALK_PATHS = "kite-walk-paths"
    const val BIKE_PATHS = "kite-bike-paths"

    val trailLayers = mapOf(HIKING to "hiking", CYCLING to "cycling")
    fun trailBrightness(dark: Boolean) = if (dark) 0.42 else 0.0

    val pathLayers = mapOf(
        WALK_PATHS to ("""["all",["==",["geometry-type"],"LineString"],["match",["get","subclass"],["path","footway","steps"],true,false],["!=",["get","foot"],"no"],["!=",["get","access"],"private"]]""" to 0xFF188858),
        BIKE_PATHS to ("""["all",["==",["geometry-type"],"LineString"],["any",["==",["get","subclass"],"cycleway"],["==",["get","bicycle"],"designated"]],["!=",["get","access"],"private"]]""" to 0xFF3276C6),
    )
    const val ATTRIBUTION = "© OpenStreetMap contributors · Waymarked Trails, CC BY-SA 3.0"
    fun trailTile(kind: String) = "https://tile.waymarkedtrails.org/$kind/{z}/{x}/{y}.png"

    val detailIcons = linkedMapOf(
        "drinking_water" to "water", "water_point" to "water", "water_tap" to "water", "water_well" to "water",
        "parking" to "parking", "parking_entrance" to "parking", "parking_space" to "parking",
        "bench" to "bench", "shelter" to "shelter", "viewpoint" to "viewpoint", "picnic_site" to "picnic",
        "picnic_table" to "picnic", "camp_site" to "camp", "toilets" to "toilets", "shower" to "toilets",
        "bicycle_repair_station" to "bike-repair", "bicycle_parking" to "bike", "bicycle_rental" to "bike",
        "charging_station" to "charging", "charging-station" to "charging", "cafe" to "coffee",
        "bakery" to "bakery", "pharmacy" to "pharmacy", "hospital" to "health", "bus_stop" to "bus",
        "bus_station" to "bus", "museum" to "culture", "library" to "library", "school" to "education",
        "playground" to "playground", "fire_station" to "fire", "police" to "police", "post_office" to "post",
        "recycling" to "recycling", "waste_basket" to "waste", "atm" to "bank", "bank" to "bank",
        "fountain" to "fountain", "hotel" to "hotel",
    )
    private val category = """["coalesce",["get","subclass"],["get","class"],""]"""
    private fun quote(value: String) = '"' + value + '"'
    private fun groupFor(field: String, otherwise: String): String =
        """["match",$field,${OpenPlaceGroup.entries.filter { it.tags.isNotEmpty() }.joinToString(",") { g ->
            g.tags.joinToString(",", "[", "]", transform = ::quote) + "," + quote(g.name)
        }},$otherwise]"""
    private val classField = """["coalesce",["get","class"],""]"""
    val group = groupFor(category, groupFor(classField, quote("MORE")))
    private val groupIcon = """["match",$group,${OpenPlaceGroup.entries.filter { it != OpenPlaceGroup.MORE }
        .joinToString(",") { quote(it.name) + "," + quote("open-${it.name.lowercase()}") }},"open-more"]"""
    val icon = """["match",$category,${detailIcons.entries.joinToString(",") { (tag, id) ->
        quote(tag) + "," + quote("open-$id")
    }},$groupIcon]"""
    fun filter(groups: Set<OpenPlaceGroup>): String {
        fun matches(values: Set<OpenPlaceGroup>): String {
            val names = values.joinToString(",") { '"' + it.name + '"' }
            return """["in",$group,["literal",[$names]]]"""
        }
        val enabled = matches(groups)
        val facilities = matches(setOf(OpenPlaceGroup.WATER, OpenPlaceGroup.TOILETS))
        val closeFacilities = matches(setOf(OpenPlaceGroup.WATER, OpenPlaceGroup.TOILETS,
            OpenPlaceGroup.PARKING, OpenPlaceGroup.BIKE, OpenPlaceGroup.TRANSIT))
        fun show(rank: Int, more: String = "false") =
            """["all",$enabled,["any",["<=",["number",["get","rank"],999],$rank],$more]]"""
        return """["step",["zoom"],${show(4)},14,${show(8)},15,${show(18, facilities)},
            16,${show(32, closeFacilities)},17,${show(68, closeFacilities)}]"""
    }

    fun visibleGroups(model: AppModel): Set<OpenPlaceGroup> = when {
        model.nav != null || model.cruise != null ->
            if (AppSettings.navigationPlaces && !navigationUsesGoogle()) AppSettings.navigationPlaceGroups else emptySet()
        model.transitGuidance == null && AppSettings.openMapPlaces && !model.searching && model.results.isEmpty() ->
            AppSettings.openPlaceGroups
        else -> emptySet()
    }

    fun navigationUsesGoogle(): Boolean =
        !AppSettings.noGoogle && AppSettings.navigationPlaceSource == NavigationPlaceSource.GOOGLE

    fun googleNavigationActive(model: AppModel): Boolean =
        (model.nav != null || model.cruise != null) && AppSettings.navigationPlaces &&
            AppSettings.navigationPlaceGroups.isNotEmpty() && navigationUsesGoogle()

    fun groupFor(place: Place): OpenPlaceGroup {
        val category = Place.fold(place.category.orEmpty()).replace('_', ' ')
        fun has(vararg labels: String) = labels.any(category::contains)
        return when {
            FuelPrices.isStation(place) || has("charging station", "fuel") -> OpenPlaceGroup.FUEL
            has("parking", "car park") -> if (has("bicycle", "bike")) OpenPlaceGroup.BIKE else OpenPlaceGroup.PARKING
            has("toilet", "restroom") -> OpenPlaceGroup.TOILETS
            has("drinking water", "water fountain") -> OpenPlaceGroup.WATER
            has("restaurant", "cafe", "coffee", "fast food", " pub") || category in setOf("bar", "pub") -> OpenPlaceGroup.FOOD
            has("pharmacy", "hospital", "clinic", "doctor", "dentist", "medical") -> OpenPlaceGroup.MEDICAL
            has("station", "transit stop", "bus stop") -> OpenPlaceGroup.TRANSIT
            has("bicycle", "bike") -> OpenPlaceGroup.BIKE
            has("park", "viewpoint", "camping", "scenic") -> OpenPlaceGroup.OUTDOORS
            has("store", "shop", "supermarket", "bakery", "mall", "market") -> OpenPlaceGroup.SHOP
            else -> OpenPlaceGroup.MORE
        }
    }

    private val googleQueries = mapOf(
        OpenPlaceGroup.WATER to "drinking water", OpenPlaceGroup.PARKING to "parking",
        OpenPlaceGroup.TOILETS to "public toilets", OpenPlaceGroup.BIKE to "bicycle parking",
        OpenPlaceGroup.OUTDOORS to "parks and viewpoints", OpenPlaceGroup.FOOD to "restaurants and cafes",
        OpenPlaceGroup.SHOP to "shops",
        OpenPlaceGroup.MEDICAL to "pharmacies and clinics", OpenPlaceGroup.TRANSIT to "bus stops",
        OpenPlaceGroup.MORE to "places",
    )

    fun navigationGoogleQueries(): List<String> =
        OpenPlaceGroup.entries.filter { it in AppSettings.navigationPlaceGroups }.flatMap { group ->
            if (group == OpenPlaceGroup.FUEL) listOf("gas stations", "EV charging stations") else listOfNotNull(googleQueries[group])
        }

    fun title(name: String?, subclass: String?, klass: String?): String =
        name?.takeIf { it.isNotBlank() } ?: (subclass ?: klass ?: "place").replace('_', ' ').replaceFirstChar(Char::titlecase)
}
