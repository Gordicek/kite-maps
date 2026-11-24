package app.kite.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.kite.*
import app.kite.core.*

@Composable
internal fun TransitTimeBar(trip: TransitTrip, live: Map<String, TransitLive>) {
    if (trip.legs.isEmpty()) return
    val waitColor = secondaryText.copy(alpha = 0.22f)
    val walkColor = secondaryText.copy(alpha = 0.72f)
    val colors = trip.legs.map { if (it.isWalk) walkColor else Color(0xFF000000 or it.mapColor) }
    val durations = trip.legs.mapIndexed { i, leg ->
        val status = live[leg.tripId]
        val departure = status?.board?.departure ?: leg.depart
        val arrival = status?.exit?.arrival ?: leg.arrive
        val duration = if (departure != null && arrival != null && arrival > departure)
            (arrival - departure) / 1000.0 else leg.durationSeconds
        val previous = trip.legs.getOrNull(i - 1)
        val before = previous?.let { live[it.tripId]?.exit?.arrival ?: it.arrive }
        val wait = if (before != null && departure != null) ((departure - before) / 1000.0).coerceAtLeast(0.0) else 0.0
        wait to duration.coerceAtLeast(1.0)
    }
    val total = durations.sumOf { it.first + it.second }.coerceAtLeast(1.0)
    val description = trip.legs.zip(durations).joinToString(", ") { (leg, time) ->
        val movement = if (leg.isWalk) "Walk" else leg.line.ifBlank { leg.vehicle.name.lowercase() }
        val wait = if (time.first >= 60) "Wait ${Fmt.duration(time.first)}, " else ""
        "$wait$movement ${Fmt.duration(time.second)}"
    }
    Canvas(Modifier.fillMaxWidth().height(13.dp).clip(RoundedCornerShape(6.dp))
        .semantics { contentDescription = description }.background(waitColor)) {
        var offset = 0.0
        durations.forEachIndexed { index, (wait, duration) ->
            offset += wait
            val start = (offset / total * size.width).toFloat()
            val width = (duration / total * size.width).toFloat()
            drawRect(colors[index], topLeft = Offset(start, if (trip.legs[index].isWalk) size.height * 0.27f else 0f),
                size = Size(width, if (trip.legs[index].isWalk) size.height * 0.46f else size.height))
            offset += duration
        }
    }
}

@Composable
internal fun TransitTransferNotes(trip: TransitTrip, live: Map<String, TransitLive>, fromLeg: Int = 0) {
    for (risk in trip.transferWarnings(live, AppSettings.learnedWalkingPace).filter { it.afterLeg >= fromLeg }.take(2)) {
        val color = if (risk.kind == TransferTiming.Kind.TIGHT) {
            if (LocalDark.current) Color(0xFFFFCE85) else Color(0xFF82500F)
        } else Vela.closed
        val text = when (risk.kind) {
            TransferTiming.Kind.CANCELLED -> "Service cancelled near ${risk.station}"
            TransferTiming.Kind.MISSED -> "Connection at ${risk.station} no longer fits"
            TransferTiming.Kind.TIGHT -> "Tight transfer at ${risk.station} · ${risk.bufferSeconds / 60} min spare"
        }
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(color.copy(alpha = 0.13f))
            .padding(horizontal = 9.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Icons.Filled.WarningAmber, null, tint = color, modifier = Modifier.size(16.dp))
            Text(text, color = color, fontSize = KType.caption, fontWeight = FontWeight.SemiBold)
        }
    }
}
