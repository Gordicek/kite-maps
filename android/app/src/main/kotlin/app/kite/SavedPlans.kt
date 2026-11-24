package app.kite

import android.net.Uri
import app.kite.core.*
import java.util.UUID

/** An editable itinerary. The rendered routes are requested again when it is opened. */
data class SavedPlan(
    val name: String,
    val points: List<Place>,
    val mode: TravelMode,
    val transit: Boolean = false,
    val id: String = UUID.randomUUID().toString(),
    val transitDeparture: Long? = null,
    val transitLines: List<String> = emptyList(),
    val reminderAt: Long? = null,
) {
    fun toMap() = mapOf("id" to id, "name" to name, "points" to points.map(PlaceCodec::toMap),
        "mode" to mode.name, "transit" to transit, "transitDeparture" to transitDeparture,
        "transitLines" to transitLines, "reminderAt" to reminderAt)
    fun shareText(): String {
        fun coords(p: Place) = "${p.location.lat.plain()},${p.location.lng.plain()}"
        val builder = Uri.parse("https://www.google.com/maps/dir/").buildUpon()
            .appendQueryParameter("api", "1")
            .appendQueryParameter("origin", coords(points.first()))
            .appendQueryParameter("destination", coords(points.last()))
            .appendQueryParameter("travelmode", if (transit) "transit" else when (mode) {
                TravelMode.DRIVE -> "driving"
                TravelMode.BIKE -> "bicycling"
                TravelMode.WALK -> "walking"
            })
        val via = points.drop(1).dropLast(1)
        if (via.isNotEmpty()) builder.appendQueryParameter("waypoints", via.joinToString("|", transform = ::coords))
        val itinerary = points.mapIndexed { i, point -> "${i + 1}. ${point.name} (${coords(point)})" }
        return (listOf(name) + itinerary + builder.build().toString()).joinToString("\n")
    }

    companion object {
        fun fromMap(m: Map<String, Any?>): SavedPlan? {
            val name = m["name"] as? String ?: return null
            val points = m["points"].asMaps()?.mapNotNull(PlaceCodec::fromMap).orEmpty()
            val id = m["id"] as? String ?: return null
            if (points.size !in 2..10) return null
            val mode = TravelMode.entries.firstOrNull { it.name == m["mode"] } ?: TravelMode.DRIVE
            return SavedPlan(name, points, mode, transit = m["transit"] == true, id = id,
                transitDeparture = (m["transitDeparture"] as? Number)?.toLong(),
                transitLines = m["transitLines"].asList().orEmpty().filterIsInstance<String>(),
                reminderAt = (m["reminderAt"] as? Number)?.toLong())
        }
    }
}

internal fun TransitTrip.savedLineKeys(): List<String> = rides.map { "${it.mode}:${it.line}" }

data class PlaceFolder(val name: String, val members: Set<String> = emptySet(), val id: String = UUID.randomUUID().toString()) {
    fun toMap() = mapOf("id" to id, "name" to name, "members" to members.toList())
    companion object {
        fun fromMap(m: Map<String, Any?>): PlaceFolder? {
            return PlaceFolder(m["name"] as? String ?: return null,
                m["members"].asList().orEmpty().filterIsInstance<String>().toSet(), m["id"] as? String ?: return null)
        }
    }
}
