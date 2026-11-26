package app.kite.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.kite.*
import app.kite.core.Box as GeoBox
import app.kite.core.LatLng
import kotlinx.coroutines.flow.collectLatest

private data class CatalogueRow(val region: OfflineRegions.Region, val depth: Int, val branch: Boolean)

@Composable
internal fun OfflineMapsPage(model: AppModel) {
    LaunchedEffect(Unit) { OfflineMaps.load() }
    var removing by remember { mutableStateOf<OfflineMaps.Area?>(null) }
    var selected by remember { mutableStateOf<OfflineRegions.Region?>(null) }
    var opened by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var query by rememberSaveable { mutableStateOf("") }
    val areas = OfflineMaps.areas.values.sortedWith(compareByDescending<OfflineMaps.Area> { it.active }.thenByDescending { it.id })
    val catalogue = OfflineRegions.catalogue
    val grouped = remember(catalogue) { catalogue.groupBy { it.parent } }
    val parts = remember(opened, query, catalogue) {
        if (query.isNotBlank()) catalogue.filter { it.parent != null && it.displayName.contains(query.trim(), true) }
            .map { CatalogueRow(it, 0, grouped[it.id].orEmpty().isNotEmpty()) }
        else buildList {
            fun addChildren(parent: String?, depth: Int) {
                for (region in grouped[parent].orEmpty()) {
                    add(CatalogueRow(region, depth, grouped[region.id].orEmpty().isNotEmpty()))
                    if (region.id in opened) addChildren(region.id, depth + 1)
                }
            }
            addChildren(null, 0)
        }
    }
    val busy = OfflineMaps.creating || areas.any { it.active }
    val total = areas.sumOf { it.bytes }
    val context = LocalContext.current
    fun size(bytes: Long) = android.text.format.Formatter.formatShortFileSize(context, bytes)

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 122.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item("viewport") {
            Surface(color = interfaceSurface, shape = RoundedCornerShape(24.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(kt("Take the map with you", "Mapa na cesty"), fontSize = KType.headline, fontWeight = FontWeight.Bold)
                    Text(kt("Save streets and map labels to view without a connection.", "Uložte si ulice a popisky mapy pro zobrazení bez připojení."),
                        color = secondaryText)
                    FilledTonalButton(model::pickOfflineViewport, enabled = !busy) {
                        Icon(Icons.Filled.CropFree, null)
                        Spacer(Modifier.width(8.dp))
                        Text(kt("Choose area on map", "Vybrat oblast na mapě"))
                    }
                }
            }
        }
        item("storage") {
            Text(kt("Downloaded maps", "Stažené mapy") + " · " + size(total),
                fontSize = KType.headline, fontWeight = FontWeight.Bold)
            if (areas.isEmpty() && !busy) Text(kt("No maps downloaded", "Žádné stažené mapy"), color = secondaryText)
        }
        OfflineMaps.pendingName?.let { name ->
            item("pending") {
                Text(name, fontWeight = FontWeight.SemiBold)
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
        items(areas, key = { "saved:${it.id}" }) { area ->
            val status = when {
                area.complete -> kt("Ready offline", "Dostupná offline")
                area.active && !Connectivity.online -> kt("Waiting for connection", "Čeká na připojení")
                area.active && area.error != null -> kt("Retrying", "Opakuji pokus")
                area.active -> kt("Downloading", "Stahuje se")
                else -> kt("Paused", "Pozastaveno")
            }
            Surface(color = interfaceSurface, shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.fillMaxWidth().padding(13.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    val name = catalogue.firstOrNull { it.id == area.regionId }?.displayName ?: area.name
                    Text(name, fontWeight = FontWeight.Bold)
                    val percent = area.progress?.let { " · ${(it * 100).toInt()} %" }.orEmpty()
                    Text("$status · ${size(area.bytes)}$percent", color = secondaryText, fontSize = KType.caption)
                    if (area.active) {
                        if (area.progress != null) LinearProgressIndicator(progress = { area.progress }, modifier = Modifier.fillMaxWidth())
                        else LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    Row {
                        if (area.complete) TextButton({
                            val c = area.bounds.center
                            model.offlineFocus = LatLng(c.latitude, c.longitude) to minOf(13.0, area.maxZoom - 1.0)
                            model.offlineMapShown = true
                            model.tab = AppTab.MAP
                        }) { Text(kt("Open map", "Otevřít mapu")) }
                        else if (area.active) TextButton({ OfflineMaps.stop(area.id) }) { Text(kt("Pause", "Pozastavit")) }
                        else TextButton({ OfflineMaps.resume(area.id) }, enabled = Connectivity.online && !busy) {
                            Text(kt("Resume", "Pokračovat"))
                        }
                        TextButton({ removing = area }) { Text(kt("Remove", "Odebrat")) }
                    }
                }
            }
        }
        if (model.offlineMapShown) item("online") {
            TextButton({ model.offlineMapShown = false; model.tab = AppTab.MAP }) {
                Text(kt("Return to the online map", "Přejít na online mapu"))
            }
        }
        OfflineMaps.message?.let { message -> item("message") { Text(message, color = MaterialTheme.colorScheme.error) } }
        item("catalogue") {
            Text(kt("Countries and regions", "Státy a kraje"), fontSize = KType.headline, fontWeight = FontWeight.Bold)
            OutlinedTextField(query, { query = it }, leadingIcon = { Icon(Icons.Filled.Search, null) },
                label = { Text(kt("Find a country or region", "Najít stát nebo kraj")) }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(15.dp))
        }
        items(parts, key = { "catalogue:${it.region.id}" }) { row ->
            val region = row.region
            val saved = areas.firstOrNull { it.regionId == region.id || it.regionId == null && it.name == region.name }
            val expanded = region.id in opened
            val choose = {
                if (row.branch && query.isBlank()) opened = ArrayList(if (expanded) opened - region.id else opened + region.id)
                else if (saved == null && region.parent != null) selected = region
            }
            Surface(color = interfaceSurface, shape = RoundedCornerShape(16.dp), modifier = Modifier.padding(start = (row.depth * 14).dp)) {
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).tap { choose() }.padding(start = 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    Icon(if (expanded) Icons.Filled.ExpandLess else if (row.branch) Icons.Filled.ExpandMore else Icons.Filled.Map,
                        null, tint = interfaceAccentText)
                    Column(Modifier.weight(1f)) {
                        Text(region.displayName, color = primaryText, fontWeight = FontWeight.SemiBold)
                        if (region.parent != null) Text(if (saved?.complete == true) kt("Ready offline", "Dostupná offline")
                            else if (saved?.active == true) kt("Downloading", "Stahuje se")
                            else "≈ ${size(region.estimate)}", color = secondaryText, fontSize = KType.caption)
                    }
                    if (saved?.complete == true) Icon(Icons.Filled.Check, null,
                        Modifier.padding(horizontal = 13.dp), tint = interfaceAccentText)
                    else if (region.parent != null && saved == null) IconButton({ selected = region }, enabled = !busy) {
                        Icon(Icons.Filled.Download, kt("Download", "Stáhnout"))
                    }
                }
            }
        }
        if (parts.isEmpty()) item("no-match") { Text(kt("No regions found", "Žádné odpovídající oblasti"), color = secondaryText) }
    }
    selected?.let { region ->
        AlertDialog(onDismissRequest = { selected = null },
            title = { Text(region.displayName) },
            text = { Text(kt("Estimated download size", "Odhad velikosti stahování") + " · " + size(region.estimate) + "\n" +
                if (region.maxZoom >= 14) kt("Includes street-level map detail.", "Obsahuje podrobnou mapu ulic.")
                else kt("For street detail, choose a smaller region or area on the map.", "Pro podrobnou mapu ulic zvolte menší kraj nebo oblast na mapě.")) },
            confirmButton = {
                Button({ OfflineMaps.download(region); selected = null }, enabled = Connectivity.online && !busy) {
                    Text(kt("Download", "Stáhnout"))
                }
            },
            dismissButton = { TextButton({ selected = null }) { Text(kt("Cancel", "Zrušit")) } })
    }
    removing?.let { area ->
        AlertDialog(onDismissRequest = { removing = null }, title = { Text(area.name) },
            text = { Text(kt("Remove this downloaded map?", "Odebrat tuto staženou mapu?")) },
            confirmButton = { TextButton({ OfflineMaps.delete(area.id); removing = null }) { Text(kt("Remove", "Odebrat")) } },
            dismissButton = { TextButton({ removing = null }) { Text(kt("Cancel", "Zrušit")) } })
    }
}

@Composable
internal fun OfflineViewportPicker(model: AppModel, pointAt: (Float, Float) -> LatLng?) {
    fun close() {
        model.offlineSelecting = false
        model.offlineSettingsRequested = true
        model.tab = AppTab.SETTINGS
    }
    BackHandler { close() }
    val context = LocalContext.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val w = with(density) { maxWidth.toPx() }
        val h = with(density) { maxHeight.toPx() }
        fun bounds(): GeoBox? {
            val corners = listOfNotNull(pointAt(w * .10f, h * .22f), pointAt(w * .90f, h * .22f),
                pointAt(w * .10f, h * .72f), pointAt(w * .90f, h * .72f))
            if (corners.size != 4) return null
            return GeoBox(corners.minOf { it.lat }, corners.minOf { it.lng }, corners.maxOf { it.lat }, corners.maxOf { it.lng })
        }
        var box by remember { mutableStateOf<GeoBox?>(null) }
        LaunchedEffect(w, h) { snapshotFlow { model.mapTick to model.viewport }.collectLatest { box = bounds() } }
        val accent = interfaceAccent
        Canvas(Modifier.matchParentSize()) {
            drawRect(accent, Offset(size.width * .10f, size.height * .22f), Size(size.width * .80f, size.height * .50f),
                style = Stroke(3.dp.toPx()))
        }
        Surface(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(12.dp).fillMaxWidth(),
            shape = RoundedCornerShape(20.dp), color = interfaceSheet) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(kt("Choose area on map", "Vybrat oblast na mapě"), fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(::close) { Text(kt("Cancel", "Zrušit")) }
            }
        }
        Surface(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(12.dp).fillMaxWidth(),
            shape = RoundedCornerShape(24.dp), color = interfaceSheet) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                val count = box?.let { OfflineRegions.tileCount(it, OfflineRegions.VIEWPORT_ZOOM) } ?: 0
                val estimated = if (count in 1..OfflineRegions.MAX_VIEWPORT_TILES) box?.let {
                    android.text.format.Formatter.formatShortFileSize(context, OfflineRegions.viewportEstimate(it))
                } else null
                Text(if (count > OfflineRegions.MAX_VIEWPORT_TILES) kt("Zoom in to download a smaller area.", "Přibližte mapu a vyberte menší oblast.")
                    else if (estimated == null) kt("Pan and zoom to frame the area.", "Posuňte a přibližte mapu do rámečku.")
                    else kt("Estimated size", "Odhad velikosti") + " · " + estimated, color = secondaryText)
                Button({
                    val area = bounds() ?: return@Button
                    if (OfflineRegions.tileCount(area, OfflineRegions.VIEWPORT_ZOOM) !in 1..OfflineRegions.MAX_VIEWPORT_TILES) return@Button
                    OfflineMaps.downloadViewport(kt("Selected area", "Vybraná oblast"), area)
                    close()
                }, enabled = estimated != null && Connectivity.online && !OfflineMaps.creating &&
                    OfflineMaps.areas.values.none { it.active }, modifier = Modifier.fillMaxWidth()) { Text(kt("Download map", "Stáhnout mapu")) }
            }
        }
    }
}
