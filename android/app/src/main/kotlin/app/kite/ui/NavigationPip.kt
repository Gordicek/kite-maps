package app.kite.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsTransit
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import app.kite.core.Geo
import app.kite.core.ManeuverType
import app.kite.*
import app.kite.R
import app.kite.core.TravelMode

@Composable
internal fun NavigationPictureInPicture(model: AppModel, showMapbox: Boolean) {
    val nav = model.mapNavigator
    val transit = model.transitGuidance
    val maneuver = nav?.nextManeuver
    val progress = nav?.progress
    val distance = progress?.distanceToNext?.let(Fmt::distance)
    val instruction = when {
        nav?.arrived == true || transit?.phase == TransitGuidance.Phase.COMPLETE -> stringResource(R.string.nav_arrived)
        maneuver != null -> maneuver.road?.takeIf { it.isNotBlank() } ?: Banner.headline(maneuver)
        transit != null -> transit.nextStop?.name ?: transit.leg?.toName ?: transit.destination.name
        else -> "Kite"
    }
    val eta = progress?.remainingSeconds?.let(Fmt::arrival)
    val accent = Color(0xFF8BF2D6)
    val panel = Color(0xFF123437)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxHeight < 125.dp
        val portrait = maxHeight > maxWidth
        val markerSize = if (compact) 32.dp else 42.dp
        if (nav != null && model.glide.carOnScreen) {
            val marker = if (nav.route.mode == TravelMode.DRIVE) AppSettings.driveMarker else DriveMarker.ARROW
            val tilted = marker != DriveMarker.ARROW && AppSettings.driveLook == app.kite.core.MapLook.TILTED
            val dark = LocalDark.current
            val puck = remember(marker, dark, tilted) { MapIcons.drivePuck(marker, dark, tilted).asImageBitmap() }
            val density = LocalDensity.current
            val height = maxHeight.value
            Image(puck, null, Modifier.align(Alignment.TopCenter).offset {
                IntOffset(0, with(density) { (model.glide.markerY(height, compact = true).dp - markerSize / 2).roundToPx() })
            }.size(markerSize).graphicsLayer {
                rotationZ = model.glide.carTurn
                if (!tilted) scaleY = model.glide.carSquash
            })
        }
        Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(4.dp)
            .background(panel, RoundedCornerShape(14.dp)).padding(horizontal = 8.dp, vertical = if (compact) 4.dp else 7.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            val iconSize = if (compact) 35.dp else 44.dp
            if (maneuver?.type == ManeuverType.ROUNDABOUT) {
                RoundaboutDiagram(maneuver.roundabout, maneuver.exit, Geo.drivesOnLeft(maneuver.location),
                    Modifier.size(iconSize), inkColor = Color.White, routeColor = accent)
            } else {
                Icon(maneuver?.type?.let(::maneuverIcon) ?: if (transit != null) Icons.Filled.DirectionsTransit else Icons.Filled.Navigation,
                    null, tint = accent, modifier = Modifier.size(iconSize))
            }
            Column(Modifier.weight(1f)) {
                if (distance != null) Text(distance, color = accent, fontSize = if (compact) 21.sp else 25.sp,
                    lineHeight = if (compact) 22.sp else 27.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
                Text(instruction, color = Color.White, fontSize = if (compact) 13.sp else 16.sp,
                    lineHeight = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = if (portrait) 2 else 1,
                    overflow = TextOverflow.Ellipsis)
            }
        }
        if (eta != null) Text(eta, color = Color.White, fontSize = if (compact) 13.sp else 15.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.BottomEnd).padding(5.dp).background(panel, RoundedCornerShape(10.dp))
                .padding(horizontal = 8.dp, vertical = 3.dp))
        if (showMapbox) MapboxAttribution(Modifier.align(Alignment.BottomStart).padding(4.dp))
    }
}
