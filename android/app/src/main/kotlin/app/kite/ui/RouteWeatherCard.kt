package app.kite.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Umbrella
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.kite.R
import app.kite.*
import app.kite.core.Route
import app.kite.core.RouteWeather

@Composable
internal fun RouteWeatherCard(route: Route) {
    var open by rememberSaveable(route.id) { mutableStateOf(false) }
    var offset by rememberSaveable(route.id) { mutableIntStateOf(0) }
    var refreshed by rememberSaveable(route.id) { mutableIntStateOf(0) }
    var forecasts by remember(route.id, offset, refreshed) { mutableStateOf<List<RouteWeather.Forecast>?>(null) }
    var loaded by remember(route.id, offset, refreshed) { mutableStateOf(false) }
    LaunchedEffect(open, route.id, offset, refreshed) {
        if (open && !loaded) {
            forecasts = RouteWeather.forecast(route, System.currentTimeMillis() + offset * 60_000L)
            loaded = true
        }
    }
    Column(Modifier.fillMaxWidth().background(interfaceSurface, RoundedCornerShape(22.dp))) {
        IconRow(Icons.Filled.Cloud, stringResource(R.string.route_weather),
            trailing = if (open) "−" else "+") { open = !open }
        if (open) Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(0, 60, 120, 180).forEach { minutes ->
                    FilterChip(offset == minutes, { offset = minutes }, label = {
                        Text(if (minutes == 0) stringResource(R.string.weather_now) else "+${minutes / 60} h")
                    })
                }
            }
            val entries = forecasts
            if (!loaded) CircularProgressIndicator(Modifier.size(24.dp))
            else if (entries.isNullOrEmpty()) {
                Text(stringResource(R.string.weather_unavailable), color = secondaryText)
                TextButton({ refreshed++ }) { Text(stringResource(R.string.retry)) }
            } else Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                entries.forEach { reading ->
                    Column(Modifier.widthIn(min = 88.dp).background(interfaceChip,
                        RoundedCornerShape(16.dp)).padding(9.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        val label = when {
                            reading.sample.distance == 0.0 -> stringResource(R.string.weather_departure)
                            reading.sample.arrival -> stringResource(R.string.weather_arrival)
                            else -> Fmt.distance(reading.sample.distance)
                        }
                        Text(label, color = secondaryText, fontSize = KType.caption2)
                        val icon = when {
                            reading.code <= 1 -> Icons.Filled.WbSunny
                            reading.code >= 51 -> Icons.Filled.Umbrella
                            else -> Icons.Filled.Cloud
                        }
                        Icon(icon, if (reading.code >= 51) stringResource(R.string.weather_precipitation) else null,
                            Modifier.size(22.dp), tint = interfaceAccentText)
                        Text("${reading.celsius} °C", fontWeight = FontWeight.Bold, color = primaryText)
                        Text(Fmt.time(java.util.Date(reading.sample.time)), color = secondaryText, fontSize = KType.caption2)
                        reading.rainChance?.let { Text("☂ $it %", color = secondaryText, fontSize = KType.caption2) }
                    }
                }
            }
            Text("Open-Meteo", color = secondaryText, fontSize = KType.caption2)
        }
    }
}
