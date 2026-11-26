@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.kite.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.kite.*
import app.kite.core.Place
import app.kite.core.TransitCall
import app.kite.core.TransitLeg
import app.kite.core.TransitLive
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
import java.util.Date

@Composable
fun TransitNavigationOverlay(model: AppModel, journey: TransitGuidance, showMapbox: Boolean) {
    val leg = journey.leg
    val phase = journey.phase
    val status = model.transitLive[leg?.tripId]
    val refreshedTrip = model.latestTransit(journey.trip) ?: journey.trip
    val station = leg?.takeIf { it.vehicle in setOf(TransitLeg.Vehicle.METRO, TransitLeg.Vehicle.TRAIN) }
    val accessAt = when (phase) {
        TransitGuidance.Phase.BOARD -> station?.let { it.calls.firstOrNull()?.at ?: it.path.firstOrNull() }
        TransitGuidance.Phase.RIDE -> station?.let { it.calls.lastOrNull()?.at ?: it.path.lastOrNull() }
        else -> null
    }
    val entrances = rememberStationEntrances(accessAt)
    var showTrip by remember { mutableStateOf(false) }
    var tripDetent by remember { mutableStateOf(SheetDetent.HALF) }
    var showDepartures by remember(journey.legIndex) { mutableStateOf(false) }
    var showAccess by remember(journey.legIndex, phase) { mutableStateOf(false) }
    var confirmEnd by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(journey) {
        while (true) { delay(5000); now = System.currentTimeMillis() }
    }
    val walking = journey.walking
    if (walking != null && !showTrip) {
        NavigationOverlay(model, walking, showMapbox, transitWalk = journey,
            onTransitTrip = { tripDetent = SheetDetent.HALF; showTrip = true })
        return
    }
    BackHandler {
        if (showTrip) {
            showTrip = false
            model.stopTransitOverview()
        } else confirmEnd = true
    }
    BoxWithConstraints(Modifier.fillMaxSize().systemBarsPadding()) {
        val full = maxHeight
        if (showMapbox) MapboxAttribution(Modifier.align(Alignment.TopStart).padding(start = 16.dp, top = 112.dp))
        if (!showTrip) Column(Modifier.align(Alignment.BottomCenter).padding(12.dp).fillMaxWidth().glass(RoundedCornerShape(25.dp), solid = true)
            .padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            val label = when (phase) {
                TransitGuidance.Phase.WALK -> leg?.toName?.takeUnless { it.isBlank() || it == "END" }?.let { "Walk to $it" }
                    ?: "Walk to ${journey.destination.name}"
                TransitGuidance.Phase.BOARD -> "Board at ${leg?.fromName.orEmpty()}"
                TransitGuidance.Phase.RIDE -> if (journey.nearExit) "Get off at ${leg?.toName.orEmpty()}" else "On board"
                TransitGuidance.Phase.COMPLETE -> "You reached ${journey.destination.name}"
            }
            val rideColor = leg?.takeUnless { it.isWalk }?.let { Color(0xFF000000 or it.mapColor) }
            val rideInk = if (leg != null && !leg.isWalk) transitInk(leg) else primaryText
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .background(rideColor ?: interfaceGround).padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (leg != null && !leg.isWalk) LineChip(leg)
                Text(label, color = rideInk, fontSize = KType.headline, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (phase != TransitGuidance.Phase.COMPLETE && (!journey.following || walking?.following == false))
                    IconButton(model::followTransit) { Icon(Icons.Filled.MyLocation, localizeLabel("Re-centre"), tint = rideInk) }
            }
            when (phase) {
                TransitGuidance.Phase.WALK -> {
                    walking?.progress?.let {
                        Text("${Fmt.distance(it.remainingMeters)} · ${Fmt.duration(it.remainingSeconds)}", color = secondaryText)
                    }
                    if (walking == null && !journey.walkingError) Text(localizeLabel("Preparing walking guidance…"), color = secondaryText)
                    if (journey.walkingError) {
                        Text(localizeLabel("Walking directions unavailable. The transfer path is still on the map."), color = secondaryText)
                        TextButton(model::retryTransitWalk) { Text(localizeLabel("Retry walking directions")) }
                    }
                    TextButton(model::advanceTransitLeg) { Text(if (journey.legIndex == journey.trip.legs.lastIndex) "I'm at the destination" else "I'm at the stop") }
                }
                TransitGuidance.Phase.BOARD -> {
                    val live = journey.liveDeparture
                    val time = status?.board?.departure ?: live?.time ?: leg?.depart
                    val call = leg?.calls?.firstOrNull()
                    val platform = (status?.board?.platform ?: live?.platform ?: call?.platform)?.takeIf(String::isNotBlank)
                    val details = listOfNotNull(leg?.headsign?.let { "Toward $it" }, time?.let { Fmt.time(Date(it)) })
                    if (platform != null) {
                        val change = if (call?.platform != null && call.platform != platform) " · changed from ${call.platform}" else ""
                        Text(kt("Platform $platform$change", "Nástupiště $platform$change"), fontWeight = FontWeight.Bold, color = interfaceAccentText)
                    } else if (leg?.vehicle == TransitLeg.Vehicle.TRAIN) {
                        Text(localizeLabel("Platform not published"), color = secondaryText, fontSize = KType.caption)
                    }
                    if (leg != null) TransitStatus(leg, status, departureDelay = live?.delayMinutes)
                    if (details.isNotEmpty()) Text(details.joinToString(" · "), color = secondaryText)
                    if (time != null && now > time + 180_000) Text(localizeLabel("This departure may have passed."), color = Vela.closed)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton({ showDepartures = true }) { Text(localizeLabel("Departures")) }
                        Button(model::boardTransit, enabled = !journey.boarding) {
                            Text(if (journey.boarding) "Preparing ride…" else "I'm on board")
                        }
                    }
                }
                TransitGuidance.Phase.RIDE -> {
                    val positionAt = journey.lastAlongAt
                    val gps = positionAt != null && now - positionAt <= 20_000
                    val next = if (gps) journey.nextStop else journey.scheduledNext(now, status)
                    next?.let {
                        val updated = status?.matchCall(it) ?: it
                        val time = (updated.arrival ?: updated.departure)?.let { event -> Fmt.time(Date(event)) }
                        val label = if (gps || updated.arrivalDelay != null || updated.departureDelay != null) "Next stop" else "Scheduled next stop"
                        Text(listOfNotNull(label, time).joinToString(" · "), fontSize = KType.caption, color = secondaryText)
                        Text(it.name, color = primaryText, fontSize = KType.headline, fontWeight = FontWeight.Bold)
                    }
                    val followingLeg = journey.trip.legs.drop(journey.legIndex + 1).firstOrNull { !it.isWalk }
                    if (followingLeg != null) Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(localizeLabel("Transfer to"), color = secondaryText)
                        LineChip(followingLeg)
                        followingLeg.headsign?.let { Text(it, color = secondaryText, maxLines = 1) }
                    }
                    if (leg != null) TransitStatus(leg, status, arrival = true, next = next, departureDelay = journey.liveDeparture?.delayMinutes)
                    val exit = listOfNotNull(leg?.toName, (status?.exit?.arrival ?: leg?.arrive)?.let { Fmt.time(Date(it)) })
                    if (exit.isNotEmpty()) Text("Get off · ${exit.joinToString(" · ")}", color = secondaryText)
                    if (!gps) Text(localizeLabel("Waiting for a position along the route"), color = secondaryText, fontSize = KType.caption)
                    TextButton(model::advanceTransitLeg) { Text(localizeLabel("I'm off the vehicle")) }
                }
                TransitGuidance.Phase.COMPLETE -> {}
            }
            if (phase != TransitGuidance.Phase.COMPLETE)
                TransitTransferNotes(refreshedTrip, model.transitLive, fromLeg = journey.legIndex)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                if (phase != TransitGuidance.Phase.COMPLETE) {
                    TextButton({ tripDetent = SheetDetent.HALF; showTrip = true }) { Text(localizeLabel("Trip")) }
                    if (entrances.isNotEmpty()) {
                        TextButton({ showAccess = true }) { Text(if (phase == TransitGuidance.Phase.RIDE) "Exits" else "Entrances") }
                    }
                    TextButton(model::replanTransit) { Text(localizeLabel("Replan")) }
                    TextButton({ confirmEnd = true }) { Text(localizeLabel("End")) }
                } else Button(model::endNavigation) { Text(localizeLabel("Done")) }
            }
        }
        if (showTrip) Box(Modifier.align(Alignment.BottomCenter)) {
            BottomSheet(tripDetent, { tripDetent = it }, full, peekHeight = 76.dp, topClearance = 12.dp, allowPeek = false, opaque = true,
                header = {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(localizeLabel("Trip"), modifier = Modifier.weight(1f).padding(start = 6.dp),
                            color = primaryText, fontWeight = FontWeight.Bold, fontSize = KType.headline)
                        TextButton({
                            if (journey.overviewing) model.followTransit()
                            else {
                                tripDetent = SheetDetent.HALF
                                model.previewTransitJourney()
                            }
                        }) { Text(if (journey.overviewing) "Follow" else "Overview") }
                        IconButton({ tripDetent = if (tripDetent == SheetDetent.FULL) SheetDetent.HALF else SheetDetent.FULL }) {
                            Icon(if (tripDetent == SheetDetent.FULL) Icons.Filled.ExpandMore else Icons.Filled.ExpandLess,
                                if (tripDetent == SheetDetent.FULL) "Half height" else "Expand trip")
                        }
                        IconButton({ showTrip = false; model.stopTransitOverview() }) { Icon(Icons.Filled.Close, "Close trip") }
                    }
                },
                content = {
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp)) {
                        if (walking != null) {
                            Text(localizeLabel("Walking directions"), color = primaryText, fontWeight = FontWeight.Bold, fontSize = KType.headline)
                            StepsList(walking.route, walking.progress?.nextIndex ?: 0)
                            HorizontalDivider()
                        }
                        TransitTimeBar(refreshedTrip, model.transitLive)
                        TransitSteps(refreshedTrip, activeLeg = journey.legIndex, live = model.transitLive, onStep = { leg, at ->
                            showTrip = false
                            model.previewTransitStep(leg, at)
                        })
                    }
                })
        }
    }
    if (showAccess && accessAt != null && entrances.isNotEmpty()) {
        StationAccessDialog(accessAt, entrances, { showAccess = false }) {
            showAccess = false
            model.showStationEntrance(it)
        }
    }
    if (showDepartures) TripSheet("Departures", "Done", { showDepartures = false }) {
        val call = leg?.calls?.firstOrNull()
        val at = call?.at ?: leg?.path?.firstOrNull()
        if (at != null) Column(Modifier.verticalScroll(rememberScrollState()).padding(18.dp)) {
            val id = call?.stopId?.let { "transit:$it" } ?: "stop:${at.lat},${at.lng}"
            DepartureBoard(Place(id, leg?.fromName.orEmpty(), at, "transitous", category = "Transit stop"))
        }
    }
    if (confirmEnd) AlertDialog(onDismissRequest = { confirmEnd = false },
        title = { Text(localizeLabel("End this trip?")) },
        confirmButton = { TextButton(model::endNavigation) { Text(localizeLabel("End trip")) } },
        dismissButton = { TextButton({ confirmEnd = false }) { Text(localizeLabel("Keep going")) } })
}

internal fun transitDelay(minutes: Int): String = when {
    minutes > 0 -> "$minutes min late"
    minutes < 0 -> "${-minutes} min early"
    else -> "On time"
}

@Composable
internal fun TransitStatus(
    leg: TransitLeg, live: TransitLive?, arrival: Boolean = false, next: TransitCall? = null, departureDelay: Int? = null,
) {
    val context = LocalContext.current
    val boardingDelay = live?.board?.departureDelay ?: departureDelay ?: leg.delayMinutes
    val nextStop = next?.let { live?.matchCall(it) ?: it }
    val nextDelay = nextStop?.let { it.arrivalDelay ?: it.departureDelay }
    val exitDelay = live?.exit?.arrivalDelay ?: leg.calls.lastOrNull()?.arrivalDelay
    val delay = if (arrival) nextDelay ?: exitDelay ?: boardingDelay else boardingDelay
    if (live?.cancelled == true || leg.cancelled) {
        Text(localizeLabel("Service cancelled or stop skipped"), color = Vela.closed, fontSize = KType.caption)
    } else {
        val prefix = when {
            !arrival || nextDelay != null || delay == null -> ""
            exitDelay != null -> "Exit stop · "
            else -> "Boarding update · "
        }
        val ink = when {
            delay == null -> secondaryText
            delay > 0 -> Vela.closed
            else -> interfaceAccentText
        }
        if (arrival && delay != null) {
            Surface(color = ink.copy(alpha = 0.12f), shape = RoundedCornerShape(8.dp)) {
                Text(prefix + transitDelay(delay), Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    fontSize = KType.subheadline, fontWeight = FontWeight.SemiBold, color = ink)
            }
        } else Text(delay?.let(::transitDelay) ?: "Live delay unavailable · timetable", fontSize = KType.caption, color = ink)
        live?.position?.let {
            Text(if (live.gps) "Live vehicle position on map" else "Estimated vehicle position on map",
                fontSize = KType.caption, color = secondaryText)
        }
        live?.occupancy?.let { Text(kt("Occupancy · $it", "Obsazenost · ${localizeLabel(it)}"), fontSize = KType.caption, color = secondaryText) }
    }
    for (notice in live?.notices ?: leg.notices) {
        val title = notice.title.ifBlank { "Service alert" }
        var expanded by remember(notice) { mutableStateOf(false) }
        TextButton({ expanded = !expanded }, contentPadding = PaddingValues(horizontal = 4.dp)) {
            Text(title, modifier = Modifier.weight(1f, fill = false))
            Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null)
        }
        if (expanded) {
            if (notice.message.isNotBlank()) Text(notice.message, fontSize = KType.caption, color = primaryText)
            notice.url?.let { url -> TextButton({ context.open(url) }) { Text(localizeLabel("Operator notice")) } }
        }
    }

}
