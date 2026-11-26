@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.kite

import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import app.kite.core.Daylight
import app.kite.core.LatLng
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kite.core.*
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Kite's colors: a deep navy night look with one bright teal for "go".
 * Sized and colored for a phone in a car holder. The same values as the iPhone app.
 */
object Vela {
    val teal = Color(0xFF0F766E)
    val tealLight = Color(0xFF2DD4BF)
    val routeBlue = Color(0xFF2F7BFF)
    val open = Color(0xFF16A34A)
    val openDark = Color(0xFF4ADE80)
    val closed = Color(0xFFDC2626)
    val closedDark = Color(0xFFF87171)
    val star = Color(0xFFF9AB00)
    val camera = Color(0xFFFF8A00)
    val police = Color(0xFF2F7BFF)

    fun accent(dark: Boolean) = if (dark) Color(0xFF3FA9F5) else Color(0xFF14B8A6)
    fun onAccent(@Suppress("UNUSED_PARAMETER") dark: Boolean) = Color(0xFF04241F)
    fun accentText(dark: Boolean) = if (dark) Color(0xFF3FA9F5) else teal
    fun sheet(dark: Boolean) = if (dark) Color(0xFF151517) else Color.White
    fun surface(dark: Boolean) = if (dark) Color(0xFF232326) else Color.White
    fun ground(dark: Boolean) = if (dark) Color(0xFF08090B) else Color(0xFFF2F5F9)
    fun chip(dark: Boolean) = if (dark) Color(0xFF303034) else Color(0xFFEDF1F6)
    /** A soft amber for "worth knowing" chips, like a longer route or a road charge. */
    fun warnFill(dark: Boolean) = if (dark) Color(0xFF3A2A10) else Color(0xFFFDEFD9)
    fun warnText(dark: Boolean) = if (dark) Color(0xFFFBBF24) else Color(0xFF9A4A08)
    fun tonal(dark: Boolean) = if (dark) Color(0xFF12403C) else Color(0xFFCCFBF1)
    fun onTonal(dark: Boolean) = if (dark) Color(0xFF99F6E4) else Color(0xFF134E4A)
    fun muted(dark: Boolean) = if (dark) Color(0xFF9A9AA3) else Color(0xFF64748B)
    fun primary(dark: Boolean) = if (dark) Color(0xFFF4F5F7) else Color(0xFF0F172A)
}

/** Whether the screen is in dark colors right now. */
data class MapChrome(val accent: Color?, val panel: Color?) {
    fun panel(dark: Boolean): Color {
        val fallback = Vela.surface(dark)
        return panel?.withContrast(Vela.muted(dark), fallback) ?: fallback
    }

    fun primary(dark: Boolean): Color =
        (accent ?: Vela.accentText(dark)).withContrast(panel(dark), Vela.primary(dark))

    fun icon(dark: Boolean, preferred: Color? = null): Color =
        (preferred ?: accent ?: Vela.primary(dark)).withContrast(panel(dark), Vela.primary(dark))
}

private fun Color.withContrast(background: Color, fallback: Color): Color {
    val base = background.luminance()
    for (step in 0..10) {
        val candidate = lerp(this, fallback, step / 10f)
        val light = candidate.luminance()
        if ((maxOf(base, light) + 0.05f) / (minOf(base, light) + 0.05f) >= 4.5f) return candidate
    }
    return fallback
}

val LocalMapChrome = staticCompositionLocalOf<MapChrome?> { null }
private val LocalInterfaceScheme = staticCompositionLocalOf<ColorScheme?> { null }

val mapControlTint: Color @Composable get() {
    val chrome = LocalMapChrome.current
    return if (chrome?.accent != null) interfaceAccentText else primaryText
}
val interfaceSurface: Color @Composable get() =
    LocalMapChrome.current?.takeIf { it.panel != null }?.panel(LocalDark.current)
        ?: LocalInterfaceScheme.current?.surfaceContainerHigh ?: Vela.surface(LocalDark.current)
val interfaceSheet: Color @Composable get() =
    LocalMapChrome.current?.takeIf { it.panel != null }?.panel(LocalDark.current)
        ?: LocalInterfaceScheme.current?.surfaceContainerLow ?: Vela.sheet(LocalDark.current)
val interfaceChip: Color @Composable get() =
    LocalInterfaceScheme.current?.surfaceContainerHighest ?: Vela.chip(LocalDark.current)

val interfaceTonal: Color @Composable get() =
    LocalInterfaceScheme.current?.secondaryContainer ?: Vela.tonal(LocalDark.current)
val interfaceOnTonal: Color @Composable get() =
    LocalInterfaceScheme.current?.onSecondaryContainer ?: Vela.onTonal(LocalDark.current)

val interfaceHighlight: Color @Composable get() =
    LocalInterfaceScheme.current?.primaryContainer ?: lerp(interfaceSheet, interfaceAccent, 0.13f)
val interfaceOnHighlight: Color @Composable get() =
    LocalInterfaceScheme.current?.onPrimaryContainer ?: primaryText

val interfaceGround: Color @Composable get() {
    val dark = LocalDark.current
    val fallback = LocalInterfaceScheme.current?.background ?: Vela.ground(dark)
    return LocalMapChrome.current?.takeIf { it.panel != null }?.let { lerp(fallback, it.panel(dark), 0.66f) } ?: fallback
}
val interfaceAccent: Color @Composable get() =
    MaterialTheme.colorScheme.primary
val interfaceAccentText: Color @Composable get() {
    val chrome = LocalMapChrome.current
    val scheme = LocalInterfaceScheme.current
    val dark = LocalDark.current
    return when {
        chrome?.accent != null -> chrome.primary(dark)
        scheme != null -> scheme.primary.withContrast(interfaceSurface, primaryText)
        chrome != null -> chrome.primary(dark)
        else -> Vela.accentText(dark)
    }
}
val interfaceOnAccent: Color @Composable get() =
    MaterialTheme.colorScheme.onPrimary

val LocalDark = staticCompositionLocalOf { true }

/** The rounded type, like the iPhone's SF Rounded. */
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
val Rounded: FontFamily = FontFamily(
    listOf(400, 500, 600, 700, 800, 900).map { w ->
        Font(R.font.nunito, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
    }
)

/** Text sizes matching iOS's at the "xLarge" type size the iPhone app never goes below. */
object KType {
    val largeTitle = 36.sp
    val title = 30.sp
    val title2 = 24.sp
    val title3 = 22.sp
    val headline = 19.sp
    val body = 19.sp
    val callout = 18.sp
    val subheadline = 17.sp
    val footnote = 15.sp
    val caption = 14.sp
    val caption2 = 13.sp
}

private val LocalWallpaperPalettes = staticCompositionLocalOf<Pair<MapPalette, MapPalette>?> { null }

@Composable
internal fun wallpaperMapPalette(dark: Boolean): MapPalette? =
    LocalWallpaperPalettes.current?.let { if (dark) it.second else it.first }

@Composable
internal fun mapPalette(dark: Boolean): MapPalette =
    AppSettings.resolveMapPalette(dark, wallpaperMapPalette(dark))

internal fun ColorScheme.mapArtwork(dark: Boolean): MapPalette {
    fun hex(color: Color) = String.format(java.util.Locale.ROOT, "#%06X", color.toArgb() and 0xFFFFFF)
    val ground = lerp(surfaceContainerLow, primaryContainer, if (dark) 0.19f else 0.22f)
    val water = lerp(Color(if (dark) 0xFF1F3E51 else 0xFFB4D7E6), surfaceVariant, 0.23f)
    val parks = lerp(Color(if (dark) 0xFF31493D else 0xFFCADDC0), primaryContainer, 0.14f)
    val buildings = lerp(surfaceContainerHighest, primary, if (dark) 0.23f else 0.12f)
    val streets = if (dark) lerp(surfaceContainerHigh, primary, 0.23f) else surfaceBright
    val highways = lerp(primaryContainer, primary, if (dark) 0.51f else 0.34f)
    return MapPalette(listOf(ground, water, parks, buildings, streets, highways, primary).map(::hex))
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun KiteTheme(position: () -> LatLng?, content: @Composable () -> Unit) {
    var clock by androidx.compose.runtime.remember { androidx.compose.runtime.mutableLongStateOf(System.currentTimeMillis()) }
    androidx.compose.runtime.LaunchedEffect(AppSettings.theme) {
        while (AppSettings.theme == ThemeMode.SUN) {
            clock = System.currentTimeMillis()
            kotlinx.coroutines.delay(60_000)
        }
    }
    val dark = when (AppSettings.theme) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SUN -> position()?.let { Daylight.isDark(it, clock) } ?: isSystemInDarkTheme()
    }
    fun parse(value: String?) = value?.let { Color(android.graphics.Color.parseColor(it)) }
    val chrome = MapChrome(parse(AppSettings.mapUiAccent), parse(if (dark) AppSettings.mapUiNightPanel else AppSettings.mapUiDayPanel))
    val context = LocalContext.current
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val systemSchemes = remember(context, configuration) {
        if (android.os.Build.VERSION.SDK_INT >= 31) dynamicLightColorScheme(context) to dynamicDarkColorScheme(context)
        else null
    }
    val wallpaper = remember(systemSchemes) { systemSchemes?.let { it.first.mapArtwork(false) to it.second.mapArtwork(true) } }
    val selected = if (AppSettings.interfacePalette == InterfacePalette.EXPRESSIVE)
        systemSchemes?.let { if (dark) it.second else it.first } else null
    val panel = chrome.panel(dark)
    val baseGround = selected?.background ?: Vela.ground(dark)
    val ground = if (chrome.panel != null) lerp(baseGround, panel, 0.66f) else baseGround
    val accent = if (chrome.accent != null) chrome.primary(dark) else selected?.primary ?: Vela.accentText(dark)
    val onAccent = if (chrome.accent == null && selected != null) selected.onPrimary
        else if (accent.luminance() > 0.179f) Color(0xFF071525) else Color.White
    val scheme = selected?.copy(
        primary = accent, onPrimary = onAccent, background = ground,
        surface = if (chrome.panel != null) panel else selected.surface,
    ) ?: if (dark) darkColorScheme(
        primary = accent, onPrimary = onAccent, background = ground,
        surface = if (chrome.panel != null) panel else Vela.sheet(true), onSurface = Vela.primary(true), onBackground = Vela.primary(true),
        surfaceVariant = Vela.chip(true), onSurfaceVariant = Vela.muted(true),
        secondaryContainer = Vela.tonal(true), onSecondaryContainer = Vela.onTonal(true),
    ) else lightColorScheme(
        primary = accent, onPrimary = onAccent, background = ground,
        surface = panel, onSurface = Vela.primary(false), onBackground = Vela.primary(false),
        surfaceVariant = Vela.chip(false), onSurfaceVariant = Vela.muted(false),
        secondaryContainer = Vela.tonal(false), onSecondaryContainer = Vela.onTonal(false),
    )
    val type = remember {
        val base = Typography()
        Typography(
            displayLarge = base.displayLarge.copy(fontFamily = Rounded),
            headlineLarge = base.headlineLarge.copy(fontFamily = Rounded),
            headlineMedium = base.headlineMedium.copy(fontFamily = Rounded),
            titleLarge = base.titleLarge.copy(fontFamily = Rounded, fontSize = KType.title3),
            titleMedium = base.titleMedium.copy(fontFamily = Rounded, fontSize = KType.headline),
            titleSmall = base.titleSmall.copy(fontFamily = Rounded),
            bodyLarge = base.bodyLarge.copy(fontFamily = Rounded, fontSize = KType.body),
            bodyMedium = base.bodyMedium.copy(fontFamily = Rounded, fontSize = KType.subheadline),
            bodySmall = base.bodySmall.copy(fontFamily = Rounded, fontSize = KType.footnote),
            labelLarge = base.labelLarge.copy(fontFamily = Rounded, fontSize = KType.callout),
            labelMedium = base.labelMedium.copy(fontFamily = Rounded),
            labelSmall = base.labelSmall.copy(fontFamily = Rounded),
        )
    }
    CompositionLocalProvider(LocalDark provides dark, LocalMapChrome provides chrome, LocalInterfaceScheme provides selected,
        LocalWallpaperPalettes provides wallpaper) {
        MaterialExpressiveTheme(colorScheme = scheme, typography = type, motionScheme = MotionScheme.expressive()) {
            CompositionLocalProvider(
                LocalContentColor provides scheme.onSurface,
                LocalTextStyle provides TextStyle(fontFamily = Rounded, fontSize = KType.body),
            ) {
                content()
            }
        }
    }
}

/** Primary and secondary text colors for the current look. */
val primaryText: Color @Composable get() = LocalInterfaceScheme.current?.onSurface ?: Vela.primary(LocalDark.current)
val secondaryText: Color @Composable get() = LocalInterfaceScheme.current?.onSurfaceVariant ?: Vela.muted(LocalDark.current)

object Fmt {
    private val country: String get() = AppSettings.language.locale.country
    private val autoImperial: Boolean get() = country in setOf("US", "GB", "LR", "MM", "UK")

    val metric: Boolean
        get() = when (AppSettings.units) {
            UnitsMode.METRIC -> true
            UnitsMode.IMPERIAL, UnitsMode.YARDS -> false
            UnitsMode.AUTO -> !autoImperial
        }

    /** Short distances in yards, the British way. */
    val yards: Boolean
        get() = when (AppSettings.units) {
            UnitsMode.YARDS -> true
            UnitsMode.AUTO -> country == "GB" || country == "UK"
            else -> false
        }

    private fun r(x: Double) = x.roundToInt()

    fun distance(m: Double): String {
        if (metric) {
            if (m < 950) return "${r(m / 10) * 10} m"
            return if (m < 9_950) String.format(AppSettings.language.locale, "%.1f km", m / 1000) else "${r(m / 1000)} km"
        }
        if (yards) {
            val yd = m * 1.09361
            if (yd < 500) return "${max(r(yd / 10) * 10, 10)} yd"
        } else {
            val ft = m * 3.28084
            if (ft < 1000) return "${max(r(ft / 50) * 50, 50)} ft"
        }
        val mi = m / 1609.344
        return if (mi < 9.95) String.format(Locale.US, "%.1f mi", mi) else "${r(mi)} mi"
    }

    /** Words for the voice: "400 feet", "1.2 miles". */
    fun spokenDistance(m: Double): String {
        if (czechUi) return distance(m).replace(" mi", " mil").replace(" yd", " yardů").replace(" ft", " stop")
        if (metric) {
            if (m < 950) return "${r(m / 50) * 50} meters"
            val km = m / 1000
            return if (km < 1.05) "1 kilometer" else String.format(Locale.US, "%.1f kilometers", km)
        }
        if (yards) {
            val yd = m * 1.09361
            if (yd < 500) return "${max(r(yd / 50) * 50, 50)} yards"
        } else {
            val ft = m * 3.28084
            if (ft < 1000) return "${max(r(ft / 100) * 100, 100)} feet"
        }
        val mi = m / 1609.344
        if (mi < 0.3) return if (yards) "a quarter of a mile" else "a quarter mile"
        if (mi < 0.6) return "half a mile"
        if (mi < 0.9) return "three quarters of a mile"
        return if (mi < 1.05) "1 mile" else String.format(Locale.US, "%.1f miles", mi)
    }

    /** A limit for the voice: "30 miles per hour" (to the posted 5), "50 kilometers per hour". */
    fun spokenSpeed(kmh: Double): String =
        if (metric) "${max(5, r(kmh / 5) * 5)} " + kt("kilometers per hour", "kilometrů v hodině")
        else "${postedMph(kmh)} " + kt("miles per hour", "mil v hodině")

    /**
     * A limit in mph as the sign shows it, to the nearest 5. Never 0: a road mapped "walk" (about
     * 7 km/h, a car park or a living street) would round down to a "0" sign; the lowest sign is 5.
     */
    fun postedMph(kmh: Double): Int = max(5, r(kmh / 1.609344 / 5) * 5)

    /** A speed in the user's units, without the unit. */
    fun speed(kmh: Double): Int = r(if (metric) kmh else kmh / 1.609344)

    val speedUnit: String get() = if (metric) "km/h" else "mph"

    fun duration(s: Double): String {
        val mins = max(1, r(s / 60))
        if (mins < 60) return "$mins min"
        val h = mins / 60; val m = mins % 60
        return if (m == 0) "$h h" else "$h h $m min"
    }

    fun shortDuration(s: Double): String {
        val mins = max(1, r(s / 60))
        if (mins < 60) return "$mins min"
        val h = mins / 60; val m = mins % 60
        return if (m == 0) "${h}h" else "${h}h ${m}m"
    }

    /** "4 min" on foot, from metres in a straight line (a little slower than walking pace for the corners). */
    fun walkMinutes(meters: Double): String = "${maxOf(1, Math.round(meters / 70).toInt())} min"

    fun arrival(s: Double): String = time(Date(System.currentTimeMillis() + (s * 1000).toLong()))

    fun time(d: Date): String = android.text.format.DateFormat.getTimeFormat(KiteApp.app).format(d)

    fun count(n: Int): String = java.text.NumberFormat.getIntegerInstance().format(n)
}

@Composable
fun statusColor(p: Place): Color {
    val dark = LocalDark.current
    if (p.permanentlyClosed) return if (dark) Vela.closedDark else Vela.closed
    return when (p.openNow) {
        true -> if (dark) Vela.openDark else Vela.open
        false -> if (dark) Vela.closedDark else Vela.closed
        null -> secondaryText
    }
}

/** "Open" in green, then "· Closes 9:30 PM" in gray, the way Vela splits Google's status. */
@Composable
fun StatusText(place: Place, fontSize: androidx.compose.ui.unit.TextUnit = KType.subheadline) {
    val c = statusColor(place)
    val gray = secondaryText
    if (place.permanentlyClosed) {
        Text("Permanently closed", color = c, fontSize = fontSize)
    } else place.statusText?.let { s ->
        val parts = s.split(" · ")
        Text(buildAnnotatedString {
            withStyle(SpanStyle(color = c)) { append(parts[0]) }
            if (parts.size > 1) withStyle(SpanStyle(color = gray)) { append(" · " + parts.drop(1).joinToString(" · ")) }
        }, fontSize = fontSize)
    }
}

@Composable
fun Stars(rating: Double, size: Dp = 13.dp) {
    Row(horizontalArrangement = Arrangement.spacedBy(1.dp)) {
        for (i in 0 until 5) {
            val fill = rating - i
            Icon(
                when { fill >= 0.75 -> Icons.Filled.Star; fill >= 0.25 -> Icons.AutoMirrored.Filled.StarHalf; else -> Icons.Outlined.StarOutline },
                null, tint = Vela.star, modifier = Modifier.size(size),
            )
        }
    }
}

/** "4.7 ★★★★★ (1,341) · 0.2 mi · Sushi restaurant · $10–20" */
@Composable
fun PlaceMetaLine(place: Place, showStars: Boolean = true, here: LatLng? = null, showDistance: Boolean = true) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        place.rating?.let { r ->
            Text(String.format(Locale.US, "%.1f", r), color = primaryText, fontSize = KType.subheadline)
            if (showStars) Stars(r) else Icon(Icons.Filled.Star, null, tint = Vela.star, modifier = Modifier.size(12.dp))
            place.reviewCount?.let { Text("(${Fmt.count(it)})", color = secondaryText, fontSize = KType.subheadline) }
        }
        val meters = if (showDistance) here?.distanceTo(place.location) ?: place.distanceMeters else null
        val rest = listOfNotNull(meters?.let { Fmt.distance(it) }, place.category, place.priceText)
        if (rest.isNotEmpty()) {
            Text((if (place.rating != null) "· " else "") + rest.joinToString(" · "), color = secondaryText,
                fontSize = KType.subheadline, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Arrow for a maneuver. */
fun maneuverIcon(t: ManeuverType): ImageVector = when (t) {
    ManeuverType.DEPART, ManeuverType.STRAIGHT, ManeuverType.UNKNOWN -> Icons.Filled.ArrowUpward
    ManeuverType.ARRIVE -> Icons.Filled.Place
    ManeuverType.TURN_LEFT -> Icons.Filled.TurnLeft
    ManeuverType.TURN_RIGHT -> Icons.Filled.TurnRight
    ManeuverType.SLIGHT_LEFT, ManeuverType.KEEP_LEFT, ManeuverType.RAMP_LEFT, ManeuverType.FORK_LEFT -> Icons.Filled.TurnSlightLeft
    ManeuverType.SLIGHT_RIGHT, ManeuverType.KEEP_RIGHT, ManeuverType.RAMP_RIGHT, ManeuverType.FORK_RIGHT -> Icons.Filled.TurnSlightRight
    ManeuverType.SHARP_LEFT -> Icons.Filled.TurnSharpLeft
    ManeuverType.SHARP_RIGHT -> Icons.Filled.TurnSharpRight
    ManeuverType.UTURN -> Icons.Filled.UTurnLeft
    ManeuverType.MERGE -> Icons.Filled.Merge
    ManeuverType.ROUNDABOUT -> Icons.Filled.RoundaboutLeft
}

/**
 * See-through look for everything floating over the map (the light look): the surface color with the
 * map showing faintly through it and a thin bright edge. No shadow: one would show through the glass.
 */
@Composable
fun Modifier.glass(shape: Shape, solid: Boolean = false): Modifier {
    val dark = LocalDark.current
    val surface = interfaceSurface
    return this
        .clip(shape)
        .background(surface.copy(alpha = if (solid) 1f else if (dark) 0.96f else 0.91f))
        .border(0.75.dp, Color.White.copy(alpha = if (dark) 0.20f else 0.6f), shape)
}

/** Round buttons floating on the map (layers, location, compass). */
@Composable
fun MapFab(icon: ImageVector, tint: Color? = null, size: Dp = 48.dp, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val ink = tint?.withContrast(interfaceSurface, primaryText) ?: mapControlTint
    Box(modifier.size(size).glass(CircleShape, solid = true).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = ink, modifier = Modifier.size(size * 0.46f))
    }
}

/** Pill buttons for primary and secondary actions in sheets. */
@Composable
fun PillButton(title: String, icon: ImageVector, filled: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val dark = LocalDark.current
    val chrome = LocalMapChrome.current
    val customized = chrome?.accent != null || chrome?.panel != null
    val fill = when {
        filled -> interfaceAccent
        customized -> interfaceGround
        else -> LocalInterfaceScheme.current?.secondaryContainer ?: Vela.tonal(dark)
    }
    Row(
        modifier.height(52.dp).clip(CircleShape).background(fill)
            .clickable(onClick = onClick).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
    ) {
        val c = when {
            filled -> interfaceOnAccent
            customized -> interfaceAccentText
            else -> LocalInterfaceScheme.current?.onSecondaryContainer ?: Vela.onTonal(dark)
        }
        Icon(icon, null, tint = c, modifier = Modifier.size(20.dp))
        Text(title, color = c, fontWeight = FontWeight.Bold, fontSize = KType.body, maxLines = 1)
    }
}

@Composable
fun CircleIconButton(icon: ImageVector, modifier: Modifier = Modifier, description: String? = null, onClick: () -> Unit) {
    val chrome = LocalMapChrome.current
    val customized = chrome?.accent != null || chrome?.panel != null
    Box(modifier.size(44.dp).clip(CircleShape)
        .background(if (customized) interfaceGround else LocalInterfaceScheme.current?.surfaceContainerHighest ?: Vela.chip(LocalDark.current)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center) {
        Icon(icon, description, tint = if (customized) interfaceAccentText else primaryText, modifier = Modifier.size(20.dp))
    }
}
