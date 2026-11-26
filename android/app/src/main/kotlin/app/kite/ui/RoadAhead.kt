package app.kite.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import app.kite.RoadStripStyle
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.Traffic
import androidx.compose.material.icons.filled.Train
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import app.kite.core.TravelMode
import app.kite.core.TrafficSpan
import androidx.compose.ui.unit.Dp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.kite.AppSettings
import app.kite.Navigator
import app.kite.MapIcons
import app.kite.core.WazeAlert
import androidx.compose.foundation.clickable
import app.kite.Fmt
import app.kite.core.Overpass
import app.kite.glass
import app.kite.interfaceSurface
import app.kite.LocalDark
import app.kite.LocalMapChrome
import app.kite.primaryText
import app.kite.secondaryText
import app.kite.KType
import app.kite.kt
import app.kite.localizeLabel
import app.kite.interfaceAccent
import app.kite.interfaceOnAccent

private data class AheadMarker(val distance: Double, val name: String, val icon: ImageVector, val color: Color, val alert: WazeAlert? = null, val scalable: Boolean = alert != null)

@Composable
fun RoadAheadStrip(nav: Navigator, modifier: Modifier = Modifier, showLabels: Boolean = true,
                   verticalHeight: androidx.compose.ui.unit.Dp? = null, onAlert: (WazeAlert) -> Unit = {}) {
    val progress = nav.progress
    if ((progress?.offsetMeters ?: 0.0) > 120 || (progress?.remainingMeters ?: nav.route.distanceMeters) <= 0) return
    val start = progress?.alongMeters ?: 0.0
    val walking = nav.route.mode == TravelMode.WALK
    val range = (progress?.remainingMeters ?: nav.route.distanceMeters).coerceIn(1.0, if (walking) AppSettings.walkingAheadRange.coerceIn(300, 1000).toDouble() else 3_000.0)
    val end = start + range
    val dark = LocalDark.current
    val traffic = nav.roadTraffic.takeIf { AppSettings.aheadTraffic }.orEmpty().filter { it.startMeters < end && it.startMeters + it.lengthMeters > start }
    val controls = nav.roadControls.asSequence().filter { it.along in start..end && (if (walking) AppSettings.showsWalkingControl(it.kind) else AppSettings.showsControl(it.kind)) }.map {
        val distance = it.along - start
        when (it.kind) {
            Overpass.ControlKind.SIGNAL -> AheadMarker(distance, "Traffic lights", Icons.Filled.Traffic,
                if (dark) Color(0xFFA8CBFF) else Color(0xFF29558C))
            Overpass.ControlKind.STOP -> AheadMarker(distance, localizeLabel("Stop sign"), Icons.Filled.StopCircle,
                if (dark) Color(0xFFFF8D88) else Color(0xFFB11F32))
            Overpass.ControlKind.CROSSING -> AheadMarker(distance, localizeLabel("Pedestrian crossing"), Icons.AutoMirrored.Filled.DirectionsWalk,
                if (dark) Color(0xFF98D9F0) else Color(0xFF126782))
            Overpass.ControlKind.RAIL -> AheadMarker(distance, localizeLabel("Railroad crossing"), Icons.Filled.Train,
                if (dark) Color(0xFFFFCD80) else Color(0xFF835021))
        }
    }
    val cameras = nav.routeCameras.asSequence().filter { (if (walking) AppSettings.walkingCameras else AppSettings.aheadCameras) && it.along in start..end && !it.camera.quiet }
        .map { AheadMarker(it.along - start, localizeLabel("Camera"), Icons.Filled.PhotoCamera, if (dark) Color(0xFFDBC5FF) else Color(0xFF643894), scalable = true) }
    val reports = nav.routeWaze.asSequence().filter {
        it.along in start..end && (!walking || AppSettings.walkingWaze) && AppSettings.showsInStrip(it.alert)
    }.map { AheadMarker(it.along - start, localizeLabel(it.alert.title), MapIcons.wazeIcon(it.alert.kind),
        Color(0xFF000000 or MapIcons.wazeColor(it.alert.kind)), it.alert) }
    val markers = (reports + controls + cameras).sortedBy { it.distance }.toList()

    if (walking && markers.isEmpty()) return
    RoadAheadContent(markers, traffic, start, range, modifier, showLabels, verticalHeight,
        AppSettings.wazeStripPercent.coerceIn(60, 160) / 100f, onAlert)
}

@Composable
internal fun RoadAheadPreview(percent: Int = AppSettings.wazeStripPercent) {
    val dark = LocalDark.current
    val position = AppSettings.roadStripPosition
    val color = if (dark) Color(0xFFA8CBFF) else Color(0xFF29558C)
    val markers = listOf(
        AheadMarker(250.0, kt("Traffic light", "Semafor"), Icons.Filled.Traffic, color),
        AheadMarker(1200.0, kt("Police", "Policie"), MapIcons.wazeIcon(WazeAlert.Kind.POLICE), Color(0xFF1E88E5), scalable = true),
        AheadMarker(2260.0, kt("Railroad crossing", "Železniční přejezd"), Icons.Filled.Train,
            if (dark) Color(0xFFFFCD80) else Color(0xFF835021)),
    )
    val vertical = position == app.kite.RoadStripPosition.LEFT || position == app.kite.RoadStripPosition.RIGHT
    RoadAheadContent(markers, listOf(TrafficSpan(2, 1450.0, 660.0)), 0.0, 3000.0,
        Modifier, true, if (vertical) 156.dp else null, percent / 100f, {})
}

@Composable
private fun RoadGlyph(icon: ImageVector, label: String, size: Dp, tint: Color) {
    val theme = AppSettings.markerTheme
    if (theme == app.kite.MarkerTheme.CLASSIC) {
        Icon(icon, label, Modifier.size(size), tint = tint)
    } else {
        val bitmap = remember(theme, icon, tint) { MapIcons.stripBadge(icon, tint.toArgb()).asImageBitmap() }
        Image(bitmap, label, Modifier.size(size + 6.dp))
    }
}

@Composable
private fun RoadAheadContent(
    markers: List<AheadMarker>, traffic: List<TrafficSpan>, start: Double, range: Double,
    modifier: Modifier, showLabels: Boolean, verticalHeight: Dp?, iconScale: Float, onAlert: (WazeAlert) -> Unit,
) {
    val vertical = verticalHeight != null
    val dark = LocalDark.current
    val style = AppSettings.roadStripStyle
    var opened by remember { mutableStateOf<List<AheadMarker>?>(null) }
    fun action(group: List<AheadMarker>): Modifier = when {
        group.size > 1 -> Modifier.clickable { opened = group }
        else -> group.first().alert?.let { alert -> Modifier.clickable { onAlert(alert) } } ?: Modifier
    }
    opened?.let { group ->
        AlertDialog(onDismissRequest = { opened = null }, title = { Text(kt("Road ahead", "Co je před vámi")) },
            text = {
                LazyColumn(Modifier.heightIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(group) { marker ->
                        val click = marker.alert?.let { alert ->
                            Modifier.clickable { opened = null; onAlert(alert) }
                        } ?: Modifier
                        Row(Modifier.fillMaxWidth().then(click).padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            RoadGlyph(marker.icon, marker.name, 24.dp, marker.color)
                            Column(Modifier.weight(1f)) {
                                Text(marker.name, color = primaryText, fontWeight = FontWeight.SemiBold)
                                marker.alert?.street?.let { Text(it, color = secondaryText, fontSize = KType.caption) }
                            }
                            Text(Fmt.distance(marker.distance), color = secondaryText)
                        }
                    }
                }
            },
            confirmButton = { TextButton({ opened = null }) { Text(localizeLabel("Close")) } })
    }
    if (style == RoadStripStyle.CONTRAST && markers.isNotEmpty()) {
        val n = if (vertical) ((requireNotNull(verticalHeight).value - 10) / 53).toInt().coerceIn(1, 4) else 4
        @Composable
        fun event(group: List<AheadMarker>, width: Modifier) {
            val marker = group.first()
            val label = Fmt.distance(marker.distance)
            Column(width.then(action(group)).glass(RoundedCornerShape(13.dp), solid = true).padding(5.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                val icon = 21.dp * if (marker.scalable) iconScale else 1f
                Box {
                    RoadGlyph(marker.icon, marker.name, icon, marker.color)
                    if (group.size > 1) GroupCount(group.size, Modifier.align(Alignment.TopEnd).offset(x = 9.dp, y = (-3).dp))
                }
                Text(label, color = primaryText, fontWeight = FontWeight.Bold, fontSize = KType.caption2)
            }
        }
        Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val groups = markers.take(n - 1).map { listOf(it) } + listOf(markers.drop(n - 1)).filter { it.isNotEmpty() }
            if (vertical) groups.forEach { event(it, Modifier.width(64.dp)) }
            else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                groups.forEach { event(it, Modifier.weight(1f)) }
            }
            if (traffic.isNotEmpty() && !vertical) {
                Canvas(Modifier.fillMaxWidth().height(5.dp)) {
                    drawLine(Color.Gray, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), size.height)
                    for (span in traffic) {
                        val a = ((span.startMeters - start) / range).coerceIn(0.0, 1.0).toFloat() * size.width
                        val b = ((span.startMeters + span.lengthMeters - start) / range).coerceIn(0.0, 1.0).toFloat() * size.width
                        drawLine(trafficInk(span.level), Offset(a, size.height / 2), Offset(b, size.height / 2), size.height)
                    }
                }
            }
        }
        return
    }
    val railWidth = when (style) {
        RoadStripStyle.MINIMAL -> 3.dp
        RoadStripStyle.CLASSIC -> 6.dp
        RoadStripStyle.CONTRAST -> 8.dp
    }
    val railColor = if (dark) Color(0xFF667994) else Color(0xFF90A6BC)
    val railEdge = if (dark) Color(0xFF1E2B41) else Color(0xFFDCE6EF)
    val origin = LocalMapChrome.current?.accent ?: if (dark) Color(0xFF82E6D8) else Color(0xFF0B8074)
    val shape = RoundedCornerShape(if (vertical) 27.dp else 19.dp)
    val stripSize = if (vertical) Modifier.width(maxOf(51.dp, 23.dp * iconScale + 20.dp)).height(requireNotNull(verticalHeight))
        else Modifier.fillMaxWidth()
    val chrome = if (style == RoadStripStyle.MINIMAL) Modifier else Modifier.glass(shape, solid = true)
    Column(modifier.then(stripSize).then(chrome).padding(if (style == RoadStripStyle.MINIMAL) 0.dp else if (vertical) 5.dp else 8.dp)) {
        if (showLabels && !vertical && style != RoadStripStyle.MINIMAL) Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween) {
            Text(kt("Road ahead", "Co je před vámi"), color = primaryText, fontSize = KType.caption, fontWeight = FontWeight.SemiBold)
            val incident = traffic.minByOrNull { it.startMeters }
            val label = when (incident?.level) {
                1 -> kt("Slow traffic", "Zpomalený provoz")
                2 -> kt("Heavy traffic", "Hustý provoz")
                3 -> kt("Standstill traffic", "Stojící kolona")
                else -> null
            }
            Text(label?.let { if (incident != null && incident.startMeters > start)
                "$it · ${Fmt.distance(incident.startMeters - start)}" else it } ?: Fmt.distance(range),
                color = secondaryText, fontSize = KType.caption)
        }
        val badge = maxOf(32.dp, 23.dp * iconScale + 7.dp)
        BoxWithConstraints(if (vertical) Modifier.fillMaxSize()
            else Modifier.fillMaxWidth().height(if (markers.isEmpty()) 14.dp else badge + 6.dp)) {
            val length = (if (vertical) maxHeight else maxWidth) - badge
            Canvas(Modifier.fillMaxSize()) {
                val inset = (badge / 2).toPx()
                fun point(distance: Double): Offset {
                    val f = (distance / range).coerceIn(0.0, 1.0).toFloat()
                    return if (vertical) Offset(size.width / 2, size.height - inset - f * (size.height - 2 * inset))
                    else Offset(inset + f * (size.width - 2 * inset), size.height / 2)
                }
                if (style != RoadStripStyle.MINIMAL)
                    drawLine(railEdge, point(0.0), point(range), strokeWidth = (railWidth + 6.dp).toPx(), cap = StrokeCap.Round)
                val rail = if (style == RoadStripStyle.CONTRAST) origin.copy(alpha = 0.72f) else railColor
                drawLine(rail, point(0.0), point(range), strokeWidth = railWidth.toPx(), cap = StrokeCap.Round)
                for (span in traffic) {
                    val color = trafficInk(span.level)
                    drawLine(color, point(span.startMeters - start), point(span.startMeters + span.lengthMeters - start),
                        strokeWidth = railWidth.toPx(), cap = StrokeCap.Round)
                }
                drawCircle(origin, radius = (if (style == RoadStripStyle.MINIMAL) 3.dp else 5.dp).toPx(), center = point(0.0))
            }
            val spacing = range * ((badge + 3.dp) / length.coerceAtLeast(1.dp))
            for (bucket in roadAheadGroups(markers.map { it.distance }, spacing)) {
                val group = bucket.map(markers::get)
                val marker = group.firstOrNull { it.alert != null } ?: group.first()
                val offset = length * (group.first().distance / range).toFloat()
                val position = if (vertical) Modifier.align(Alignment.TopCenter).offset(y = length - offset)
                    else Modifier.align(Alignment.CenterStart).offset(x = offset)
                val size = 23.dp * if (marker.scalable) iconScale else 1f
                val ring = when (style) {
                    RoadStripStyle.MINIMAL -> 0.dp
                    RoadStripStyle.CLASSIC -> 1.5.dp
                    RoadStripStyle.CONTRAST -> 2.5.dp
                }
                val iconInk = if (marker.color.luminance() < 0.20f && dark) Color.White else marker.color
                val backing = if (style == RoadStripStyle.MINIMAL) Modifier else Modifier.background(interfaceSurface, CircleShape)
                    .border(ring, marker.color.copy(alpha = 0.72f), CircleShape)
                Box(position.size(badge).then(backing).then(action(group)), contentAlignment = Alignment.Center) {
                    RoadGlyph(marker.icon, "${marker.name} · ${Fmt.distance(marker.distance)}", size, iconInk)
                    if (group.size > 1) GroupCount(group.size, Modifier.align(Alignment.TopEnd).offset(x = 3.dp, y = (-2).dp))
                }
            }
        }
    }
}

private fun trafficInk(level: Int): Color = when (level) {
    1 -> Color(0xFFF59E0B)
    2 -> Color(0xFFF05240)
    else -> Color(0xFFC01E40)
}

@Composable
private fun GroupCount(count: Int, modifier: Modifier) {
    Text(count.toString(), modifier.background(interfaceAccent, CircleShape).padding(horizontal = 4.dp, vertical = 1.dp),
        color = interfaceOnAccent, fontSize = KType.caption2, fontWeight = FontWeight.ExtraBold)
}

internal fun roadAheadGroups(distances: List<Double>, spacing: Double): List<List<Int>> {
    val groups = mutableListOf<MutableList<Int>>()
    for (index in distances.indices) {
        val current = groups.lastOrNull()
        if (current == null || distances[index] - distances[current.first()] >= spacing) groups += mutableListOf(index)
        else current += index
    }
    return groups
}
