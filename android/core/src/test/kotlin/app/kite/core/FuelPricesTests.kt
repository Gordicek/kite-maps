package app.kite.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FuelPricesTests {
    @Test fun onlyUsesPriceLikeGoogleLabels() {
        assertEquals("$3.95/Regular", FuelPrices.googleLabel("$3.95/Regular"))
        assertEquals("36,90 Kč/l", FuelPrices.googleLabel("36,90\u00a0Kč/l"))
        assertNull(FuelPrices.googleLabel("Diesel B7"))
        assertNull(FuelPrices.googleLabel("Open until 10 pm"))
    }

    @Test fun onlyMatchesRecentAndUnambiguousPrices() {
        val stations = FuelPrices.readStations(
            """
            brand,lat,lng,e10,e5,b7s,b7p,updated
            "A, Fuels",51.50000,-0.12000,153.9,,160,,1785000000
            B,51.50000,-0.12060,157,,162,,1785000000
            C,52.60,-1.3,999,,,,1785000000
            """.trimIndent().reader(),
        )
        assertEquals(2, stations.size)
        val now = 1785000000 + 100L
        assertEquals(listOf("E10 153.9 p/L", "Diesel 160 p/L"), FuelPrices.match(LatLng(51.5, -0.12), stations, now)?.labels)
        assertNull(FuelPrices.match(LatLng(51.5, -0.1203), stations, now))
        assertNull(FuelPrices.match(LatLng(51.5, -0.12), stations, now + 8 * 86400))
    }

    @Test fun stationsKeepOneListingPerForecourtAndStartWithTheNearest() {
        val here = LatLng(49.6, 17.25)
        fun station(id: String, name: String, distance: Double, source: String, price: String? = null, open: Boolean? = null) =
            Place(id, name, LatLng(here.lat, here.lng + distance), source, category = "Fuel",
                fuelStation = true, fuelPrice = price, openNow = open)
        val duplicate = station("osm-1", "Shell", 0.00052, "osm")
        val primary = station("google-1", "Shell", 0.0005, "google", price = "36,90 Kč/l", open = true)
        val otherEntrance = station("osm-2", "Shell Express", 0.00065, "osm")
        val nearby = station("osm-3", "Orlen", 0.0001, "osm")
        val farther = station("google-2", "Shell", 0.0040, "google", open = true)
        assertEquals(listOf("osm-3", "google-1", "google-2"),
            FuelPrices.rankedStations(listOf(farther, duplicate, nearby, otherEntrance, primary), here).map { it.id })
    }

}
