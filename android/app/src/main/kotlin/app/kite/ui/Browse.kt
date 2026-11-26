@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.kite.ui

import androidx.compose.ui.res.stringResource
import app.kite.R
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import app.kite.*
import app.kite.core.*
import java.util.Locale

private enum class BrowseSheetKind { MAP, RESULTS, PLACE }
private data class BrowseSheetFrame(val kind: BrowseSheetKind, val place: Place?, val detent: SheetDetent)

/** The map page: a big "Where to?" bar, Home and Work one tap away, and the results or place sheet. */
@Composable
fun BrowseOverlay(model: AppModel, showMapbox: Boolean) {
    val resultScroll = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(model.results) { resultScroll.scrollToItem(0) }
    val dark = LocalDark.current
    val ctx = LocalContext.current
    val s = AppSettings
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var previousQuery by rememberSaveable { mutableStateOf("") }
    fun openSearch() {
        if (searchOpen) return
        previousQuery = model.query
        model.typed(model.query)
        searchOpen = true
    }
    LaunchedEffect(model.searchPrompt) {
        if (model.consumeSearchPrompt()) openSearch()
    }
    var showCategories by rememberSaveable { mutableStateOf(false) }
    var nearbyDepartures by rememberSaveable { mutableStateOf(false) }
    val hasSheet = model.selected != null || model.results.isNotEmpty()
    val idle = !hasSheet

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val full = maxHeight
        val placePeekHeight = minOf(118.dp, full * 0.32f)
        val sheetH = if (!hasSheet) 0.dp else when (model.detent) {
            SheetDetent.PEEK -> if (model.selected != null) placePeekHeight else 230.dp
            SheetDetent.HALF -> full * 0.46f
            SheetDetent.FULL -> full * 0.94f
        }
        val navBottom = with(LocalDensity.current) { WindowInsets.navigationBars.getBottom(this).toDp() }

        // Map buttons: layers, parking and the 3D/2D/north-up button on the right, under the top cards.
        Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = if (model.resumeOffer != null) 266.dp else 166.dp)) {
                Spacer(Modifier.weight(1f))
                Column(verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    MapFab(Icons.Outlined.Layers) { model.showLayers = true }
                    if (idle && AppSettings.nearbyDeparturesButton) MapFab(Icons.Filled.DirectionsTransit) { nearbyDepartures = true }
                    if (s.parkingButton || s.parking != null) {
                        MapFab(Icons.Filled.LocalParking, tint = if (s.parking != null) Vela.routeBlue else null) {
                            val p = s.parking
                            if (p != null) model.select(p) else model.saveParking()
                        }
                    }
                    // 3D, 2D or north up, always there. On north up with the map turned by hand,
                    // the needle shows which way north is and a tap faces north again.
                    MapLookButton(model.browseLook, 48.dp) { model.cycleBrowseLook(ctx) }
                    CompassButton(model.mapHeading, model.currentCompass, onClick = model::toggleBrowseCompass)
                    val risk = model.areaRisk
                    if (s.wazeOn && s.wazePolice && s.policeRisk && risk != null && PoliceRisk.level(risk) != PoliceRisk.Level.LOW)
                        PoliceRiskButton(risk, opensLeft = true)
                }
            }
            Spacer(Modifier.weight(1f))
            if (sheetH < full * 0.6f) {
                Row(Modifier.fillMaxWidth().padding(bottom = if (hasSheet) (sheetH - navBottom).coerceAtLeast(0.dp) + 14.dp else 86.dp),
                    verticalAlignment = Alignment.Bottom) {
                    if (idle) Box(Modifier.offset(y = (-58).dp)) { WazeMapChips(model) }
                    Spacer(Modifier.weight(1f))
                    LocateButton(model)
                }
            }
        }

        val place = model.selected
        val sheetKind = when {
            place != null -> BrowseSheetKind.PLACE
            hasSheet -> BrowseSheetKind.RESULTS
            else -> BrowseSheetKind.MAP
        }
        AnimatedContent(
            targetState = BrowseSheetFrame(sheetKind, place, model.detent),
            contentKey = { it.kind },
            modifier = Modifier.align(Alignment.BottomCenter),
            contentAlignment = Alignment.BottomCenter,
            transitionSpec = {
                val changingSheets = initialState.kind != BrowseSheetKind.MAP && targetState.kind != BrowseSheetKind.MAP
                val enter = slideInVertically(tween(185, easing = FastOutSlowInEasing)) { if (changingSheets) it / 10 else it * 2 / 3 }
                val exit = slideOutVertically(tween(120, easing = FastOutSlowInEasing)) { if (changingSheets) it / 10 else it * 2 / 3 }
                ((enter + fadeIn(tween(150))) togetherWith (exit + fadeOut(tween(100)))).using(sizeTransform = null)
            },
            label = "Map place sheet",
        ) { frame ->
            val active = frame.kind == sheetKind
            val interaction = if (active) Modifier else Modifier.clearAndSetSemantics { }.pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                }
            }
            Box(interaction) {
                val detent = if (active) model.detent else frame.detent
                when (frame.kind) {
                    BrowseSheetKind.PLACE -> {
                        val shown = checkNotNull(frame.place)
                        key(shown.id) {
                            BottomSheet(detent, { if (active) model.detent = it }, full, placePeekHeight, opaque = true,
                                dismissOnDragFromExpanded = false, detentAnimationMillis = 165, onDismiss = { if (active) model.closePlace() }, header = {
                                    if (detent == SheetDetent.PEEK) PlacePeek(model, shown) else PlaceHeader(model, shown)
                                }, content = {
                                    if (detent != SheetDetent.PEEK) PlaceBody(model, shown)
                                })
                        }
                    }
                    BrowseSheetKind.RESULTS -> BottomSheet(detent, { if (active) model.detent = it }, full, 230.dp,
                        onDismiss = { if (active) model.clearSearch() }, header = { ResultsHeader(model) }, content = { ResultsList(model, list = resultScroll) })
                    BrowseSheetKind.MAP -> Unit
                }
            }
        }

        AnimatedVisibility(idle, Modifier.align(Alignment.BottomCenter), enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
            KiteDock(model)
        }

        if (idle && showCategories && s.categoryMenu) {
            Box(Modifier.matchParentSize().clickable(interactionSource = null, indication = null,
                onClickLabel = localizeLabel("Close place categories"), onClick = { showCategories = false }))
        }

        if (!hasSheet || model.detent != SheetDetent.FULL) Column(
            Modifier.fillMaxWidth().statusBarsPadding().padding(top = 6.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SearchBar(model, onOpen = ::openSearch)
            if (idle && s.categoryMenu) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        AssistChip({ showCategories = !showCategories }, { Text(localizeLabel("Places")) },
                            leadingIcon = { Icon(Icons.Filled.Category, null, Modifier.size(18.dp)) },
                            trailingIcon = { Icon(if (showCategories) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null) },
                            colors = AssistChipDefaults.assistChipColors(containerColor = interfaceSurface))
                        Spacer(Modifier.weight(1f))
                        if (showMapbox) MapboxAttribution()
                    }
                    AnimatedVisibility(showCategories,
                        enter = androidx.compose.animation.expandVertically() + fadeIn(),
                        exit = androidx.compose.animation.shrinkVertically() + fadeOut()) {
                        Column(Modifier.widthIn(min = 190.dp).heightIn(max = 300.dp).clip(RoundedCornerShape(18.dp))
                            .background(interfaceSheet).verticalScroll(rememberScrollState()).padding(vertical = 5.dp)) {
                            for (category in Category.all) {
                                IconRow(category.icon, category.label) {
                                    showCategories = false
                                    model.search(category.query, localizeLabel(category.label))
                                }
                            }
                        }
                    }
                }
            } else if (idle) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Category.all.forEach { category ->
                        AssistChip({ model.search(category.query, localizeLabel(category.label)) }, { Text(localizeLabel(category.label)) },
                            leadingIcon = { Icon(category.icon, null, Modifier.size(16.dp)) },
                            colors = AssistChipDefaults.assistChipColors(containerColor = interfaceSurface))
                    }
                }
            }
            if (showMapbox && (!idle || !s.categoryMenu))
                MapboxAttribution(Modifier.align(Alignment.End).padding(end = 16.dp))
            if (model.selected == null && model.canSearchHere && !model.searching) {
                Button(onClick = model::searchHere, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Icon(Icons.Filled.Search, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.search_here))
                }
            }
            val n = model.notice
            if (n != null) NoticeBanner(n) { model.notice = null }
            StatusCards()
            // Kite closed in the middle of a drive: one big button to carry on.
            val trip = model.resumeOffer
            if (idle && trip != null) ResumeCard(trip, onGo = { model.resumeTrip() }, onClose = { model.dismissResume() })
        }
    }
    if (DemoScript.step == "searchfocus") LaunchedEffect(Unit) { kotlinx.coroutines.delay(8000); openSearch() }
    androidx.activity.compose.BackHandler(enabled = !searchOpen && (hasSheet || showCategories)) {
        when {
            showCategories -> showCategories = false
            model.selected != null && model.detent == SheetDetent.FULL -> model.detent = SheetDetent.HALF
            model.selected != null -> model.closePlace()
            else -> model.backFromSearch()
        }
    }
    if (searchOpen) {
        fun dismiss() {
            model.query = previousQuery
            model.typed("")
            searchOpen = false
        }
        Dialog(onDismissRequest = ::dismiss, properties = DialogProperties(
            usePlatformDefaultWidth = false, decorFitsSystemWindows = false,
        )) {
            val window = (LocalView.current.parent as? DialogWindowProvider)?.window
            val searchBackground = interfaceGround
            val dark = LocalDark.current
            DisposableEffect(window, searchBackground, dark) {
                window?.let {
                    it.setWindowAnimations(R.style.Animation_KiteSearch)
                    it.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(searchBackground.toArgb()))
                    androidx.core.view.WindowCompat.setDecorFitsSystemWindows(it, false)
                    androidx.core.view.WindowCompat.getInsetsController(it, it.decorView).apply {
                        isAppearanceLightStatusBars = !dark
                        isAppearanceLightNavigationBars = !dark
                    }
                }
                onDispose { }
            }
            val keyboard = LocalSoftwareKeyboardController.current
            val finish = { keyboard?.hide(); searchOpen = false }
            Column(Modifier.fillMaxSize().background(searchBackground).systemBarsPadding().imePadding().padding(top = 6.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SearchBar(model, editing = true, onOpen = {}, onBack = { keyboard?.hide(); dismiss() }, finish = finish)
                SuggestionsPanel(model, Modifier.weight(1f), done = finish)
            }
        }
    }
    if (nearbyDepartures) NearbyDepartures(model) { nearbyDepartures = false }
}

/** "Centre on me", with sharing and parking on a long press. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LocateButton(model: AppModel) {
    val ctx = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    Box {
        Box(Modifier.size(52.dp).glass(CircleShape).combinedClickable(onClick = { model.centerOnUser(ctx) }, onLongClick = { menu = true }),
            contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.MyLocation, "Centre on me", tint = interfaceAccentText, modifier = Modifier.size(24.dp))
        }
        DropdownMenu(menu, { menu = false }) {
            model.userLatLng?.let { u ->
                DropdownMenuItem(text = { Text(localizeLabel("Share my location")) }, leadingIcon = { Icon(Icons.Filled.Share, null) },
                    onClick = { menu = false; ctx.share("https://maps.google.com/?q=${u.lat.plain()},${u.lng.plain()}") })
                DropdownMenuItem(text = { Text(localizeLabel("Copy my coordinates")) }, leadingIcon = { Icon(Icons.Filled.ContentCopy, null) },
                    onClick = { menu = false; ctx.copy(String.format(Locale.US, "%.6f, %.6f", u.lat, u.lng)) })
            }
            DropdownMenuItem(text = { Text(localizeLabel("I parked here")) }, leadingIcon = { Icon(Icons.Filled.LocalParking, null) },
                onClick = { menu = false; model.saveParking() })
        }
    }
}

/** "Where to?": the one way into search, big enough to hit from a phone holder. */
@Composable
private fun SearchBar(
    model: AppModel, onOpen: () -> Unit, editing: Boolean = false,
    onBack: () -> Unit = model::backFromSearch, finish: () -> Unit = {},
) {
    val ctx = LocalContext.current
    val requester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    if (editing) LaunchedEffect(Unit) {
        // Start the window transition before the keyboard starts its own animation.
        kotlinx.coroutines.delay(100)
        requester.requestFocus()
        keyboard?.show()
    }
    val voicePrompt = stringResource(R.string.where_to)
    val voice = rememberDictation({ finish(); model.search(it) }) {
        model.notice = kt("Voice input is unavailable on this phone.", "Hlasové zadávání není v tomto telefonu dostupné.")
    }
    Row(Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(54.dp).glass(CircleShape, AppSettings.solidMapSearch).padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        if (editing || model.results.isNotEmpty() || model.selected != null) {
            Box(Modifier.size(44.dp).clip(CircleShape).tap(onBack), contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, localizeLabel("Back"), tint = primaryText, modifier = Modifier.size(26.dp))
            }
        } else {
            Icon(Icons.Filled.Search, voicePrompt, tint = secondaryText, modifier = Modifier.tap(onOpen).padding(start = 8.dp).size(24.dp))
        }
        if (editing) {
            BasicTextField(model.query, { q -> model.query = q; model.typed(q) },
                Modifier.weight(1f).focusRequester(requester),
                singleLine = true,
                textStyle = TextStyle(fontSize = 19.sp, fontWeight = FontWeight.Medium, color = primaryText, fontFamily = Rounded),
                cursorBrush = SolidColor(mapControlTint),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { if (model.query.isNotBlank()) { finish(); model.search() } }),
                decorationBox = { field ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (model.query.isEmpty()) Text(voicePrompt, fontSize = 19.sp, fontWeight = FontWeight.Medium, color = secondaryText)
                        field()
                    }
                })
        } else {
            Box(Modifier.weight(1f).fillMaxHeight().tap(onOpen), contentAlignment = Alignment.CenterStart) {
                Text(model.query.ifEmpty { voicePrompt }, fontSize = 19.sp, fontWeight = FontWeight.Medium,
                    color = if (model.query.isEmpty()) secondaryText else primaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        when {
            editing && model.query.isNotBlank() -> IconButton({ finish(); model.search() }) {
                Icon(Icons.Filled.Search, kt("Search for ${model.query}", "Hledat ${model.query}"), tint = mapControlTint)
            }
            model.searching -> CircularProgressIndicator(Modifier.padding(12.dp).size(24.dp), strokeWidth = 3.dp, color = interfaceAccentText)
            !editing && (model.query.isNotEmpty() || model.results.isNotEmpty() || model.selected != null) ->
                Box(Modifier.size(48.dp).clip(CircleShape).tap { model.clearSearch() }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Close, localizeLabel("Clear search"), tint = primaryText, modifier = Modifier.size(22.dp))
                }
            else -> Box(Modifier.size(48.dp).clip(CircleShape).tap { voice() }, contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Mic, localizeLabel("Search by voice"), tint = primaryText, modifier = Modifier.size(24.dp))
            }
        }
    }
}

@Composable
private fun RecentSwipe(onDelete: () -> Unit, onSave: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val delete by rememberUpdatedState(onDelete)
    val save by rememberUpdatedState(onSave)
    val swipe = rememberSwipeToDismissBoxState(confirmValueChange = { value ->
        when (value) {
            SwipeToDismissBoxValue.EndToStart -> { delete(); false }
            SwipeToDismissBoxValue.StartToEnd -> { save?.invoke(); false }
            SwipeToDismissBoxValue.Settled -> true
        }
    })
    SwipeToDismissBox(
        swipe, modifier = Modifier.clip(RoundedCornerShape(15.dp)),
        enableDismissFromStartToEnd = onSave != null,
        backgroundContent = {
            val deleting = swipe.dismissDirection == SwipeToDismissBoxValue.EndToStart
            val saving = onSave != null && swipe.dismissDirection == SwipeToDismissBoxValue.StartToEnd
            if (deleting || saving) {
                val scheme = MaterialTheme.colorScheme
                Box(Modifier.fillMaxSize().background(if (deleting) scheme.errorContainer else scheme.primaryContainer)
                    .padding(horizontal = 18.dp), contentAlignment = if (deleting) Alignment.CenterEnd else Alignment.CenterStart) {
                    Icon(if (deleting) Icons.Filled.Delete else Icons.Filled.BookmarkAdd,
                        if (deleting) "Remove from recents" else "Save place",
                        tint = if (deleting) scheme.onErrorContainer else scheme.onPrimaryContainer)
                }
            }
        },
        content = { Box(Modifier.fillMaxWidth().background(interfaceGround)) { content() } },
    )
}

@Composable
fun SuggestionsPanel(model: AppModel, modifier: Modifier = Modifier, done: () -> Unit) {
    val ctx = LocalContext.current
    val s = AppSettings
    val clip = remember { ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager }
    // Only checks whether the clipboard looks like a link (its type), without reading it.
    val copiedLink = remember { clip.primaryClipDescription?.let { it.hasMimeType("text/plain") || it.hasMimeType("text/uri-list") } == true }
    Column(modifier.fillMaxWidth().background(interfaceGround)
        .verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (s.noGoogle) Text(localizeLabel("OpenStreetMap search"), color = primaryText, modifier = Modifier.padding(vertical = 10.dp))
            else {
                val open = s.osmOnlySearch
                FilterChip(!open, { model.switchSearchSource(false) }, label = { Text(localizeLabel("Standard")) })
                FilterChip(open, { model.switchSearchSource(true) }, label = { Text(localizeLabel("OpenStreetMap only")) })
            }
        }
        val sug = model.suggestions
        if (model.query.isNotBlank()) {
            IconRow(Icons.Filled.Search, "Search for “${model.query.trim()}”") { done(); model.search() }
        }
        if (model.query.isEmpty() && (s.osmOnlySearch || s.noGoogle)) {
            listOf("Drinking water", "Public toilets", "Parking", "Bicycle parking", "Viewpoint").forEach { label ->
                IconRow(Icons.Filled.Place, label) { done(); model.search(label) }
            }
        } else if (model.query.isNotEmpty() && !(sug.places.isEmpty() && sug.queries.isEmpty())) {
            sug.places.take(6).forEach { p ->
                IconRow(Icons.Filled.Place, p.name, p.address, p.distanceMeters?.let { Fmt.distance(it) }, height = 54.dp) { done(); model.openSuggestion(p) }
            }
            sug.queries.take(5).forEach { q -> IconRow(Icons.Filled.Search, q) { done(); model.search(q) } }
        } else {
            if (copiedLink) {
                Row(Modifier.fillMaxWidth().height(50.dp).padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Icon(Icons.Filled.Link, null, tint = secondaryText, modifier = Modifier.size(22.dp))
                    Text(localizeLabel("Open a copied map link"), color = primaryText, modifier = Modifier.weight(1f))
                    Text(localizeLabel("Paste"), color = androidx.compose.ui.graphics.Color.White, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clip(CircleShape).background(Vela.routeBlue).tap {
                            val text = clip.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString()
                            if (!text.isNullOrBlank()) { done(); model.openLink(text) }
                        }.padding(horizontal = 14.dp, vertical = 6.dp))
                }
            }
            Shortcuts(model, done)
            if (s.recentPlaces.isNotEmpty() || s.recents.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(top = 14.dp, bottom = 4.dp)) {
                    Text(localizeLabel("Recent"), fontSize = KType.subheadline, fontWeight = FontWeight.SemiBold, color = secondaryText, modifier = Modifier.weight(1f))
                    Text(localizeLabel("Clear"), fontSize = KType.subheadline, color = Vela.routeBlue, modifier = Modifier.tap { s.recents = emptyList(); s.recentPlaces = emptyList() })
                }
                val shown = s.recentPlaces.take(5)
                shown.forEach { p -> key(p.id) {
                    var menu by remember(p.id) { mutableStateOf(false) }
                    val remove = {
                        s.recentPlaces = s.recentPlaces.filter { it.id != p.id }
                        s.recents = s.recents.filterNot { it == p.name }
                    }
                    val save = { if (!s.isSaved(p)) s.savePlace(p, emptySet()) }
                    RecentSwipe(remove, onSave = save.takeUnless { s.isSaved(p) }) {
                        Box {
                            IconRow(Icons.Filled.Schedule, p.name, p.address ?: p.category,
                                model.userLatLng?.let { Fmt.distance(it.distanceTo(p.location)) },
                                onLongClick = { menu = true }) { done(); model.openSuggestion(p) }
                            DropdownMenu(menu, { menu = false }) {
                                if (!s.isSaved(p)) DropdownMenuItem(text = { Text(localizeLabel("Save place")) },
                                    leadingIcon = { Icon(Icons.Filled.BookmarkAdd, null) }, onClick = { menu = false; save() })
                                DropdownMenuItem(text = { Text(localizeLabel("Remove from recents")) }, leadingIcon = { Icon(Icons.Filled.Delete, null) },
                                    onClick = { menu = false; remove() })
                            }
                        }
                    }
                } }
                s.recents.filter { q -> shown.none { it.name == q } }.take(6).forEach { q -> key(q) {
                    var menu by remember(q) { mutableStateOf(false) }
                    val remove = { s.recents = s.recents.filter { it != q } }
                    RecentSwipe(remove) {
                        Box {
                            IconRow(Icons.Filled.Search, q, onLongClick = { menu = true }) { done(); model.search(q) }
                            DropdownMenu(menu, { menu = false }) {
                                DropdownMenuItem(text = { Text(localizeLabel("Remove from recents")) }, leadingIcon = { Icon(Icons.Filled.Delete, null) },
                                    onClick = { menu = false; remove() })
                            }
                        }
                    }
                } }
            }
            // Recents first, like every maps app: most searches are for somewhere you've been.
            Text(localizeLabel("Nearby"), fontSize = KType.subheadline, fontWeight = FontWeight.SemiBold, color = secondaryText,
                modifier = Modifier.padding(horizontal = 18.dp).padding(top = 14.dp, bottom = 4.dp))
            Category.all.forEach { c -> IconRow(c.icon, c.label) { done(); model.search(c.query, title = c.label) } }
        }
    }
}
