package app.kite.core

import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertEquals

class IndoorMapsTests {
    @Test fun keepsMixedFloorsAndNamedFacilities() {
        val plan = IndoorMaps.parsePlan("""
            {"elements":[
              {"type":"node","lat":50.0,"lon":14.0,"tags":{"level":"-1;0;0.5","highway":"elevator"}},
              {"type":"way","geometry":[{"lat":50.0,"lon":14.0},{"lat":50.001,"lon":14.001}],
                "tags":{"level":"1","indoor":"corridor"}},
              {"type":"node","lat":50.0,"lon":14.0,"tags":{"level":"0","amenity":"toilets"}},
              {"type":"node","lat":50.0,"lon":14.0,"tags":{"level":"2","shop":"clothes","access":"private"}}
            ]}
        """.trimIndent().toByteArray())
        assertEquals(listOf("1", "0.5", "0", "-1"), plan.floors)
        assertEquals(listOf("Lift", null, "Toilets"), plan.features.map { it.name })
        assertEquals(2, plan.features[1].geometry.size)
    }

    @Test fun resolvesNodeReferencesFromTheSmallOsmMapEndpoint() {
        val plan = IndoorMaps.parsePlan("""
            {"elements":[
              {"type":"node","id":10,"lat":52.5,"lon":13.37},
              {"type":"node","id":11,"lat":52.501,"lon":13.37},
              {"type":"way","nodes":[10,11],"tags":{"level":"-1","indoor":"corridor"}},
              {"type":"way","nodes":[10,11],"tags":{"level":"99","highway":"residential"}}
            ]}
        """.trimIndent().toByteArray())
        assertEquals(listOf("-1"), plan.floors)
        assertEquals(listOf(LatLng(52.5, 13.37), LatLng(52.501, 13.37)), plan.features.single().geometry)
    }
    @Test fun indoorGuideFollowsConnectedCorridorsOnTheSelectedFloor() {
        val a = LatLng(49.0, 17.0)
        val b = LatLng(49.0002, 17.0)
        val c = LatLng(49.0002, 17.0004)
        val floors = setOf("0")
        val corridor = IndoorMaps.Feature(floors, "corridor", null, listOf(a, b, c))
        val shop = IndoorMaps.Feature(floors, "shop", "Shop", listOf(c))
        val plan = IndoorMaps.Plan(listOf(corridor, shop))
        val guide = assertNotNull(IndoorMaps.walkingGuide(plan, "0", a, shop))
        assertEquals(listOf(a, b, c), guide)
        assertNull(IndoorMaps.walkingGuide(plan, "1", a, shop))
        val disconnected = shop.copy(geometry = listOf(LatLng(49.001, 17.0)))
        assertNull(IndoorMaps.walkingGuide(plan, "0", a, disconnected))
    }
}
