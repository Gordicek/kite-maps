package app.kite.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kite.*
import app.kite.R
import app.kite.core.*

/** A full-screen first-launch page: scrolls with very big text, with the button always in reach at the bottom. */
@Composable
private fun IntroPage(button: String, onButton: () -> Unit, enabled: Boolean = true,
                      onBack: () -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    // Drawn straight over the app (not in a Dialog): a Dialog's window didn't reach the bottom of
    // the screen on a Pixel, which hid the button. Back does nothing here; the choice comes first.
    androidx.activity.compose.BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().background(interfaceGround).pointerInput(Unit) { }.systemBarsPadding().imePadding()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
            Spacer(Modifier.height(20.dp))
            content()
        }
        Button(onButton, Modifier.padding(horizontal = 24.dp, vertical = 12.dp).fillMaxWidth().heightIn(min = 54.dp), enabled = enabled) {
            Text(button, fontSize = KType.headline, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun IntroOption(title: String, detail: String, icon: ImageVector, picked: Boolean, tap: () -> Unit) {
    val dark = LocalDark.current
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
        .border(if (picked) 2.dp else 1.dp, if (picked) interfaceAccent else secondaryText.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
        .tap(tap).padding(16.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Icon(icon, null, tint = if (picked) interfaceAccent else secondaryText, modifier = Modifier.size(26.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontSize = KType.headline, fontWeight = FontWeight.SemiBold, color = primaryText)
            Text(detail, fontSize = KType.subheadline, color = secondaryText)
        }
        Icon(if (picked) Icons.Filled.CheckCircle else Icons.Outlined.Circle, null, tint = if (picked) interfaceAccent else secondaryText, modifier = Modifier.size(24.dp))
    }
}

/** First launch keeps the Mapbox choice independent from the Google and Waze choices. */
@Composable
fun WelcomeScreen() {
    val s = AppSettings
    val context = LocalContext.current
    var chooseData by rememberSaveable { mutableStateOf(false) }
    var token by rememberSaveable { mutableStateOf(s.mapboxPublicToken) }
    var openOnly by rememberSaveable { mutableStateOf(false) }
    val value = token.trim()
    val valid = MapboxDirections.isPublicToken(value)
    if (!chooseData) {
        IntroPage(
            if (value.isBlank()) kt("Continue without Mapbox", "Pokračovat bez Mapboxu") else kt("Continue", "Pokračovat"),
            onButton = { chooseData = true }, enabled = value.isBlank() || valid,
        ) {
            Image(painterResource(R.drawable.kite_logo), null, Modifier.size(66.dp).clip(RoundedCornerShape(16.dp)))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(kt("Welcome to Kite Driving", "Vítej v Kite Driving"), fontSize = 34.sp,
                    fontWeight = FontWeight.Bold, color = primaryText)
                Text(kt("For the best Kite Driving experience, connect Mapbox. It brings the 3D map and traffic-aware car routes.",
                    "Kite Driving funguje nejlépe s Mapboxem. Získáš 3D mapu a plánování tras autem podle provozu."),
                    fontSize = KType.body, color = secondaryText)
            }
            MapAppearanceScene(mapPalette(LocalDark.current), Modifier.fillMaxWidth().height(136.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(token, { token = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text(localizeLabel("Mapbox public token")) },
                    placeholder = { Text("pk.…") }, isError = value.isNotBlank() && !valid,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri,
                        autoCorrectEnabled = false,
                    ),
                    supportingText = {
                        Text(if (value.isNotBlank() && !valid)
                            kt("Paste a complete public token starting with pk.", "Vlož celý veřejný token začínající pk.")
                        else kt("Use a public token from your Mapbox account.", "Použij veřejný token ze svého účtu Mapbox."))
                    })
                TextButton({ context.open("https://account.mapbox.com/access-tokens/") }) {
                    Text(kt("Open Mapbox access tokens", "Otevřít přístupové tokeny Mapbox"))
                }
                Text(kt("Without a token, Kite uses OpenFreeMap. You can connect Mapbox later in Settings.",
                    "Bez tokenu Kite použije OpenFreeMap. Mapbox můžeš připojit později v nastavení."),
                    fontSize = KType.footnote, color = secondaryText)
            }
        }
        return
    }

    IntroPage(kt("Get started", "Začít"), onButton = {
        if (valid) {
            s.mapboxPublicToken = value
            s.mapboxEnabled = true
        }
        s.noGoogle = openOnly
        if (openOnly) s.wazeAsked = true
        s.welcomed = true
    }, onBack = { chooseData = false }) {
        Text(kt("Search and routes", "Hledání a trasy"), fontSize = 34.sp, fontWeight = FontWeight.Bold, color = primaryText)
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            IntroOption(kt("Use Google when available", "Použít Google, kde je dostupný"),
                kt("Google provides optional search, place details, traffic and routing.",
                    "Google doplní hledání, informace o místech, dopravu a plánování tras."),
                Icons.Filled.AutoAwesome, !openOnly) { openOnly = false }
            IntroOption(kt("Continue without Google", "Pokračovat bez Googlu"),
                kt("Kite uses open providers for search and directions. Mapbox stays available if you connected it.",
                    "Kite použije otevřené zdroje pro hledání a trasy. Připojený Mapbox je dál dostupný."),
                Icons.Filled.Security, openOnly) { openOnly = true }
        }
        TextButton({ chooseData = false }) { Text(kt("Back to Mapbox setup", "Zpět k nastavení Mapboxu")) }
    }
}

/**
 * Asked once, after the welcome: whether to turn on live Waze reports. A beta, since Waze could
 * stop it working any day.
 */
@Composable
fun WazeIntroScreen() {
    val s = AppSettings
    var on by remember { mutableStateOf(true) }
    val police = WazeAlert.Kind.POLICE in Waze.shares
    IntroPage(kt("Continue", "Pokračovat"), { s.waze = on; s.wazeAsked = true }) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            // Only kinds Waze still shares, so the picture matches what Kite can really show.
            (if (police) listOf(WazeAlert.Kind.POLICE, WazeAlert.Kind.ACCIDENT, WazeAlert.Kind.HAZARD) else listOf(WazeAlert.Kind.CLOSURE)).forEach { WazeBadge(it, 56.dp) }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(localizeLabel("Road alerts"), fontSize = 34.sp, fontWeight = FontWeight.Bold, color = primaryText)
                Text("BETA", fontSize = KType.caption, fontWeight = FontWeight.ExtraBold, color = Color.White,
                    modifier = Modifier.clip(CircleShape).background(Color(0xFFF29900)).padding(horizontal = 8.dp, vertical = 3.dp))
            }
            Text(if (police) "Live police, crash and hazard reports from Waze drivers, on the map and as warnings while you drive, plus a police heat map and risk score."
                else "Live road closures from Waze, on the map and as warnings while you drive.", fontSize = 20.sp, color = secondaryText)
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            IntroOption("Turn on road alerts", "Kite asks Waze anonymously about the area you're looking at and your route. Reports Kite sees are kept only on this phone.",
                Icons.Filled.NotificationsActive, on) { on = true }
            IntroOption("Not now", "Nothing is sent to Waze.", Icons.Outlined.NotificationsOff, !on) { on = false }
        }
        Text(localizeLabel("This is a beta: Waze doesn't officially allow other apps to read its live map, so it may stop working. You can change this any time in Settings › Road alerts."),
            fontSize = KType.footnote, color = secondaryText)
    }
}
