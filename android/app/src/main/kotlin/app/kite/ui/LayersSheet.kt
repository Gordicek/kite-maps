package app.kite.ui

import androidx.compose.ui.res.stringResource
import app.kite.R
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import kotlinx.coroutines.launch
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kite.*
import app.kite.core.RoadCamera
import app.kite.core.Waze
import app.kite.core.WazeAlert

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LayersSheet(liveCount: Int? = null, close: () -> Unit) {
    val s = AppSettings
    val sections = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var editColors by rememberSaveable { mutableStateOf(false) }
    val hasHeatmap = WazeAlert.Kind.POLICE in Waze.shares
    ModalBottomSheet(
        onDismissRequest = close,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = interfaceSheet,
        contentColor = primaryText,
        dragHandle = { BottomSheetDefaults.DragHandle(color = secondaryText.copy(alpha = 0.4f)) },
    ) {
        Column(Modifier.fillMaxHeight(0.90f).fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.layers_title), fontSize = 29.sp, fontWeight = FontWeight.ExtraBold, color = primaryText,
                    modifier = Modifier.weight(1f))
                CircleIconButton(Icons.Filled.Close, description = "Close layers", onClick = close)
            }
            LazyColumn(
                Modifier.fillMaxWidth().weight(1f),
                state = sections,
                contentPadding = PaddingValues(bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                item {
                    LayerSection("Map type") {
                        Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            BasemapCard("Default", satellite = false, selected = !s.satellite, modifier = Modifier.weight(1f)) { s.satellite = false }
                            BasemapCard("Satellite", satellite = true, selected = s.satellite, modifier = Modifier.weight(1f)) { s.satellite = true }
                        }
                    }
                }
                item {
                    LayerSection(localizeLabel("Appearance")) {
                        Text(kt("Map appearance", "Vzhled mapy"), fontSize = KType.caption, fontWeight = FontWeight.SemiBold, color = secondaryText)
                        AppearanceOptions(s.mapStyleMode, MapStyleMode.entries, kt("App", "Aplikace")) { s.mapStyleMode = it }
                        Text(localizeLabel("App appearance"), fontSize = KType.caption, fontWeight = FontWeight.SemiBold, color = secondaryText)
                        AppearanceOptions(s.theme, ThemeMode.entries, kt("System", "Systém")) { s.theme = it }
                        FilledTonalButton({ editColors = !editColors }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Filled.Palette, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(localizeLabel("Interface colors"), Modifier.weight(1f))
                            Icon(if (editColors) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null)
                        }
                        if (editColors) Column(Modifier.clip(RoundedCornerShape(16.dp)).background(interfaceSurface)) {
                            MapInterfaceColors()
                        }
                    }
                }
                item {
                    LayerSection("Place icons") {
                        LayerRow {
                            if (!s.noGoogle) LayerToggle(Icons.Filled.Place, "Google Maps", s.googleMapPlaces) { s.googleMapPlaces = it }
                            LayerToggle(Icons.Filled.Map, "OpenStreetMap", s.openMapPlaces) { s.openMapPlaces = it }
                        }
                    }
                }
                item {
                    LayerSection("Routes & terrain") {
                        LayerRow {
                            LayerToggle(Icons.Filled.Hiking, "Hiking routes", s.hikingTrails) { s.hikingTrails = it }
                            LayerToggle(Icons.Filled.PedalBike, localizeLabel("Cycling routes"), s.bikeTrails) { s.bikeTrails = it }
                        }
                        LayerRow {
                            LayerToggle(Icons.Filled.Tram, localizeLabel("Transit"), s.transitLines) { s.transitLines = it }
                            LayerToggle(Icons.Filled.Landscape, localizeLabel("Hillshade"), s.terrain) { s.terrain = it }
                        }
                        if (s.standardMap && s.activeMapboxToken != null) LayerRow {
                            LayerToggle(Icons.Filled.Terrain, localizeLabel("3D terrain"), s.terrain3D) { s.terrain3D = it }
                        }
                    }
                }
                item(key = "on-map") {
                    FilledTonalButton({
                        s.layersOnMapExpanded = !s.layersOnMapExpanded
                        if (s.layersOnMapExpanded) scope.launch {
                            withFrameNanos { }
                            val header = sections.layoutInfo.visibleItemsInfo.firstOrNull { it.key == "on-map" }
                            if (header != null) {
                                sections.animateScrollToItem(header.index)
                            }
                        }
                    }, Modifier.fillMaxWidth()) {
                        Icon(Icons.Filled.Map, null, Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(stringResource(R.string.on_map), Modifier.weight(1f))
                        Icon(if (s.layersOnMapExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null)
                    }
                }
                item {
                    androidx.compose.animation.AnimatedVisibility(s.layersOnMapExpanded) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (s.standardMap && s.activeMapboxToken != null) {
                                LayerRow {
                                    LayerToggle(Icons.Filled.Apartment, localizeLabel("3D buildings"), s.mapBuildings) { s.mapBuildings = it }
                                    LayerToggle(Icons.Filled.Park, localizeLabel("Trees"), s.mapTrees) { s.mapTrees = it }
                                }
                                LayerRow {
                                    LayerToggle(Icons.Filled.Lightbulb, localizeLabel("Street lamps"), s.streetLamps) { s.streetLamps = it }
                                }
                            }
                            LayerRow {
                                LayerToggle(Icons.Filled.DirectionsWalk, localizeLabel("Footpaths"), s.footpaths) { s.footpaths = it }
                                LayerToggle(Icons.Filled.PedalBike, localizeLabel("Cycleways"), s.cycleways) { s.cycleways = it }
                            }
                            LayerRow {
                                LayerToggle(Icons.Filled.Cloud, stringResource(R.string.route_weather), s.routeWeather) { s.routeWeather = it }
                            }
                            LayerRow {
                                LayerToggle(Icons.Filled.Traffic, localizeLabel("Traffic"), s.traffic) { s.traffic = it }
                                LayerToggle("Road alerts", s.wazeOn && s.wazeOnMap, {
                                    WazeBadge(if (hasHeatmap) WazeAlert.Kind.POLICE else WazeAlert.Kind.CLOSURE, 25.dp)
                                }) { enabled ->
                                    if (enabled) s.waze = true
                                    s.wazeOnMap = enabled
                                }
                            }
                            LayerRow {
                                LayerToggle("Speed cameras", s.speedCameras, { CameraBadge(RoadCamera.Kind.SPEED, 25.dp) }) {
                                    s.speedCameras = it
                                }
                                LayerToggle("Plate cameras", s.plateCameras, { CameraBadge(RoadCamera.Kind.PLATE, 25.dp) }) {
                                    s.plateCameras = it
                                }
                            }
                            LayerRow {
                                LayerToggle(Icons.Filled.Videocam, "Surveillance", s.surveillanceCameras) { s.surveillanceCameras = it }
                                LayerToggle(Icons.Filled.LiveTv, localizeLabel("Live cameras"), s.liveCams && (s.liveRoadCams || s.showsWebcams)) { enabled ->
                                    s.liveCams = enabled
                                    if (enabled) {
                                        s.liveRoadCams = true
                                        s.liveWebcams = true
                                    }
                                }
                            }
                            if (hasHeatmap) LayerRow {
                                LayerToggle(Icons.Filled.LocalFireDepartment, localizeLabel("Police heat map"), s.wazeOn && s.wazeHeatmap) { enabled ->
                                    if (enabled) s.waze = true
                                    s.wazeHeatmap = enabled
                                }
                            }
                            if (liveCount != null && s.wazeOn && (s.wazeOnMap || s.wazeHeatmap)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Box(Modifier.size(7.dp).clip(CircleShape).background(Vela.open))
                                    Text("$liveCount live Waze report${if (liveCount == 1) "" else "s"}",
                                        fontSize = KType.caption, color = secondaryText)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LayerSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(localizeLabel(title), fontSize = KType.subheadline, fontWeight = FontWeight.Bold, color = primaryText)
        content()
    }
}

@Composable
private fun LayerRow(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

@Composable
private fun BasemapCard(title: String, satellite: Boolean, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(17.dp)
    val color = if (selected) interfaceAccent else secondaryText.copy(alpha = 0.18f)
    Column(modifier.clip(shape).background(interfaceGround).border(if (selected) 1.5.dp else 1.dp, color, shape)
        .selectable(selected, role = Role.RadioButton, onClick = onClick)) {
        BasemapPreview(satellite, Modifier.fillMaxWidth().height(78.dp))
        Row(Modifier.fillMaxWidth().heightIn(min = 45.dp).padding(horizontal = 12.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(localizeLabel(title), color = primaryText, fontSize = KType.subheadline,
                fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            LayerCheck(selected)
        }
    }
}

@Composable
private fun BasemapPreview(satellite: Boolean, modifier: Modifier) {
    val dark = LocalDark.current
    val ground = when {
        satellite -> Color(0xFF2D4237)
        dark -> Color(0xFF202C3A)
        else -> Color(0xFFE6EBE5)
    }
    val field = when {
        satellite -> Color(0xFF667653)
        dark -> Color(0xFF2A443B)
        else -> Color(0xFFBFD8B7)
    }
    val water = if (satellite || dark) Color(0xFF2D5A69) else Color(0xFF9CCBDE)
    val road = when {
        satellite -> Color(0xFFADA590)
        dark -> Color(0xFF718093)
        else -> Color.White
    }
    Canvas(modifier) {
        fun point(x: Float, y: Float) = Offset(size.width * x, size.height * y)
        fun area(color: Color, vararg points: Pair<Float, Float>) {
            val path = Path().apply {
                val (firstX, firstY) = points.first()
                moveTo(size.width * firstX, size.height * firstY)
                for ((x, y) in points.drop(1)) lineTo(size.width * x, size.height * y)
                close()
            }
            drawPath(path, color)
        }
        drawRect(ground)
        area(field, 0f to 0f, .43f to 0f, .34f to .41f, .14f to .62f, 0f to .46f)
        area(lerp(field, ground, .26f), .69f to .48f, 1f to .36f, 1f to 1f, .52f to 1f)
        area(water, .58f to 0f, .68f to 0f, .72f to .28f, .57f to .64f, .52f to 1f,
            .40f to 1f, .49f to .59f, .63f to .24f)
        drawLine(road.copy(alpha = .8f), point(0f, .78f), point(1f, .18f), 3.dp.toPx(), StrokeCap.Round)
        drawLine(road.copy(alpha = .65f), point(.23f, 0f), point(.51f, 1f), 1.5.dp.toPx())
        drawLine(road.copy(alpha = .65f), point(.86f, 0f), point(.72f, 1f), 1.5.dp.toPx())
        drawLine(road.copy(alpha = .55f), point(0f, .49f), point(.90f, .84f), 1.dp.toPx())
    }
}

@Composable
private fun <T> AppearanceOptions(selected: T, modes: List<T>, automaticLabel: String, choose: (T) -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Row(Modifier.fillMaxWidth().clip(shape).background(interfaceGround).selectableGroup().padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        modes.forEachIndexed { index, mode ->
            val (icon, label) = when (index) {
                1 -> Icons.Filled.LightMode to kt("Light", "Světlý")
                2 -> Icons.Filled.DarkMode to kt("Dark", "Tmavý")
                3 -> Icons.Filled.WbTwilight to kt("Sunrise", "Slunce")
                else -> Icons.Filled.SettingsBrightness to automaticLabel
            }
            val on = mode == selected
            val tint = if (on) interfaceAccentText else secondaryText
            Column(Modifier.weight(1f).heightIn(min = 54.dp).clip(RoundedCornerShape(12.dp))
                .background(if (on) interfaceSurface else Color.Transparent)
                .selectable(on, role = Role.RadioButton) { choose(mode) }.padding(vertical = 7.dp, horizontal = 2.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Icon(icon, if (index == 3) kt("Sunrise and sunset", "Podle východu a západu slunce") else null, tint = tint, modifier = Modifier.size(19.dp))
                Text(label, fontSize = KType.caption, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                    color = if (on) primaryText else secondaryText, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun RowScope.LayerToggle(icon: ImageVector, title: String, enabled: Boolean, toggle: (Boolean) -> Unit) {
    val tint = if (enabled) interfaceAccentText else secondaryText
    LayerToggle(title, enabled, { Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp)) }, toggle)
}

@Composable
private fun RowScope.LayerToggle(title: String, enabled: Boolean, icon: @Composable () -> Unit, toggle: (Boolean) -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    val background = if (enabled) lerp(interfaceGround, interfaceAccent, 0.10f) else interfaceGround
    val borderColor = if (enabled) interfaceAccent.copy(alpha = 0.60f) else secondaryText.copy(alpha = 0.16f)
    Row(Modifier.weight(1f).heightIn(min = 54.dp).clip(shape).background(background).border(1.dp, borderColor, shape)
        .toggleable(enabled, role = Role.Switch, onValueChange = toggle).padding(horizontal = 9.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(25.dp), contentAlignment = Alignment.Center) { icon() }
        Text(localizeLabel(title), color = primaryText, fontSize = KType.caption, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        LayerCheck(enabled)
    }
}

@Composable
private fun LayerCheck(on: Boolean) {
    val outline = if (on) Color.Transparent else secondaryText.copy(alpha = 0.38f)
    Box(Modifier.size(17.dp).clip(CircleShape).background(if (on) interfaceAccent else Color.Transparent)
        .border(1.dp, outline, CircleShape), contentAlignment = Alignment.Center) {
        if (on) Icon(Icons.Filled.Check, null, tint = interfaceOnAccent, modifier = Modifier.size(12.dp))
    }
}
