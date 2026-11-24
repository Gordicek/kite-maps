package app.kite

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.kite.core.*

class TransitGuidance(val trip: TransitTrip, val destination: Place) {
    enum class Phase { WALK, BOARD, RIDE, COMPLETE }

    var legIndex by mutableIntStateOf(0)
        private set
    val leg: TransitLeg? get() = trip.legs.getOrNull(legIndex)
    var phase by mutableStateOf(Phase.WALK)
        private set
    var walking by mutableStateOf<Navigator?>(null)
    var walkingError by mutableStateOf(false)
    var boarding by mutableStateOf(false)
    var liveDeparture by mutableStateOf<Transitous.Departure?>(null)
    var overviewing by mutableStateOf(false)
    var following by mutableStateOf(true)
    var fix by mutableStateOf<Fix?>(null)
        private set
    var nextStop by mutableStateOf<TransitCall?>(null)
        private set
    var nearExit by mutableStateOf(false)
        private set
    var lastAlongAt by mutableStateOf<Long?>(null)
        private set
    private var tracker: TransitRide? = null
    private var departureMotion: TransitBoarding? = null
    private var toldExit = false
    private var closeFixes = 0
    private var boardedAt = 0L
    private var saidNext: TransitCall? = null
    private var walkingSampleStart: Fix? = null

    fun prepareLeg() {
        nearExit = false
        nextStop = null
        lastAlongAt = null
        walkingError = false
        boarding = false
        liveDeparture = null
        saidNext = null
        following = true
        tracker = null
        toldExit = false
        closeFixes = 0
        walkingSampleStart = null
        phase = when (leg?.isWalk) {
            true -> Phase.WALK
            false -> Phase.BOARD
            null -> Phase.COMPLETE
        }
        departureMotion = leg?.takeIf { phase == Phase.BOARD }?.let { ride ->
            (ride.calls.firstOrNull()?.at ?: ride.path.firstOrNull())?.let { TransitBoarding(ride.path, it) }
        }
        fix?.let { shouldAutoBoard(it) }
        if (phase == Phase.BOARD) announce("Board ${leg?.line} at ${leg?.fromName} toward ${leg?.headsign ?: leg?.toName}.")
        if (phase == Phase.COMPLETE) announce("You have arrived at ${destination.name}.")
    }

    fun advance() {
        legIndex++
        prepareLeg()
    }

    fun board(ride: TransitRide) {
        if (phase != Phase.BOARD) return
        boarding = false
        tracker = ride
        boardedAt = System.currentTimeMillis()
        phase = Phase.RIDE
        nextStop = tracker?.next
        following = true
        sayNext(nextStop, scheduled = false)
    }

    fun observe(f: Fix): Boolean {
        fix = f
        val ride = tracker ?: return false
        val update = ride.observe(f.coord, f.accuracy, f.timeMillis)
        lastAlongAt = ride.lastPositionAt
        nextStop = ride.next
        nearExit = ride.nearExit
        if (update.exitApproach && !toldExit) {
            warnExit(scheduled = false)
        } else if (update.nextChanged) sayNext(nextStop, scheduled = false)
        closeFixes = if (nearExit && f.accuracy in 0.0..75.0) closeFixes + 1 else 0
        return closeFixes >= 2 && (!f.speedKnown || f.speed < 4)
    }

    fun shouldAutoBoard(f: Fix): Boolean {
        if (phase != Phase.BOARD || boarding || f.ageMs !in 0..15_000) return false
        return departureMotion?.observe(f.coord, f.accuracy, f.speed, f.course, f.timeMillis, liveDeparture?.time ?: leg?.depart) == true
    }

    fun scheduledNext(time: Long, live: TransitLive? = null) = tracker?.scheduledNext(time, live)

    fun tick(now: Long, live: TransitLive?) {
        if (phase != Phase.RIDE || live?.cancelled == true || now - (lastAlongAt ?: boardedAt) <= 25_000) return
        val next = scheduledNext(now, live)
        val exit = leg?.calls?.lastOrNull()
        val arrival = live?.exit?.arrival ?: exit?.arrival ?: leg?.arrive
        if (next != null && next == exit && arrival != null && arrival - now in 0..75_000) warnExit(scheduled = true)
        else sayNext(next, scheduled = true)
    }

    fun sampleWalkingPace(f: Fix): Double? {
        if (phase != Phase.WALK || f.ageMs !in 0..5_000 || f.accuracy !in 0.0..18.0 || f.speed !in 0.65..2.2) {
            walkingSampleStart = null
            return null
        }
        val from = walkingSampleStart ?: run { walkingSampleStart = f; return null }
        val seconds = (f.elapsedMs - from.elapsedMs) / 1000.0
        if (seconds < 18) return null
        walkingSampleStart = f
        if (seconds > 40 || from.coord.distanceTo(f.coord) / seconds < 0.5) return null
        return (f.speed + from.speed) / 2
    }

    private fun warnExit(scheduled: Boolean) {
        if (toldExit) return
        val stop = leg?.toName?.takeIf(String::isNotBlank) ?: return
        toldExit = true
        val text = if (scheduled) "Scheduled exit coming up at $stop." else "Get off at the next stop, $stop."
        announce(text)
        if (AppSettings.transitExitAlerts) TransitReminders.exit(text)
    }

    private fun sayNext(call: TransitCall?, scheduled: Boolean) {
        if (call == null || call == saidNext) return
        saidNext = call
        announce(if (scheduled) "Scheduled next stop, ${call.name}." else "Next stop, ${call.name}.")
    }

    private fun announce(text: String) {
        if (AppSettings.voice && !AppSettings.voiceAlertsOnly && !Voice.onCall) Voice.say(text)
    }
}
