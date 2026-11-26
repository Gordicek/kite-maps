package app.kite

import app.kite.core.LatLng
import app.kite.core.Place

internal object AmbientPlaces {
    fun reviewed(place: Place): Boolean = place.rating != null || (place.reviewCount ?: 0) > 0

    /** Coarse ranks are independent of camera position and the order in which requests finish. */
    fun rank(place: Place): Int = when {
        (place.reviewCount ?: 0) >= 150 -> 0
        (place.reviewCount ?: 0) >= 15 -> 1
        reviewed(place) -> 2
        else -> 3
    }

    /** Keep published pins within the buffered area, adding new ones and deduplicating repeated searches. */
    fun merge(retained: List<Place>, fresh: List<Place>, center: LatLng, radius: Double, capacity: Int): List<Place> {
        val byId = fresh.groupBy { it.id }.mapValues { (_, places) -> places.minBy(::rank) }
        val visible = ArrayList<Place>(capacity)
        val seen = HashSet<String>()
        fun add(place: Place, near: Boolean = place.location.distanceTo(center) <= radius) {
            if (near && visible.size < capacity && seen.add(place.id) && visible.none { it.matchesNearby(place) }) visible += place
        }
        for (old in retained) {
            val update = byId[old.id]?.takeIf { rank(it) <= rank(old) && it.location.distanceTo(old.location) < 70 }
            add(update?.copy(location = old.location) ?: old)
        }
        byId.values.asSequence().map { it to it.location.distanceTo(center) }.filter { it.second <= radius }
            .sortedWith(compareBy<Pair<Place, Double>> { rank(it.first) }.thenBy { it.second }.thenBy { it.first.id })
            .forEach { (place) -> add(place, near = true) }
        return visible.sortedWith(compareBy(::rank).thenBy { it.id })
    }
}
