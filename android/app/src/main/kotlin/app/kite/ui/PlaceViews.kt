@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.kite.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.kite.*
import app.kite.core.*
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
internal fun PlacePeek(model: AppModel, place: Place) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable { model.detent = SheetDetent.HALF }
            .padding(vertical = 7.dp)) {
            Text(place.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                color = primaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
            PlaceMetaLine(place, showStars = false, here = model.userLatLng)
        }
        CircleIconButton(Icons.Filled.Directions, description = localizeLabel("Directions")) { model.openDirections(place) }
        CircleIconButton(Icons.Filled.Close, description = localizeLabel("Close place")) { model.closePlace(restoreViewport = false) }
    }
}

@Composable
fun PlaceHeader(model: AppModel, place: Place) {
    val ctx = LocalContext.current
    var share by remember(place.id) { mutableStateOf(false) }
    var chooseLists by remember(place.id) { mutableStateOf(false) }
    Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (model.placeIndoorPlan?.first == place.id) {
            AssistChip(onClick = { model.openIndoor(place) }, label = { Text(localizeLabel("Indoor floors")) },
                leadingIcon = { Icon(Icons.Filled.Apartment, null, Modifier.size(17.dp)) })
        }
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) {
                Copyable(place.name) {
                    Text(place.name, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, color = primaryText, maxLines = 2,
                        overflow = TextOverflow.Ellipsis, lineHeight = 30.sp)
                }
            }
            CircleIconButton(Icons.Filled.Close, description = localizeLabel("Close place")) { model.closePlace() }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Box(Modifier.clickable(enabled = place.rating != null) {
                    model.showReviews = place.id
                    model.detent = SheetDetent.FULL
                }) { PlaceMetaLine(place, showStars = false, here = model.userLatLng) }
                StatusText(place)
            }
            val saved = AppSettings.isSaved(place)
            CircleIconButton(if (saved) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                description = if (saved) "Manage saved place" else "Save place") { chooseLists = true }
            Box {
                CircleIconButton(Icons.Filled.Share, description = localizeLabel("Share place")) { share = true }
                DropdownMenu(share, { share = false }) {
                    listOf("Open in Google Maps" to { ctx.open(place.shareUrl) },
                        "Kite link" to { ctx.share(QuickLaunch.shareLink(place)) },
                        "Share Google Maps link" to { ctx.share(place.shareUrl) },
                        "Share place name" to { ctx.share(place.name) },
                        "Share coordinates" to { ctx.share("${place.location.lat.plain()}, ${place.location.lng.plain()}") },
                    ).forEach { (label, action) ->
                        DropdownMenuItem(text = { Text(label) }, onClick = { share = false; action() })
                    }
                }
            }
        }
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.weight(1f).heightIn(min = 56.dp).clip(CircleShape).background(interfaceAccent)
                .clickable { model.openDirections(place) }, horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Directions, null, tint = interfaceOnAccent, modifier = Modifier.size(24.dp))
                Text(localizeLabel("Directions"), fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = interfaceOnAccent)
            }
            Row(Modifier.heightIn(min = 56.dp).clip(CircleShape).background(interfaceGround)
                .clickable { model.openDirections(place, TravelMode.DRIVE, startNow = true) }.padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Navigation, null, tint = interfaceAccentText, modifier = Modifier.size(22.dp))
                Text(localizeLabel("Go"), fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = interfaceAccentText)
            }
        }
    }
    if (chooseLists) SavePlaceDialog(place, { chooseLists = false }) {
        chooseLists = false
        model.nameNewShortcut(place)
    }
}

@Composable
private fun SavePlaceDialog(place: Place, close: () -> Unit, shortcut: () -> Unit) {
    val settings = AppSettings
    val existing = settings.savedEntry(place)
    var selected by remember(place.id) {
        mutableStateOf(settings.placeFolders.filter { existing?.id in it.members }.map { it.id }.toSet())
    }
    var creating by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var pendingFolders by remember { mutableStateOf<List<PlaceFolder>>(emptyList()) }
    AlertDialog(
        onDismissRequest = close,
        title = { Text(if (existing == null) "Save place" else "Saved lists") },
        text = {
            Column(Modifier.heightIn(max = 350.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(place.name, style = MaterialTheme.typography.titleMedium, color = primaryText)
                if (existing == null) Text(localizeLabel("Save to your collection and choose any lists."), color = secondaryText)
                FilledTonalButton(shortcut, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.AddLocationAlt, null)
                    Spacer(Modifier.width(8.dp))
                    Text(localizeLabel("Add shortcut"))
                }
                for (folder in settings.placeFolders + pendingFolders) {
                    val checked = folder.id in selected
                    Row(Modifier.fillMaxWidth().toggleable(checked, role = androidx.compose.ui.semantics.Role.Checkbox) {
                        selected = if (it) selected + folder.id else selected - folder.id
                    }.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked, onCheckedChange = null, modifier = Modifier.padding(end = 14.dp))
                        Text(folder.name, color = primaryText)
                    }
                }
                if (creating) {
                    OutlinedTextField(name, { name = it }, label = { Text(localizeLabel("List name")) }, singleLine = true,
                        modifier = Modifier.fillMaxWidth())
                    Row {
                        TextButton({ creating = false }) { Text(localizeLabel("Cancel")) }
                        TextButton({
                            val folder = PlaceFolder(name.trim())
                            pendingFolders = pendingFolders + folder
                            selected = selected + folder.id
                            name = ""
                            creating = false
                        }, enabled = name.isNotBlank()) { Text(localizeLabel("Add list")) }
                    }
                } else TextButton({ name = ""; creating = true }) {
                    Icon(Icons.Filled.CreateNewFolder, null)
                    Spacer(Modifier.width(6.dp))
                    Text(localizeLabel("New list"))
                }
            }
        },
        confirmButton = {
            TextButton({
                settings.placeFolders = settings.placeFolders + pendingFolders
                settings.savePlace(place, selected)
                close()
            }, enabled = !creating) { Text(if (existing == null && selected.isEmpty()) "Save place" else "Save") }
        },
        dismissButton = {
            if (existing != null) TextButton({ settings.removeSaved(existing.id); close() }) { Text(localizeLabel("Remove saved place")) }
            else TextButton(close) { Text(localizeLabel("Cancel")) }
        },
    )
}

@Composable
fun PlaceBody(model: AppModel, place: Place) {
    if (place.id.startsWith("transit:")) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 72.dp)) {
            DepartureBoard(place) { model.previewDeparture(it, returnTo = place) }
            IndoorPlaceActions(model, place, station = true)
        }
        return
    }
    var tab by remember(place.id) { mutableStateOf("Overview") }
    val allowsGoogle = !AppSettings.noGoogle
    var related by remember(place.id, allowsGoogle) { mutableStateOf<Place?>(null) }
    LaunchedEffect(place.id, allowsGoogle) {
        if (!allowsGoogle || place.featureId != null || place.source != "osm" ||
            place.name.equals(OpenMapStyle.title(null, place.category, null), ignoreCase = true)) return@LaunchedEffect
        delay(400)
        val matches = try {
            GoogleClient.shared.search(place.name, place.location, 2_000.0, pages = 1)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
        related = matches.filter {
            it.featureId != null && it.name.equals(place.name, ignoreCase = true) && it.location.distanceTo(place.location) <= 85
        }.minByOrNull { it.location.distanceTo(place.location) }
    }
    val mediaPlace = related ?: place
    val mediaId = mediaPlace.featureId.takeIf { allowsGoogle }
    val canReview = mediaId != null && (mediaPlace.reviewCount ?: 1) > 0
    var reviews by remember(mediaId) { mutableStateOf<List<PlaceReview>?>(null) }
    var reviewsFailed by remember(mediaId) { mutableStateOf(false) }
    var nextReviewPage by remember(mediaId) { mutableStateOf<String?>(null) }
    var fetchingMore by remember(mediaId) { mutableStateOf(false) }
    var histogram by remember(mediaId) { mutableStateOf(emptyList<Int>()) }
    var extraPopularTimes by remember(mediaId) { mutableStateOf(emptyList<BusyDay>()) }
    val reviewScope = rememberCoroutineScope()
    var attempt by remember(mediaId) { mutableIntStateOf(0) }
    var gallery by remember(mediaId) { mutableStateOf<List<String>?>(null) }
    var driveSeconds by remember(place.id) { mutableStateOf<Double?>(null) }
    fun mergeReviews(incoming: List<PlaceReview>) {
        val merged = (reviews.orEmpty() + incoming).groupBy { Triple(it.author, it.stars, it.text) }.values.map { group ->
            group.first().copy(photos = group.flatMap { it.photos }.distinct(),
                avatar = group.firstNotNullOfOrNull { it.avatar }, likes = group.lastOrNull { it.likes != null }?.likes,
                publishedMs = group.firstNotNullOfOrNull { it.publishedMs })
        }.take(200)
        if (reviews != merged) reviews = merged
    }
    LaunchedEffect(mediaId, canReview, attempt) {
        if (mediaId == null || !canReview) return@LaunchedEffect
        reviewsFailed = false
        delay(220)
        try {
            val page = GoogleClient.shared.reviewsPage(mediaId)
            mergeReviews(page.reviews)
            nextReviewPage = page.nextToken
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            reviewsFailed = true
        }
    }
    LaunchedEffect(mediaId, mediaPlace.address) {
        val address = mediaPlace.address?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        if (mediaId == null || mediaPlace.popularTimes.isNotEmpty()) return@LaunchedEffect
        delay(700)
        try {
            extraPopularTimes = GoogleClient.shared.search("${mediaPlace.name} $address", mediaPlace.location, 1600.0, pages = 1)
                .firstOrNull { it.featureId == mediaId }?.popularTimes.orEmpty()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Some places do not publish a visitation chart.
        }
    }
    LaunchedEffect(mediaId) {
        if (mediaId != null) delay(220)
        gallery = if (mediaId == null) emptyList() else try {
            GoogleClient.shared.photos(mediaId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
    }
    val previews = if (AppSettings.noGoogle) place.photoUrls.filterNot(::isGooglePhoto) else (place.photoUrls + mediaPlace.photoUrls)
    val reviewPhotos = reviews.orEmpty().flatMap { it.photos }
    val photos = (previews + gallery.orEmpty() + reviewPhotos).distinctBy { it.substringBeforeLast("=w") }
    LaunchedEffect(place.id) {
        val from = model.userLatLng ?: return@LaunchedEffect
        val d = from.distanceTo(place.location)
        if (d <= 300 || d >= 300_000) return@LaunchedEffect
        delay(700)
        val s = model.settings
        var r: List<Route>? = null
        if (!s.noGoogle) r = runCatching { GoogleClient.shared.directions(from, place.location, TravelMode.DRIVE, s.avoidHighways, s.avoidTolls) }.getOrNull()
        if (r.isNullOrEmpty()) r = runCatching { OSRM.route(from, place.location, TravelMode.DRIVE) }.getOrNull()
        driveSeconds = r?.minOfOrNull { it.durationSeconds }
    }
    val scroll = rememberScrollState()
    LaunchedEffect(tab) { scroll.scrollTo(0) }
    Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 16.dp).padding(bottom = 80.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PlaceActions(model, place, driveSeconds)
        val mediaTabs = buildList {
            add("Overview")
            if (canReview) add("Reviews")
            if (mediaId != null || photos.isNotEmpty()) add("Photos")
            if (place.about.isNotEmpty()) add("About")
        }
        LaunchedEffect(model.showReviews, place.id) {
            if (model.showReviews == place.id && "Reviews" in mediaTabs) {
                tab = "Reviews"
                model.showReviews = null
            }
        }
        val selectedTab = tab.takeIf { it in mediaTabs } ?: "Overview"
        if (selectedTab == "Overview") {
            if (photos.isEmpty() && mediaId != null && gallery == null) {
                Box(Modifier.fillMaxWidth().height(156.dp).clip(RoundedCornerShape(18.dp))
                    .background(interfaceSurface), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            } else {
                PhotoStrip(photos.take(6), 156.dp, opensViewer = true, streetView = place, model = model)
            }
        }
        if (mediaTabs.size > 1) {
            Surface(color = interfaceSurface, shape = RoundedCornerShape(22.dp)) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(5.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    mediaTabs.forEach { label ->
                        val active = selectedTab == label
                        val icon = when (label) {
                            "Reviews" -> Icons.Filled.Star
                            "Photos" -> Icons.Filled.PhotoLibrary
                            "About" -> Icons.Filled.Info
                            else -> Icons.Filled.Place
                        }
                        Row(Modifier.clip(CircleShape).background(if (active) interfaceAccent else Color.Transparent)
                            .clickable { tab = label }.padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            val ink = if (active) interfaceOnAccent else secondaryText
                            Icon(icon, null, tint = ink, modifier = Modifier.size(17.dp))
                            Text(if (label == "Overview") kt("Overview", "Přehled") else if (label == "Photos") kt("Photos", "Fotky") else localizeLabel(label),
                                color = ink, maxLines = 1, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
        when (selectedTab) {
            "Reviews" -> PlaceReviews(mediaPlace, reviews, histogram, reviewsFailed, fetchingMore,
                retry = { attempt++ }, more = nextReviewPage?.let { token ->
                    {
                        if (mediaId != null && !fetchingMore) reviewScope.launch {
                            fetchingMore = true
                            try {
                                val page = GoogleClient.shared.reviewsPage(mediaId, token)
                                mergeReviews(page.reviews)
                                nextReviewPage = page.nextToken?.takeIf { it != token }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                nextReviewPage = null
                            } finally {
                                fetchingMore = false
                            }
                        }
                    }
                }, onReviews = { page ->
                    mergeReviews(page.reviews)
                    if (page.histogram.isNotEmpty()) histogram = page.histogram
                    reviewsFailed = false
                })
            "Photos" -> PlacePhotos(mediaPlace, photos, loading = mediaId != null && gallery == null)
            "About" -> PlaceAbout(place)
            else -> {
                PlaceOverview(model, place)
                if (allowsGoogle) PopularTimesCard(mediaPlace.popularTimes.ifEmpty { extraPopularTimes })
                IndoorPlaceActions(model, place, station = isStop(place))
                if (reviews.orEmpty().isNotEmpty() || canReview && !place.featuredReview.isNullOrBlank()) {
                    Text(localizeLabel("Reviews"), fontWeight = FontWeight.Bold, color = primaryText, fontSize = KType.headline)
                    if (reviews.orEmpty().isEmpty()) place.featuredReview?.let { Text(it, color = secondaryText) }
                    reviews.orEmpty().take(3).forEach { ReviewRow(it) }
                    TextButton({ tab = "Reviews" }) { Text(localizeLabel("See more reviews")) }
                }
            }
        }
        val attribution = if (related != null) "Map data © OpenStreetMap contributors · Photos and reviews from Google Maps"
        else when (place.source) {
            "google" -> "Place details from Google Maps"
            "mapbox" -> "Map data © Mapbox and OpenStreetMap contributors"
            else -> "Map data © OpenStreetMap contributors"
        }
        Text(attribution, fontSize = KType.caption, color = secondaryText)
    }
}

@Composable
private fun PlaceReviews(
    place: Place, reviews: List<PlaceReview>?, histogram: List<Int>, failed: Boolean, loadingMore: Boolean,
    retry: () -> Unit, more: (() -> Unit)?, onReviews: (PublicPlaceReviews) -> Unit,
) {
    val ctx = LocalContext.current
    var openWeb by remember(place.id) { mutableStateOf(false) }
    var sort by remember(place.id) { mutableIntStateOf(0) }
    var sorting by remember { mutableStateOf(false) }
    val properties = reviews.orEmpty()
    val shown = when (sort) {
        1 -> properties.sortedByDescending { it.publishedMs ?: Long.MIN_VALUE }
        2 -> properties.sortedByDescending { it.likes ?: -1 }
        3 -> properties.sortedByDescending { it.stars ?: 0 }
        4 -> properties.sortedBy { it.stars ?: 6 }
        else -> properties
    }
    val sortLabels = listOf(kt("Most relevant", "Nejrelevantnější"), kt("Newest", "Nejnovější"),
        kt("Most liked", "Nejvíce reakcí"), kt("Highest rated", "Nejlepší hodnocení"), kt("Lowest rated", "Nejhorší hodnocení"))
    ReviewHistogram(place, properties, histogram)
    if (failed) {
        Text(localizeLabel("Reviews are temporarily unavailable."), color = secondaryText)
        TextButton(retry) { Text(localizeLabel("Try again")) }
    } else if (reviews == null) {
        CircularProgressIndicator(Modifier.size(26.dp))
    } else if (properties.isEmpty()) {
        Text(place.featuredReview ?: kt("No reviews returned for this place.", "Pro toto místo se recenze nenačetly."), color = secondaryText)
    }
    if (properties.isNotEmpty()) Box {
        FilledTonalButton({ sorting = true }) {
            Icon(Icons.Filled.Sort, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(sortLabels[sort])
        }
        DropdownMenu(sorting, { sorting = false }) {
            sortLabels.forEachIndexed { index, label ->
                val available = when(index) {
                    1 -> properties.any { it.publishedMs != null }
                    2 -> properties.any { it.likes != null }
                    else -> true
                }
                DropdownMenuItem(text = { Text(label) }, onClick = { sort = index; sorting = false }, enabled = available)
            }
        }
    }
    shown.forEach { ReviewRow(it) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button({ if (more != null) more() else openWeb = true }, enabled = !loadingMore) {
            if (loadingMore) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else Text(kt("More reviews", "Další recenze"))
        }
        TextButton({ ctx.open(place.shareUrl) }) { Text(localizeLabel("Open Google Maps")) }
    }
    if (openWeb && !AppSettings.noGoogle) GoogleReviewsPage(place, onReviews) { openWeb = false }
}

@Composable
private fun ReviewHistogram(place: Place, reviews: List<PlaceReview>, histogram: List<Int>) {
    val loaded = reviews.mapNotNull { it.stars?.takeIf { stars -> stars in 1..5 } }
    val complete = histogram.size == 5 && histogram.any { it > 0 }
    val counts = if (complete) histogram else (1..5).map { star -> loaded.count { it == star } }
    if (place.rating == null && counts.all { it == 0 }) return
    val max = (counts.maxOrNull() ?: 0).coerceAtLeast(1)
    val starColor = Color(if (LocalDark.current) 0xFFFFC55E else 0xFF985300)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(interfaceGround).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(kt("Ratings", "Hodnocení"), fontWeight = FontWeight.Bold, color = primaryText)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                place.rating?.let { Text(String.format(Locale.getDefault(), "%.1f", it), fontSize = 31.sp,
                    fontWeight = FontWeight.ExtraBold, color = primaryText) }
                Icon(Icons.Filled.Star, null, tint = starColor, modifier = Modifier.size(20.dp))
                place.reviewCount?.let { Text(Fmt.count(it), color = secondaryText, fontSize = KType.caption) }
            }
            if (max > 0 && counts.any { it > 0 }) Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                for (star in 5 downTo 1) {
                    val count = counts[star - 1]
                    Row(Modifier.semantics { contentDescription = "$star ★ · ${Fmt.count(count)}" },
                        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("$star", fontSize = KType.caption, color = secondaryText)
                        Box(Modifier.weight(1f).height(5.dp).clip(CircleShape).background(starColor.copy(alpha = 0.15f))) {
                            Box(Modifier.fillMaxWidth(count.toFloat() / max).fillMaxHeight().background(starColor))
                        }
                    }
                }
            }
        }
        if (!complete && loaded.isNotEmpty()) Text(kt("Loaded reviews", "Načtené recenze") + " · ${loaded.size}",
            color = secondaryText, fontSize = KType.caption)
    }
}

@Composable
private fun PopularTimesCard(days: List<BusyDay>) {
    if (days.isEmpty()) return
    val today = java.time.LocalDate.now().dayOfWeek.value
    var day by remember(days) { mutableIntStateOf(days.firstOrNull { it.dayOfWeek == today }?.dayOfWeek ?: days.first().dayOfWeek) }
    var selectedHour by remember(day) { mutableStateOf<Int?>(null) }
    val hours = days.firstOrNull { it.dayOfWeek == day }?.hours.orEmpty().associate { it.hour to it.percent }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(interfaceGround).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(kt("Popular times", "Obvyklá návštěvnost"), fontWeight = FontWeight.Bold, color = primaryText)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            days.forEach { item ->
                val label = java.time.DayOfWeek.of(item.dayOfWeek).getDisplayName(java.time.format.TextStyle.SHORT, Locale.getDefault())
                FilterChip(selected = item.dayOfWeek == day, onClick = { day = item.dayOfWeek }, label = { Text(label) })
            }
        }
        Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            for (hour in 0..23) {
                val value = hours[hour]
                Box(Modifier.weight(1f).fillMaxHeight().then(if (value == null) Modifier else
                    Modifier.semantics { contentDescription = "$hour:00 · $value%" }.clickable { selectedHour = hour }),
                    contentAlignment = Alignment.BottomCenter) {
                    if (value != null) Box(Modifier.fillMaxWidth().height((2 + value.coerceIn(0, 100) * 0.60f).dp)
                        .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                        .background(interfaceAccent.copy(alpha = if (selectedHour == hour) 1f else 0.65f)))
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("0:00", "6:00", "12:00", "18:00", "24:00").forEach { Text(it, color = secondaryText, fontSize = KType.caption2) }
        }
        selectedHour?.let { Text("$it:00 · ${hours[it]} %", color = primaryText, fontSize = KType.caption) }
    }
}

@Composable
private fun ReviewRow(review: PlaceReview) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(interfaceGround).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            Box(Modifier.size(38.dp).clip(CircleShape).background(interfaceTonal), contentAlignment = Alignment.Center) {
                if (review.avatar != null) AsyncImage(review.avatar, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                else Text(review.author.firstOrNull()?.uppercase().orEmpty(), fontWeight = FontWeight.Bold, color = interfaceOnTonal)
            }
            Text(review.author, fontWeight = FontWeight.Bold, color = primaryText, fontSize = KType.subheadline, modifier = Modifier.weight(1f))
            review.likes?.let { likes ->
                Icon(Icons.Outlined.ThumbUp, kt("Likes", "Reakce"), tint = secondaryText, modifier = Modifier.size(16.dp))
                Text(Fmt.count(likes), fontSize = KType.caption, color = secondaryText)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            review.stars?.let { rating ->
                val stars = rating.coerceIn(0, 5)
                Row(Modifier.semantics { contentDescription = "$stars out of 5 stars" }) {
                    repeat(5) { index ->
                        val filled = index < stars
                        Icon(if (filled) Icons.Filled.Star else Icons.Outlined.StarBorder, null,
                            tint = if (filled) Color(if (LocalDark.current) 0xFFFFC55E else 0xFF985300) else secondaryText,
                            modifier = Modifier.size(17.dp))
                    }
                }
            }
            review.relativeTime?.let { Text(it, fontSize = KType.caption, color = secondaryText) }
        }
        review.text?.let { Text(it, color = primaryText) }
        if (review.photos.isNotEmpty()) PhotoStrip(review.photos, 96.dp, opensViewer = true)
    }
}

@Composable
private fun PlacePhotos(place: Place, photos: List<String>, loading: Boolean) {
    val ctx = LocalContext.current
    var viewing by remember(place.id) { mutableStateOf<Int?>(null) }
    if (loading) CircularProgressIndicator(Modifier.size(26.dp))
    photos.chunked(3).forEachIndexed { row, urls ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            urls.forEachIndexed { col, url ->
                AsyncImage(url, "Photo ${row * 3 + col + 1}", contentScale = ContentScale.Crop,
                    modifier = Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(10.dp))
                        .background(secondaryText.copy(alpha = 0.15f)).clickable { viewing = row * 3 + col })
            }
            repeat(3 - urls.size) { Spacer(Modifier.weight(1f)) }
        }
    }
    if (photos.isEmpty() && !loading) Text(localizeLabel("No photos returned for this place."), color = secondaryText)
    if (place.featureId != null && !AppSettings.noGoogle) TextButton({ ctx.open(place.shareUrl) }) { Text(localizeLabel("More photos on Google Maps")) }
    viewing?.takeIf { it in photos.indices }?.let { PhotoViewer(photos, it) { viewing = null } }
}

@Composable
private fun PlaceActions(model: AppModel, place: Place, driveSeconds: Double?) {
    val ctx = LocalContext.current
    var more by remember { mutableStateOf(false) }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        driveSeconds?.let {
            Row(Modifier.height(52.dp).border(1.dp, secondaryText.copy(alpha = 0.35f), CircleShape).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Filled.DirectionsCar, null, tint = primaryText, modifier = Modifier.size(20.dp))
                Text("${Fmt.duration(it)} drive", fontWeight = FontWeight.Bold, color = primaryText)
            }
        }
        telUrl(place.phone)?.let { PillButton("Call", Icons.Filled.Phone) { ctx.open(it) } }
        webUrl(place.website)?.let { PillButton("Website", Icons.Filled.Language) { ctx.open(it) } }
        if (place.id == model.settings.parking?.id) PillButton("Clear spot", Icons.Outlined.Cancel) { model.clearParking() }
        Box {
            Row(Modifier.height(40.dp).clip(CircleShape).background(interfaceGround).clickable { more = true }.padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Filled.MoreHoriz, null, tint = interfaceAccentText, modifier = Modifier.size(18.dp))
                Text(localizeLabel("More"), fontSize = KType.subheadline, fontWeight = FontWeight.SemiBold, color = interfaceAccentText)
            }
            DropdownMenu(more, { more = false }) {
                fun item(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, f: () -> Unit) = @Composable {
                    DropdownMenuItem(text = { Text(label) }, leadingIcon = { Icon(icon, null) }, onClick = { more = false; f() })
                }
                if (model.settings.home?.id == place.id) item("Remove home", Icons.Filled.Home) { model.setHome(null) }()
                else item("Set as home", Icons.Outlined.Home) { model.setHome(place) }()
                if (model.settings.work?.id == place.id) item("Remove work", Icons.Filled.Work) { model.setWork(null) }()
                else item("Set as work", Icons.Outlined.WorkOutline) { model.setWork(place) }()
                item("Add shortcut", Icons.Filled.AddLocationAlt) { model.nameNewShortcut(place) }()
                place.address?.let { a -> item("Copy address", Icons.Filled.ContentPaste) { ctx.copy("${place.name}, $a") }() }
                HorizontalDivider()
                Text(localizeLabel("Open in"), fontSize = KType.caption, color = secondaryText, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                for (app in OtherApp.entries) item(app.label, Icons.Filled.Map) { ctx.open(app.url(place)) }()
            }
        }
    }
}

/** A bus stop or a station, which gets a live departure board. */
private fun isStop(p: Place): Boolean {
    if (p.id.startsWith("transit:")) return true
    val text = ((p.category ?: "") + " " + p.name).lowercase()
    if (text.contains("gas station") || text.contains("charging station") || text.contains("petrol station")) return false
    return listOf("station", "bus stop", "stop ", "transit", "subway", "metro", "tram", "light rail", "terminal", "depot").any { text.contains(it) }
}

@Composable
private fun PlaceOverview(model: AppModel, place: Place) {
    val ctx = LocalContext.current
    var hoursOpen by remember(place.id) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        place.editorialSummary?.let { s -> Copyable(s) { Text(s, color = primaryText) } }
        place.address?.let { PlaceInfoCard(Icons.Outlined.Place, "Address", it) }
        if (isStop(place)) DepartureBoard(place) { model.previewDeparture(it, returnTo = place) }
        if (place.hours.isNotEmpty()) OpeningHoursCard(place, hoursOpen) { hoursOpen = !hoursOpen }
        place.phone?.let { phone -> PlaceInfoCard(Icons.Outlined.Phone, "Phone", phone, telUrl(phone)?.let { url -> { ctx.open(url) } }) }
        place.website?.let { site ->
            PlaceInfoCard(Icons.Filled.Language, localizeLabel("Website"), site.removePrefix("https://").removePrefix("http://"),
                webUrl(site)?.let { url -> { ctx.open(url) } })
        }
        place.featuredReview?.let { r ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(localizeLabel("Review"), fontSize = KType.headline, fontWeight = FontWeight.SemiBold, color = primaryText)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Filled.AccountCircle, null, tint = secondaryText, modifier = Modifier.size(28.dp))
                    Text("“$r”", fontSize = KType.subheadline, color = primaryText)
                }
            }
        }
    }
}

@Composable
internal fun PlaceInfoCard(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String, open: (() -> Unit)? = null) {
    val accent = MaterialTheme.colorScheme.primary
    val context = LocalContext.current
    Surface(color = interfaceGround, shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, secondaryText.copy(alpha = 0.16f))) {
        Copyable(value, onClick = open ?: { context.copy(value) }) {
            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(accent.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center) {
                    Icon(icon, null, tint = accent, modifier = Modifier.size(21.dp))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(label, fontWeight = FontWeight.SemiBold, color = primaryText)
                    Text(value, fontSize = KType.footnote, color = secondaryText, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                Icon(if (open == null) Icons.Filled.ContentCopy else Icons.Filled.OpenInNew,
                    if (open == null) "Copy $label" else "Open $label", tint = secondaryText, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
internal fun OpeningHoursCard(place: Place, expanded: Boolean, toggle: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Surface(color = interfaceGround, shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, secondaryText.copy(alpha = 0.16f))) {
        Column(Modifier.fillMaxWidth()) {
            Copyable(place.hours.joinToString("\n"), onClick = toggle) {
                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(accent.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.Schedule, null, tint = accent, modifier = Modifier.size(21.dp))
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(localizeLabel("Opening hours"), fontWeight = FontWeight.SemiBold, color = primaryText)
                        StatusText(place)
                    }
                    Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        if (expanded) "Collapse hours" else "Expand hours", tint = secondaryText)
                }
            }
            if (expanded) Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp)) {
                for (hours in place.hours) {
                    val separator = hours.indexOf(": ")
                    val day = hours.substringBefore(": ").takeIf { separator > 0 && it.none(Char::isDigit) }
                    HorizontalDivider(color = secondaryText.copy(alpha = 0.16f))
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (day == null) Text(hours, fontSize = KType.subheadline, color = primaryText)
                        else {
                            Text(day, fontSize = KType.subheadline, fontWeight = FontWeight.Medium,
                                color = primaryText, modifier = Modifier.weight(1f))
                            Text(hours.substring(separator + 2), fontSize = KType.subheadline, color = secondaryText,
                                textAlign = androidx.compose.ui.text.style.TextAlign.End, modifier = Modifier.weight(1.7f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaceAbout(place: Place) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        for (section in place.about) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(section.title, fontSize = KType.headline, fontWeight = FontWeight.SemiBold, color = primaryText)
                for (item in section.items) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Check, null, tint = secondaryText, modifier = Modifier.size(14.dp))
                    Text(item, fontSize = KType.subheadline, color = primaryText)
                }
            }
        }
    }
}

@Composable
fun InfoRow(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, null, tint = secondaryText, modifier = Modifier.size(22.dp))
        Text(text, fontSize = KType.subheadline, color = primaryText, modifier = Modifier.weight(1f))
    }
}

/** Next departures at a stop from Transitous, refreshed every 30 seconds while it's open. */
@Composable
fun DepartureBoard(place: Place, onDeparture: ((Transitous.Departure) -> Unit)? = null) {
    var board by remember(place.id) { mutableStateOf<Transitous.Board?>(null) }
    var loaded by remember(place.id) { mutableStateOf(false) }
    LaunchedEffect(place.id) {
        while (true) {
            val b = runCatching { Transitous.departures(place.location,
                stopId = place.id.removePrefix("transit:").takeIf { place.id.startsWith("transit:") },
                stopName = place.name) }.getOrNull()
            if (b != null || !loaded) board = b
            loaded = true
            delay(30_000)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(localizeLabel("Departures"), fontSize = KType.headline, fontWeight = FontWeight.SemiBold, color = primaryText)
        val b = board
        when {
            b != null -> {
                for (d in b.departures.take(8)) Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                    .then(if (onDeparture != null && d.tripId != null) Modifier.tap { onDeparture(d) } else Modifier)
                    .background(interfaceSurface).padding(horizontal = 10.dp, vertical = 7.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    LineChip(TransitLeg(isWalk = false, mode = d.mode, line = d.line, color = d.color, textColor = d.textColor))
                    Column(Modifier.weight(1f)) {
                        Text(d.headsign, fontSize = KType.subheadline, color = primaryText, maxLines = 2,
                            overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                        d.platform?.takeIf(String::isNotBlank)?.let {
                            Text(kt("Platform $it", "Nástupiště $it"), fontSize = KType.caption, color = secondaryText)
                        }
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        if (onDeparture != null && d.tripId != null) {
                            Icon(Icons.Filled.Map, "Show service on map", Modifier.size(16.dp), tint = interfaceAccentText)
                        }
                        Text(countdown(d.time), fontSize = KType.subheadline, fontWeight = FontWeight.SemiBold,
                            color = if (d.delayMinutes != null) Vela.open else primaryText)
                        d.delayMinutes?.takeIf { it != 0 }?.let { late ->
                            Text(if (late > 0) "$late min late" else "${-late} min early", fontSize = KType.caption2,
                                color = if (late > 0) Vela.closed else Vela.open)
                        }
                    }
                }
                Text(localizeLabel("Live times in green · Transitous"), fontSize = KType.caption, color = secondaryText)
            }
            loaded -> Text(localizeLabel("No departures found here."), fontSize = KType.subheadline, color = secondaryText)
            else -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Text(localizeLabel("Loading departures…"), color = secondaryText)
            }
        }
    }
}

private fun countdown(t: Long): String {
    val mins = Math.round((t - System.currentTimeMillis()) / 60_000.0).toInt()
    if (mins <= 0) return "Now"
    if (mins < 60) return "$mins min"
    return Fmt.time(java.util.Date(t))
}

@Composable
fun PhotoStrip(all: List<String>, height: Dp = 130.dp, opensViewer: Boolean = false, streetView: Place? = null, model: AppModel? = null) {
    // "Use Kite without Google": a place saved earlier still has Google's photo links; don't load them.
    val urls = if (AppSettings.noGoogle) all.filterNot(::isGooglePhoto) else all
    var viewing by remember { mutableStateOf<Int?>(null) }
    androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (streetView != null && model != null) item { StreetViewTile(model, streetView, height) }
        items(urls.size) { i ->
            AsyncImage(urls[i], "Photo ${i + 1}", contentScale = ContentScale.Crop,
                modifier = Modifier.size(height * 1.4f, height).clip(RoundedCornerShape(15.dp)).background(secondaryText.copy(alpha = 0.15f))
                    .then(if (opensViewer) Modifier.clickable { viewing = i } else Modifier))
        }
    }
    viewing?.let { start -> PhotoViewer(urls, start) { viewing = null } }
}

fun isGooglePhoto(u: String): Boolean {
    val host = runCatching { java.net.URI(u).host?.lowercase() }.getOrNull() ?: return false
    return "google" in host || "gstatic" in host || "ggpht" in host
}

/** Google photo links carry their size ("=w408-h306-k-no"): ask for a bigger one. */
fun largePhoto(u: String): String = u.replace(Regex("=w\\d+-h\\d+"), "=w1600-h1200")

/** Photos full screen, swiped through. */
@Composable
fun PhotoViewer(urls: List<String>, start: Int, close: () -> Unit) {
    FullScreenDialog(close) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            val pager = rememberPagerState(start) { urls.size }
            var zoom by remember { mutableFloatStateOf(1f) }
            var translation by remember { mutableStateOf(Offset.Zero) }
            LaunchedEffect(pager.settledPage) {
                zoom = 1f
                translation = Offset.Zero
            }
            HorizontalPager(pager, Modifier.fillMaxSize(), userScrollEnabled = zoom <= 1f) { i ->
                AsyncImage(largePhoto(urls[i]), null, contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().pointerInput(i) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            do {
                                val event = awaitPointerEvent()
                                val centroid = event.calculateCentroid()
                                if (centroid.isSpecified && event.changes.any { it.pressed } &&
                                    (event.changes.count { it.pressed } >= 2 || zoom > 1f)) {
                                    val next = (zoom * event.calculateZoom()).coerceIn(1f, 5f)
                                    val pivot = centroid - Offset(size.width / 2f, size.height / 2f)
                                    val position = translation * (next / zoom) + pivot * (1f - next / zoom) + event.calculatePan()
                                    val x = size.width * (next - 1f) / 2f
                                    val y = size.height * (next - 1f) / 2f
                                    translation = Offset(position.x.coerceIn(-x, x), position.y.coerceIn(-y, y))
                                    zoom = next
                                    event.changes.forEach { it.consume() }
                                }
                            } while (event.changes.any { it.pressed })
                        }
                    }.pointerInput(i) {
                        detectTapGestures(onDoubleTap = {
                            zoom = if (zoom > 1f) 1f else 2.5f
                            translation = Offset.Zero
                        })
                    }.graphicsLayer {
                        if (i == pager.settledPage) {
                            scaleX = zoom; scaleY = zoom
                            translationX = translation.x; translationY = translation.y
                        }
                    })
            }
            Box(Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(16.dp).size(40.dp).clip(CircleShape)
                .background(Color.White.copy(alpha = 0.2f)).clickable(onClick = close), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Close, localizeLabel("Close"), tint = Color.White)
            }
        }
    }
}

/** The first tile of a place's photos: Google Street View looking at the place. */
@Composable
fun StreetViewTile(model: AppModel, place: Place, height: Dp) {
    val ctx = LocalContext.current
    if (AppSettings.noGoogle) return
    var pano by remember(place.id, place.streetViewHint) { mutableStateOf<StreetView.Panorama?>(null) }
    var loaded by remember(place.id, place.streetViewHint) { mutableStateOf(false) }
    fun heading(p: StreetView.Panorama): Double = place.streetViewHint?.takeIf { it.id == p.id }?.yaw
        ?: if (p.location.distanceTo(place.location) > 2) Geo.bearing(p.location, place.location) else p.heading
    LaunchedEffect(place.id, place.location, place.streetViewHint) {
        val hint = place.streetViewHint
        val resolved = hint?.let { StreetView.byId(it.id) } ?: StreetView.find(place.location)
            ?: hint?.let { StreetView.Panorama(it.id, place.location, heading = it.yaw ?: 0.0) }
        pano = resolved
        loaded = true
        if (resolved != null && DemoScript.step == "streetview") {
            model.openStreetView(StreetViewRequest(place.name, resolved, heading(resolved)))
        }
    }
    val p = pano
    if (p != null) {
        val heading = heading(p)
        Box(Modifier.size(height * 1.4f, height).clip(RoundedCornerShape(10.dp))
            .clickable { model.openStreetView(StreetViewRequest(place.name, p, heading)) }) {
            val thumbnail = ImageRequest.Builder(ctx).data(StreetView.thumbnailUrl(p, heading)).apply {
                StreetView.imageHeaders.forEach { (name, value) -> addHeader(name, value) }
            }.build()
            AsyncImage(thumbnail, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().background(secondaryText.copy(alpha = 0.15f)))
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.55f))))
            Row(Modifier.align(Alignment.BottomStart).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Streetview, null, tint = Color.White, modifier = Modifier.size(16.dp))
                Text("Street View", fontSize = KType.caption, fontWeight = FontWeight.SemiBold, color = Color.White)
            }
        }
    } else {
        Box(Modifier.size(height * 1.4f, height).clip(RoundedCornerShape(10.dp)).background(interfaceTonal)
            .clickable {
                val at = "${place.location.lat.plain()},${place.location.lng.plain()}"
                ctx.open("https://www.google.com/maps/@?api=1&map_action=pano&viewpoint=$at")
            }, contentAlignment = Alignment.Center) {
            Text(if (loaded) "Open Street View" else "Loading Street View…", color = primaryText, fontWeight = FontWeight.SemiBold)
        }
    }
}

internal fun transitInk(leg: TransitLeg): Color {
    val background = Color(0xFF000000 or leg.mapColor).luminance()
    leg.textColor?.let {
        val ink = Color(0xFF000000 or it)
        val luminance = ink.luminance()
        val contrast = (maxOf(background, luminance) + 0.05f) / (minOf(background, luminance) + 0.05f)
        if (contrast >= 4.5f) return ink
    }
    return if (background > 0.179f) Color.Black else Color.White
}

/** A transit line's badge in its own colors. */
@Composable
fun LineChip(leg: TransitLeg) {
    val bg = Color(0xFF000000 or leg.mapColor)
    val fg = transitInk(leg)
    Row(Modifier.heightIn(min = 26.dp).clip(RoundedCornerShape(8.dp)).background(bg).padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Icon(vehicleIcon(leg.vehicle), null, tint = fg, modifier = Modifier.size(12.dp))
        Text(leg.line.ifEmpty { leg.mode.lowercase().replaceFirstChar { it.uppercase() } }, fontSize = KType.caption, fontWeight = FontWeight.Bold, color = fg, maxLines = 1)
    }
}

fun vehicleIcon(v: TransitLeg.Vehicle) = when (v) {
    TransitLeg.Vehicle.WALK -> Icons.AutoMirrored.Filled.DirectionsWalk
    TransitLeg.Vehicle.BUS -> Icons.Filled.DirectionsBus
    TransitLeg.Vehicle.METRO -> Icons.Filled.Subway
    TransitLeg.Vehicle.TRAM -> Icons.Filled.Tram
    TransitLeg.Vehicle.TRAIN -> Icons.Filled.Train
    TransitLeg.Vehicle.FERRY -> Icons.Filled.DirectionsBoat
    TransitLeg.Vehicle.OTHER -> Icons.Filled.DirectionsTransit
}

/**
 * A place's Google reviews inside Kite (GitHub #70): stars, who, when and what they said, the ones
 * Google Maps shows first. When Google won't list them, a button opens them on Google.
 */
@Composable
fun ReviewsList(place: Place) {
    val ctx = LocalContext.current
    val dark = LocalDark.current
    // Google's list of reviews stopped answering apps (checked 2026-11-21): the one review Google
    // sends with the place, and the full list on Google's own page.
    val reviews: List<GoogleReview>? = null
    val failed = true
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        place.rating?.let { rating ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(String.format(Locale.US, "%.1f", rating), fontSize = 40.sp, fontWeight = FontWeight.ExtraBold, color = primaryText)
                Column {
                    Text(starsText(rating), fontSize = 18.sp, color = Color(0xFFF59E0B))
                    place.reviewCount?.let { Text("%,d reviews on Google".format(it), fontSize = KType.subheadline, color = secondaryText) }
                }
            }
        }
        val list = reviews
        if (list != null) {
            list.forEach { r ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Filled.AccountCircle, null, tint = secondaryText, modifier = Modifier.size(30.dp))
                        Column {
                            Text(r.author, fontSize = KType.subheadline, fontWeight = FontWeight.Bold, color = primaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(listOfNotNull(r.rating?.let { starsText(it.toDouble()) }, r.whenText).joinToString("  "), fontSize = KType.caption, color = secondaryText)
                        }
                    }
                    Text(r.text, fontSize = KType.subheadline, color = primaryText)
                }
                HorizontalDivider()
            }
        } else if (!failed) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(localizeLabel("Loading reviews…"), color = secondaryText)
            }
        }
        if ((failed || list?.isEmpty() == true) && place.featuredReview != null) {
            Text("“${place.featuredReview}”", fontSize = KType.subheadline, color = primaryText)
        }
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(CircleShape).background(interfaceTonal)
            .clickable { ctx.open(GoogleReviews.webUrl(place)) }, horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.RateReview, null, tint = interfaceOnTonal, modifier = Modifier.size(20.dp))
            Text(if (list?.isNotEmpty() == true) "More reviews on Google" else "Read the reviews on Google", fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold, color = interfaceOnTonal)
        }
    }
}

private fun starsText(rating: Double): String = (0 until 5).joinToString("") { if (rating - it >= 0.5) "★" else "☆" }
