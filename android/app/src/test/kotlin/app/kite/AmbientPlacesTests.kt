package app.kite

import app.kite.core.LatLng
import app.kite.core.Place
import kotlin.test.assertEquals
import org.junit.Test

class AmbientPlacesTests {
    private val center = LatLng(49.6, 17.25)

    @Test fun refreshPreservesPublishedPinsAndAddsNewOnesWithStableRanks() {
        val cafe = Place("cafe", "Café", center, "google", category = "cafe")
        val market = Place("market", "Market", LatLng(49.603, 17.257), "google", reviewCount = 175)
        val shiftedCafe = cafe.copy(location = LatLng(49.6001, 17.2501), reviewCount = 30)
        val result = AmbientPlaces.merge(listOf(cafe), listOf(shiftedCafe, market), center, 1500.0, 5)

        assertEquals(listOf("market", "cafe"), result.map { it.id })
        assertEquals(center, result.last().location)
        assertEquals(1, AmbientPlaces.rank(result.last()))
        assertEquals(result, AmbientPlaces.merge(result, listOf(market), center, 1500.0, 5))
    }

    @Test fun movementRemovesPinsOnlyWhenTheyLeaveTheBufferedArea() {
        val kept = Place("kept", "Parking", center, "google")
        val outside = Place("outside", "Park", LatLng(49.7, 17.35), "google")
        val duplicate = kept.copy(id = "same parking")
        assertEquals(listOf(kept), AmbientPlaces.merge(listOf(kept, outside), listOf(duplicate), center, 2000.0, 5))
    }
}
