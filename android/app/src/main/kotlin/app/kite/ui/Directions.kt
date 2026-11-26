@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.kite.ui

import androidx.compose.ui.res.stringResource
import app.kite.R
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import kotlinx.coroutines.flow.drop
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.kite.*
import app.kite.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Date
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** Route preview: the from/to card on top, routes on the map, the trip sheet below. */
@Composable
fun DirectionsOverlay(model: AppModel, showMapbox: Boolean) {
    val density = LocalDensity.current
    var cardHeight by remember { mutableStateOf(130.dp) }
    val statusTop = with(density) { WindowInsets.statusBars.getTop(this).toDp() }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val d = model.directions
        val full = maxHeight
        val transit = d?.transit == true
        val fullTransit = transit && model.detent == SheetDetent.FULL
        Box(Modifier.align(Alignment.BottomCenter)) {
            BottomSheet(model.detent, { model.detent = it }, full, tripPeek(d),
                topClearance = if (transit) statusTop + 6.dp else statusTop + 6.dp + cardHeight + 16.dp,
                opaque = true, allowPeek = !transit,
                header = { TripHeader(model) }, content = { TripBody(model) })
        }
        if (showMapbox && !fullTransit) {
            MapboxAttribution(Modifier.align(Alignment.TopStart).statusBarsPadding()
                .padding(start = 16.dp, top = cardHeight + 9.dp))
        }
        if (d != null && !fullTransit) {
            Box(Modifier.statusBarsPadding().padding(top = 6.dp).onSizeChanged { cardHeight = with(density) { it.height.toDp() } }) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    EndpointsCard(model, d)
                    if (transit && model.transitStepPreview?.tripId == d.pickedTrip?.id && model.transitStepPreview != null) {
                        AssistChip(model::frameRoute, label = { Text(kt("Whole trip", "Celá trasa")) },
                            leadingIcon = { Icon(Icons.Filled.Route, null) },
                            colors = AssistChipDefaults.assistChipColors(containerColor = interfaceSurface))
                    }
                    model.plannedStep?.let { step ->
                        AssistChip(model::clearPlannedStep, label = {
                            Text(Banner.headline(step), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }, leadingIcon = { Icon(maneuverIcon(step.type), null) },
                            trailingIcon = { Icon(Icons.Filled.Close, kt("Show whole route", "Zobrazit celou trasu")) },
                            colors = AssistChipDefaults.assistChipColors(containerColor = interfaceSurface))
                    }
                    model.pathSectionHighlight?.takeIf { it.first == d.route?.id }?.second?.let { section ->
                    AssistChip(onClick = { model.pathSectionHighlight = null },
                        label = { Text("${Fmt.distance(section.from)} – ${Fmt.distance(section.to)}") },
                        leadingIcon = { Icon(Icons.Filled.Terrain, null, Modifier.size(17.dp), tint = Color(0xFFE56C16)) },
                        trailingIcon = { Icon(Icons.Filled.Close, "Clear section", Modifier.size(16.dp)) },
                        colors = AssistChipDefaults.assistChipColors(containerColor = interfaceSurface))
                }
                }
            }
            Box(Modifier.statusBarsPadding().padding(top = 6.dp + cardHeight + 14.dp).padding(horizontal = 16.dp).fillMaxWidth(),
                contentAlignment = Alignment.TopCenter) {
                AvoidRoadCard(model, d)
            }
        }
    }
    androidx.activity.compose.BackHandler {
        if (model.directions?.transit == true && model.detent == SheetDetent.FULL) model.detent = SheetDetent.HALF
        else model.closeDirections()
    }
}

@Composable
fun EndpointsCard(model: AppModel, state: DirectionsState) {
    val dark = LocalDark.current
    val points = state.orderedPlaces()
    var edit by remember { mutableStateOf(false) }
    var compact by rememberSaveable { mutableStateOf(false) }
    if (compact) {
        Row(Modifier.padding(horizontal = 16.dp).fillMaxWidth().glass(CircleShape, solid = true).padding(end = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(model::closeDirections) { Icon(Icons.AutoMirrored.Filled.ArrowBack, localizeLabel("Back")) }
            Text("${if (state.missingOrigin) "Choose start" else points.first().waypointName()} → ${points.last().waypointName()}", color = primaryText,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).tap { compact = false })
            IconButton({ compact = false }) { Icon(Icons.Filled.ExpandMore, "Expand route places") }
        }
        return
    }
    Row(Modifier.padding(horizontal = 16.dp).fillMaxWidth().glass(RoundedCornerShape(24.dp), solid = true).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        IconButton(model::closeDirections) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, localizeLabel("Back"), tint = primaryText)
        }
        Column(Modifier.weight(1f)) {
            if (state.reversed) Endpoint(Icons.Outlined.Circle, Vela.tealLight, points.first().waypointName())
            else OriginRow(model, state)
            if (points.size > 2) {
                Text(points.drop(1).dropLast(1).joinToString(" → ") { it.waypointName() },
                    color = secondaryText, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().tap { edit = true }.padding(start = 30.dp, bottom = 5.dp))
            }
            HorizontalDivider(Modifier.padding(start = 30.dp))
            Box(Modifier.fillMaxWidth().tap { model.pickingDestination = true }) {
                Endpoint(Icons.Filled.Place, Vela.closed, points.last().waypointName())
            }
            if (!state.transit && state.stops.size < 8) TextButton({ model.addingStop = true }) {
                Icon(Icons.Filled.Add, null)
                Text(stringResource(R.string.add_stop_title))
            }
        }
        Column {
            IconButton({ edit = true }) { Icon(Icons.Filled.Reorder, "Edit route", tint = primaryText) }
            IconButton({ compact = true }) { Icon(Icons.Filled.ExpandLess, "Collapse route places", tint = primaryText) }
        }
    }
    if (edit) RouteEditor(model, state) { edit = false }
}

private fun Place?.waypointName() = this?.name ?: "Your location"

@Composable
private fun RouteEditor(model: AppModel, state: DirectionsState, close: () -> Unit) {
    val ctx = LocalContext.current
    val points = state.orderedPlaces()
    val draft = model.routePlan("")
    var naming by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    val canMove = !state.missingOrigin && (model.userLatLng != null || points.none { it == null })
    val dark = LocalDark.current
    val scroll = rememberScrollState()
    var dragged by remember { mutableStateOf<Int?>(null) }
    var fingerY by remember { mutableFloatStateOf(0f) }
    var startY by remember { mutableFloatStateOf(0f) }
    var startScroll by remember { mutableIntStateOf(0) }
    var viewportHeight by remember { mutableIntStateOf(0) }
    val rowHeight = 68.dp
    val density = LocalDensity.current
    val stepPixels = with(density) { rowHeight.roundToPx().toFloat() }
    val handleWidth = with(density) { 52.dp.toPx() }
    val edge = with(density) { 44.dp.toPx() }
    val displacement = fingerY - startY + scroll.value - startScroll
    val target = dragged?.let { (it + (displacement / stepPixels).roundToInt()).coerceIn(0, points.lastIndex) }
    val scrollDirection = when {
        dragged == null -> 0
        fingerY < edge && scroll.canScrollBackward -> -1
        fingerY > viewportHeight - edge && scroll.canScrollForward -> 1
        else -> 0
    }
    LaunchedEffect(scrollDirection) {
        if (scrollDirection != 0) while (true) {
            scroll.scrollBy(scrollDirection * 12f)
            delay(16)
        }
    }

    TripSheet("Edit route", "Done", close) {
        Box(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp, vertical = 6.dp)
            .onSizeChanged { viewportHeight = it.height }
            .pointerInput(points, canMove, stepPixels, handleWidth) {
                if (!canMove) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val index = ((down.position.y + scroll.value) / stepPixels).toInt()
                    if (down.isConsumed || down.position.x !in 0f..handleWidth || index !in points.indices) return@awaitEachGesture
                    down.consume()
                    startY = down.position.y
                    fingerY = startY
                    startScroll = scroll.value
                    dragged = index
                    try {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (change.isConsumed) break
                            fingerY = change.position.y
                            change.consume()
                            if (!change.pressed) {
                                val shift = fingerY - startY + scroll.value - startScroll
                                val drop = (index + (shift / stepPixels).roundToInt()).coerceIn(0, points.lastIndex)
                                if (drop != index) model.moveWaypoint(index, drop - index)
                                break
                            }
                        }
                    } finally {
                        dragged = null
                    }
                }
            }) {
            Column(Modifier.fillMaxWidth().verticalScroll(scroll)) {
                points.forEachIndexed { index, place ->
                    val dragIndex = dragged
                    val shift = when {
                        dragIndex == null || target == null -> 0f
                        index == dragIndex -> displacement.coerceIn(-index * stepPixels, (points.lastIndex - index) * stepPixels)
                        index > dragIndex && index <= target -> -stepPixels
                        index < dragIndex && index >= target -> stepPixels
                        else -> 0f
                    }
                    val moving = dragIndex == index
                    Box(Modifier.fillMaxWidth().height(rowHeight).zIndex(if (moving) 1f else 0f)
                        .graphicsLayer { translationY = shift }
                        .padding(vertical = 3.dp).clip(RoundedCornerShape(15.dp))
                        .background(if (moving) interfaceAccent.copy(alpha = 0.18f) else interfaceSurface)) {
                        Row(Modifier.fillMaxSize().padding(end = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.width(52.dp).fillMaxHeight().semantics {
                                contentDescription = "Drag ${place.waypointName()} to reorder"
                            }, contentAlignment = Alignment.Center) {
                                Icon(Icons.Filled.DragHandle, null, tint = secondaryText, modifier = Modifier.size(24.dp))
                            }
                            val endpoint = index == points.lastIndex || index == 0 && !state.reversed
                            Column(Modifier.weight(1f).then(if (endpoint) Modifier.tap {
                                close()
                                if (index == points.lastIndex) model.pickingDestination = true else model.pickingOrigin = true
                            } else Modifier).padding(vertical = 6.dp)) {
                                Text(when (index) {
                                    0 -> "Start"
                                    points.lastIndex -> "Destination"
                                    else -> "Stop $index"
                                }, color = secondaryText, fontSize = KType.caption)
                                Text(if (index == 0 && state.missingOrigin) "Choose starting point" else place.waypointName(),
                                    color = if (index == 0 && state.missingOrigin) interfaceAccentText else primaryText,
                                    fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            IconButton({ model.moveWaypoint(index, -1) }, enabled = canMove && index > 0) {
                                Icon(Icons.Filled.KeyboardArrowUp, "Move up")
                            }
                            IconButton({ model.moveWaypoint(index, 1) }, enabled = canMove && index < points.lastIndex) {
                                Icon(Icons.Filled.KeyboardArrowDown, "Move down")
                            }
                            if (index in 1 until points.lastIndex) IconButton({ model.removeStop(index - 1) }) {
                                Icon(Icons.Filled.Close, "Remove stop")
                            } else if (endpoint) IconButton({
                                close()
                                if (index == points.lastIndex) model.pickingDestination = true else model.pickingOrigin = true
                            }) { Icon(Icons.Filled.Edit, if (index == points.lastIndex) "Change destination" else "Change start") }
                            else Spacer(Modifier.width(48.dp))
                        }
                    }
                }
                if (!state.transit && state.stops.size < 8) FilledTonalButton(
                    { close(); model.addingStop = true }, Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Icon(Icons.Filled.AddLocationAlt, null, Modifier.size(19.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.add_stop_title))
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton({ if (state.missingOrigin) close(); model.reverseDirections() }) { Text(localizeLabel("Reverse")) }
                    TextButton({ name = draft?.name.orEmpty(); naming = true }, enabled = draft != null) { Text(localizeLabel("Save route")) }
                    TextButton({ draft?.let { ctx.share(it.shareText()) } }, enabled = draft != null) { Text(localizeLabel("Share route")) }
                }
            }
        }
    }
    if (naming) AlertDialog(
        onDismissRequest = { naming = false },
        title = { Text(localizeLabel("Save route")) },
        text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text(localizeLabel("Name")) }) },
        confirmButton = { TextButton({
            model.routePlan(name)?.let { AppSettings.savedPlans = listOf(it) + AppSettings.savedPlans }
            naming = false
            close()
        }, enabled = name.isNotBlank()) { Text(localizeLabel("Save")) } },
        dismissButton = { TextButton({ naming = false }) { Text(localizeLabel("Cancel")) } })
}

/**
 * "Your location", or the place picked to start from instead: tap to pick one (GitHub #55),
 * ✕ to go back to where you are.
 */
@Composable
private fun OriginRow(model: AppModel, state: DirectionsState) {
    Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(if (state.start == null) Icons.Filled.MyLocation else Icons.Outlined.Circle, null,
            tint = if (state.reversed) Vela.closed else Vela.tealLight, modifier = Modifier.size(18.dp))
        Text(state.start?.name ?: if (state.missingOrigin) "Choose starting point" else "Your location",
            fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = if (state.missingOrigin) interfaceAccentText else primaryText, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).tap { model.pickingOrigin = true })
        if (state.start != null) {
            Icon(Icons.Filled.Close, "Start from your location", tint = secondaryText,
                modifier = Modifier.size(20.dp).clip(CircleShape).tap { model.setOrigin(null) })
        }
    }
}

/**
 * Tap the route to avoid a road (GitHub idea, 0.7.17): "Avoid Bassett Avenue?", then while it looks
 * for a way round, then "Avoiding Bassett Avenue" with Undo. Small, under the from/to box.
 */
@Composable
fun AvoidRoadCard(model: AppModel, state: DirectionsState) {
    val dark = LocalDark.current
    val ask = model.avoidAsk
    when {
        ask != null -> Row(Modifier.fillMaxWidth().heightIn(min = 54.dp).glass(RoundedCornerShape(27.dp)).padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Filled.Block, null, tint = Vela.closed, modifier = Modifier.size(22.dp))
            // Two lines rather than a cut-off name ("Avoid Russell Boule…").
            Text("Avoid ${ask.road ?: "this road"}?", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = primaryText, maxLines = 2, lineHeight = 20.sp,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(localizeLabel("Cancel"), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = secondaryText, modifier = Modifier.tap { model.avoidAsk = null }.padding(6.dp))
            Box(Modifier.height(40.dp).clip(CircleShape).background(interfaceAccent).tap { model.avoidRoad() }.padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center) {
                Text(localizeLabel("Avoid"), fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, color = interfaceOnAccent)
            }
        }
        model.avoidBusy -> Row(Modifier.height(46.dp).glass(CircleShape).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Text(localizeLabel("Finding a way round…"), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = primaryText)
        }
        state.avoidNames.isNotEmpty() && state.mode == TravelMode.DRIVE && !state.transit -> Row(Modifier.height(44.dp).glass(CircleShape).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Filled.Block, null, tint = Vela.closed, modifier = Modifier.size(18.dp))
            Text("Avoiding ${avoidNames(state.avoidNames)}", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = primaryText,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Text(localizeLabel("Undo"), fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = interfaceAccentText, modifier = Modifier.tap { model.undoAvoid() })
        }
    }
}

fun avoidNames(list: List<String>): String {
    val seen = list.distinct()
    return if (seen.size <= 2) seen.joinToString(" and ") else "${seen[0]} and ${seen.size - 1} more"
}

@Composable
private fun Endpoint(icon: ImageVector, color: Color, text: String) {
    Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, null, tint = color, modifier = Modifier.size(18.dp))
        Text(text, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = primaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun delayColor(r: Route, dark: Boolean): Color = when (r.delayLevel) {
    2 -> if (dark) Vela.closedDark else Vela.closed
    1 -> Color(0xFFE37400)
    else -> interfaceAccentText
}

/** Route cards show when there's a choice to make: up to three, the picked one lit. */
fun routeCards(d: DirectionsState): List<Route> = if (!d.transit && d.route != null && d.current.size > 1) d.current.take(3) else emptyList()

/** The place shuts before, or just as, this trip gets there. */
fun closingWarning(d: DirectionsState): ClosingCheck.Warning? {
    val r = d.route ?: return null
    if (d.transit || d.reversed) return null
    val c = Calendar.getInstance()
    return ClosingCheck.warning(d.destination.statusText, c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE), r.durationSeconds)
}

/** How tall the sheet sits at rest, so everything above the fold (cards, Go, modes) is in view. */
fun tripPeek(d: DirectionsState?): androidx.compose.ui.unit.Dp {
    if (d == null) return 300.dp
    if (d.transit) return if (d.pickedTrip != null) 265.dp else 200.dp
    return if (closingWarning(d) != null) 336.dp else 300.dp
}

@Composable
private fun RouteOptionsButton(model: AppModel) {
    val dark = LocalDark.current
    val s = AppSettings
    var menu by remember { mutableStateOf(false) }
    Box {
        Box(Modifier.size(48.dp).clip(CircleShape).background(interfaceChip).tap { menu = true }, contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Tune, "Route options", tint = primaryText, modifier = Modifier.size(22.dp))
        }
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem(text = { Text(localizeLabel("Avoid tolls")) }, trailingIcon = { Checkbox(s.avoidTolls, null) },
                onClick = { s.avoidTolls = !s.avoidTolls; model.reloadRoutes() })
            DropdownMenuItem(text = { Text(if (Fmt.yards) "Avoid motorways" else "Avoid highways") }, trailingIcon = { Checkbox(s.avoidHighways, null) },
                onClick = { s.avoidHighways = !s.avoidHighways; model.reloadRoutes() })
        }
    }
}

@Composable
private fun GoButton(model: AppModel, pending: Boolean = false) {
    val dark = LocalDark.current
    Button(model::startNavigation, Modifier.fillMaxWidth().heightIn(min = 60.dp), enabled = !pending,
        colors = ButtonDefaults.buttonColors(containerColor = interfaceAccent, contentColor = interfaceOnAccent,
            disabledContainerColor = interfaceChip, disabledContentColor = primaryText)) {
        if (pending) CircularProgressIndicator(Modifier.size(21.dp), strokeWidth = 2.dp, color = interfaceAccentText)
        else Icon(Icons.Filled.Navigation, null, modifier = Modifier.size(25.dp))
        Spacer(Modifier.width(10.dp))
        Text(if (pending) "Starting navigation…" else "Go", fontSize = 23.sp, fontWeight = FontWeight.ExtraBold)
    }
}

@Composable
private fun ClosingLine(d: DirectionsState) {
    val w = closingWarning(d) ?: return
    val dark = LocalDark.current
    val red = if (dark) Vela.closedDark else Vela.closed
    Row(Modifier.height(22.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Filled.Schedule, null, tint = red, modifier = Modifier.size(20.dp))
        Text(if (w.before) "Closes at ${w.closes}, before you arrive" else "Closes at ${w.closes}, just after you arrive",
            fontSize = 17.sp, fontWeight = FontWeight.Bold, color = red, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** One route to choose: the road it uses, how its traffic is, and its time, big. */
@Composable
private fun RouteCard(r: Route, on: Boolean, pick: () -> Unit) {
    val dark = LocalDark.current
    val fg = if (on) interfaceOnAccent else primaryText
    val tone by androidx.compose.animation.animateColorAsState(
        if (on) interfaceAccent else interfaceChip, label = "route selection")
    Row(Modifier.fillMaxWidth().heightIn(min = 62.dp).clip(RoundedCornerShape(20.dp)).background(tone)
        .tap(pick).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(r.summary?.let { "via $it" } ?: Fmt.distance(r.distanceMeters), fontSize = 19.sp, fontWeight = FontWeight.Bold, color = fg,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (r.mode == TravelMode.DRIVE && r.trafficSeconds != null) {
                Text(if (r.delayLevel == 0) "Traffic is light" else "${Fmt.duration(r.durationSeconds - r.typicalSeconds)} slower than usual",
                    fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = if (on) fg else delayColor(r, dark), maxLines = 1, overflow = TextOverflow.Ellipsis)
            } else {
                Text(if (r.summary == null) "Arrive ${Fmt.arrival(r.durationSeconds)}" else Fmt.distance(r.distanceMeters), fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold, color = if (on) fg.copy(alpha = 0.85f) else secondaryText, maxLines = 1)
            }
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(Fmt.duration(r.durationSeconds), fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = fg, maxLines = 1, lineHeight = 26.sp)
            Text(kt("arrive ${Fmt.arrival(r.durationSeconds)}", "příjezd v ${Fmt.arrival(r.durationSeconds)}"), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = fg.copy(alpha = 0.8f), maxLines = 1)
        }
    }
}

/** The part of the trip sheet always in view: how long, how you go, and a giant Go. */
@Composable
fun TripHeader(model: AppModel) {
    val d = model.directions ?: return
    val dark = LocalDark.current
    val ctx = LocalContext.current
    Column(Modifier.padding(horizontal = 18.dp).padding(bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!d.transit && (d.route != null || d.startRequested && !d.waitingForLocation)) GoButton(model, d.startRequested)
        if (d.transit) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(localizeLabel("Public transport"), color = primaryText, fontWeight = FontWeight.Bold, fontSize = KType.headline)
                    val trip = d.pickedTrip
                    Text(if (trip == null) d.destination.name else listOfNotNull(Fmt.duration(trip.durationSeconds), trip.arrive?.let { Fmt.time(Date(it)) }).joinToString(" · "),
                        color = secondaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (d.pickedTrip != null) IconButton({
                    model.detent = SheetDetent.HALF
                    model.frameRoute()
                }) { Icon(Icons.Filled.Map, "Show trip on map", tint = interfaceAccentText) }
                if (model.detent == SheetDetent.FULL) IconButton({ model.detent = SheetDetent.HALF }) {
                    Icon(Icons.Filled.ExpandMore, "Half height")
                } else IconButton({ model.detent = SheetDetent.FULL }) {
                    Icon(Icons.Filled.ExpandLess, "Expand connections")
                }
            }
            ModeTabs(model, d)
            return
        }
        Row(verticalAlignment = Alignment.Top) {
            Box(Modifier.weight(1f)) {
                val r = d.route
                when {
                    d.missingOrigin -> Column {
                        Text(localizeLabel("Choose where to start"), color = primaryText, fontWeight = FontWeight.Bold)
                        TextButton({ model.pickingOrigin = true }) { Text(localizeLabel("Set starting point")) }
                    }
                    d.waitingForLocation -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(when {
                            model.location.denied -> AppModel.LOCATION_OFF_TEXT
                            model.location.systemOff -> "Turn on Location to start from where you are."
                            else -> "Waiting for your location…"
                        }, fontSize = KType.subheadline, fontWeight = FontWeight.SemiBold, color = primaryText)
                        if (model.location.denied || model.location.systemOff)
                            TextButton({ LocationService.openSettings(ctx) }) { Text(localizeLabel("Location settings")) }
                    }
                    r != null -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(Fmt.duration(r.durationSeconds), fontSize = 44.sp, fontWeight = FontWeight.ExtraBold, color = delayColor(r, dark),
                            maxLines = 1, lineHeight = 48.sp)
                        Text(kt("${Fmt.distance(r.distanceMeters)} · arrive ${Fmt.arrival(r.durationSeconds)}", "${Fmt.distance(r.distanceMeters)} · příjezd v ${Fmt.arrival(r.durationSeconds)}"), fontSize = 17.sp,
                            fontWeight = FontWeight.Medium, color = secondaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    d.mode in d.loading -> Row(Modifier.height(52.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 3.dp, color = interfaceAccent)
                        Text(localizeLabel("Finding routes…"), fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = primaryText)
                    }
                    else -> Column(Modifier.heightIn(min = 52.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(d.errors[d.mode] ?: "No route found.", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = secondaryText)
                        if (model.location.denied) PillButton("Open Settings", Icons.Filled.MyLocation, filled = true) { LocationService.openSettings(ctx) }
                        else if (d.errors[d.mode] != null) PillButton("Try again", Icons.Filled.Refresh) { model.reloadRoutes() }
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            if (!d.transit) RouteOptionsButton(model)
        }
        ModeTabs(model, d)
        ClosingLine(d)
    }
}

fun modeIcon(m: TravelMode): ImageVector = when (m) {
    TravelMode.DRIVE -> Icons.Filled.DirectionsCar
    TravelMode.BIKE -> Icons.AutoMirrored.Filled.DirectionsBike
    TravelMode.WALK -> Icons.AutoMirrored.Filled.DirectionsWalk
}

@Composable
fun ModeTabs(model: AppModel, state: DirectionsState) {
    // Four equal tiles, always all in view, so the right one is easy to hit.
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TravelMode.entries.forEach { m ->
            ModeTile(modeIcon(m), !state.transit && m == state.mode, m in state.loading,
                state.routes[m]?.firstOrNull()?.let { Fmt.shortDuration(it.durationSeconds) }) { model.setMode(m) }
        }
        ModeTile(Icons.Filled.Tram, state.transit, state.transit && state.trips == null && !state.waitingForLocation && !state.missingOrigin,
            // The same trip the header shows (the picked one, else the first), so the two never disagree.
            (state.pickedTrip ?: state.trips?.firstOrNull())?.let { Fmt.shortDuration(it.durationSeconds) }) { model.showTransit() }
    }
}

@Composable
private fun RowScope.ModeTile(icon: ImageVector, on: Boolean, loading: Boolean, time: String?, onClick: () -> Unit) {
    val dark = LocalDark.current
    val fg = if (on) interfaceOnAccent else primaryText
    Column(Modifier.weight(1f).heightIn(min = 62.dp).clip(RoundedCornerShape(18.dp)).background(if (on) interfaceAccent else interfaceChip)
        .tap(onClick), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically)) {
        Icon(icon, null, tint = fg, modifier = Modifier.size(24.dp))
        if (loading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = fg)
        else Text(time ?: "–", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = fg, maxLines = 1)
    }
}

@Composable
fun TripBody(model: AppModel) {
    val d = model.directions ?: return
    val dark = LocalDark.current
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 80.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val r = d.route
        when {
            d.transit -> TransitBody(model, d)
            r != null -> {
                routeCards(d).forEachIndexed { index, candidate ->
                    RouteCard(candidate, candidate.id == r.id) { model.selectRoute(index) }
                }
                if (r.mode == TravelMode.DRIVE) {
                    Surface(color = interfaceSurface, shape = RoundedCornerShape(22.dp)) {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            val best = d.current.minOfOrNull { it.durationSeconds } ?: r.durationSeconds
                            val fast = r.durationSeconds <= best + 30
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Icon(Icons.Filled.AltRoute, null, tint = interfaceAccentText)
                                Column {
                                    Text(if (fast) kt("Fastest route", "Nejrychlejší trasa") else kt("Alternative route", "Alternativní trasa"),
                                        fontWeight = FontWeight.Bold, color = primaryText)
                                    r.summary?.let { Text(it, color = secondaryText, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                }
                            }
                            if (r.trafficSeconds != null) Text(if (r.delayLevel == 0) kt("Traffic is light", "Provoz je plynulý")
                                else "+${Fmt.duration((r.durationSeconds - r.typicalSeconds).coerceAtLeast(0.0))} · " + kt("Traffic", "Provoz"),
                                color = delayColor(r, dark), fontWeight = FontWeight.SemiBold)
                            RouteTolls(r)
                            RouteCharges(r)
                            RouteWazeSummary(r, onShow = { model.showSpot(it.location) }, onFound = { model.routeReports = it })
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                Icon(Icons.Filled.TouchApp, null, tint = secondaryText, modifier = Modifier.size(17.dp))
                                Text(localizeLabel("Tap a road on the route to avoid it"), fontSize = KType.caption, color = secondaryText)
                            }
                        }
                    }
                    ArrivalRows(model, d)
                    RouteLiveCams(model, r)
                }
                // More than three routes is rare; the ones past the cards stay reachable here.
                if (d.current.size > 3) {
                    Column {
                        d.current.forEachIndexed { i, alt ->
                            if (i >= 3 && alt.id != r.id) {
                                Row(Modifier.fillMaxWidth().tap { model.selectRoute(i) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.AutoMirrored.Filled.CallSplit, null, tint = secondaryText, modifier = Modifier.width(22.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text("${Fmt.duration(alt.durationSeconds)} · ${Fmt.distance(alt.distanceMeters)}", fontSize = KType.subheadline,
                                            fontWeight = FontWeight.Medium, color = primaryText)
                                        alt.summary?.let { Text("via $it", fontSize = KType.caption, color = secondaryText) }
                                    }
                                    val diff = alt.durationSeconds - r.durationSeconds
                                    // Under half a minute apart would round to "+1 min": call it the same.
                                    Text(if (abs(diff) < 30) "Same time" else if (diff > 0) "+${Fmt.duration(diff)}" else "−${Fmt.duration(-diff)}",
                                        fontSize = KType.caption, color = secondaryText)
                                }
                            }
                        }
                    }
                }
                Surface(color = interfaceSurface, shape = RoundedCornerShape(22.dp)) {
                    Column {
                        if (AppSettings.routeWeather) {
                            RouteWeatherCard(r)
                            HorizontalDivider(Modifier.padding(horizontal = 14.dp))
                        }
                        IconRow(Icons.Filled.Terrain, kt("Elevation and surface", "Převýšení a povrch"),
                            trailing = kt("View", "Zobrazit")) { model.openProfile(r) }
                    }
                }
                ExpandableSteps(r, onStep = model::previewPlannedStep)
                Row(Modifier.height(52.dp).clip(CircleShape).background(interfaceTonal).tap { ctx.share(d.destination.shareUrl) }.padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Filled.Share, null, tint = interfaceOnTonal, modifier = Modifier.size(20.dp))
                    Text(localizeLabel("Share place"), fontSize = 17.sp, fontWeight = FontWeight.Bold, color = interfaceOnTonal)
                }
            }
            d.mode in d.loading -> Row(Modifier.padding(vertical = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); Text(localizeLabel("Finding routes…"), color = secondaryText)
            }
            else -> Text(d.errors[d.mode] ?: "No route found.", color = secondaryText, modifier = Modifier.padding(vertical = 20.dp))
        }
    }
}

/** Pay-later charges on this route (Dartford, the Mersey bridges, London's zone): there's no barrier to remind you. */
@Composable
fun RouteCharges(route: Route) {
    val dark = LocalDark.current
    val clean = AppSettings.cleanCar
    var charges by remember(route.id, clean) { mutableStateOf(RouteChargesDemo.charges) }
    LaunchedEffect(route.id, clean) {
        val found = withContext(Dispatchers.Default) { RoadCharges.on(route.polyline) + CleanAirZones.on(route.polyline, clean) }
        if (found.isNotEmpty() || RouteChargesDemo.charges.isEmpty()) charges = found
    }
    charges.forEach { c ->
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(interfaceChip).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(if (c.zone) Icons.Filled.Eco else Icons.Filled.CurrencyPound, null,
                tint = if (c.zone) Color(0xFF15803D) else Color(0xFFE37400), modifier = Modifier.size(28.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(if (c.zone) "Clean air zone on this route: ${c.name}" else "Charge on this route: ${c.name}", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = primaryText)
                Text(c.note, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = secondaryText, lineHeight = 19.sp)
            }
        }
    }
}

/**
 * With "Avoid tolls" on: a quiet "No tolls" when the route is clear, or a plain warning naming the toll
 * road when there was no way round it. Checked against the road map's own toll marks; nothing at all
 * when the map server doesn't answer.
 */
@Composable
fun RouteTolls(route: Route) {
    val avoid = AppSettings.avoidTolls
    var tolls by remember(route.id, avoid) { mutableStateOf(RouteTollsDemo.tolls) }
    LaunchedEffect(route.id, avoid) {
        if (RouteTollsDemo.tolls != null || !avoid || route.mode != TravelMode.DRIVE) return@LaunchedEffect
        tolls = withContext(Dispatchers.IO) { Valhalla.tolls(route.polyline) }
    }
    val t = tolls ?: return
    if (!avoid) return
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
        if (t.isEmpty()) {
            Icon(Icons.Filled.CheckCircle, null, tint = Vela.open, modifier = Modifier.size(18.dp))
            Text(localizeLabel("No tolls on this route"), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Vela.open)
        } else {
            Icon(Icons.Filled.Warning, null, tint = Color(0xFFE37400), modifier = Modifier.size(18.dp))
            Text("No toll-free way found. This route uses ${tollList(t)}.", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFE37400))
        }
    }
}

/** "the M6 Toll", "the M6 Toll and the Dartford Crossing". */
fun tollList(names: List<String>): String {
    val n = names.take(3).map { if (it == "A toll road") "a toll road" else "the $it" }
    return if (n.size <= 1) n.firstOrNull() ?: "a toll road" else n.dropLast(1).joinToString(", ") + " and " + n.last()
}

object RouteTollsDemo { var tolls: List<String>? = null }

/** Made-up charges for the build's screenshots. */
object RouteChargesDemo { var charges: List<RoadCharge> = emptyList() }

/** The steps worth saying, each with the length of the quiet name changes after it added on. */
@Composable
fun ExpandableSteps(route: Route, from: Int = 0, initiallyOpen: Boolean = false, onStep: (Maneuver) -> Unit) {
    var opened by rememberSaveable(route.id) { mutableStateOf(initiallyOpen) }
    Surface(color = interfaceSurface, shape = RoundedCornerShape(22.dp)) {
        Column {
            IconRow(Icons.Filled.FormatListNumbered, localizeLabel("Steps"), trailing = if (opened) "−" else "+") { opened = !opened }
            if (opened) Box(Modifier.padding(horizontal = 12.dp)) { StepsList(route, from, onStep) }
        }
    }
}

@Composable
fun StepsList(route: Route, from: Int = 0, onStep: ((Maneuver) -> Unit)? = null) {
    val shown = remember(route.maneuvers, from) {
        val out = mutableListOf<Maneuver>()
        for (m in route.maneuvers.drop(from.coerceIn(0, route.maneuvers.size))) {
            if (m.silent && out.isNotEmpty()) out[out.size - 1] = out.last().copy(distanceMeters = out.last().distanceMeters + m.distanceMeters)
            else if (!m.silent) out += m
        }
        out
    }
    Column {
        shown.forEach { m ->
            val action = if (onStep == null) Modifier else Modifier.clickable(
                onClickLabel = localizeLabel("Show on map"), role = Role.Button, onClick = { onStep(m) })
            Row(Modifier.fillMaxWidth().then(action).padding(vertical = 9.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Icon(maneuverIcon(m.type), null, tint = primaryText, modifier = Modifier.size(30.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(Banner.headline(m), fontSize = 18.sp, fontWeight = FontWeight.Medium, color = primaryText)
                    m.laneHint?.let { Text(it, fontSize = KType.subheadline, color = secondaryText) }
                    if (m.distanceMeters > 0) Text(Fmt.distance(m.distanceMeters), fontSize = KType.subheadline, color = secondaryText)
                }
            }
            HorizontalDivider(Modifier.padding(start = 44.dp))
        }
    }
}

private fun clock(t: Long) = Fmt.time(Date(t))

/** The Transit tab: each trip as a row of line chips with its times, the picked one's legs below. */
@Composable
fun TransitBody(model: AppModel, state: DirectionsState) {
    val ctx = LocalContext.current
    var kind by remember { mutableIntStateOf(if (state.transitTime == null) 0 else if (state.arriveBy) 2 else 1) }
    var whenAt by remember { mutableLongStateOf(state.transitTime ?: System.currentTimeMillis()) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            listOf("Leave now", "Depart at", "Arrive by").forEachIndexed { i, label ->
                SegmentedButton(kind == i, { kind = i; model.setTransitTime(i, whenAt) }, SegmentedButtonDefaults.itemShape(i, 3)) { Text(label, maxLines = 1) }
            }
        }
        if (kind != 0) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(localizeLabel("Time"), fontSize = KType.subheadline, color = primaryText, modifier = Modifier.weight(1f))
                Text(java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(Date(whenAt)),
                    fontSize = KType.subheadline, fontWeight = FontWeight.SemiBold, color = primaryText,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(interfaceChip).tap {
                        val c = Calendar.getInstance().apply { timeInMillis = whenAt }
                        DatePickerDialog(ctx, { _, y, mo, day ->
                            c.set(y, mo, day)
                            TimePickerDialog(ctx, { _, h, min ->
                                c.set(Calendar.HOUR_OF_DAY, h); c.set(Calendar.MINUTE, min)
                                whenAt = max(c.timeInMillis, System.currentTimeMillis() - 3_600_000)
                                model.setTransitTime(kind, whenAt)
                            }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), android.text.format.DateFormat.is24HourFormat(ctx)).show()
                        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
                    }.padding(horizontal = 10.dp, vertical = 6.dp))
            }
        }
    }
    if (state.missingOrigin) return
    state.recommendedWalk?.let { walk ->
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(interfaceHighlight)
            .tap(model::showRecommendedWalk).padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AutoMirrored.Filled.DirectionsWalk, null, tint = interfaceOnHighlight)
            Column(Modifier.weight(1f)) {
                Text(localizeLabel("Fastest on foot"), fontWeight = FontWeight.Bold, color = interfaceOnHighlight)
                Text("${Fmt.duration(walk.durationSeconds)} · ${Fmt.distance(walk.distanceMeters)}", color = interfaceOnHighlight)
            }
            Icon(Icons.Filled.ArrowForward, "Show walking route", tint = interfaceOnHighlight)
        }
    }
    val trips = state.trips
    var expandedTrip by rememberSaveable { mutableStateOf<String?>(null) }
    var bookmarkedTrip by rememberSaveable { mutableStateOf<String?>(null) }
    when {
        trips != null && trips.isNotEmpty() -> {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                trips.forEachIndexed { i, t ->
                    val picked = t.id == state.pickedTrip?.id
                    val expanded = picked && expandedTrip == t.id
                    Column {
                        val shown = if (picked) model.latestTransit(t) ?: t else t
                        val live = if (picked) model.transitLive else emptyMap()
                        TripRow(shown, picked, expanded, live, select = {
                            if (!picked) {
                                expandedTrip = null
                                model.selectTrip(i)
                            }
                        }, toggleDetails = {
                            if (!picked) model.selectTrip(i)
                            expandedTrip = if (expanded) null else t.id
                        })
                        if (picked) {
                            val disrupted = shown.rides.any { it.cancelled || live[it.tripId]?.cancelled == true } ||
                                shown.transferWarnings(live, AppSettings.learnedWalkingPace).any { it.kind == TransferTiming.Kind.MISSED }
                            Button({ if (disrupted) model.loadTransit() else model.startTransitNavigation() },
                                Modifier.fillMaxWidth().padding(top = 5.dp).heightIn(min = 45.dp)) {
                                Icon(if (disrupted) Icons.Filled.Refresh else Icons.Filled.Navigation, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(if (disrupted) "Find another connection" else "Start transit navigation")
                            }
                            TextButton({
                                model.saveTransitPlan(t)
                                bookmarkedTrip = t.id
                            }, enabled = t.id != bookmarkedTrip && t.depart != null && model.routePlan("", t) != null) {
                                Icon(if (t.id == bookmarkedTrip) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                                    null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(if (t.id == bookmarkedTrip) "Saved for later" else "Save for later")
                            }
                        }
                        if (expanded) TransitSteps(shown, live = live, onStep = model::previewTransitStep)
                    }
                }
            }
            Text(localizeLabel("Timetables from Transitous"), fontSize = KType.caption, color = secondaryText, modifier = Modifier.padding(top = 4.dp))
        }
        trips == null -> Row(Modifier.padding(vertical = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); Text(localizeLabel("Finding transit…"), color = secondaryText)
        }
        else -> Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(state.tripsError ?: "No transit trips found.", color = secondaryText)
            val (from, to) = state.endpoints(model.userLatLng)
            if (!AppSettings.noGoogle && from != null && to != null && state.stops.isEmpty()) {
                OutlinedButton({
                    fun coords(p: LatLng) = urlEnc("${p.lat.plain()},${p.lng.plain()}")
                    ctx.open("https://www.google.com/maps/dir/?api=1&origin=${coords(from)}&destination=${coords(to)}&travelmode=transit")
                }) { Text(localizeLabel("Check transit in Google Maps")) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TripRow(trip: TransitTrip, picked: Boolean, expanded: Boolean, liveUpdates: Map<String, TransitLive>, select: () -> Unit, toggleDetails: () -> Unit) {
    val live = liveUpdates[trip.rides.firstOrNull()?.tripId]
    val shape = RoundedCornerShape(18.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(interfaceGround)
        .border(1.dp, if (picked) interfaceAccent else secondaryText.copy(alpha = 0.25f), shape)
        .tap(select).padding(start = 12.dp, end = 5.dp, top = 2.dp, bottom = 11.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val a = trip.depart; val b = trip.arrive
            if (a != null && b != null) Text("${clock(a)} – ${clock(b)}", fontSize = KType.subheadline, fontWeight = FontWeight.SemiBold, color = primaryText)
            Spacer(Modifier.weight(1f))
            Text(Fmt.duration(trip.durationSeconds), fontSize = KType.headline, fontWeight = FontWeight.Bold,
                color = if (picked) interfaceAccentText else primaryText)
            IconButton(toggleDetails) {
                Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    if (expanded) "Collapse trip details" else "Expand trip details", tint = primaryText)
            }
        }
        TransitTimeBar(trip, liveUpdates)
        // The labels wrap independently of the timeline so even a short connecting leg keeps its name.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            trip.legs.forEachIndexed { i, leg ->
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (i > 0) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = secondaryText, modifier = Modifier.size(12.dp))
                    if (leg.isWalk) Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.AutoMirrored.Filled.DirectionsWalk, null, tint = secondaryText, modifier = Modifier.size(16.dp))
                        if (leg.durationSeconds >= 60) Text("${Math.round(leg.durationSeconds / 60)}", fontSize = KType.caption2, color = secondaryText)
                    } else LineChip(leg)
                }
            }
        }
        TransitTransferNotes(trip, liveUpdates)
        val transfers = when (trip.transfers) { 0 -> "Direct"; 1 -> "1 transfer"; else -> "${trip.transfers} transfers" }
        Text("$transfers · ${Fmt.duration(trip.walkSeconds)} " + kt("walking", "pěšky"), fontSize = KType.caption, color = secondaryText)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val first = trip.rides.firstOrNull()
            (live?.board?.departure ?: first?.depart)?.let { Text("${clock(it)} from ${first?.fromName.orEmpty()}", fontSize = KType.caption, color = secondaryText, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false)) }
            val delay = live?.board?.departureDelay ?: first?.delayMinutes
            when {
                live?.cancelled == true -> Text(localizeLabel("Disrupted"), fontSize = KType.caption, color = Vela.closed)
                delay != null -> Text(transitDelay(delay), fontSize = KType.caption, color = if (delay > 0) Vela.closed else Vela.open)
            }
        }
        (live?.board?.platform ?: trip.rides.firstOrNull()?.calls?.firstOrNull()?.platform)
            ?.takeIf(String::isNotBlank)?.let { Text(kt("Platform $it", "Nástupiště $it"), fontSize = KType.caption, fontWeight = FontWeight.Bold, color = interfaceAccentText) }
    }
}

@Composable
fun TransitSteps(trip: TransitTrip, activeLeg: Int? = null, live: Map<String, TransitLive> = emptyMap(),
                 onStep: ((TransitLeg, LatLng?) -> Unit)? = null) {
    fun times(t: Long?, text: String) = t?.let { "${clock(it)}  $text" } ?: text
    Column(Modifier.padding(top = 5.dp)) {
        trip.legs.forEachIndexed { index, leg ->
            val accent = Color(0xFF000000 or leg.mapColor)
            val transfer = index > 0 && !leg.isWalk && trip.legs.take(index).any { !it.isWalk }
            var expanded by rememberSaveable(trip.id, index) { mutableStateOf(false) }
            val action = if (onStep == null) Modifier else Modifier.clickable(
                onClickLabel = localizeLabel("Show on map"), role = Role.Button, onClick = { onStep(leg, null) })
            Row(Modifier.fillMaxWidth().then(action).height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.width(28.dp).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
                    Canvas(Modifier.fillMaxSize()) {
                        val x = size.width / 2
                        drawLine(accent.copy(alpha = 0.55f), androidx.compose.ui.geometry.Offset(x, 12.dp.toPx()),
                            androidx.compose.ui.geometry.Offset(x, size.height), strokeWidth = 3.dp.toPx(),
                            pathEffect = if (leg.isWalk) androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(5f, 6f)) else null)
                    }
                    Box(Modifier.padding(top = 7.dp).size(27.dp).clip(CircleShape).background(accent), contentAlignment = Alignment.Center) {
                        Icon(vehicleIcon(leg.vehicle), null, Modifier.size(15.dp), tint = transitInk(leg))
                    }
                }
                Column(Modifier.weight(1f).padding(bottom = 9.dp).clip(RoundedCornerShape(13.dp))
                    .background(if (index == activeLeg) interfaceGround else Color.Transparent).padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (index == activeLeg) Text(localizeLabel("Current leg"), fontSize = KType.caption, color = interfaceAccentText)
                    if (leg.isWalk) {
                        val to = leg.toName.takeUnless { it.isBlank() || it == "END" } ?: "the destination"
                        Text(kt("Walk to $to", "Pěšky na $to"), fontSize = KType.subheadline, fontWeight = FontWeight.Bold, color = primaryText)
                        Text("${Fmt.duration(leg.durationSeconds)} · ${Fmt.distance(leg.distanceMeters)}",
                            fontSize = KType.caption, color = secondaryText)
                    } else {
                        if (transfer) Text(localizeLabel("Transfer"), fontSize = KType.caption, color = interfaceAccentText, fontWeight = FontWeight.Bold)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            LineChip(leg)
                            leg.headsign?.let { Text(kt("Toward $it", "Směr $it"), fontSize = KType.subheadline, color = primaryText, modifier = Modifier.weight(1f)) }
                        }
                        val status = live[leg.tripId]
                        TransitStatus(leg, status)
                        val platform = (status?.board?.platform ?: leg.calls.firstOrNull()?.platform)
                            ?.takeIf(String::isNotBlank)?.let { " · Platform $it" }.orEmpty()
                        Text(times(status?.board?.departure ?: leg.depart, "Board at ${leg.fromName}") + platform, fontSize = KType.subheadline, color = primaryText)
                        val intermediate = leg.calls.drop(1).dropLast(1)
                        if (intermediate.isNotEmpty()) {
                            TextButton({ expanded = !expanded }, contentPadding = PaddingValues(horizontal = 4.dp)) {
                                Text(kt("${intermediate.size} intermediate stops", "Mezilehlé zastávky · ${intermediate.size}"))
                                Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null)
                            }
                            if (expanded) intermediate.forEach { call ->
                                val updated = status?.matchCall(call) ?: call
                                val delay = (updated.arrivalDelay ?: updated.departureDelay)?.let(::transitDelay)
                                val name = if (updated.cancelled) call.name + " · Stop skipped"
                                    else listOfNotNull(call.name, delay).joinToString(" · ")
                                Text(times(updated.arrival ?: updated.departure, name), fontSize = KType.caption, color = secondaryText,
                                    modifier = if (onStep == null) Modifier else Modifier.tap { onStep(leg, call.at) })
                            }
                        } else Text(Fmt.duration(leg.durationSeconds), fontSize = KType.caption, color = secondaryText)
                        val exitPlatform = (status?.exit?.platform ?: leg.calls.lastOrNull()?.platform)
                            ?.takeIf(String::isNotBlank)?.let { " · Platform $it" }.orEmpty()
                        Text(times(status?.exit?.arrival ?: leg.arrive, "Get off at ${leg.toName}") + exitPlatform,
                            fontSize = KType.subheadline, color = primaryText)
                    }
                }
            }
        }
    }
}

/** A full-screen page with a title bar and Cancel, used for the pickers. */
@Composable
fun PickerPage(title: String, close: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    FullScreenDialog(close) {
        val dark = LocalDark.current
        Column(Modifier.fillMaxSize().background(interfaceGround).systemBarsPadding().imePadding()) {
            Box(Modifier.fillMaxWidth().height(56.dp).background(interfaceSheet)) {
                Text(localizeLabel("Cancel"), fontSize = KType.body, color = interfaceAccentText, modifier = Modifier.align(Alignment.CenterStart)
                    .padding(start = 8.dp).clip(RoundedCornerShape(8.dp)).tap(close).padding(horizontal = 10.dp, vertical = 8.dp))
                Text(title, fontSize = KType.headline, fontWeight = FontWeight.SemiBold, color = primaryText, modifier = Modifier.align(Alignment.Center))
            }
            content()
        }
    }
}

/** The search box at the top of a picker. */
@Composable
fun PickerSearchField(
    query: String, onQuery: (String) -> Unit, prompt: String, autoFocus: Boolean = true,
    onFocused: () -> Unit = {}, onSearch: () -> Unit,
) {
    val dark = LocalDark.current
    var voiceUnavailable by remember { mutableStateOf(false) }
    val dictate = rememberDictation({ voiceUnavailable = false; onQuery(it) }, { voiceUnavailable = true })
    val requester = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(autoFocus) { if (autoFocus) runCatching { requester.requestFocus() } }
    Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().height(48.dp).clip(RoundedCornerShape(14.dp))
        .background(interfaceSurface).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Filled.Search, null, tint = secondaryText, modifier = Modifier.size(22.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) Text(localizeLabel(prompt), fontSize = KType.body, color = secondaryText)
            BasicTextField(query, onQuery, Modifier.fillMaxWidth().focusRequester(requester).onFocusChanged { if (it.isFocused) onFocused() }, singleLine = true,
                textStyle = TextStyle(fontSize = KType.body, color = primaryText, fontFamily = Rounded), cursorBrush = SolidColor(interfaceAccent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { onSearch() }))
        }
        if (query.isNotEmpty()) Icon(Icons.Filled.Cancel, localizeLabel("Clear"), tint = secondaryText, modifier = Modifier.size(20.dp).tap { onQuery("") })
        IconButton(dictate, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Filled.Mic, kt("Dictate search", "Diktovat hledání"), tint = interfaceAccentText)
        }
    }
    if (voiceUnavailable) Text(kt("Voice input is unavailable on this phone.", "Hlasové zadávání není v tomto telefonu dostupné."),
        color = secondaryText, modifier = Modifier.padding(horizontal = 18.dp))
}

/** A section title in a picker list. */
@Composable
fun SectionTitle(text: String) {
    Text(localizeLabel(text), fontSize = KType.subheadline, fontWeight = FontWeight.SemiBold, color = secondaryText,
        modifier = Modifier.padding(horizontal = 18.dp).padding(top = 16.dp, bottom = 4.dp))
}

/**
 * At the other end (GitHub #63): a big place's main entrance, to drive to the door instead of the
 * middle of the building, and the car parks near it. Two slim rows, only when there's something to say.
 */
@Composable
fun ArrivalRows(model: AppModel, state: DirectionsState) {
    val dark = LocalDark.current
    val place = state.original ?: state.destination
    var door by remember(place.id) { mutableStateOf<LatLng?>(null) }
    var parks by remember(place.id) { mutableStateOf<List<CarPark>>(emptyList()) }
    var showParks by remember { mutableStateOf(false) }
    LaunchedEffect(place.id) {
        val found = async(Dispatchers.IO) { ArrivalHelp.parking(place.location) }
        if (ArrivalHelp.isBigPlace(place.category)) door = withContext(Dispatchers.IO) { ArrivalHelp.mainEntrance(place.location) }
        parks = found.await() ?: emptyList()
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val note = state.endNote
        if (note != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(if (note == "Main entrance") Icons.Filled.DoorFront else Icons.Filled.LocalParking, null, tint = Vela.routeBlue, modifier = Modifier.size(20.dp))
                Text(if (note == "Main entrance") "Going to the main entrance" else "Parking at $note", fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                    color = primaryText, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Text(localizeLabel("Undo"), fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = interfaceAccentText, modifier = Modifier.tap { model.endAt(null, null) })
            }
        } else {
            door?.let { dr ->
                Row(Modifier.fillMaxWidth().tap { model.endAt(dr, "Main entrance") }, verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Filled.DoorFront, null, tint = Vela.routeBlue, modifier = Modifier.size(20.dp))
                    Text(localizeLabel("Drive to the main entrance"), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = primaryText)
                    Text("${Fmt.distance(dr.distanceTo(place.location))} from the pin", fontSize = 14.sp, color = secondaryText)
                }
            }
            if (parks.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().tap { showParks = true }, verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Filled.LocalParking, null, tint = Vela.routeBlue, modifier = Modifier.size(20.dp))
                    Text("${parks.size} car park${if (parks.size == 1) "" else "s"} near there", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = primaryText)
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = secondaryText, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
    if (showParks) {
        ModalBottomSheet(onDismissRequest = { showParks = false }, containerColor = interfaceSheet, dragHandle = null) {
            Column(Modifier.padding(20.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(kt("Parking near ${place.name}", "Parkování u místa ${place.name}"), fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = primaryText,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    CircleIconButton(Icons.Filled.Close) { showParks = false }
                }
                LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(parks, key = { it.id }) { c ->
                        Row(Modifier.fillMaxWidth().tap { showParks = false; model.endAt(c.location, c.name ?: "a car park") },
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(Icons.Filled.LocalParking, null, tint = Vela.routeBlue, modifier = Modifier.size(28.dp))
                            Column(Modifier.weight(1f)) {
                                Text(c.name ?: "Car park", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = primaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(listOfNotNull(c.kind, c.spaces?.let { "$it spaces" }, c.fee?.let { if (it) "Paid" else "Free" }).joinToString(" · "),
                                    fontSize = KType.caption, color = secondaryText)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text("${Fmt.walkMinutes(c.meters)} walk", fontSize = KType.subheadline, fontWeight = FontWeight.Medium, color = primaryText)
                                Text(localizeLabel("Park here"), fontSize = KType.caption, fontWeight = FontWeight.SemiBold, color = Vela.routeBlue)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Search for a new route endpoint without clearing the current itinerary. */
@Composable
fun EndpointPicker(model: AppModel, destination: Boolean = false) {
    var query by rememberSaveable(destination) { mutableStateOf("") }
    var submitted by rememberSaveable(destination) { mutableStateOf<String?>(null) }
    var withinOsm by rememberSaveable(destination) { mutableStateOf(AppSettings.osmOnlySearch) }
    val osm = AppSettings.noGoogle || withinOsm
    var suggestions by remember { mutableStateOf(SuggestParser.Result()) }
    var searching by remember { mutableStateOf(false) }
    val title = if (destination) "Change destination" else "Change start"
    val (center, span) = model.directions?.takeIf { it.missingOrigin }?.destination?.location?.let { it to 50_000.0 } ?: model.viewport
    fun close() {
        if (destination) model.pickingDestination = false else model.pickingOrigin = false
    }
    fun pick(place: Place) {
        close()
        if (destination) model.setDestination(place) else model.setOrigin(place)
    }
    fun search(text: String = query) {
        val trimmed = text.trim()
        if (trimmed.isNotBlank()) { query = text; submitted = trimmed }
    }
    LaunchedEffect(query, submitted, osm) {
        val text = query.trim()
        suggestions = SuggestParser.Result()
        searching = false
        if (text.length < 2) return@LaunchedEffect
        val full = submitted == text
        if (!full) delay(250)
        searching = true
        try {
            val linked = if (full && MapLinks.looksLikeLink(text)) MapLinks.resolve(text) else null
            suggestions = when {
                linked is MapLinks.Target.Spot -> SuggestParser.Result(places = listOf(
                    Place("pin:${linked.at.lat},${linked.at.lng}", linked.name ?: "Dropped pin", linked.at, "pin")))
                full && osm -> SuggestParser.Result(places = OpenPlaceSearch.search(text, center, span))
                full -> SuggestParser.Result(places = PlaceSearch.search(text, center, span, true).places)
                osm -> OpenPlaceSearch.suggestions(text, center)
                else -> {
                    val google = runCatching { GoogleClient.shared.suggest(text, center, span ?: 20_000.0) }
                        .getOrElse { if (it is kotlinx.coroutines.CancellationException) throw it else SuggestParser.Result() }
                    val near = PlaceSearch.nearFirst(google.places, center)
                    val matches = if (PlaceSearch.needsNearbyFallback(near, center)) {
                        val open = runCatching { Photon.search(text, center, 8) }
                            .getOrElse { if (it is kotlinx.coroutines.CancellationException) throw it else emptyList() }
                        PlaceSearch.withNearbyFallback(near, open, center)
                    } else near
                    google.copy(places = matches)
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            suggestions = SuggestParser.Result()
        } finally {
            searching = false
        }
    }

    PickerPage(title, ::close) {
        PickerSearchField(query, { query = it; submitted = null }, "Search places or addresses", autoFocus = false, onSearch = { search() })
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            item {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (!AppSettings.noGoogle) FilterChipLabel(if (osm) "OpenStreetMap" else kt("All sources", "Všechny zdroje"), osm, menu = false) { withinOsm = !withinOsm }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!destination || model.userLatLng != null) FilledTonalButton({
                            if (destination) model.userLatLng?.let { pick(Place("here", kt("Your location", "Moje poloha"), it, "pin")) }
                            else { close(); model.setOrigin(null) }
                        }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Filled.MyLocation, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(kt("Your location", "Moje poloha"))
                        }
                        FilledTonalButton({ model.beginEndpointPin(destination) }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Filled.Map, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(kt("Pick on map", "Vybrat na mapě"))
                        }
                    }
                }
            }
            if (query.isNotBlank() && submitted != query.trim()) item {
                IconRow(Icons.Filled.Search, "Search for “${query.trim()}”") { search() }
            }
            if (searching) item { Text(localizeLabel("Searching…"), Modifier.padding(16.dp), color = secondaryText) }
            val places = if (query.isBlank()) {
                val s = AppSettings
                (listOfNotNull(s.home, s.work) + s.placeShortcuts + s.recentPlaces.take(6) + s.saved.take(8)).distinctBy { it.id }
            } else suggestions.places.distinctBy { it.id }.take(25)
            if (places.isNotEmpty() && query.isBlank()) item { SectionTitle("Saved and recent") }
            items(places, key = { it.id }) { place ->
                Box(Modifier.padding(horizontal = 16.dp).clip(RoundedCornerShape(18.dp)).background(interfaceSurface)) {
                    PlaceResultSummary(place, onOpen = { pick(place) }, here = model.userLatLng, openLabel = "Use this place") {
                        FilledTonalIconButton({ pick(place) }) { Icon(Icons.Filled.Check, "Use ${place.name}") }
                    }
                }
            }
            if (query.isBlank()) {
                item { SectionTitle("Nearby places") }
                items(Category.all.distinctBy { it.query }, key = { it.query }) { IconRow(it.icon, it.label) { search(it.query) } }
            } else {
                items(suggestions.queries.distinct()) { next -> IconRow(Icons.Filled.Search, next) { search(next) } }
                if (submitted != null && !searching && places.isEmpty()) item {
                    Text(localizeLabel("No matching places"), Modifier.padding(16.dp), color = secondaryText)
                }
            }
        }
    }
}

/** Search for a stop, then choose a result on the map. */
@Composable
fun StopPicker(model: AppModel) {
    var query by rememberSaveable { mutableStateOf(model.stopSearch?.query.orEmpty()) }
    val close = { model.clearStopDraft(); model.addingStop = false }
    DisposableEffect(Unit) { onDispose { model.clearStopDraft() } }
    LaunchedEffect(query) {
        model.clearStopDraft()
        if (query.trim().length >= 2) {
            delay(475)
            model.searchStops(query, named = OutdoorData.kind(query) == null, draft = true)
        }
    }
    fun search() { model.searchStops(query, named = OutdoorData.kind(query) == null) }
    var detent by rememberSaveable { mutableStateOf(SheetDetent.HALF) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val imeOpen = WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current) > 0
    fun hideKeyboard() {
        keyboard?.hide()
        focus.clearFocus()
    }
    fun resize(value: SheetDetent) {
        if (value != SheetDetent.FULL) hideKeyboard()
        detent = value
    }
    androidx.activity.compose.BackHandler {
        when {
            imeOpen -> hideKeyboard()
            detent == SheetDetent.FULL -> resize(SheetDetent.HALF)
            else -> close()
        }
    }
    // Keep the panel's anchors independent of the animated IME insets.
    BoxWithConstraints(Modifier.fillMaxSize().statusBarsPadding()) {
        val height = maxHeight
        Box(Modifier.align(Alignment.BottomCenter)) {
            BottomSheet(detent, ::resize, height, opaque = true, allowPeek = false,
                halfFraction = 0.62f, fullFraction = 1f, edgeToEdge = true,
                header = {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.add_stop_title), Modifier.weight(1f), fontSize = KType.headline, fontWeight = FontWeight.Bold, color = primaryText)
                        IconButton({ resize(if (detent == SheetDetent.FULL) SheetDetent.HALF else SheetDetent.FULL) }) {
                            Icon(if (detent == SheetDetent.FULL) Icons.Filled.ExpandMore else Icons.Filled.ExpandLess, "Resize search panel")
                        }
                        IconButton(close) { Icon(Icons.Filled.Close, "Close stop search") }
                    }
                    PickerSearchField(query, { query = it }, "Search by name or address", autoFocus = false, onFocused = { resize(SheetDetent.FULL) }, onSearch = ::search)
                }, content = {
                    LazyColumn(Modifier.fillMaxSize().imePadding().navigationBarsPadding(), contentPadding = PaddingValues(bottom = 16.dp)) {
                        if (query.trim().isNotEmpty()) {
                            val current = model.stopDraft?.takeIf { it.query == query.trim() }
                            if (current?.loading == true) item {
                                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                    Text(localizeLabel("Searching along your route…"), color = secondaryText)
                                }
                            }
                            if (current != null && !current.loading && current.results.isEmpty()) item {
                                Text(localizeLabel("No nearby results along this route"), Modifier.padding(16.dp), color = secondaryText)
                            }
                            items(current?.results.orEmpty(), key = { it.place.id }) { candidate ->
                                val place = candidate.place
                                Box(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                                    .clip(RoundedCornerShape(17.dp)).background(interfaceSurface)) {
                                    PlaceResultSummary(place, onOpen = { model.acceptStop(candidate) }, here = model.userLatLng,
                                        openLabel = "Add ${place.name}") {
                                        FilledTonalIconButton({ model.acceptStop(candidate) }) {
                                            Icon(Icons.Filled.Add, "Add ${place.name}")
                                        }
                                    }
                                }
                            }
                            if (query.trim().length >= 2) item {
                                IconRow(Icons.Filled.Map, "Show results on map") { search() }
                            }
                            return@LazyColumn
                        }
                        item { IconRow(Icons.Filled.AddLocationAlt, "Choose a point on the map") { model.beginStopPin() } }
                        item { SectionTitle("Along the route") }
                        val categories = Category.all.filter { it.icon in listOf(Icons.Filled.LocalGasStation,
                            Icons.Filled.LocalCafe, Icons.Filled.Restaurant, Icons.Filled.ShoppingCart,
                            Icons.Filled.LocalParking, Icons.Filled.LocalPharmacy) } +
                            listOf(Category("EV charging", Icons.Filled.EvStation, "ev charging station"),
                                Category(if (Fmt.yards) "Toilets" else "Restrooms", Icons.Filled.Wc,
                                    if (Fmt.yards) "public toilets" else "public restroom"))
                        items(categories.distinctBy { it.label }) { c -> IconRow(c.icon, c.label) { model.searchStops(c.query) } }
                        val s = AppSettings
                        val mine = (listOfNotNull(s.home?.let { it to Icons.Filled.Home }, s.work?.let { it to Icons.Filled.Work }) +
                            (s.placeShortcuts + s.saved.take(12)).map { it to Icons.Filled.Bookmark }).distinctBy { it.first.id }
                        if (mine.isNotEmpty()) {
                            item { SectionTitle("Your places") }
                            items(mine) { (p, icon) ->
                                IconRow(icon, p.name, p.address) { model.acceptStop(StopCandidate(p, null, 0.0)) }
                            }
                        }
                    }
                })
        }
    }
}

private enum class StopPanel { MAP, HALF, FULL }

@Composable
fun StopMapOverlay(model: AppModel, showMapbox: Boolean) {
    val search = model.stopSearch ?: return
    var panel by rememberSaveable(search.id) { mutableStateOf(StopPanel.HALF) }
    var returnPanel by rememberSaveable(search.id) { mutableStateOf(StopPanel.HALF) }
    var onlyOpen by rememberSaveable(search.id) { mutableStateOf(false) }
    val list = key(search.id) { rememberLazyListState() }
    fun expandList() {
        if (panel == StopPanel.FULL) return
        returnPanel = panel
        panel = StopPanel.FULL
    }
    LaunchedEffect(search.selected?.place?.id) {
        if (search.selected != null) panel = StopPanel.MAP
    }
    androidx.activity.compose.BackHandler {
        when (panel) {
            StopPanel.FULL -> panel = returnPanel
            StopPanel.HALF -> panel = StopPanel.MAP
            StopPanel.MAP -> model.closeStopSearch()
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().statusBarsPadding()) {
        val height = maxHeight
        if (showMapbox) MapboxAttribution(Modifier.align(Alignment.TopStart).padding(start = 16.dp, top = 62.dp))
        Row(Modifier.align(Alignment.TopCenter).padding(12.dp).fillMaxWidth().glass(RoundedCornerShape(22.dp))
            .padding(start = 16.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (search.parking) "Parking near destination" else search.query.replaceFirstChar { it.titlecase() }, modifier = Modifier.weight(1f),
                fontWeight = FontWeight.SemiBold, color = primaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!search.parking) TextButton({ model.addingStop = true }) { Text(localizeLabel("Search")) }
            IconButton({ model.closeStopSearch() }) { Icon(Icons.Filled.Close, "Close stop search") }
        }
        val selected = search.selected
        if (panel != StopPanel.MAP && !search.loading && search.results.isNotEmpty()) {
            Box(Modifier.align(Alignment.BottomCenter)) {
                StopResultsPanel(search, list, onlyOpen, { onlyOpen = it },
                    if (panel == StopPanel.FULL) SheetDetent.FULL else SheetDetent.HALF,
                    { if (it == SheetDetent.FULL) expandList() else panel = returnPanel },
                    height, close = { panel = StopPanel.MAP }) { candidate ->
                    model.showStopPin(candidate)
                    panel = StopPanel.MAP
                }
            }
        } else if (selected != null && !search.loading) {
            val ordered = if (onlyOpen && selected.place.openNow == true) {
                search.results.filter { it.place.openNow == true }
            } else search.results
            val selectedIndex = ordered.indexOfFirst { it.place.id == selected.place.id }.coerceAtLeast(0)
            val pager = rememberPagerState(initialPage = selectedIndex) { ordered.size }
            LaunchedEffect(pager, selected.place.id, ordered) {
                if (pager.settledPage != selectedIndex) pager.scrollToPage(selectedIndex)
                snapshotFlow { pager.settledPage }.drop(1).collect { page ->
                    ordered.getOrNull(page)?.let(model::showStopPin)
                }
            }
            HorizontalPager(pager, Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding(),
                contentPadding = PaddingValues(horizontal = if (ordered.size > 1) 23.dp else 12.dp),
                pageSpacing = 8.dp, verticalAlignment = Alignment.Bottom,
                key = { ordered[it].place.id }) { page ->
                val candidate = ordered[page]
                var expanded by rememberSaveable { mutableStateOf(false) }
                Box(Modifier.padding(bottom = 12.dp)) {
                    StopResultCard(search, candidate, search.results.indexOf(candidate) + 1, expanded,
                        toggle = { expanded = !expanded }, footer = {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp).padding(bottom = 9.dp),
                                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                TextButton(::expandList) { Text(stringResource(R.string.all_results)) }
                                if (ordered.size > 1) Text("${page + 1}/${ordered.size}", color = secondaryText, fontSize = KType.caption2)
                                Button({ model.acceptStop(candidate) }) { Text(if (search.parking) "Drive here" else "Add stop") }
                            }
                        })
                }
            }
        } else Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(12.dp)
            .glass(RoundedCornerShape(24.dp)).padding(horizontal = 16.dp, vertical = 12.dp)) {
            when {
                search.loading -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(if (search.parking) "Searching near the destination…" else "Looking along your route…", color = secondaryText)
                }
                search.results.isEmpty() -> Text(if (search.parking) "No parking found nearby." else "No stops found near the road ahead.", color = primaryText, modifier = Modifier.padding(vertical = 8.dp))
                else -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(localizeLabel("Choose a pin on the map"), Modifier.weight(1f), color = primaryText)
                    TextButton(::expandList) { Text("List (${search.results.size})", color = interfaceAccentText) }
                }
            }
        }
    }
}

@Composable
fun StopPinOverlay(model: AppModel, showMapbox: Boolean, center: () -> LatLng?) {
    MapPinOverlay("Add this stop", showMapbox, { model.finishStopPin() }) { center()?.let(model::finishStopPin) }
}

@Composable
fun EndpointPinOverlay(model: AppModel, showMapbox: Boolean, center: () -> LatLng?) {
    val label = if (model.placingEndpoint == true) "Set destination" else "Set start"
    MapPinOverlay(label, showMapbox, { model.finishEndpointPin() }) { center()?.let(model::finishEndpointPin) }
}

@Composable
private fun MapPinOverlay(label: String, showMapbox: Boolean, cancel: () -> Unit, confirm: () -> Unit) {
    androidx.activity.compose.BackHandler(onBack = cancel)
    Box(Modifier.fillMaxSize()) {
        Icon(Icons.Filled.AddLocationAlt, kt("Pin position", "Poloha špendlíku"), tint = interfaceAccentText,
            modifier = Modifier.align(Alignment.Center).offset(y = (-15).dp).size(38.dp))
        if (showMapbox) MapboxAttribution(Modifier.align(Alignment.TopStart).statusBarsPadding().padding(start = 16.dp, top = 88.dp))
        Row(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(12.dp).glass(RoundedCornerShape(24.dp), solid = true)
            .padding(start = 14.dp, end = 5.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(interfaceHighlight), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.MyLocation, null, tint = interfaceOnHighlight, modifier = Modifier.size(20.dp))
            }
            Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
                Text(label, fontWeight = FontWeight.Bold, color = primaryText)
                Text(localizeLabel("Move the map to place the pin"), color = secondaryText, fontSize = KType.caption)
            }
            CircleIconButton(Icons.Filled.Close, description = localizeLabel("Cancel"), onClick = cancel)
        }
        Button(confirm, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(18.dp).heightIn(min = 52.dp)) {
            Icon(Icons.Filled.Check, null, Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(label)
        }
    }
}

@Composable
fun DistanceMeasure(model: AppModel, modifier: Modifier = Modifier) {
    val (a, b) = model.measurement ?: return
    Row(modifier.padding(12.dp).glass(RoundedCornerShape(16.dp)).padding(start = 12.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Straighten, null, tint = interfaceAccentText)
        Column(Modifier.padding(start = 8.dp)) {
            Text(Fmt.distance(a.distanceTo(b)), fontWeight = FontWeight.Bold, color = primaryText)
            Text(localizeLabel("Straight-line distance"), fontSize = KType.caption, color = secondaryText)
        }
        IconButton({ model.measurement = null }) { Icon(Icons.Filled.Close, "Clear measurement") }
    }
}
