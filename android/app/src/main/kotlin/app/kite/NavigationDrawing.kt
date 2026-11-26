package app.kite

import app.kite.core.LaneApproach
import app.kite.core.Maneuver
import app.kite.core.ManeuverType
import kotlin.math.pow

internal object NavigationDrawing {
    const val TURN_HEAD_SCALE = 0.62

    fun isExitApproach(turn: Maneuver): Boolean =
        turn.type == ManeuverType.RAMP_LEFT || turn.type == ManeuverType.RAMP_RIGHT ||
            turn.type in setOf(ManeuverType.KEEP_LEFT, ManeuverType.KEEP_RIGHT, ManeuverType.FORK_LEFT, ManeuverType.FORK_RIGHT) &&
            (turn.instruction.contains("exit", ignoreCase = true) || turn.instruction.contains("slip road", ignoreCase = true))

    /** Use the lane approach when available, otherwise give a speed-appropriate lead on ramps. */
    fun arrowStart(turn: Maneuver, lanes: List<LaneApproach>, speed: Double): Double {
        val at = turn.alongMeters
        if (!isExitApproach(turn)) return (at - 50).coerceAtLeast(0.0)
        val lead = (speed * 10).coerceIn(145.0, 330.0)
        return minOf(at - lead, exitLanes(turn, lanes)?.alongMeters ?: at).coerceAtLeast(0.0)
    }

    fun exitLanes(turn: Maneuver, lanes: List<LaneApproach>): LaneApproach? =
        if (isExitApproach(turn)) lanes.lastOrNull { candidate ->
            turn.alongMeters - candidate.alongMeters in 35.0..380.0 &&
                candidate.lanes.any { it.valid } && candidate.lanes.any { !it.valid }
        } else null

    private fun channels(color: Long) = listOf(16, 8, 0).map { ((color shr it) and 255).toInt() }

    internal fun luminance(color: Long): Double {
        fun linear(value: Int): Double {
            val channel = value / 255.0
            return if (channel <= 0.04045) channel / 12.92 else ((channel + 0.055) / 1.055).pow(2.4)
        }
        val (r, g, b) = channels(color).map(::linear)
        return .2126 * r + .7152 * g + .0722 * b
    }

    /** Lift the same operator color only as much as needed to read against dark map surfaces. */
    fun nightTransit(color: Long): Long {
        val source = color and 0xFFFFFF
        val minimum = (luminance(0x1B2635L) + 0.05) * 4.5 - 0.05
        if (luminance(source) >= minimum) return source
        val rgb = channels(source)
        return (1..20).asSequence().map { step ->
            rgb.fold(0L) { acc, value -> (acc shl 8) or (value + (255 - value) * step / 20).toLong() }
        }.first { luminance(it) >= minimum }
    }
}
