@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.kite.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kite.*
import app.kite.core.FuelPrices
import app.kite.core.GoogleClient
import app.kite.core.Place
import kotlinx.coroutines.CancellationException

@Composable
internal fun StopResultsPanel(
    search: StopSearch, list: LazyListState, onlyOpen: Boolean, setOnlyOpen: (Boolean) -> Unit,
    detent: SheetDetent, setDetent: (SheetDetent) -> Unit, height: Dp,
    close: () -> Unit, showOnMap: (StopCandidate) -> Unit,
) {
    var expandedId by rememberSaveable { mutableStateOf<String?>(null) }
    val numbered = remember(search.results, onlyOpen) {
        search.results.withIndex().filter { (_, candidate) ->
            val place = candidate.place
            !onlyOpen || !place.permanentlyClosed && (place.openNow == true || place.hours.singleOrNull()?.trim() == "24/7")
        }
    }

    BottomSheet(detent, setDetent, height, peekHeight = 104.dp, topClearance = 68.dp, opaque = true, allowPeek = false, edgeToEdge = true,
        header = {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(if (search.parking) "Parking nearby" else "Stops along the route",
                        fontSize = KType.headline, fontWeight = FontWeight.ExtraBold, color = primaryText,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val count = numbered.size
                    Text(if (count == 1) "1 result" else "$count results", fontSize = KType.caption, color = secondaryText)
                }
                IconButton({ setDetent(if (detent == SheetDetent.FULL) SheetDetent.HALF else SheetDetent.FULL) }) {
                    Icon(if (detent == SheetDetent.FULL) Icons.Filled.ExpandMore else Icons.Filled.ExpandLess, "Resize results")
                }
                TextButton(close) { Text(localizeLabel("Map")) }
            }
            if (detent != SheetDetent.PEEK) Box(Modifier.padding(start = 16.dp, top = 5.dp, bottom = 8.dp)) {
                FilterChipLabel("Open now", onlyOpen, menu = false) { setOnlyOpen(!onlyOpen) }
            }
        }, content = {
            if (detent != SheetDetent.PEEK) LazyColumn(
                modifier = Modifier.fillMaxSize().navigationBarsPadding(),
                state = list,
                contentPadding = PaddingValues(start = 16.dp, top = 4.dp, end = 16.dp, bottom = 18.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (numbered.isEmpty()) item {
                    Text(if (onlyOpen) "No places with confirmed opening status right now." else "No results",
                        color = secondaryText, modifier = Modifier.padding(vertical = 12.dp))
                }
                items(numbered, key = { it.value.place.id }) { (index, candidate) ->
                    val expanded = candidate.place.id == expandedId
                    StopResultCard(search, candidate, index + 1, expanded,
                        toggle = { expandedId = if (expanded) null else candidate.place.id },
                        showOnMap = { showOnMap(candidate) })
                }
            }
        })
}

@Composable
internal fun StopResultCard(
    search: StopSearch, candidate: StopCandidate, number: Int?, expanded: Boolean, toggle: () -> Unit,
    showOnMap: (() -> Unit)? = null, footer: (@Composable () -> Unit)? = null,
) {
    val place = candidate.place
    val selected = place.id == search.selected?.place?.id
    val photoId = place.featureId.takeIf { !AppSettings.noGoogle && place.photoUrls.size < 4 }
    var gallery by remember(place.id, photoId) { mutableStateOf<List<String>?>(null) }
    LaunchedEffect(expanded, photoId) {
        if (!expanded || photoId == null || gallery != null) return@LaunchedEffect
        gallery = try {
            GoogleClient.shared.photos(photoId).take(4)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
    }
    val distance = if (search.parking) "${Fmt.distance(candidate.offset)} from destination"
        else candidate.ahead?.let { "${Fmt.distance(it)} ahead" }
    val detour = search.detours[place.id]?.let(::detourShort)
    val ukQuote = search.fuelQuotes[place.id]
    val price = ukQuote?.labels?.firstOrNull() ?: place.fuelPrice.takeUnless { AppSettings.noGoogle }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = if (showOnMap == null) interfaceSheet else interfaceGround,
        border = if (selected) BorderStroke(1.dp, interfaceAccentText.copy(alpha = 0.6f)) else null,
    ) {
        Column {
            PlaceResultSummary(
                place, onOpen = showOnMap ?: toggle, mapNumber = number, mapSelected = selected,
                openLabel = if (showOnMap != null) "Show on map" else if (expanded) "Collapse details" else "Expand details",
                supporting = {
                    if (distance != null || detour != null || price != null) {
                        FlowRow(Modifier.padding(top = 3.dp), horizontalArrangement = Arrangement.spacedBy(5.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            distance?.let { StopMetric(Icons.Filled.Place, it) }
                            detour?.let { StopMetric(Icons.Outlined.Schedule, it) }
                            price?.let { StopMetric(Icons.Filled.LocalGasStation, it) }
                        }
                    }
                },
            ) {
                FilledTonalIconButton(toggle, modifier = Modifier.size(48.dp),
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = if (expanded) interfaceAccent else interfaceSurface,
                        contentColor = if (expanded) interfaceOnAccent else interfaceAccentText,
                    ),
                ) {
                    Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        if (expanded) "Collapse details for ${place.name}" else "Expand details for ${place.name}")
                }
            }
            if (expanded) StopResultDetails(place, gallery.orEmpty(), photoId != null && gallery == null,
                price, ukQuote, showOnMap)
            footer?.invoke()
        }
    }
}

@Composable
internal fun NavigationPlaceCard(model: AppModel, place: Place, modifier: Modifier = Modifier) {
    var expanded by rememberSaveable(place.id) { mutableStateOf(false) }
    val nav = model.nav ?: return
    val candidate = remember(place) { StopCandidate(place, null, 0.0) }
    val search = remember(place) { StopSearch(place.name, wasFollowing = nav.following, loading = false) }
    androidx.activity.compose.BackHandler { model.showNavigationPlace(null) }
    Box(modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp)) {
        StopResultCard(search, candidate, null, expanded, toggle = { expanded = !expanded }, footer = {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                TextButton({ model.showNavigationPlace(null) }) { Text(localizeLabel("Close")) }
                val canAdd = !nav.rerouting && nav.stops.size < 8 && nav.stops.none(place::matchesNearby) && !nav.destination.matchesNearby(place)
                Button({ model.addNavigationPlace(place) }, enabled = canAdd) { Text(localizeLabel("Add as next stop")) }
            }
        })
    }
}

@Composable
private fun StopMetric(icon: ImageVector, label: String) {
    Row(Modifier.background(interfaceSurface, CircleShape).padding(horizontal = 7.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(13.dp), tint = interfaceAccentText)
        Text(localizeLabel(label), fontSize = KType.caption2, fontWeight = FontWeight.SemiBold, color = primaryText)
    }
}

@Composable
private fun StopResultDetails(
    place: Place, morePhotos: List<String>, loadingPhotos: Boolean, price: String?, ukQuote: FuelPrices.Quote?,
    showOnMap: (() -> Unit)?,
) {
    var hoursOpen by rememberSaveable(place.id) { mutableStateOf(false) }
    val context = LocalContext.current
    val photos = (place.photoUrls + morePhotos).filterNot { AppSettings.noGoogle && isGooglePhoto(it) }
        .distinctBy { it.substringBeforeLast("=w") }.take(4)
    val summary = place.editorialSummary?.takeIf(String::isNotBlank)
    val website = place.website?.takeIf(String::isNotBlank)?.removePrefix("https://")?.removePrefix("http://")
    val details = if (showOnMap == null) Modifier.heightIn(max = 230.dp).verticalScroll(rememberScrollState()) else Modifier
    Column(details.padding(start = 12.dp, end = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        HorizontalDivider(color = secondaryText.copy(alpha = 0.15f))
        if (photos.isNotEmpty()) {
            PhotoStrip(photos, height = 100.dp)
            if (photos.any(::isGooglePhoto)) Text(localizeLabel("Photos from Google Maps"), fontSize = KType.caption2, color = secondaryText)
        } else if (loadingPhotos) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(localizeLabel("Loading photos…"), fontSize = KType.caption, color = secondaryText)
            }
        }
        summary?.let { Text(it, fontSize = KType.footnote, color = primaryText, maxLines = 4, overflow = TextOverflow.Ellipsis) }
        if (FuelPrices.isStation(place)) {
            val value = ukQuote?.labels?.joinToString(" · ") ?: price ?: "Price unavailable"
            val source = when {
                ukQuote != null -> "UK Fuel Finder · " + java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM)
                    .format(java.util.Date(ukQuote.updatedSeconds * 1000))
                price != null -> "Reported by Google Maps"
                else -> null
            }
            PlaceInfoCard(Icons.Filled.LocalGasStation, "Fuel prices", listOfNotNull(value, source).joinToString("\n"))
            if (ukQuote != null) Text("Contains public sector information licensed under the Open Government Licence v3.0.",
                fontSize = KType.caption2, color = secondaryText)
        }
        place.address?.let { PlaceInfoCard(Icons.Outlined.Place, "Address", it) }
        if (place.hours.isNotEmpty()) OpeningHoursCard(place, hoursOpen) { hoursOpen = !hoursOpen }
        place.phone?.let { phone -> PlaceInfoCard(Icons.Outlined.Phone, "Phone", phone, telUrl(phone)?.let { url -> { context.open(url) } }) }
        website?.let { PlaceInfoCard(Icons.Filled.Language, localizeLabel("Website"), it, webUrl(place.website)?.let { url -> { context.open(url) } }) }
        if (summary == null && photos.isEmpty() && place.address.isNullOrBlank() && place.hours.isEmpty() &&
            place.phone.isNullOrBlank() && website == null && !loadingPhotos && !FuelPrices.isStation(place)) {
            Text(localizeLabel("No additional details available"), fontSize = KType.caption, color = secondaryText)
        }
        if (showOnMap != null) OutlinedButton(onClick = showOnMap, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Outlined.Map, null, Modifier.size(18.dp))
            Spacer(Modifier.width(7.dp))
            Text(localizeLabel("Show on map"))
        }
    }
}
