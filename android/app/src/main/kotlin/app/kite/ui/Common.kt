@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.kite.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.snap
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import kotlin.math.roundToInt
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import app.kite.*
import app.kite.core.*
import kotlin.math.abs

/** A tap target with no ripple flash, for rows that change color themselves. */
fun Modifier.tap(onClick: () -> Unit): Modifier = this.clickable(onClick = onClick)

/**
 * Kite's draggable bottom sheet: peek, half and full heights, like the iPhone app's. Drag it from
 * anywhere: below full height the whole sheet moves; at full height its list scrolls, and
 * pulling down from the top of the list brings the sheet down.
 */
@Composable
fun BottomSheet(
    detent: SheetDetent,
    onDetent: (SheetDetent) -> Unit,
    containerHeight: Dp,
    peekHeight: Dp = 230.dp,
    /** Room kept clear above the sheet at full height (the directions from/to card). */
    topClearance: Dp = 0.dp,
    opaque: Boolean = false,
    allowPeek: Boolean = true,
    halfFraction: Float = 0.46f,
    fullFraction: Float = 0.94f,
    edgeToEdge: Boolean = false,
    onDismiss: (() -> Unit)? = null,
    dismissOnDragFromExpanded: Boolean = true,
    detentAnimationMillis: Int? = null,
    header: @Composable ColumnScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val dark = LocalDark.current
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val effectiveDetent = if (detent == SheetDetent.PEEK && !allowPeek) SheetDetent.HALF else detent
    fun height(d: SheetDetent): Float = with(density) {
        when (d) {
            SheetDetent.PEEK -> peekHeight.toPx()
            SheetDetent.HALF -> containerHeight.toPx() * halfFraction
            SheetDetent.FULL -> if (topClearance > 0.dp) containerHeight.toPx() - topClearance.toPx() else containerHeight.toPx() * fullFraction
        }
    }
    var drag by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    var dismissing by remember { mutableStateOf(false) }
    val target = height(effectiveDetent) - drag
    val animated = animateFloatAsState(target, when {
        dragging -> snap()
        detentAnimationMillis != null -> tween(detentAnimationMillis)
        else -> spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 410f)
    }, label = "sheet")
    val lowestVisible = minOf(height(if (allowPeek) SheetDetent.PEEK else SheetDetent.HALF), height(SheetDetent.FULL)).coerceAtLeast(0f)
    val canDismiss = onDismiss != null
    val low = if (canDismiss) 0f else lowestVisible
    val top = maxOf(lowestVisible, height(SheetDetent.FULL))

    val applyDetent by rememberUpdatedState(onDetent)
    val dismiss by rememberUpdatedState(onDismiss)
    fun settle(velocity: Float) {
        if (dismissing) return
        val end = height(effectiveDetent) - drag - velocity * 0.18f
        val dismissAction = dismiss
        val canDismissFromHere = dismissOnDragFromExpanded || effectiveDetent == SheetDetent.PEEK
        if (dismissAction != null && canDismissFromHere && shouldDismissSheet(end, lowestVisible)) {
            dismissing = true
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            // Leave the outgoing card where the gesture released it. The parent animates it off screen.
            dismissAction()
            return
        }
        val best = SheetDetent.entries.filter { allowPeek || it != SheetDetent.PEEK }.minBy { abs(height(it) - end) }
        if (best != effectiveDetent) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        dragging = false
        drag = 0f
        applyDetent(best)
    }

    val connection = remember(effectiveDetent, allowPeek, canDismiss, dismissOnDragFromExpanded, containerHeight, topClearance, peekHeight, halfFraction, fullFraction, density) {
        object : NestedScrollConnection {
            private fun dragWithinSheet(delta: Float): Offset {
                val anchor = height(effectiveDetent)
                val limited = (drag + delta).coerceIn(anchor - top, anchor - low)
                val taken = limited - drag
                if (taken != 0f) {
                    dragging = true
                    drag = limited
                }
                return Offset(0f, taken)
            }
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput) return Offset.Zero
                // Content scrolls once it reaches full height. A downward pull first scrolls the
                // content back to its top, then the sheet consumes the remainder.
                return if (available.y < 0 && (effectiveDetent != SheetDetent.FULL || drag != 0f))
                    dragWithinSheet(available.y) else Offset.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
                if (source == NestedScrollSource.UserInput && available.y > 0) dragWithinSheet(available.y) else Offset.Zero

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (!dragging) return Velocity.Zero
                settle(available.y)
                return Velocity(0f, available.y)
            }
            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                if (dragging) settle(available.y)
                return Velocity.Zero
            }
        }
    }

    val floating = !edgeToEdge && detent != SheetDetent.FULL
    val shape = if (edgeToEdge) RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
        else RoundedCornerShape(if (floating) 34.dp else 28.dp)
    Column(
        Modifier
            .padding(horizontal = if (floating) 8.dp else 0.dp)
            .padding(bottom = if (floating) 8.dp else 0.dp)
            .fillMaxWidth()
            .layout { child, constraints ->
                val height = (if (dragging) target else animated.value).coerceIn(low, top)
                    .roundToInt().coerceIn(constraints.minHeight, constraints.maxHeight)
                val placeable = child.measure(constraints.copy(minHeight = height, maxHeight = height))
                layout(placeable.width, height) { placeable.placeRelative(0, 0) }
            }
            .shadow(if (floating) 5.dp else 0.dp, shape)
            .clip(shape)
            .background(interfaceSheet.copy(alpha = if (opaque) 1f else 0.97f))
            .border(0.75.dp, Color.White.copy(alpha = if (dark) 0.12f else 0.5f), shape)
            .nestedScroll(connection)
    ) {
        Column(
            Modifier.fillMaxWidth()
                .draggable(rememberDraggableState { delta ->
                    dragging = true
                    val anchor = height(effectiveDetent)
                    drag = (drag + delta).coerceIn(anchor - top, anchor - low)
                }, Orientation.Vertical,
                    onDragStarted = { dragging = true }, onDragStopped = { v -> settle(v) })
                .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) {
                    if (detent == SheetDetent.PEEK) onDetent(SheetDetent.HALF)
                },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.padding(top = 8.dp, bottom = 6.dp).size(36.dp, 4.dp).clip(CircleShape).background(secondaryText.copy(alpha = 0.45f)))
            Column(Modifier.fillMaxWidth()) { header() }
        }
        Column(Modifier.fillMaxWidth().weight(1f)) { content() }
    }
}

internal fun shouldDismissSheet(projectedHeight: Float, lowestVisible: Float): Boolean =
    lowestVisible > 0f && projectedHeight < lowestVisible * 0.66f

/**
 * The bar along the bottom: Map, Saved and Settings as three icons in one slim see-through pill,
 * with a dot under the page you're on. Each is still a big target (72 by 60).
 */
@Composable
fun KiteDock(model: AppModel, modifier: Modifier = Modifier) {
    Box(modifier.navigationBarsPadding().padding(bottom = 10.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
        Row(Modifier.height(60.dp).glass(CircleShape).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            @Composable
            fun item(tab: AppTab, icon: ImageVector, title: String) {
                val on = model.tab == tab
                val c = if (on) interfaceAccentText else secondaryText
                Column(
                    Modifier.size(72.dp, 60.dp).clip(CircleShape).clickable {
                        if (tab != model.tab && model.tab == AppTab.SETTINGS) model.reloadRoutes()
                        model.tab = tab
                    },
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterVertically),
                ) {
                    Icon(icon, title, tint = c, modifier = Modifier.size(26.dp))
                    Box(Modifier.size(5.dp).clip(CircleShape).background(if (on) c else Color.Transparent))
                }
            }
            item(AppTab.MAP, Icons.Filled.Map, localizeLabel("Map"))
            item(AppTab.SAVED, Icons.Filled.Bookmark, localizeLabel("Saved"))
            item(AppTab.SETTINGS, Icons.Filled.Settings, localizeLabel("Settings"))
        }
    }
}

@Composable
fun HeatLegend() {
    Row(Modifier.height(28.dp).glass(CircleShape).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(Icons.Filled.LocalFireDepartment, null, tint = Color(0xFF8B5CF6), modifier = Modifier.size(13.dp))
        Text(localizeLabel("Police"), fontSize = KType.caption2, fontWeight = FontWeight.SemiBold, color = primaryText)
        Box(Modifier.size(54.dp, 6.dp).clip(CircleShape).background(Brush.horizontalGradient(listOf(Color(0xFF3B82F6), Color(0xFF8B5CF6), Color(0xFFE879F9)))))
        Text("often", fontSize = KType.caption2, color = secondaryText)
    }
}

object PoliceRiskColors {
    fun color(l: PoliceRisk.Level) = when (l) {
        PoliceRisk.Level.HIGH -> Color(0xFFDC2626)
        PoliceRisk.Level.MEDIUM -> Color(0xFFF59E0B)
        PoliceRisk.Level.LOW -> Color(0xFF16A34A)
    }
}

fun PoliceRisk.Level.word() = localizeLabel(name.lowercase().replaceFirstChar { it.uppercase() })

@Composable
fun PoliceRiskChip(score: Int, label: String = "Police risk") {
    val level = PoliceRisk.level(score)
    val fg = if (level == PoliceRisk.Level.MEDIUM) Color(0xFF1F1300) else Color.White
    Row(Modifier.height(36.dp).clip(CircleShape).background(PoliceRiskColors.color(level)).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Filled.Shield, null, tint = fg, modifier = Modifier.size(17.dp))
        Text("${localizeLabel(label)} · ${level.word()}", color = fg, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun PoliceRiskButton(score: Int, opensLeft: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    val space = with(LocalDensity.current) { 56.dp.roundToPx() }
    val level = PoliceRisk.level(score)
    Box {
        MapFab(Icons.Filled.Shield, tint = PoliceRiskColors.color(level),
            modifier = Modifier.semantics { contentDescription = "${localizeLabel("Police risk")} · ${level.word()}" }) {
            expanded = !expanded
        }
        if (expanded) Popup(
            alignment = if (opensLeft) Alignment.CenterEnd else Alignment.CenterStart,
            offset = IntOffset(if (opensLeft) -space else space, 0),
            onDismissRequest = { expanded = false },
            properties = PopupProperties(focusable = true),
        ) { PoliceRiskChip(score) }
    }
}

/** Show the heat map key briefly after switching it on. */
@Composable
fun WazeMapChips(model: AppModel) {
    val s = AppSettings
    if (!s.wazeOn) return
    // The heat map was just switched on: its key is showing.
    var legend by remember { mutableStateOf(false) }
    var heatWas by remember { mutableStateOf(s.wazeHeatmap) }
    if (heatWas != s.wazeHeatmap) { heatWas = s.wazeHeatmap; legend = s.wazeHeatmap }
    // Six seconds once the Layers screen is out of the way, so the key is actually seen.
    LaunchedEffect(legend, model.showLayers) {
        if (legend && !model.showLayers) { kotlinx.coroutines.delay(6000); legend = false }
    }
    if (legend && s.wazeHeatmap && s.wazePolice) HeatLegend()
}

/** "Carry on to Tesco Portswood?": the trip that was going when Kite closed, back in one tap. */
@Composable
fun ResumeCard(trip: SavedTrip, onGo: () -> Unit, onClose: () -> Unit) {
    val dark = LocalDark.current
    Column(Modifier.padding(horizontal = 12.dp).fillMaxWidth().glass(RoundedCornerShape(22.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(localizeLabel("Carry on to"), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = secondaryText)
                Text(trip.destination.name + "?", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = primaryText, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, lineHeight = 26.sp)
                if (trip.stops.isNotEmpty()) Text("via ${trip.stops.joinToString(", ") { it.name }}", fontSize = 15.sp, color = secondaryText,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            CircleIconButton(Icons.Filled.Close, Modifier.semantics { contentDescription = localizeLabel("Not now") }, onClick = onClose)
        }
        Row(Modifier.fillMaxWidth().height(60.dp).clip(CircleShape).background(interfaceAccent).clickable(onClick = onGo),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally)) {
            Icon(Icons.Filled.Navigation, null, tint = interfaceOnAccent, modifier = Modifier.size(26.dp))
            Text(localizeLabel("Carry on"), fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = interfaceOnAccent)
        }
    }
}

@Composable
fun NoticeBanner(text: String, dismiss: () -> Unit) {
    Row(Modifier.padding(horizontal = 12.dp).fillMaxWidth().glass(RoundedCornerShape(14.dp)).padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(Icons.Outlined.Info, null, tint = secondaryText, modifier = Modifier.size(18.dp))
        Text(localizeLabel(text), fontSize = KType.footnote, color = primaryText, modifier = Modifier.weight(1f))
        Icon(Icons.Filled.Close, "Dismiss", tint = primaryText, modifier = Modifier.size(18.dp).clickable(onClick = dismiss))
    }
}

/** Why the map can't do its job right now: location turned off, or no internet. */
@Composable
fun StatusCards() {
    val ctx = LocalContext.current
    val loc = LocationService
    val fix = loc.last
    when {
        loc.denied || DemoScript.step == "locationoff" -> StatusCard(Icons.Filled.LocationOff, Vela.closed, "Location is off",
            "Kite needs it to show where you are and to guide you.", "Turn on") { LocationService.openSettings(ctx) }
        loc.authorized && loc.systemOff -> StatusCard(Icons.Filled.LocationOff, Vela.closed, "Location is off",
            "Turn on Location on this phone to see where you are and get directions.", "Turn on") {
            ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS))
        }
        loc.authorized && loc.reducedAccuracy -> StatusCard(Icons.Filled.MyLocation, Color(0xFFF59E0B), "Precise location is off",
            "Allow precise location for Kite in Settings to improve your position.", "Settings") { LocationService.openSettings(ctx) }
        loc.authorized && fix != null && fix.accuracy > 200 -> StatusCard(Icons.Filled.MyLocation, Color(0xFFF59E0B), "Location is uncertain",
            "GPS accuracy is about ${fix.accuracy.toInt()} m. Move outdoors for a better fix.", null) {}
        !Connectivity.online -> StatusCard(Icons.Filled.WifiOff, Color(0xFFF59E0B), "No internet connection",
            "Search, routes and live traffic need one. The map you've already seen still works.", null) {}
    }
}

@Composable
private fun StatusCard(icon: ImageVector, color: Color, title: String, detail: String, button: String?, action: () -> Unit) {
    val dark = LocalDark.current
    Row(Modifier.padding(horizontal = 16.dp).fillMaxWidth().glass(RoundedCornerShape(22.dp)).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(22.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(localizeLabel(title), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = primaryText)
            Text(localizeLabel(detail), fontSize = 15.sp, color = secondaryText)
        }
        if (button != null) {
            Box(Modifier.height(44.dp).clip(CircleShape).background(interfaceAccent).clickable(onClick = action).padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center) {
                Text(button, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = interfaceOnAccent)
            }
        }
    }
}

/** Red-and-gray needle pointing north. */
@Composable
fun CompassNeedle(heading: Double, onClick: () -> Unit) {
    val gray = secondaryText
    Box(Modifier.size(48.dp).glass(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(9.dp, 25.dp).rotate(-heading.toFloat())) {
            val w = size.width; val h = size.height
            drawPath(Path().apply { moveTo(w / 2, 0f); lineTo(w, h / 2); lineTo(0f, h / 2); close() }, Color(0xFFEA4335))
            drawPath(Path().apply { moveTo(0f, h / 2); lineTo(w, h / 2); lineTo(w / 2, h); close() }, gray)
        }
    }
}

@Composable
fun MapLookButton(look: MapLook, size: Dp = 52.dp, onClick: () -> Unit) {
    val label = if (look == MapLook.TILTED) "3D" else "2D"
    Box(Modifier.size(size).glass(CircleShape, solid = true).clickable(onClick = onClick)
        .semantics { contentDescription = kt("Map view: $label", "Pohled mapy: $label") }, contentAlignment = Alignment.Center) {
        Text(label, fontSize = (size.value * 0.34f).sp, fontWeight = FontWeight.Black, color = mapControlTint)
    }
}

@Composable
fun CompassButton(heading: Double, enabled: Boolean, size: Dp = 44.dp, onClick: () -> Unit) {
    val gray = if (enabled) interfaceAccentText else secondaryText
    Box(Modifier.size(size).glass(CircleShape, solid = true)
        .then(if (enabled) Modifier.border(1.5.dp, interfaceAccent, CircleShape) else Modifier)
        .clickable(onClick = onClick)
        .semantics { contentDescription = if (enabled) kt("Disable phone compass", "Vypnout kompas telefonu")
            else kt("Rotate map with phone", "Otáčet mapu podle telefonu") },
        contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(9.dp, 23.dp).rotate(-heading.toFloat())) {
            val w = this.size.width
            val h = this.size.height
            drawPath(Path().apply { moveTo(w / 2, 0f); lineTo(w, h / 2); lineTo(0f, h / 2); close() }, Color(0xFFEA4335))
            drawPath(Path().apply { moveTo(0f, h / 2); lineTo(w, h / 2); lineTo(w / 2, h); close() }, gray)
        }
    }
}

/** Home or Work: one tap for directions, or to set it. */
@Composable
fun PlaceTile(model: AppModel, icon: ImageVector, color: Color, title: String, place: Place?, modifier: Modifier = Modifier,
              unset: () -> Unit, go: () -> Unit = {}, rename: (() -> Unit)? = null, remove: (() -> Unit)? = null, pin: (() -> Unit)? = null) {
    val dark = LocalDark.current
    var menu by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 74.dp).glass(RoundedCornerShape(22.dp))
                .combinedClickable(onClick = { if (place != null) { go(); model.openDirections(place) } else unset() },
                    onLongClick = { if (place != null) menu = true })
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.size(46.dp).clip(CircleShape).background(if (place == null) Color.Gray else color), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = Color.White, modifier = Modifier.size(22.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(localizeLabel(title), fontSize = 19.sp, fontWeight = FontWeight.Bold, color = primaryText, maxLines = 1)
                val d = place?.let { p -> model.userLatLng?.distanceTo(p.location) }
                Text(d?.let { Fmt.distance(it) } ?: if (place == null) "Tap to set" else place.address ?: "Tap to go",
                    fontSize = 15.sp, fontWeight = FontWeight.Medium, color = secondaryText, maxLines = 1)
            }
        }
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem(text = { Text(if (rename == null) "Change ${title.lowercase()}" else "Rename shortcut") },
                leadingIcon = { Icon(Icons.Filled.Edit, null) }, onClick = { menu = false; (rename ?: unset)() })
            if (pin != null) DropdownMenuItem(text = { Text(localizeLabel("Pin to Android launcher")) },
                leadingIcon = { Icon(Icons.Filled.PushPin, null) }, onClick = { menu = false; pin() })
            if (remove != null) DropdownMenuItem(text = { Text(localizeLabel("Remove shortcut")) },
                leadingIcon = { Icon(Icons.Filled.DeleteOutline, null) }, onClick = { menu = false; remove() })
        }
    }
}

/** Home and Work at the top of the search panel. */
@Composable
fun Shortcuts(model: AppModel, done: () -> Unit) {
    Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PlaceTile(model, Icons.Filled.Home, Vela.routeBlue, "Home", AppSettings.home, Modifier.weight(1f),
                unset = { done(); model.picking = PickKind.HOME }, go = done)
            PlaceTile(model, Icons.Filled.Work, Color(0xFF8B5CF6), "Work", AppSettings.work, Modifier.weight(1f),
                unset = { done(); model.picking = PickKind.WORK }, go = done)
        }
        CustomShortcuts(model, done, canAdd = false)
    }
}

@Composable
internal fun CustomShortcuts(model: AppModel, done: () -> Unit, canAdd: Boolean = true) {
    val settings = model.settings
    val context = LocalContext.current
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
        maxItemsInEachRow = 2, modifier = Modifier.fillMaxWidth()) {
        for (place in settings.placeShortcuts) key(place.id) {
            PlaceTile(model, Icons.Filled.Place, interfaceAccentText, place.name, place, Modifier.weight(1f),
                unset = {}, go = { done(); model.tab = AppTab.MAP },
                rename = { model.shortcutDraft = place },
                remove = { settings.placeShortcuts = settings.placeShortcuts.filterNot { it.id == place.id } },
                pin = {
                    if (!runCatching { QuickLaunch.pin(context, place) }.getOrDefault(false))
                        android.widget.Toast.makeText(context, "This launcher does not support pinning shortcuts.", android.widget.Toast.LENGTH_SHORT).show()
                })
        }
    }
    if (canAdd) OutlinedButton(onClick = { done(); model.picking = PickKind.SHORTCUT }) {
        Icon(Icons.Filled.Add, null, Modifier.size(19.dp))
        Spacer(Modifier.width(6.dp))
        Text(localizeLabel("Add shortcut"))
    }
}

@Composable
internal fun ShortcutNameDialog(model: AppModel, place: Place) {
    val settings = model.settings
    val existing = settings.placeShortcuts.any { it.id == place.id }
    var name by remember(place.id) { mutableStateOf(place.name) }
    fun close() { model.shortcutDraft = null }
    AlertDialog(
        onDismissRequest = ::close,
        title = { Text(if (existing) "Rename shortcut" else "Name your shortcut") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                place.address?.let { Text(it, color = secondaryText) }
                OutlinedTextField(name, { name = it }, singleLine = true, label = { Text(localizeLabel("Name")) }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val shortcut = place.copy(name = name.trim(), distanceMeters = null)
                settings.placeShortcuts = if (existing) settings.placeShortcuts.map { if (it.id == place.id) shortcut else it }
                    else settings.placeShortcuts + shortcut
                close()
            }, enabled = name.isNotBlank()) { Text(localizeLabel("Save")) }
        },
        dismissButton = { TextButton(::close) { Text(localizeLabel("Cancel")) } },
    )
}

/** A simple row with an icon and text, for lists in panels. */
@Composable
fun IconRow(icon: ImageVector, text: String, sub: String? = null, trailing: String? = null, height: Dp = 52.dp,
            onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = height).combinedClickable(onClick = onClick, onLongClick = onLongClick)
        .padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(icon, null, tint = secondaryText, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f)) {
            Text(localizeLabel(text), color = primaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub != null) Text(sub, fontSize = KType.caption, color = secondaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (trailing != null) Text(trailing, fontSize = KType.caption, color = secondaryText)
    }
}

/**
 * A Dialog that really covers the whole screen. Without this, on phones with a camera hole (Pixel),
 * Android starts the dialog's window below the hole, so its bottom (and any button there) ends up
 * off the screen.
 */
@Composable
fun FullScreenDialog(onDismiss: () -> Unit, dismissOnBack: Boolean = true, content: @Composable () -> Unit) {
    androidx.compose.ui.window.Dialog(onDismiss, androidx.compose.ui.window.DialogProperties(
        usePlatformDefaultWidth = false, decorFitsSystemWindows = false, dismissOnBackPress = dismissOnBack)) {
        val window = (androidx.compose.ui.platform.LocalView.current.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window
        SideEffect {
            window?.let { w ->
                w.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
                if (android.os.Build.VERSION.SDK_INT >= 28) w.attributes = w.attributes.apply {
                    layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
        }
        content()
    }
}
