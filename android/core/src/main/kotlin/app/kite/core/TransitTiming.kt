package app.kite.core

/** A walking transfer's time after subtracting the required walk from the live connection gap. */
data class TransferTiming(val afterLeg: Int, val station: String, val bufferSeconds: Int, val kind: Kind) {
    enum class Kind { TIGHT, MISSED, CANCELLED }
}

const val DEFAULT_WALKING_PACE = 1.3

fun TransitLeg.walkingSeconds(paceMetersPerSecond: Double?): Double =
    if (paceMetersPerSecond != null && isWalk && distanceMeters > 0) {
        val personalized = distanceMeters / paceMetersPerSecond.coerceIn(0.65, 2.2)
        // Preserve the planner's allowance for stairs, crossings and station entrances.
        personalized + (durationSeconds - distanceMeters / DEFAULT_WALKING_PACE).coerceAtLeast(0.0)
    } else durationSeconds

fun TransitTrip.transferWarnings(live: Map<String, TransitLive>, walkingPace: Double? = null): List<TransferTiming> {
    val rides = legs.indices.filter { !legs[it].isWalk }
    return rides.zipWithNext().mapNotNull { (a, b) ->
        val incoming = legs[a]
        val outgoing = legs[b]
        val from = live[incoming.tripId]
        val to = live[outgoing.tripId]
        val station = incoming.toName.ifBlank { outgoing.fromName }
        val cancelled = from?.cancelled == true || incoming.cancelled || to?.cancelled == true || outgoing.cancelled
        if (cancelled) return@mapNotNull TransferTiming(a, station, 0, TransferTiming.Kind.CANCELLED)
        val arrival = from?.exit?.arrival ?: incoming.arrive ?: return@mapNotNull null
        val departure = to?.board?.departure ?: outgoing.depart ?: return@mapNotNull null
        val walk = legs.subList(a + 1, b).sumOf { it.walkingSeconds(walkingPace) }
        val buffer = kotlin.math.floor((departure - arrival) / 1000.0 - walk).toInt()
        when {
            buffer < 0 -> TransferTiming(a, station, buffer, TransferTiming.Kind.MISSED)
            buffer < 180 -> TransferTiming(a, station, buffer, TransferTiming.Kind.TIGHT)
            else -> null
        }
    }
}
