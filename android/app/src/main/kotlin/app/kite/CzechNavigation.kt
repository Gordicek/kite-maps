package app.kite

import app.kite.core.Maneuver
import app.kite.core.ManeuverType

/** Czech directions come from the maneuver, so names and exit numbers survive a provider change. */
internal fun Maneuver.czechInstruction(): String {
    val name = road?.trim()?.takeIf { it.isNotBlank() }
    val action = when (type) {
        ManeuverType.DEPART -> if (name == null) "Vyrazte po trase" else "Pokračujte po ulici $name"
        ManeuverType.ARRIVE -> "Dorazili jste do cíle"
        ManeuverType.STRAIGHT -> "Pokračujte rovně"
        ManeuverType.UNKNOWN -> "Pokračujte po trase"
        ManeuverType.TURN_LEFT -> "Odbočte vlevo"
        ManeuverType.TURN_RIGHT -> "Odbočte vpravo"
        ManeuverType.SLIGHT_LEFT -> "Zahněte mírně vlevo"
        ManeuverType.SLIGHT_RIGHT -> "Zahněte mírně vpravo"
        ManeuverType.SHARP_LEFT -> "Zahněte ostře vlevo"
        ManeuverType.SHARP_RIGHT -> "Zahněte ostře vpravo"
        ManeuverType.UTURN -> "Otočte se"
        ManeuverType.MERGE -> "Zařaďte se do provozu"
        ManeuverType.RAMP_LEFT -> "Použijte nájezd vlevo"
        ManeuverType.RAMP_RIGHT -> "Použijte nájezd vpravo"
        ManeuverType.FORK_LEFT, ManeuverType.KEEP_LEFT -> "Držte se vlevo"
        ManeuverType.FORK_RIGHT, ManeuverType.KEEP_RIGHT -> "Držte se vpravo"
        ManeuverType.ROUNDABOUT -> when {
            leavesRoundabout -> "Vyjeďte z kruhového objezdu"
            exit != null -> "Na kruhovém objezdu použijte ${exit}. výjezd"
            else -> "Vjeďte na kruhový objezd"
        }
    }
    return if (name != null && type != ManeuverType.DEPART && type != ManeuverType.ARRIVE) "$action směrem na $name" else action
}

internal fun announcedIn(distance: Double, instruction: String): String =
    kt("In ${Fmt.spokenDistance(distance)}, $instruction", "Za ${Fmt.spokenDistance(distance)} $instruction")
