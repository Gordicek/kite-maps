@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.kite.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kite.*
import app.kite.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun hex(v: Long) = Color(0xFF000000 or v)

/** The Saved page of the dock: Home and Work as big tiles, then every saved place with its own Go. */
@Composable
fun SavedPage(model: AppModel) {
    val dark = LocalDark.current
    val s = AppSettings
    val ctx = LocalContext.current
    var folderId by remember { mutableStateOf<String?>(null) }
    val folder = s.placeFolders.firstOrNull { it.id == folderId }
    val places = if (folder == null) s.saved else s.saved.filter { it.id in folder.members }
    var renaming by remember { mutableStateOf<Place?>(null) }
    var assigning by remember { mutableStateOf<Place?>(null) }
    var editingFolder by remember { mutableStateOf(false) }
    var removingFolder by remember { mutableStateOf(false) }
    var removePlan by remember { mutableStateOf<SavedPlan?>(null) }
    var pendingReminder by remember { mutableStateOf<SavedPlan?>(null) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        if (allowed) pendingReminder?.let { TransitReminders.toggleDeparture(ctx, it) }
        pendingReminder = null
    }
    var newName by remember { mutableStateOf("") }
    var folderName by remember { mutableStateOf("") }
    fun go(p: Place) { model.tab = AppTab.MAP; model.openDirections(p) }
    fun show(p: Place) { model.tab = AppTab.MAP; model.openSaved(p) }
    fun distance(p: Place) = model.userLatLng?.let { Fmt.distance(it.distanceTo(p.location)) }

    Box(Modifier.fillMaxSize().background(interfaceGround)) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(horizontal = 16.dp).padding(top = 12.dp, bottom = 140.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (folder != null) IconButton({ folderId = null }) { Icon(Icons.Filled.Close, "Back to Saved") }
                Text(folder?.name ?: "Saved", fontSize = 36.sp, fontWeight = FontWeight.ExtraBold, color = primaryText,
                    modifier = Modifier.weight(1f).padding(horizontal = 4.dp))
                if (folder != null) {
                    IconButton({ folderName = folder.name; editingFolder = true }) { Icon(Icons.Filled.Edit, "Rename list") }
                    IconButton({ removingFolder = true }) { Icon(Icons.Filled.Delete, "Delete list") }
                }
            }
            if (folder == null) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    MineTile(model, Icons.Filled.Home, Vela.routeBlue, "Home", s.home, PickKind.HOME, ::go, ::distance)
                    MineTile(model, Icons.Filled.Work, hex(0x8B5CF6), "Work", s.work, PickKind.WORK, ::go, ::distance)
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { CustomShortcuts(model, done = {}) }
                s.parking?.let { car ->
                    Box(Modifier.clip(RoundedCornerShape(24.dp)).background(interfaceSurface)) {
                        SavedRow(car, Icons.Filled.LocalParking, Vela.routeBlue, distance(car), null, ::show, ::go)
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Lists", fontSize = KType.headline, fontWeight = FontWeight.Bold, color = primaryText)
                    TextButton({ folderName = ""; editingFolder = true }) { Text("New list") }
                }
                s.placeFolders.forEach { list ->
                    ListItem(headlineContent = { Text(list.name) }, leadingContent = { Icon(Icons.Filled.Folder, null) },
                        supportingContent = { Text("${list.members.size} saved places") },
                        modifier = Modifier.clip(RoundedCornerShape(18.dp)).tap { folderId = list.id })
                }
                if (s.savedPlans.isNotEmpty()) {
                    Text("Saved routes", fontSize = KType.headline, fontWeight = FontWeight.Bold, color = primaryText)
                    s.savedPlans.forEach { plan ->
                        ListItem(
                            headlineContent = { Text(plan.name) },
                            supportingContent = {
                                val detail = plan.transitDeparture?.let {
                                    java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT)
                                        .format(java.util.Date(it))
                                } ?: plan.points.joinToString(" → ") { it.name }
                                Text(detail, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            },
                            leadingContent = { Icon(if (plan.transit) Icons.Filled.DirectionsTransit else Icons.Filled.Directions, null) },
                            trailingContent = {
                                Row {
                                    if (plan.transitDeparture?.let { it > System.currentTimeMillis() } == true) {
                                        val armed = plan.reminderAt?.let { it > System.currentTimeMillis() } == true
                                        IconButton({
                                            if (armed || TransitReminders.canNotify(ctx)) TransitReminders.toggleDeparture(ctx, plan)
                                            else if (android.os.Build.VERSION.SDK_INT >= 33) {
                                                pendingReminder = plan
                                                notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                                            }
                                        }) {
                                            Icon(if (armed) Icons.Filled.NotificationsActive else Icons.Filled.NotificationsNone,
                                                if (armed) "Cancel departure reminder" else "Remind me five minutes before leaving")
                                        }
                                    }
                                    IconButton({ ctx.share(plan.shareText()) }) { Icon(Icons.Filled.Share, "Share route") }
                                    IconButton({ removePlan = plan }) { Icon(Icons.Filled.Delete, "Delete route") }
                                }
                            },
                            modifier = Modifier.clip(RoundedCornerShape(18.dp)).tap { model.openSavedPlan(plan) })
                    }
                }
            }
            if (places.isEmpty()) {
                Column(Modifier.fillMaxWidth().padding(vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Outlined.BookmarkBorder, null, tint = Vela.muted(dark), modifier = Modifier.size(44.dp))
                    Text(if (folder == null) "No saved places yet" else "This list is empty", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = primaryText)
                    Text(if (folder == null) "Open any place and tap Save to keep it here." else "Hold a saved place and choose Lists to add it here.", fontSize = KType.body, color = Vela.muted(dark), textAlign = TextAlign.Center)
                }
            } else {
                Column(Modifier.clip(RoundedCornerShape(24.dp)).background(interfaceSurface)) {
                    places.forEachIndexed { i, p ->
                        if (i > 0) HorizontalDivider(Modifier.padding(start = 78.dp))
                        SavedRow(p, Icons.Filled.Bookmark, hex(0xF59E0B), distance(p), { action ->
                            when (action) {
                                0 -> { newName = p.name; renaming = p }
                                1 -> if (folder == null) s.removeSaved(p.id) else s.toggleFolderMember(folder.id, p.id)
                                2 -> assigning = p
                            }
                        }, ::show, ::go)
                    }
                }
                Text("Hold a place to manage it or add it to lists.", fontSize = KType.subheadline, color = Vela.muted(dark), modifier = Modifier.padding(horizontal = 4.dp))
            }
        }
        KiteDock(model, Modifier.align(Alignment.BottomCenter))
    }
    renaming?.let { p ->
        AlertDialog(onDismissRequest = { renaming = null }, title = { Text("Rename") },
            text = { OutlinedTextField(newName, { newName = it }, singleLine = true, label = { Text("Name") }) },
            confirmButton = { TextButton({ s.renameSaved(p.id, newName); renaming = null }) { Text("Save") } },
            dismissButton = { TextButton({ renaming = null }) { Text("Cancel") } })
    }
    assigning?.let { place ->
        AlertDialog(onDismissRequest = { assigning = null }, title = { Text("Lists for ${place.name}") }, text = {
            Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                s.placeFolders.forEach { list ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(place.id in list.members, { s.toggleFolderMember(list.id, place.id) })
                        Text(list.name, modifier = Modifier.weight(1f).tap { s.toggleFolderMember(list.id, place.id) })
                    }
                }
            }
        }, confirmButton = { TextButton({ assigning = null }) { Text("Done") } },
            dismissButton = { TextButton({ folderName = ""; editingFolder = true }) { Text("New list") } })
    }
    if (editingFolder) AlertDialog(
        onDismissRequest = { editingFolder = false },
        title = { Text(if (folder == null || assigning != null) "New list" else "Rename list") },
        text = { OutlinedTextField(folderName, { folderName = it }, singleLine = true, label = { Text("Name") }) },
        confirmButton = { TextButton({
            val assigned = assigning
            s.placeFolders = if (folder == null || assigned != null) {
                s.placeFolders + PlaceFolder(folderName.trim(), setOfNotNull(assigned?.id))
            } else s.placeFolders.map { if (it.id == folder.id) it.copy(name = folderName.trim()) else it }
            editingFolder = false
        }, enabled = folderName.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton({ editingFolder = false }) { Text("Cancel") } })
    if (removingFolder && folder != null) AlertDialog(
        onDismissRequest = { removingFolder = false },
        title = { Text("Delete ${folder.name}?") },
        text = { Text("Its places will still be in Saved.") },
        confirmButton = { TextButton({
            s.placeFolders = s.placeFolders.filter { it.id != folder.id }
            removingFolder = false
            folderId = null
        }) { Text("Delete list") } },
        dismissButton = { TextButton({ removingFolder = false }) { Text("Cancel") } })
    removePlan?.let { plan ->
        AlertDialog(onDismissRequest = { removePlan = null }, title = { Text("Delete ${plan.name}?") },
            confirmButton = { TextButton({
                TransitReminders.cancel(ctx, plan.id)
                s.savedPlans = s.savedPlans.filter { it.id != plan.id }
                removePlan = null
            }) {
                Text("Delete route")
            } }, dismissButton = { TextButton({ removePlan = null }) { Text("Cancel") } })
    }
    BackHandler { if (folderId != null) folderId = null else model.tab = AppTab.MAP }
}

@Composable
private fun RowScope.MineTile(model: AppModel, icon: ImageVector, color: Color, title: String, place: Place?, kind: PickKind,
                              go: (Place) -> Unit, distance: (Place) -> String?) {
    val dark = LocalDark.current
    var menu by remember { mutableStateOf(false) }
    Box(Modifier.weight(1f)) {
        Column(Modifier.fillMaxWidth().heightIn(min = 118.dp).clip(RoundedCornerShape(24.dp)).background(interfaceSurface)
            .combinedClickable(onClick = { if (place != null) go(place) else model.picking = kind }, onLongClick = { if (place != null) menu = true })
            .padding(16.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Box(Modifier.size(48.dp).clip(CircleShape).background(if (place == null) Color.Gray else color), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = Color.White, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(title, fontSize = 21.sp, fontWeight = FontWeight.Bold, color = primaryText, modifier = Modifier.weight(1f))
                Text(place?.let(distance) ?: (if (place == null) "Set" else ""), fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Vela.muted(dark))
            }
        }
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem(text = { Text("Change ${title.lowercase()}") }, leadingIcon = { Icon(Icons.Filled.Edit, null) },
                onClick = { menu = false; model.picking = kind })
        }
    }
}

/** [edit]: 0 rename, 1 remove, 2 manage lists. */
@Composable
private fun SavedRow(p: Place, icon: ImageVector, color: Color, distance: String?, edit: ((Int) -> Unit)?, show: (Place) -> Unit, go: (Place) -> Unit) {
    val dark = LocalDark.current
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(Modifier.fillMaxWidth().heightIn(min = 88.dp).padding(start = 14.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.weight(1f).combinedClickable(onClick = { show(p) }, onLongClick = { if (edit != null) menu = true }),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.size(48.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
                    Icon(icon, null, tint = Color.White, modifier = Modifier.size(24.dp))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(p.name, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = primaryText, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    (distance ?: p.address ?: p.category)?.let { Text(it, fontSize = 16.sp, color = Vela.muted(dark), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
            Box(Modifier.size(60.dp).clip(CircleShape).background(Vela.accent(dark)).tap { go(p) }, contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Navigation, "Go to ${p.name}", tint = Vela.onAccent(dark), modifier = Modifier.size(28.dp))
            }
        }
        if (edit != null) DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem(text = { Text("Rename") }, leadingIcon = { Icon(Icons.Filled.Edit, null) }, onClick = { menu = false; edit(0) })
            DropdownMenuItem(text = { Text("Lists") }, leadingIcon = { Icon(Icons.Filled.Folder, null) }, onClick = { menu = false; edit(2) })
            DropdownMenuItem(text = { Text("Remove") }, leadingIcon = { Icon(Icons.Filled.Delete, null) }, onClick = { menu = false; edit(1) })
        }
    }
}

// Settings

private enum class SettingsScreen(val title: String) {
    MENU("Settings"), DRIVING("Voice & driving"), CAMERAS("Cameras & speed"), WAZE("Road alerts"), LIVECAMS("Live cameras"), MAP("Map & display"),
    ROUTING("Route engines"), TRANSIT("Public transport"), OFFLINE("Offline maps"), PLACES("My places"), PRIVACY("Privacy"), ABOUT("About"), DIAGNOSTICS("Diagnostics");

    /** Where the back button goes. */
    val parent: SettingsScreen get() = if (this == DIAGNOSTICS) ABOUT else MENU
}

/** The Settings page of the dock: a short menu like Waze's, each row opening a page with its switches. */
@Composable
fun SettingsPage(model: AppModel) {
    val dark = LocalDark.current
    var screen by rememberSaveable { mutableStateOf(when (DemoScript.step) {
        "wazesettings" -> SettingsScreen.WAZE
        "diagnostics" -> SettingsScreen.DIAGNOSTICS
        "mapsettings" -> SettingsScreen.MAP
        else -> SettingsScreen.MENU
    }) }
    Box(Modifier.fillMaxSize().background(interfaceGround)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            if (screen == SettingsScreen.MENU) {
                Text("Settings", fontSize = 34.sp, fontWeight = FontWeight.ExtraBold, color = primaryText, modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 8.dp))
            } else {
                Box(Modifier.fillMaxWidth().height(56.dp)) {
                    Row(Modifier.align(Alignment.CenterStart).padding(start = 4.dp).clip(RoundedCornerShape(8.dp)).tap { screen = screen.parent }
                        .padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Vela.accentText(dark))
                        Text(screen.parent.title, fontSize = KType.body, color = Vela.accentText(dark), modifier = Modifier.padding(start = 4.dp))
                    }
                    Text(screen.title, fontSize = KType.headline, fontWeight = FontWeight.SemiBold, color = primaryText, modifier = Modifier.align(Alignment.Center))
                }
            }
            AnimatedContent(screen, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "settings") { sc ->
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 120.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    when (sc) {
                        SettingsScreen.MENU -> SettingsMenu { screen = it }
                        SettingsScreen.DRIVING -> DrivingSettings()
                        SettingsScreen.CAMERAS -> CameraSettings()
                        SettingsScreen.WAZE -> WazeSettings()
                        SettingsScreen.LIVECAMS -> LiveCamSettings()
                        SettingsScreen.MAP -> MapSettings()
                        SettingsScreen.ROUTING -> RouteEngineSettings()
                        SettingsScreen.TRANSIT -> TransitSettings()
                        SettingsScreen.OFFLINE -> OfflineMapsPage(model)
                        SettingsScreen.PLACES -> PlacesSettings(model)
                        SettingsScreen.PRIVACY -> PrivacySettings()
                        SettingsScreen.ABOUT -> AboutSettings { screen = SettingsScreen.DIAGNOSTICS }
                        SettingsScreen.DIAGNOSTICS -> DiagnosticsSettings(model)
                    }
                }
            }
        }
        KiteDock(model, Modifier.align(Alignment.BottomCenter))
    }
    BackHandler { if (screen != SettingsScreen.MENU) screen = screen.parent else { model.reloadRoutes(); model.tab = AppTab.MAP } }
}

/** A rounded group of rows, like the iPhone's Settings. */
@Composable
fun SettingsGroup(header: String? = null, footer: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val dark = LocalDark.current
    Column(Modifier.padding(top = 10.dp)) {
        if (header != null) Text(header.uppercase(), fontSize = KType.caption, fontWeight = FontWeight.SemiBold, color = secondaryText,
            modifier = Modifier.padding(start = 16.dp, bottom = 6.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(interfaceSurface)) { content() }
        if (footer != null) Text(footer, fontSize = KType.caption, color = secondaryText, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp))
    }
}

/** A settings row's name with a small coloured icon in front. */
@Composable
fun SettingLabel(title: String, icon: ImageVector?, color: Long, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (icon != null) Box(Modifier.size(29.dp).clip(RoundedCornerShape(7.dp)).background(hex(color)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(18.dp))
        }
        Text(title, fontSize = KType.body, color = primaryText)
    }
}

@Composable
fun ToggleRow(title: String, icon: ImageVector?, color: Long, on: Boolean, enabled: Boolean = true, leading: (@Composable () -> Unit)? = null, set: (Boolean) -> Unit) {
    val dark = LocalDark.current
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).tap { if (enabled) set(!on) }.padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (leading != null) { leading(); Spacer(Modifier.width(10.dp)); Text(title, fontSize = KType.body, color = primaryText, modifier = Modifier.weight(1f)) }
        else SettingLabel(title, icon, color, Modifier.weight(1f))
        Switch(on, { if (enabled) set(it) }, enabled = enabled,
            colors = SwitchDefaults.colors(checkedTrackColor = interfaceAccent, checkedThumbColor = interfaceOnAccent))
    }
}

@Composable
fun <T> PickRow(title: String, icon: ImageVector?, color: Long, value: T, options: List<T>, label: (T) -> String, set: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).tap { open = true }.padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            SettingLabel(title, icon, color, Modifier.weight(1f))
            Text(label(value), fontSize = KType.body, color = secondaryText)
            Icon(Icons.Filled.UnfoldMore, null, tint = secondaryText, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(open, { open = false }, Modifier.align(Alignment.CenterEnd)) {
            options.forEach { o ->
                DropdownMenuItem(text = { Text(label(o)) }, trailingIcon = { if (o == value) Icon(Icons.Filled.Check, null) }, onClick = { set(o); open = false })
            }
        }
    }
}

@Composable
private fun RowDivider() = HorizontalDivider(Modifier.padding(start = 55.dp))

@Composable
private fun SettingsMenu(open: (SettingsScreen) -> Unit) {
    val s = AppSettings
    SettingsGroup {
        @Composable
        fun item(sc: SettingsScreen, detail: String, value: String?, icon: ImageVector, color: Long, last: Boolean = false) {
            Row(Modifier.fillMaxWidth().tap { open(sc) }.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.size(36.dp).clip(RoundedCornerShape(9.dp)).background(hex(color)), contentAlignment = Alignment.Center) {
                    Icon(icon, null, tint = Color.White, modifier = Modifier.size(20.dp))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(sc.title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = primaryText)
                    Text(detail, fontSize = KType.subheadline, color = secondaryText)
                }
                if (value != null) Text(value, color = secondaryText)
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = secondaryText)
            }
            if (!last) HorizontalDivider(Modifier.padding(start = 64.dp))
        }
        item(SettingsScreen.DRIVING, "Voice, travel mode, avoid tolls and ${if (Fmt.yards) "motorways" else "highways"}", null, Icons.AutoMirrored.Filled.VolumeUp, 0x0F766E)
        item(SettingsScreen.CAMERAS, "Camera warnings, speeding alert", null, Icons.Filled.PhotoCamera, 0xDC2626)
        item(SettingsScreen.WAZE, if (WazeAlert.Kind.POLICE in Waze.shares) "Police, crashes, closures" else "Closures, police aircraft",
            if (s.wazeOn) "On" else "Off", Icons.Filled.Warning, 0x7C3AED)
        item(SettingsScreen.LIVECAMS, "Road camera photos and scenic webcams", if (s.liveCams) "On" else "Off", Icons.Filled.Videocam, 0x0284C7)
        item(SettingsScreen.MAP, "Theme, units, map zoom, parking button", null, Icons.Filled.Contrast, 0x334155)
        item(SettingsScreen.ROUTING, "Driving, walking and cycling", null, Icons.Filled.AltRoute, 0x0F766E)
        item(SettingsScreen.TRANSIT, "Live data for Prague and Central Bohemia", null, Icons.Filled.Tram, 0x6750A4)
        item(SettingsScreen.OFFLINE, "Download and manage map areas", null, Icons.Filled.Download, 0x315E91)
        item(SettingsScreen.PLACES, "Home and work", null, Icons.Filled.Home, 0x16A34A)
        item(SettingsScreen.PRIVACY, "Use Kite without Google", if (s.noGoogle) "On" else null, Icons.Filled.Lock, 0x475569)
        item(SettingsScreen.ABOUT, "Version ${BuildConfig.VERSION_NAME}", null, Icons.Filled.Info, 0x64748B, last = true)
    }
}

@Composable
private fun DrivingSettings() {
    val s = AppSettings
    SettingsGroup {
        ToggleRow("Voice guidance", Icons.AutoMirrored.Filled.VolumeUp, 0x0F766E, s.voice) {
            // Switched here it means everything or nothing: "Alerts only" is the drive screen's button.
            s.voice = it; s.voiceAlertsOnly = false
        }
        RowDivider()
        PickRow("Default travel mode", Icons.Filled.DirectionsCar, 0x2563EB, s.defaultMode, TravelMode.entries.toList(), { it.label }) { s.defaultMode = it }
    }
    SettingsGroup("Routes") {
        ToggleRow("Avoid tolls", Icons.Filled.Toll, 0xF59E0B, s.avoidTolls) { s.avoidTolls = it }
        RowDivider()
        ToggleRow(if (Fmt.yards) "Avoid motorways" else "Avoid highways", Icons.Filled.AddRoad, 0x1D4ED8, s.avoidHighways) { s.avoidHighways = it }
        RowDivider()
        ToggleRow("My car meets clean air zone rules", Icons.Filled.Eco, 0x16A34A, s.cleanCar) { s.cleanCar = it }
    }
    SettingsGroup(footer = "With Kite open and no route, the driving view comes up by itself once you're going faster than about 15 mph, " +
        "with cameras, the speed limit and the road's name. It goes back to the map after a couple of minutes parked. " +
        "Turn this off if you're often a passenger.") {
        ToggleRow("Driving view when I set off", Icons.Filled.DirectionsCar, 0x0F766E, s.autoDrive) { s.autoDrive = it }
    }
}

@Composable
private fun CameraSettings() {
    val s = AppSettings
    SettingsGroup("Warnings") {
        ToggleRow("Say when a speed camera is ahead", Icons.Filled.RecordVoiceOver, 0xDC2626, s.cameraWarnings) { s.cameraWarnings = it }
        RowDivider()
        ToggleRow("Say when I'm over the speed limit", Icons.Filled.Speed, 0xEA580C, s.speedingAlert) { s.speedingAlert = it }
    }
    SettingsGroup("Show on the map and route", "Camera locations come from OpenStreetMap, and speed cameras from Waze's map too while Road alerts are on. General surveillance cameras are not necessarily plate readers and are shown only on the map. Some countries, like France and Germany, don't allow warnings of speed cameras, so check before you turn the voice on there.") {
        ToggleRow("Speed cameras", Icons.Filled.PhotoCamera, 0xDC2626, s.speedCameras) { s.speedCameras = it }
        RowDivider()
        ToggleRow("Surveillance cameras", Icons.Filled.Videocam, 0x16A34A, s.surveillanceCameras) { s.surveillanceCameras = it }
        RowDivider()
        ToggleRow(if (Fmt.yards) "Number plate cameras" else "License plate cameras", Icons.Filled.Pin, 0x6B7280, s.plateCameras) { s.plateCameras = it }
    }
}

/** Settings › Live cameras: the camera dots on the map, which kinds, and the row on a route. */
@Composable
private fun LiveCamSettings() {
    val s = AppSettings
    SettingsGroup(footer = "See the latest photo from traffic cameras and webcams: tap a camera on the map, or look at the ones on your route before you go. Never shown while you're driving.") {
        ToggleRow("Live cameras", Icons.Filled.Videocam, 0x0284C7, s.liveCams) { s.liveCams = it }
    }
    if (s.liveCams) {
        SettingsGroup("Show", if (WindyKey.present)
            "Road cameras: National Highways, Transport for London, Traffic Scotland, Traffic Wales and TrafficWatch NI, listed by OpenHighways. Webcams: Windy.com, worldwide."
        else "Road cameras: National Highways, Transport for London, Traffic Scotland, Traffic Wales and TrafficWatch NI, listed by OpenHighways. Scenic webcams aren't set up in this version of Kite yet.") {
            ToggleRow("Road cameras (UK)", Icons.Filled.DirectionsCar, 0x0284C7, s.liveRoadCams) { s.liveRoadCams = it }
            RowDivider()
            ToggleRow("Scenic webcams", Icons.Filled.Landscape, 0x7C3AED, s.liveWebcams && WindyKey.present, enabled = WindyKey.present) { s.liveWebcams = it }
            RowDivider()
            ToggleRow("Cameras on your route", Icons.Filled.Directions, 0x0F766E, s.liveCamsOnRoute) { s.liveCamsOnRoute = it }
        }
        LiveCamStore.status?.let { st ->
            SettingsGroup {
                Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Camera list", fontSize = KType.body, color = primaryText, modifier = Modifier.weight(1f))
                    Text(st, fontSize = KType.body, color = secondaryText)
                }
            }
        }
    }
}

@Composable
private fun MapSettingLink(title: String, icon: ImageVector, tint: Long, open: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).tap(open).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        SettingLabel(title, icon, tint, Modifier.weight(1f))
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = secondaryText)
    }
}

@Composable
private fun TransitSettings() {
    val context = LocalContext.current
    val settings = AppSettings
    var value by remember { mutableStateOf(settings.pidAccessToken) }
    SettingsGroup(header = "Transit guidance") {
        ToggleRow("Notify before my stop", Icons.Filled.NotificationsActive, 0x126782, settings.transitExitAlerts) {
            settings.transitExitAlerts = it
        }
        ToggleRow("Learn my walking pace", Icons.AutoMirrored.Filled.DirectionsWalk, 0x0F766E, settings.learnTransitWalkingPace) {
            settings.learnTransitWalkingPace = it
        }
        settings.learnedWalkingPace?.let { pace ->
            TextButton({ settings.transitWalkingPace = 0.0 }, Modifier.padding(horizontal = 10.dp)) {
                Text("Reset walking pace (${String.format(java.util.Locale.getDefault(), "%.1f", pace * 3.6)} km/h)")
            }
        }
        if (android.os.Build.VERSION.SDK_INT >= 31 && !TransitReminders.canScheduleExactly(context)) {
            TextButton({
                context.startActivity(android.content.Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    android.net.Uri.parse("package:${context.packageName}")))
            }, Modifier.padding(horizontal = 10.dp)) { Text("Allow on-time departure reminders") }
        }
    }
    SettingsGroup(header = "Prague Integrated Transport",
        footer = "With a Golemio token, Kite can read live departure times and vehicle positions for supported PID trips. The token stays in Kite's settings.") {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            OutlinedTextField(value, { value = it }, label = { Text("Golemio API token") }, singleLine = true,
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth())
            Row {
                TextButton({ context.open("https://api.golemio.cz/api-keys") }) { Text("Get token") }
                TextButton({ settings.pidAccessToken = value.trim() }, enabled = value.trim() != settings.pidAccessToken) { Text("Save") }
                if (settings.pidAccessToken.isNotBlank()) TextButton({ settings.pidAccessToken = ""; value = "" }) { Text("Remove") }
            }
        }
    }
}

@Composable
private fun RouteEngineSettings() {
    val s = AppSettings
    val engines = RouteEngine.entries.filter { !s.noGoogle || it != RouteEngine.GOOGLE }
    SettingsGroup(header = "Navigation routing",
        footer = "Automatic chooses a provider. A manual choice is used for plans and reroutes. Mapbox requires a token.") {
        PickRow("Driving", Icons.Filled.DirectionsCar, 0x0F766E, s.driveEngine, engines, { it.label }) { s.driveEngine = it }
        RowDivider()
        PickRow("Walking", Icons.AutoMirrored.Filled.DirectionsWalk, 0x0F766E, s.walkEngine, engines, { it.label }) { s.walkEngine = it }
        RowDivider()
        PickRow("Cycling", Icons.Filled.DirectionsBike, 0x0F766E, s.bikeEngine, engines, { it.label }) { s.bikeEngine = it }
    }
    SettingsGroup(header = "Route profile") {
        ToggleRow("Prepare profile when guidance starts", Icons.Filled.Terrain, 0x0F766E, s.preloadRouteProfile) { s.preloadRouteProfile = it }
    }
}

@Composable
private fun MapSettings() {
    val s = AppSettings
    var editColors by remember { mutableStateOf(false) }
    var editRoadReports by remember { mutableStateOf(false) }
    var editOpenPlaces by remember { mutableStateOf(false) }
    var editNavigationPlaces by remember { mutableStateOf(false) }
    var editWalkingAhead by remember { mutableStateOf(false) }
    var editRoadAhead by remember { mutableStateOf(false) }
    SettingsGroup(header = "Appearance and units") {
        PickRow("App theme", Icons.Filled.Contrast, 0x334155, s.theme, ThemeMode.entries.toList(), { it.label }) { s.theme = it }
        RowDivider()
        PickRow("Distance units", Icons.Filled.Straighten, 0x0891B2, s.units, UnitsMode.entries.toList(), { it.label }) { s.units = it }
        RowDivider()
        ToggleRow("Parking button on the map", Icons.Filled.LocalParking, 0x2563EB, s.parkingButton) { s.parkingButton = it }
    }
    SettingsGroup(header = "Controls and search") {
        ToggleRow("Swap navigation controls and speedometer", Icons.Filled.SwapHoriz, 0x2563EB, s.swapNavigationSides) { s.swapNavigationSides = it }
        RowDivider()
        ToggleRow("Solid search background", Icons.Filled.Search, 0x0F766E, s.solidMapSearch) { s.solidMapSearch = it }
        RowDivider()
        ToggleRow("Categories in one button", Icons.Filled.Category, 0x0F766E, s.categoryMenu) { s.categoryMenu = it }
        RowDivider()
        MapInterfaceColors()
    }
    SettingsGroup(header = "Map icon sizes") {
        PinSizeRow("Google Maps icons", s.googleIconPercent) { s.googleIconPercent = it }
        RowDivider()
        PinSizeRow("OpenStreetMap icons", s.osmIconPercent) { s.osmIconPercent = it }
        RowDivider()
        PinSizeRow("Search results", s.resultIconPercent) { s.resultIconPercent = it }
    }
    MapboxSettings()
    SettingsGroup(header = "Mapbox Standard", footer = "Mapbox Standard uses your Mapbox token. Street lamps use mapped posts and estimated placement on nearby streets at street zoom.") {
        ToggleRow("Mapbox Standard", Icons.Filled.ViewInAr, 0x0F766E, s.standardMap) { s.standardMap = it }
        RowDivider()
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).tap { editColors = true }.padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically) {
            SettingLabel("Map colors", Icons.Filled.Palette, 0x6D4EB0, Modifier.weight(1f))
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = secondaryText)
        }
        RowDivider()
        ToggleRow("3D terrain", Icons.Filled.Terrain, 0x166534, s.terrain3D) { s.terrain3D = it }
        RowDivider()
        ToggleRow("Hillshade", Icons.Filled.Landscape, 0x475569, s.terrain) { s.terrain = it }
        RowDivider()
        ToggleRow("Building shadows", Icons.Filled.WbSunny, 0xB45309, s.buildingShadows) { s.buildingShadows = it }
        RowDivider()
        ToggleRow("Street lamps", Icons.Filled.Lightbulb, 0xB45309, s.streetLamps) { s.streetLamps = it }
    }
    if (editColors) MapColorsPage { editColors = false }
    SettingsGroup(header = "Places on the map", footer = "When enabled, Kite asks Google about nearby businesses after the map stops moving. Open map places are also displayed.") {
        ToggleRow("Google Maps places", Icons.Filled.Place, 0x1D4ED8, s.googleMapPlaces, enabled = !s.noGoogle) { s.googleMapPlaces = it }
        RowDivider()
        ToggleRow("OpenStreetMap places", Icons.Outlined.Map, 0x0F766E, s.openMapPlaces) { s.openMapPlaces = it }
        RowDivider()
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).tap { editOpenPlaces = true }.padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically) {
            SettingLabel("OpenStreetMap icon categories", Icons.Filled.Tune, 0x0F766E, Modifier.weight(1f))
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = secondaryText)
        }
    }
    if (editOpenPlaces) TripSheet("OpenStreetMap icons", "Done", { editOpenPlaces = false }) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            for (group in OpenPlaceGroup.entries) {
                ToggleRow(group.label, Icons.Filled.Place, 0x0F766E, group in s.openPlaceGroups) { show ->
                    s.openPlaceGroups = if (show) s.openPlaceGroups + group else s.openPlaceGroups - group
                }
            }
        }
    }
    SettingsGroup(header = "While driving",
        footer = "Kite still zooms in for turns and out on long roads. Closer shows the streets bigger; Wider shows more road ahead. Normal is how Kite has always been.") {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SettingLabel("Map zoom while driving", Icons.Filled.ZoomIn, 0x0F766E)
            Segmented(MapZoom.entries.toList(), s.mapZoom, { it.title }) { s.mapZoom = it }
        }
        RowDivider()
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SettingLabel("Vehicle marker", Icons.Filled.DirectionsCar, 0x0F766E)
            val dark = LocalDark.current
            Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (marker in DriveMarker.entries) {
                    val active = s.driveMarker == marker
                    val art = remember(marker, dark) { MapIcons.drivePuck(marker, dark, tilted = true).asImageBitmap() }
                    val shape = RoundedCornerShape(14.dp)
                    Column(Modifier.weight(1f).clip(shape).background(interfaceGround)
                        .border(1.dp, if (active) interfaceAccent else Color.Transparent, shape)
                        .selectable(active, role = Role.RadioButton) { s.driveMarker = marker }
                        .padding(vertical = 5.dp, horizontal = 2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Image(art, null, Modifier.size(46.dp))
                        Text(marker.label, fontSize = KType.footnote, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            fontWeight = FontWeight.SemiBold, color = if (active) interfaceAccentText else primaryText)
                    }
                }
            }
        }
        RowDivider()
        ToggleRow("Parking near destination", Icons.Filled.LocalParking, 0x2563EB, s.arrivalParking) { s.arrivalParking = it }
        RowDivider()
        ToggleRow("Road ahead strip", Icons.Filled.Traffic, 0x0F766E, s.roadAheadStrip) { s.roadAheadStrip = it }
        RowDivider()
        PickRow("Road ahead position", Icons.Filled.SwapVert, 0x0F766E,
            s.roadStripPosition, RoadStripPosition.entries.toList(), { it.label }) { s.roadStripPosition = it }
    }
    SettingsGroup(header = "Map and route icons") {
        PickRow("Icon design", Icons.Filled.Palette, 0x7C3AED, s.markerTheme, MarkerTheme.entries, { it.label }) { s.markerTheme = it }
        RowDivider()
        PickRow("Road ahead design", Icons.Filled.Traffic, 0xAD362C, s.roadStripStyle, RoadStripStyle.entries, { it.label }) { s.roadStripStyle = it }
        RowDivider()
        PinSizeRow("Waze icons on the map", s.wazeMapPercent) { s.wazeMapPercent = it }
        PinSizeRow("Waze icons on the route strip", s.wazeStripPercent) { s.wazeStripPercent = it }
    }
    SettingsGroup(header = "Navigation overlays") {
        ToggleRow("Picture-in-picture", Icons.Filled.PictureInPictureAlt, 0x2563EB, s.navigationPictureInPicture) {
            s.navigationPictureInPicture = it
        }
        if (s.navigationPictureInPicture) ToggleRow("Portrait picture-in-picture", Icons.Filled.CropPortrait, 0x2563EB, s.portraitPip) { s.portraitPip = it }
        RowDivider()
        ToggleRow("Hold anywhere to add a stop", Icons.Filled.AddLocationAlt, 0x0F766E, s.navigationHoldAnywhere) { s.navigationHoldAnywhere = it }
        RowDivider()
        MapSettingLink("Places while navigating", Icons.Filled.Place, 0x0F766E) { editNavigationPlaces = true }
        RowDivider()
        MapSettingLink("Road ahead and map alerts", Icons.Filled.Traffic, 0xAD362C) { editRoadAhead = true }
        RowDivider()
        MapSettingLink("Walking side panel", Icons.AutoMirrored.Filled.DirectionsWalk, 0x126782) { editWalkingAhead = true }
    }
    if (editWalkingAhead) TripSheet("Walking side panel", "Done", { editWalkingAhead = false }) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
            SettingsGroup(footer = "Also applies while walking to or between transit stops.") {
                ToggleRow("Show side panel", Icons.AutoMirrored.Filled.DirectionsWalk, 0x126782, s.walkingAhead) { s.walkingAhead = it }
                RowDivider()
                PickRow("Side", Icons.Filled.SwapHoriz, 0x126782, s.walkingAheadRight,
                    listOf(false, true), { if (it) "Right" else "Left" }) { s.walkingAheadRight = it }
                RowDivider()
                PickRow("Look ahead", Icons.Filled.RemoveRedEye, 0x126782, s.walkingAheadRange,
                    listOf(300, 600, 1000), { Fmt.distance(it.toDouble()) }) { s.walkingAheadRange = it }
            }
            SettingsGroup(header = "Events") {
                ToggleRow("Pedestrian crossings", Icons.AutoMirrored.Filled.DirectionsWalk, 0x126782, s.walkingCrossings) { s.walkingCrossings = it }
                RowDivider()
                ToggleRow("Traffic lights", Icons.Filled.Traffic, 0x1D4ED8, s.walkingSignals) { s.walkingSignals = it }
                RowDivider()
                ToggleRow("Stop signs", Icons.Filled.StopCircle, 0xB91C1C, s.walkingStops) { s.walkingStops = it }
                RowDivider()
                ToggleRow("Railway crossings", Icons.Filled.Train, 0x92400E, s.walkingRail) { s.walkingRail = it }
                RowDivider()
                ToggleRow("Waze reports", Icons.Filled.Warning, 0x0F766E, s.walkingWaze) { s.walkingWaze = it }
                RowDivider()
                ToggleRow("Speed cameras", Icons.Filled.PhotoCamera, 0x6D4EB0, s.walkingCameras) { s.walkingCameras = it }
            }
        }
    }
    if (editNavigationPlaces) TripSheet("Places while navigating", "Done", { editNavigationPlaces = false }) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)) {
            item {
                SettingsGroup(footer = "Tap a place for its details. Touch and hold a place to add it as the next stop.") {
                    ToggleRow("Show places while navigating", Icons.Filled.Place, 0x0F766E, s.navigationPlaces) { s.navigationPlaces = it }
                    RowDivider()
                    val choice = if (s.noGoogle) NavigationPlaceSource.OPENSTREETMAP else s.navigationPlaceSource
                    val providers = NavigationPlaceSource.entries.filter { !s.noGoogle || it == NavigationPlaceSource.OPENSTREETMAP }
                    PickRow("Icon source", Icons.Outlined.Map, 0x1D4ED8, choice, providers, { it.label }) { s.navigationPlaceSource = it }
                }
            }
            if (s.navigationPlaces) {
                items(OpenPlaceGroup.entries, key = { it.name }) { group ->
                    SettingsGroup {
                        ToggleRow(group.label, Icons.Filled.Place, 0x0F766E, group in s.navigationPlaceGroups) { show ->
                            s.navigationPlaceGroups = if (show) s.navigationPlaceGroups + group else s.navigationPlaceGroups - group
                        }
                    }
                }
            }
        }
    }
    if (editRoadAhead) TripSheet("Road ahead and map", "Done", { editRoadAhead = false }) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
            SettingsGroup(header = "Road ahead and map", footer = "Choose which events appear on your route during navigation.") {
                ToggleRow("Traffic and jams", Icons.Filled.Traffic, 0xAD362C, s.aheadTraffic) { s.aheadTraffic = it }
                RowDivider()
                ToggleRow("Traffic lights", Icons.Filled.Traffic, 0x1D4ED8, s.aheadSignals) { s.aheadSignals = it }
                RowDivider()
                ToggleRow("Stop signs", Icons.Filled.StopCircle, 0xB91C1C, s.aheadStops) { s.aheadStops = it }
                RowDivider()
                ToggleRow("Railroad crossings", Icons.Filled.Train, 0x92400E, s.aheadRail) { s.aheadRail = it }
                RowDivider()
                ToggleRow("Pedestrian crossings", Icons.AutoMirrored.Filled.DirectionsWalk, 0x126782, s.aheadCrossings) { s.aheadCrossings = it }
                RowDivider()
                ToggleRow("Waze road reports", Icons.Filled.Warning, 0x0F766E, s.aheadWaze) { s.aheadWaze = it }
                RowDivider()
                Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).tap { editRoadReports = true }.padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    SettingLabel("Waze report types", Icons.Filled.Tune, 0x0F766E, Modifier.weight(1f))
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = secondaryText)
                }
                RowDivider()
                ToggleRow("Cameras on the route", Icons.Filled.PhotoCamera, 0x6D4EB0, s.aheadCameras) { s.aheadCameras = it }
            }
        }
    }
    if (editRoadReports) TripSheet("Waze road reports", "Done", { editRoadReports = false }) {
        val items = listOf(WazeAlert.Kind.POLICE to "Police", WazeAlert.Kind.ACCIDENT to "Crashes",
            WazeAlert.Kind.HAZARD to "Stopped vehicles and hazards", WazeAlert.Kind.WEATHER to "Weather",
            WazeAlert.Kind.ROADWORKS to "Roadworks", WazeAlert.Kind.CLOSURE to "Closures", WazeAlert.Kind.JAM to "Traffic jams")
        for ((kind, label) in items.filter { it.first in Waze.shares }) {
            ToggleRow(label, null, 0, kind in s.roadWazeKinds, leading = { WazeBadge(kind, 26.dp) }) { on ->
                s.roadWazeKinds = if (on) s.roadWazeKinds + kind else s.roadWazeKinds - kind
            }
        }
    }
}

@Composable
private fun PinSizeRow(title: String, percent: Int, save: (Int) -> Unit) {
    var value by remember(percent) { mutableFloatStateOf(percent.coerceIn(60, 160).toFloat()) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 7.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, modifier = Modifier.weight(1f), color = primaryText)
            Text("${value.toInt()}%", color = secondaryText)
        }
        Slider(value, { value = it }, valueRange = 60f..160f, steps = 9, onValueChangeFinished = { save(value.toInt()) },
            modifier = Modifier.semantics { contentDescription = title })
    }
}

private val accentColors = linkedMapOf(
    "Teal" to "#0F9B8F", "Blue" to "#4289EF", "Indigo" to "#686FE8", "Purple" to "#9B63D5",
    "Rose" to "#CF527F", "Orange" to "#DC7841", "Gold" to "#C79522", "Green" to "#3A9A69",
)
private val lightPanelColors = linkedMapOf(
    "Snow" to "#FFFFFF", "Sky" to "#EDF5FF", "Mint" to "#E5F8F1", "Lavender" to "#F5EEFC",
    "Rose" to "#FFF0F4", "Sand" to "#FFF4E4", "Fog" to "#F1F4F7", "Ice" to "#E8F7FA",
)
private val darkPanelColors = linkedMapOf(
    "Midnight" to "#141E30", "Slate" to "#253042", "Deep ocean" to "#152B46", "Forest" to "#142F29",
    "Plum" to "#301D3A", "Burgundy" to "#391F2A", "Espresso" to "#30241B", "Graphite" to "#24272E",
)

@Composable
internal fun MapInterfaceColors() {
    val s = AppSettings
        MapUiColorRow("Control accent", s.mapUiAccent, accentColors) { s.mapUiAccent = it }
        RowDivider()
        MapUiColorRow("Light interface panels", s.mapUiDayPanel, lightPanelColors) { s.mapUiDayPanel = it }
        RowDivider()
        MapUiColorRow("Dark interface panels", s.mapUiNightPanel, darkPanelColors) { s.mapUiNightPanel = it }
}

@Composable
private fun MapUiColorRow(label: String, current: String?, swatches: Map<String, String>, save: (String?) -> Unit) {
    var editing by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).tap { editing = true }.padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingLabel(label, Icons.Filled.Palette, 0x6D4EB0, Modifier.weight(1f))
        if (current == null) Text("Default", color = secondaryText)
        else Box(Modifier.size(24.dp).clip(CircleShape).background(swatchColor(current))
            .border(1.dp, secondaryText, CircleShape))
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = secondaryText)
    }
    if (editing) MapColorPicker(label, current ?: swatches.values.first(), swatches, close = { editing = false },
        reset = { save(null); editing = false }, save = { save(it); editing = false })
}

private fun swatchColor(value: String) = Color(android.graphics.Color.parseColor(value))

@Composable
private fun MapColorPicker(
    title: String, initial: String, swatches: Map<String, String>, close: () -> Unit,
    reset: (() -> Unit)? = null, save: (String) -> Unit,
) {
    fun components(value: String) = FloatArray(3).also {
        android.graphics.Color.colorToHSV(android.graphics.Color.parseColor(value), it)
    }
    var hsv by remember(initial) { mutableStateOf(components(initial)) }
    var custom by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(14.dp)
    val color = android.graphics.Color.HSVToColor(hsv)
    val hex = String.format(java.util.Locale.ROOT, "#%06X", color and 0xFFFFFF)
    fun channel(index: Int, value: Float) {
        hsv = hsv.copyOf().also { it[index] = value }
    }

    AlertDialog(
        onDismissRequest = close,
        title = { Text(title) },
        text = {
            Column(Modifier.heightIn(max = 410.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.fillMaxWidth().height(64.dp).clip(shape).background(Color(color))
                    .border(1.dp, secondaryText, shape).semantics { contentDescription = "Selected color" })
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for ((name, value) in swatches.entries.distinctBy { it.value }) {
                        val on = hex.equals(value, ignoreCase = true)
                        Box(Modifier.size(46.dp).clip(CircleShape).background(swatchColor(value))
                            .border(if (on) 2.dp else 1.dp, secondaryText, CircleShape)
                            .semantics { contentDescription = name }.tap { hsv = components(value) },
                            contentAlignment = Alignment.Center) {
                            if (on) {
                                val tint = if (android.graphics.Color.luminance(color) > 0.18f) Color.Black else Color.White
                                Icon(Icons.Filled.Check, "Selected", tint = tint, modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }
                TextButton({ custom = !custom }) { Text(if (custom) "Hide sliders" else "Custom color") }
                if (custom) {
                    Text("Hue", color = primaryText)
                    Slider(hsv[0].coerceIn(0f, 359.9f), { channel(0, it) }, valueRange = 0f..359.9f)
                    Text("Saturation", color = primaryText)
                    Slider(hsv[1], { channel(1, it) })
                    Text("Brightness", color = primaryText)
                    Slider(hsv[2], { channel(2, it) })
                }
                if (reset != null) TextButton(reset) { Text("Use default") }
            }
        },
        confirmButton = { TextButton({ save(hex) }) { Text("Apply") } },
        dismissButton = { TextButton(close) { Text("Cancel") } },
    )
}

@Composable
private fun MapColorsPage(close: () -> Unit) {
    val s = AppSettings
    val appDark = LocalDark.current
    var dark by remember { mutableStateOf(appDark) }
    var field by remember { mutableStateOf<MapColor?>(null) }
    val palette = if (dark) s.nightMapPalette else s.dayMapPalette
    fun apply(value: MapPalette) {
        if (dark) s.nightMapPalette = value else s.dayMapPalette = value
    }
    fun swatch(hex: String) = Color(android.graphics.Color.parseColor(hex))
    TripSheet("Map colors", "Done", close) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Segmented(listOf(false, true), dark, { if (it) "Night" else "Day" }) { dark = it }
            SettingsGroup(header = "Palettes") {
                for ((name, preset) in MapPalette.presets(dark)) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).tap { apply(preset) }.padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(name, Modifier.weight(1f), color = primaryText)
                        for (color in preset.colors) Box(Modifier.size(15.dp).clip(CircleShape).background(swatch(color)))
                        if (palette == preset) Icon(Icons.Filled.Check, "Selected", modifier = Modifier.size(18.dp))
                    }
                }
            }
            SettingsGroup(header = "Custom colors", footer = "Day and night palettes apply to Mapbox Standard. The route color also applies on satellite.") {
                for (color in MapColor.entries) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 49.dp).tap { field = color }.padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(color.label, Modifier.weight(1f), color = primaryText)
                        Box(Modifier.size(25.dp).clip(CircleShape).background(swatch(palette[color]))
                            .border(1.dp, secondaryText, CircleShape))
                    }
                }
            }
            TextButton({ apply(MapPalette.default(dark)) }) { Text("Reset colors") }
        }
    }
    field?.let { selected ->
        val swatches = MapPalette.presets(dark).map { (name, preset) -> "$name palette" to preset[selected] }.toMap() +
            (if (dark) darkPanelColors else lightPanelColors) + accentColors
        MapColorPicker(selected.label, palette[selected], swatches, close = { field = null }) {
            apply(palette.change(selected, it))
            field = null
        }
    }
}

@Composable
private fun MapboxSettings() {
    val s = AppSettings
    var editing by remember { mutableStateOf(false) }
    val configured = MapboxDirections.isPublicToken(s.mapboxPublicToken)
    SettingsGroup(header = "Map provider",
        footer = "Mapbox receives the map area and route coordinates. OpenFreeMap and Kite routing are available as a fallback. Follow road signs, traffic signals and local traffic laws whenever they differ from the directions.") {
        ToggleRow("Prefer Mapbox", Icons.Outlined.Map, 0x1D4ED8, s.mapboxEnabled) { s.mapboxEnabled = it }
        RowDivider()
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).tap { editing = true }.padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically) {
            SettingLabel("Mapbox public token", Icons.Filled.Key, 0x334155, Modifier.weight(1f))
            Text(if (configured) "Edit" else "Add", color = secondaryText)
        }
    }
    if (editing) {
        var token by remember { mutableStateOf(s.mapboxPublicToken) }
        val value = token.trim()
        val valid = MapboxDirections.isPublicToken(value)
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text("Mapbox public token") },
            text = {
                OutlinedTextField(token, { token = it }, label = { Text("Public access token") },
                    placeholder = { Text("pk.…") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    isError = value.isNotEmpty() && !valid,
                    supportingText = { Text("Use a public token from your Mapbox account.") })
            },
            confirmButton = {
                TextButton(onClick = {
                    s.mapboxPublicToken = value
                    s.mapboxEnabled = true
                    editing = false
                }, enabled = valid) { Text("Save") }
            },
            dismissButton = {
                if (configured) TextButton(onClick = { s.mapboxPublicToken = ""; editing = false }) { Text("Remove") }
                TextButton(onClick = { editing = false }) { Text("Cancel") }
            },
        )
    }
}

/** A row of choices like the iPhone's segmented control: the picked one lifted on a white tile. */
@Composable
fun <T> Segmented(options: List<T>, value: T, label: (T) -> String, set: (T) -> Unit) {
    val dark = LocalDark.current
    Row(Modifier.fillMaxWidth().height(36.dp).clip(RoundedCornerShape(10.dp)).background(Vela.chip(dark)).padding(2.dp)) {
        options.forEach { o ->
            val on = o == value
            Box(Modifier.weight(1f).fillMaxHeight().then(if (on) Modifier.shadow(1.dp, RoundedCornerShape(8.dp)) else Modifier)
                .clip(RoundedCornerShape(8.dp)).background(if (on) Vela.surface(dark) else Color.Transparent).tap { set(o) }
                .semantics { selected = on; contentDescription = label(o) },
                contentAlignment = Alignment.Center) {
                Text(label(o), fontSize = 15.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium, color = primaryText)
            }
        }
    }
}

@Composable
private fun PlacesSettings(model: AppModel) {
    val s = AppSettings
    SettingsGroup {
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).tap { model.picking = PickKind.HOME }.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            SettingLabel("Home", Icons.Filled.Home, 0x16A34A, Modifier.weight(1f))
            Text(s.home?.name ?: "Set", color = secondaryText, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 180.dp))
        }
        RowDivider()
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).tap { model.picking = PickKind.WORK }.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            SettingLabel("Work", Icons.Filled.Work, 0x9333EA, Modifier.weight(1f))
            Text(s.work?.name ?: "Set", color = secondaryText, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 180.dp))
        }
    }
    SettingsGroup(header = "Shortcuts") {
        for (place in s.placeShortcuts) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                SettingLabel(place.name, Icons.Filled.Place, 0x2563EB, Modifier.weight(1f))
                TextButton({ model.shortcutDraft = place }) { Text("Edit") }
            }
        }
        MapSettingLink("Add shortcut", Icons.Filled.Add, 0x16A34A) { model.picking = PickKind.SHORTCUT }
    }
}

@Composable
private fun PrivacySettings() {
    val s = AppSettings
    SettingsGroup(footer = "Turn this on to use open data for search, place details and Kite routing. If you enabled Mapbox in Map settings, Mapbox still provides the map and car routing.") {
        ToggleRow("Use Kite without Google", Icons.Filled.Lock, 0x475569, s.noGoogle) { s.noGoogle = it }
    }
}

@Composable
private fun AboutSettings(openDiagnostics: () -> Unit) {
    val ctx = LocalContext.current
    val dark = LocalDark.current
    @Composable
    fun link(text: String, url: String) {
        Text(text, fontSize = KType.body, color = Vela.accentText(dark), modifier = Modifier.fillMaxWidth().tap { ctx.open(url) }.padding(horizontal = 14.dp, vertical = 14.dp))
    }
    SettingsGroup {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 14.dp)) {
            Text("Version", fontSize = KType.body, color = primaryText, modifier = Modifier.weight(1f))
            Text(BuildConfig.VERSION_NAME, color = secondaryText)
        }
        HorizontalDivider()
        link("Kite Maps on GitHub", "https://github.com/matteofx1/kite-maps")
        HorizontalDivider()
        link("Based on Vela Maps for Android", "https://github.com/PimpinPumpkin/Vela")
        HorizontalDivider()
        link("Live Waze reports the way SABRE Plus gets them (MIT)", "https://github.com/nicglazkov/highway-radar-sabre-plus")
        HorizontalDivider()
        Text("Free software under the GPL-3.0. Map © OpenFreeMap, OpenMapTiles, OpenStreetMap contributors. Routing © FOSSGIS OSRM. Imagery © Esri.",
            fontSize = KType.caption, color = secondaryText, modifier = Modifier.padding(14.dp))
    }
    SettingsGroup(footer = "Makes a small file to send with a bug report.") {
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).tap(openDiagnostics).padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            SettingLabel("Share diagnostics", Icons.Filled.BugReport, 0x64748B, Modifier.weight(1f))
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = secondaryText)
        }
    }
    SettingsGroup("Thanks to") {
        link("Surveillance cameras, saved-place matching, GPS warnings and diagnostics from Ausbloke's Kite fork", "https://github.com/NotDiscordOfficial/kite-maps")
        HorizontalDivider()
        link("UK road camera list from OpenHighways (MIT)", "https://github.com/JamesDillonDev/openhighways")
        HorizontalDivider()
        link("Webcams provided by Windy.com", "https://www.windy.com/webcams")
    }
}

/** Settings › Road alerts: which live Waze reports to show and warn about, the police heat map and police aircraft. */
@Composable
private fun WazeSettings() {
    val s = AppSettings
    var remembered by remember { mutableIntStateOf(WazeHistory.all.size) }
    var confirmClear by remember { mutableStateOf(false) }
    val kinds = listOf(
        WazeAlert.Kind.POLICE to "Police and mobile speed traps", WazeAlert.Kind.ACCIDENT to "Crashes",
        WazeAlert.Kind.HAZARD to "Hazards (stopped cars, objects, potholes)", WazeAlert.Kind.WEATHER to "Weather (fog, ice, flooding)",
        WazeAlert.Kind.ROADWORKS to "Roadworks", WazeAlert.Kind.CLOSURE to "Road closures", WazeAlert.Kind.JAM to "Traffic jams reported by drivers",
    )
    val ages = listOf(30 to "30 minutes", 60 to "1 hour", 120 to "2 hours", 240 to "4 hours", 0 to "Keep them all")
    fun whenText(at: Long): String { val m = ((System.currentTimeMillis() - at) / 60_000).toInt(); return if (m < 1) "just now" else "$m min ago" }
    val connection: Pair<String, Color> = Waze.lastStatus?.let { st ->
        val w = whenText(st.atMillis)
        when (st.code) {
            200 -> "Closures working: ${st.count} reports, $w" to hex(0x188038)
            0 -> "Closures: no connection ($w)" to hex(0xF59E0B)
            401, 403 -> "Closures: Waze turned Kite away ($w). It may work again later." to Color.Red
            -1 -> "Closures: Waze sent something Kite can't read ($w)" to Color.Red
            else -> "Closures: Waze error ${st.code} ($w)" to hex(0xF59E0B)
        }
    } ?: ("Closures: checked once you open the map at city zoom" to Color.Gray)
    val live: Pair<String, Color> = WazeLive.lastStatus?.let { st ->
        val w = whenText(st.atMillis)
        when (st.code) {
            200 -> "Driver reports working: ${st.summary}, $w" to hex(0x188038)
            0 -> "Driver reports: no connection ($w)" to hex(0xF59E0B)
            401 -> "Driver reports: Waze turned Kite away ($w). It may work again later." to Color.Red
            440 -> "Driver reports: reconnecting to Waze ($w)" to hex(0xF59E0B)
            -1 -> "Driver reports: Waze sent something Kite can't read ($w)" to Color.Red
            else -> "Driver reports: Waze error ${st.code} ($w)" to hex(0xF59E0B)
        }
    } ?: ("Driver reports: checked once you open the map at city zoom" to Color.Gray)

    SettingsGroup(footer = if (s.noGoogle) "Off while \"Use Kite without Google\" is on, since Waze belongs to Google."
        else "Kite asks Waze for drivers' reports the way the Waze app does, for the map on screen and along your route, with an anonymous account Waze makes for it (no name, email or phone). Closures also come from the Waze live map. Waze sees roughly where you are, anonymously. It doesn't allow other apps to do this, so it may stop working one day.") {
        ToggleRow("Use Waze reports", null, 0, s.waze, enabled = !s.noGoogle) { s.waze = it }
    }
    if (s.wazeOn) {
        // What the hidden live map page saw, to send to whoever looks into it.
        SettingsGroup("Connection", footer = Waze.lastStatus?.takeIf { it.code != 200 }?.let { "Details: " + WazeWeb.note }) {
            for ((text, color) in listOf(connection, live)) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(color))
                    Text(text, fontSize = KType.subheadline, color = primaryText)
                }
            }
            Waze.lastStatus?.takeIf { it.code == 200 }?.let { st ->
                // What Waze actually sent, so it's easy to see if a kind has gone missing.
                Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                    Text("Closures, last answer", fontSize = KType.subheadline, color = primaryText, modifier = Modifier.weight(1f))
                    Text(st.summary, fontSize = KType.subheadline, color = secondaryText)
                }
            }
        }
        SettingsGroup("Show and warn about") {
            // Only what Waze still shares: no switches that can't do anything.
            kinds.filter { it.first in Waze.shares }.forEachIndexed { i, (k, label) ->
                if (i > 0) HorizontalDivider(Modifier.padding(start = 50.dp))
                ToggleRow(label, null, 0, k in s.wazeKinds, leading = { WazeBadge(k, 26.dp) }) { on ->
                    s.wazeKinds = if (on) s.wazeKinds + k else s.wazeKinds - k
                }
            }
        }
        SettingsGroup("Alerts", "While navigating, a card pops up for reports on your route, only ones made by drivers going your way.") {
            ToggleRow("Show reports on the map", null, 0, s.wazeOnMap) { s.wazeOnMap = it }
            HorizontalDivider()
            ToggleRow("Chime and say them while navigating", null, 0, s.wazeVoice) { s.wazeVoice = it }
            HorizontalDivider()
            PickRow("Ignore reports older than", null, 0, s.wazeMaxAge, ages.map { it.first }, { v -> ages.first { it.first == v }.second }) { s.wazeMaxAge = it }
        }
        if (WazeAlert.Kind.POLICE in Waze.shares) {
            SettingsGroup(footer = "Heat map: density of recent police reports remembered by Kite on the Mapbox map. The classic map uses Highway Radar tiles. Risk score: low, medium or high for the road ahead (counting reports made at this time of day most), from the Waze reports Kite remembers on this phone, for 90 days and never anywhere else.") {
                ToggleRow("Police heat map", null, 0, s.wazeHeatmap) { s.wazeHeatmap = it }
                HorizontalDivider()
                ToggleRow("Police risk score while driving", null, 0, s.policeRisk) { s.policeRisk = it }
                HorizontalDivider()
                Row(Modifier.padding(horizontal = 14.dp, vertical = 14.dp)) {
                    Text("Police reports remembered", fontSize = KType.body, color = primaryText, modifier = Modifier.weight(1f))
                    Text("$remembered", color = secondaryText)
                }
                if (remembered > 0) {
                    HorizontalDivider()
                    Text("Forget them all", fontSize = KType.body, color = Vela.closed, modifier = Modifier.fillMaxWidth().tap { confirmClear = true }.padding(14.dp))
                }
            }
        }
    }
    SettingsGroup("Aircraft", "While navigating, warns when a police aircraft is within about 10 km, from open flight tracking (adsb.lol), not Waze. It spots them by their police callsign (like NPAS in the UK), and some don't broadcast at all, so no warning doesn't mean the sky is clear.") {
        ToggleRow("Police planes and helicopters", null, 0, s.policeAircraft) { s.policeAircraft = it }
    }
    if (confirmClear) {
        AlertDialog(onDismissRequest = { confirmClear = false },
            text = { Text("Forget the $remembered police reports Kite remembered? The risk score starts again from nothing.") },
            confirmButton = { TextButton({ WazeHistory.clear(); remembered = 0; confirmClear = false }) { Text("Forget them", color = Vela.closed) } },
            dismissButton = { TextButton({ confirmClear = false }) { Text("Cancel") } })
    }
}

// Choosing home or work

/** Choosing home or work: type an address or postcode, or use where you are now. */
@Composable
fun PlacePicker(model: AppModel, kind: PickKind) {
    val s = AppSettings
    val title = when (kind) { PickKind.HOME -> "Home"; PickKind.WORK -> "Work"; PickKind.SHORTCUT -> "Shortcut" }
    val current = when (kind) { PickKind.HOME -> s.home; PickKind.WORK -> s.work; PickKind.SHORTCUT -> null }
    var query by remember { mutableStateOf("") }
    var found by remember { mutableStateOf<List<Place>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var locating by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var task by remember { mutableStateOf<Job?>(null) }
    fun close() { model.picking = null }
    fun pick(p: Place) {
        close()
        when (kind) {
            PickKind.HOME -> model.setHome(p)
            PickKind.WORK -> model.setWork(p)
            PickKind.SHORTCUT -> model.nameNewShortcut(p)
        }
    }

    /** As you type: quick suggestions. On Search: a full search, postcodes included. */
    fun lookUp(text: String, full: Boolean) {
        task?.cancel()
        val q = text.trim()
        if (q.length < 2) { found = emptyList(); searching = false; return }
        searching = true
        val center = model.userLatLng ?: model.viewport.first
        val useGoogle = !s.noGoogle
        task = scope.launch {
            if (!full) delay(300)
            val places = withContext(Dispatchers.IO) {
                if (full) PlaceSearch.search(q, center, 50_000.0, useGoogle).places
                else {
                    var out = listOfNotNull(runCatching { Postcodes.lookup(q) }.getOrNull())
                    if (out.isEmpty() && useGoogle) out = runCatching { GoogleClient.shared.suggest(q, center, 50_000.0).places }.getOrDefault(emptyList())
                    if (out.isEmpty()) out = runCatching { Photon.search(q, center, 10) }.getOrDefault(emptyList())
                    out
                }
            }
            found = places.take(20)
            searching = false
        }
    }

    @Composable
    fun row(p: Place) {
        Row(Modifier.fillMaxWidth().tap { pick(p) }.padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(Icons.Filled.Place, null, tint = Vela.closed, modifier = Modifier.size(28.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(p.name, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = primaryText, maxLines = 2)
                p.address?.takeIf { it != p.name }?.let { Text(it, fontSize = 15.sp, color = secondaryText, maxLines = 2) }
            }
            model.userLatLng?.let { Text(Fmt.distance(it.distanceTo(p.location)), fontSize = 15.sp, color = secondaryText) }
        }
    }

    PickerPage(if (kind == PickKind.SHORTCUT) "Choose a shortcut location" else "Set ${title.lowercase()}", ::close) {
        PickerSearchField(query, { query = it; lookUp(it, false) }, "Address or postcode") { lookUp(query, true) }
        LazyColumn(Modifier.fillMaxSize()) {
            if (query.isBlank()) {
                item {
                    val can = model.userLatLng != null && !locating
                    Row(Modifier.fillMaxWidth().tap {
                        val me = model.userLatLng ?: return@tap
                        if (locating) return@tap
                        locating = true
                        scope.launch {
                            val near = withContext(Dispatchers.IO) { runCatching { Photon.reverse(me) }.getOrNull() }
                            val p = Place(id = "${kind.name.lowercase()}:${me.lat},${me.lng}", name = near?.name ?: title, location = me, source = "osm", address = near?.address)
                            locating = false
                            pick(p)
                        }
                    }.padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Box(Modifier.size(40.dp).clip(CircleShape).background(Vela.routeBlue.copy(alpha = if (can) 1f else 0.4f)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.MyLocation, null, tint = Color.White, modifier = Modifier.size(20.dp))
                        }
                        Text("Use where I am now", fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = primaryText.copy(alpha = if (can) 1f else 0.4f), modifier = Modifier.weight(1f))
                        if (locating) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                    Text(if (model.location.denied) "Location is off for Kite, so type the address instead." else "Or type the address or postcode above.",
                        fontSize = KType.caption, color = secondaryText, modifier = Modifier.padding(horizontal = 18.dp))
                }
                if (current != null) {
                    item { SectionTitle("Now set to") }
                    item { row(current) }
                    item {
                        Row(Modifier.fillMaxWidth().tap { if (kind == PickKind.HOME) model.setHome(null) else model.setWork(null); close() }
                            .padding(horizontal = 18.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(Icons.Filled.Delete, null, tint = Vela.closed)
                            Text("Remove ${title.lowercase()}", color = Vela.closed)
                        }
                    }
                }
            } else if (found.isNotEmpty()) {
                items(found, key = { it.id }) { row(it) }
            } else if (searching) {
                item {
                    Row(Modifier.padding(18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); Text("Searching…", color = secondaryText)
                    }
                }
            } else item {
                Text(Connectivity.explain("Nothing found yet. Keep typing, or try the postcode."), color = secondaryText, modifier = Modifier.padding(18.dp))
            }
        }
    }
}
