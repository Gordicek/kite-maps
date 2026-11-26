package app.kite.ui

import app.kite.localizeLabel
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kite.AppSettings
import app.kite.SatelliteStyle
import app.kite.R

internal val LocalMapboxAttribution = staticCompositionLocalOf<(() -> Boolean)?> { null }

@Composable
internal fun MapboxAttribution(modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val mapbox = LocalMapboxAttribution.current
    val satellite = AppSettings.satellite
    Row(modifier.clip(RoundedCornerShape(6.dp)).background(Color.White.copy(alpha = 0.92f))
        .clickable(onClickLabel = "Map attribution") { if (satellite || mapbox?.invoke() != true) open = true }
        .padding(horizontal = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(R.drawable.mapbox_attribution_black), "Mapbox", Modifier.height(20.dp))
        if (satellite) Text("Imagery © Esri", Modifier.padding(start = 5.dp), color = Color(0xFF24303C), fontSize = 10.sp)
    }
    if (open) AlertDialog(
        onDismissRequest = { open = false },
        title = { Text("Map attribution") },
        text = {
            Column {
                if (satellite) TextButton({ context.open(SatelliteStyle.URL) }) { Text(SatelliteStyle.CREDIT) }
                TextButton({ context.open("https://www.mapbox.com/about/maps/") }) { Text("© Mapbox") }
                TextButton({ context.open("https://www.openstreetmap.org/copyright") }) { Text("© OpenStreetMap") }
                TextButton({ context.open("https://apps.mapbox.com/feedback/") }) { Text("Improve this map") }
            }
        },
        confirmButton = { TextButton({ open = false }) { Text(localizeLabel("Done")) } },
    )
}
