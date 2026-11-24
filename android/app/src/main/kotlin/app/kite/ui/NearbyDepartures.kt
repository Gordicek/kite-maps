package app.kite.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Map
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.kite.AppModel
import app.kite.Fmt
import app.kite.core.OutdoorData
import app.kite.core.Place
import app.kite.core.Transitous
import app.kite.primaryText
import app.kite.secondaryText

@Composable
fun NearbyDepartures(model: AppModel, close: () -> Unit) {
    val origin = model.userLatLng
    // Keep the search anchored while reading a board; a new opening uses the latest fix.
    val initial = remember(origin != null) { origin }
    var stops by remember(initial) { mutableStateOf<List<Transitous.Stop>?>(null) }
    var selected by remember(initial) { mutableStateOf<Transitous.Stop?>(null) }
    var failed by remember(initial) { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(initial, reload) {
        val here = initial ?: return@LaunchedEffect
        stops = null
        failed = false
        val nearby = Transitous.stops(OutdoorData.bounds(here, 900.0), grouped = true)
        failed = nearby == null
        stops = nearby.orEmpty().filter { it.at.distanceTo(here) <= 900 }
            .sortedBy { it.at.distanceTo(here) }.distinctBy { it.parentId ?: it.id }.take(8)
        selected = stops?.firstOrNull()
    }

    TripSheet("Nearby departures", "Done", close) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when {
                initial == null -> Text("Waiting for your location.", color = secondaryText)
                stops == null -> CircularProgressIndicator(Modifier.size(24.dp))
                failed -> {
                    Text("Couldn't load nearby stops.", color = secondaryText)
                    TextButton({ reload++ }) { Text("Retry") }
                }
                stops.isNullOrEmpty() -> Text("No stops within walking distance.", color = secondaryText)
                else -> {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        stops.orEmpty().forEach { stop ->
                            FilterChip(selected == stop, { selected = stop },
                                label = { Text("${stop.name} · ${Fmt.distance(stop.at.distanceTo(initial))}") })
                        }
                    }
                    selected?.let { stop ->
                        val place = Place("transit:${stop.parentId ?: stop.id}", stop.name, stop.at, "transit", category = "station")
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stop.name, Modifier.weight(1f), color = primaryText)
                            IconButton({ close(); model.select(place) }) { Icon(Icons.Filled.Map, "Show stop on map") }
                        }
                        DepartureBoard(place)
                    }
                }
            }
        }
    }
}
