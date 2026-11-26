package app.kite

import android.graphics.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
import app.kite.core.Place
import app.kite.core.WazeAlert
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.PropertyFactory

/** The images Kite's map layers draw: category pins, the red picked pin, the puck, route bubbles. */
object MapIcons {
    data class Kind(val name: String, val icon: ImageVector, val color: Int, val words: List<String>)

    private fun c(hex: Long, a: Float = 1f) = Color.argb((a * 255).toInt(), ((hex shr 16) and 0xFF).toInt(), ((hex shr 8) and 0xFF).toInt(), (hex and 0xFF).toInt())

    val kinds = listOf(
        Kind("cat-pharmacy", Icons.Filled.LocalPharmacy, c(0x169173), listOf("pharmacy", "drugstore", "lekarna")),
        Kind("cat-supermarket", Icons.Filled.LocalGroceryStore, c(0x347DCA), listOf("supermarket", "grocery", "potraviny")),
        Kind("cat-bakery", Icons.Filled.BakeryDining, c(0xB65C3A), listOf("bakery", "pekarna")),
        Kind("cat-charging", Icons.Filled.EvStation, c(0x319A83), listOf("charging", "electric vehicle")),
        Kind("cat-water", Icons.Filled.WaterDrop, c(0x2C90BC), listOf("drinking water", "pitko", "water fountain")),
        Kind("cat-parking", Icons.Filled.LocalParking, c(0x1A73E8), listOf("parking", "garage", "car park", "parkoviste", "parkovaci")),
        Kind("cat-restroom", Icons.Filled.Wc, c(0x6E76C5), listOf("restroom", "toilets", "toalety")),
        Kind("cat-stationery", Icons.Filled.Edit, c(0x6866BD), listOf("paper store", "stationery", "papirnictvi")),
        Kind("cat-diy", Icons.Filled.Handyman, c(0xB07634), listOf("hardware", "diy", "home improvement")),
        Kind("cat-book", Icons.Filled.MenuBook, c(0x935CA4), listOf("bookstore", "book store", "knihkupectvi")),
        Kind("cat-clothes", Icons.Filled.Checkroom, c(0xA65381), listOf("clothing", "fashion", "obleceni")),
        Kind("cat-repair", Icons.Filled.Build, c(0x477080), listOf("repair", "mechanic", "autoservis")),
        Kind("cat-airport", Icons.Filled.Flight, c(0x4165A4), listOf("airport", "letiste")),
        Kind("cat-bike", Icons.Filled.PedalBike, c(0x278566), listOf("bicycle", "bike shop", "cyklo")),
        Kind("cat-pool", Icons.Filled.Pool, c(0x3287A6), listOf("swimming", "pool", "bazen")),
        Kind("cat-play", Icons.Filled.ChildCare, c(0xB1752D), listOf("playground", "hriste")),
        Kind("cat-lookout", Icons.Filled.Landscape, c(0x517D59), listOf("viewpoint", "lookout", "vyhlidka")),
        Kind("cat-camp", Icons.Filled.Cabin, c(0x617D43), listOf("camp", "kemp")),
        Kind("cat-worship", Icons.Filled.Church, c(0x8365A1), listOf("church", "worship", "kostel")),
        Kind("cat-library", Icons.Filled.LocalLibrary, c(0x586F9D), listOf("library", "knihovna")),
        Kind("cat-post", Icons.Filled.LocalPostOffice, c(0xA1783B), listOf("post office", "posta")),
        Kind("cat-police", Icons.Filled.LocalPolice, c(0x566C9A), listOf("police", "policie")),
        Kind("cat-coffee", Icons.Filled.LocalCafe, c(0xE8710A), listOf("coffee", "cafe", "café", "bakery", "tea", "donut")),
        Kind("cat-food", Icons.Filled.Restaurant, c(0xE8453C), listOf("restaurant", "food", "pizza", "sushi", "burger", "grill", "bar", "pub", "diner", "taco", "bistro", "steak", "ramen", "noodle", "kitchen", "eatery", "sandwich", "chicken")),
        Kind("cat-gas", Icons.Filled.LocalGasStation, c(0x1A73E8), listOf("gas", "fuel", "petrol", "charging")),
        Kind("cat-shop", Icons.Filled.ShoppingCart, c(0x1A73E8), listOf("grocery", "supermarket", "store", "shop", "market", "mall", "pharmacy", "drugstore")),
        Kind("cat-hotel", Icons.Filled.Hotel, c(0xD01884), listOf("hotel", "motel", "inn", "lodging", "hostel", "resort")),
        Kind("cat-park", Icons.Filled.Park, c(0x188038), listOf("park", "garden", "trail", "playground", "beach")),
        Kind("cat-health", Icons.Filled.MedicalServices, c(0xC5221F), listOf("hospital", "clinic", "doctor", "dentist", "medical", "urgent")),
        Kind("cat-transit", Icons.Filled.Tram, c(0x12B5A5), listOf("station", "bus", "subway", "metro", "train", "airport")),
        Kind("cat-money", Icons.Filled.Payments, c(0x5F6368), listOf("atm", "bank")),
    )

    fun iconName(p: Place): String {
        val cat = Place.fold(p.category.orEmpty()).replace('_', ' ')
        if (("parking" in cat || "parkoviste" in cat) && ("bicycle" in cat || "bike" in cat || "kol" in cat))
            return "cat-bike"
        return kinds.firstOrNull { k -> k.words.any { cat.contains(it) } }?.name ?: "cat-default"
    }

    fun ambientName(place: Place): String {
        val category = place.category.orEmpty().lowercase()
        return when {
            listOf("school", "college", "university", "nursery").any(category::contains) -> "poi-edu"
            listOf("museum", "theatre", "theater", "art gallery").any(category::contains) -> "poi-culture"
            listOf("stadium", "sports", "gym").any(category::contains) -> "poi-sport"
            "town hall" in category -> "poi-civic"
            else -> "poi-" + iconName(place).removePrefix("cat-")
        }
    }

    fun categoryTint(place: Place): String {
        val color = kinds.firstOrNull { it.name == iconName(place) }?.color ?: c(0x75849B)
        return String.format(java.util.Locale.ROOT, "#%06X", color and 0xFFFFFF)
    }

    fun searchName(place: Place): String {
        val category = Place.fold(place.category.orEmpty()).replace(' ', '_')
        val outdoor = app.kite.core.OutdoorData.kind(place.category.orEmpty()) ?: category
        return OpenMapStyle.detailIcons[outdoor]?.let { "search-open-$it" } ?: "search-${iconName(place)}"
    }

    private val osmIcons = mapOf(
        "water" to (Icons.Filled.WaterDrop to 0x116A9C), "parking" to (Icons.Filled.LocalParking to 0x3659A3),
        "toilets" to (Icons.Filled.Wc to 0x5D4688), "bike" to (Icons.Filled.PedalBike to 0x14694B),
        "bike-repair" to (Icons.Filled.Build to 0x14694B), "outdoors" to (Icons.Filled.Park to 0x286C4E),
        "bench" to (Icons.Filled.EventSeat to 0x795837), "viewpoint" to (Icons.Filled.Landscape to 0x286C4E),
        "shelter" to (Icons.Filled.Roofing to 0x795837), "picnic" to (Icons.Filled.OutdoorGrill to 0x795837),
        "camp" to (Icons.Filled.Cabin to 0x286C4E), "food" to (Icons.Filled.Restaurant to 0x9E461E),
        "coffee" to (Icons.Filled.LocalCafe to 0x9E461E), "bakery" to (Icons.Filled.BakeryDining to 0x9E461E),
        "shop" to (Icons.Filled.ShoppingBasket to 0x305B99), "fuel" to (Icons.Filled.LocalGasStation to 0x305B99),
        "charging" to (Icons.Filled.EvStation to 0x14694B), "medical" to (Icons.Filled.MedicalServices to 0xA13D4D),
        "pharmacy" to (Icons.Filled.LocalPharmacy to 0xA13D4D), "health" to (Icons.Filled.LocalHospital to 0xA13D4D),
        "transit" to (Icons.Filled.Tram to 0x5749A8), "bus" to (Icons.Filled.DirectionsBus to 0x5749A8),
        "culture" to (Icons.Filled.Museum to 0x6E4593), "library" to (Icons.Filled.LocalLibrary to 0x6E4593),
        "education" to (Icons.Filled.School to 0x6E4593), "playground" to (Icons.Filled.ChildCare to 0x98633C),
        "fire" to (Icons.Filled.LocalFireDepartment to 0xA13D4D), "police" to (Icons.Filled.LocalPolice to 0x305B99),
        "post" to (Icons.Filled.LocalPostOffice to 0x946320), "recycling" to (Icons.Filled.Recycling to 0x14694B),
        "waste" to (Icons.Filled.DeleteOutline to 0x476267), "bank" to (Icons.Filled.AccountBalance to 0x476267),
        "fountain" to (Icons.Filled.Water to 0x116A9C), "hotel" to (Icons.Filled.Hotel to 0x8B457C),
        "more" to (Icons.Filled.Place to 0x476267),
    )

    private val plates = mutableMapOf<MarkerTheme, Bitmap>()
    private fun drawThemePlate(canvas: Canvas, icon: ImageVector, tint: Int, size: Float): Boolean {
        val theme = AppSettings.markerTheme
        if (theme.assetPack != null) {
            val center = size / 2f
            val radius = size * 0.43f
            val shape = RectF(center - radius, center - radius, center + radius, center + radius)
            val rounding = if (theme == MarkerTheme.LUCIDE) size * 0.27f else radius
            canvas.drawRoundRect(shape, rounding, rounding, paint(tint))
            canvas.drawRoundRect(shape, rounding, rounding, paint(Color.WHITE, Paint.Style.STROKE, size * 0.057f))
            val halfGlyph = size * if (theme == MarkerTheme.LUCIDE) 0.29f else 0.33f
            val area = RectF(center - halfGlyph, center - halfGlyph, center + halfGlyph, center + halfGlyph)
            if (!MapGlyphs.draw(theme, canvas, icon.name, area, Color.WHITE)) drawIcon(canvas, icon, area, Color.WHITE)
            return true
        }
        val resource = theme.resource ?: return false
        val plate = plates.getOrPut(theme) { BitmapFactory.decodeResource(KiteApp.app.resources, resource) }
        val pad = size * 0.045f
        val center = size / 2f
        canvas.drawBitmap(plate, null, RectF(pad, pad, size - pad, size - pad), Paint(Paint.FILTER_BITMAP_FLAG))
        canvas.drawCircle(center, center, size * 0.32f, paint(tint).apply { alpha = 105 })
        val glyph = size * 0.225f
        drawIcon(canvas, icon, RectF(center - glyph, center - glyph, center + glyph, center + glyph), Color.WHITE)
        return true
    }

    private fun placeBadge(icon: ImageVector, tint: Int, search: Boolean) = image(44f, 44f) { cv ->
        if (drawThemePlate(cv, icon, tint, 44f)) return@image
        if (search) {
            val marker = Path().apply {
                moveTo(22f, 42f)
                cubicTo(19.2f, 38.1f, 17.5f, 35.5f, 13.9f, 34f)
                arcTo(RectF(3.2f, 2f, 40.8f, 37f), 115f, 310f, false)
                cubicTo(26.5f, 35.5f, 24.8f, 38.1f, 22f, 42f)
                close()
            }
            cv.save()
            cv.translate(0f, 1.1f)
            cv.drawPath(marker, paint(c(0x101827, 0.62f), Paint.Style.STROKE, 2.7f))
            cv.restore()
            cv.drawPath(marker, paint(tint))
            cv.drawPath(marker, paint(Color.WHITE, Paint.Style.STROKE, 2.0f))
            drawIcon(cv, icon, RectF(12f, 9.5f, 32f, 29.5f), Color.WHITE)
            return@image
        }
        val shape = RectF(4f, 4f, 40f, 40f)
        val corner = 10f
        cv.drawRoundRect(RectF(shape.left, shape.top + 1.6f, shape.right, shape.bottom + 1.6f), corner, corner, paint(c(0x0B1828, 0.38f)))
        val body = paint(tint).apply {
            shader = LinearGradient(0f, 4f, 0f, 40f, intArrayOf(tint, c(0x111C34)), null, Shader.TileMode.CLAMP)
        }
        cv.drawRoundRect(shape, corner, corner, body)
        cv.drawRoundRect(shape, corner, corner, paint(c(0xFFFFFF, 0.85f), Paint.Style.STROKE, 2f))
        drawIcon(cv, icon, RectF(11f, 11f, 33f, 33f), Color.WHITE)
    }

    private val density get() = KiteApp.app.resources.displayMetrics.density

    /** A bitmap [w] x [h] dp, drawn in dp units, at the screen's density. */
    private fun image(w: Float, h: Float, draw: (Canvas) -> Unit): Bitmap {
        val d = density
        val b = Bitmap.createBitmap((w * d).toInt().coerceAtLeast(1), (h * d).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        b.density = KiteApp.app.resources.displayMetrics.densityDpi
        val cv = Canvas(b)
        cv.scale(d, d)
        draw(cv)
        return b
    }

    private fun paint(color: Int, style: Paint.Style = Paint.Style.FILL, width: Float = 0f) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color; this.style = style; strokeWidth = width; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }

    /** Draws a Material icon (24-unit art) filling [r]. */
    private fun drawIcon(cv: Canvas, icon: ImageVector, r: RectF, color: Int) {
        val p = paint(color)
        cv.save()
        cv.translate(r.left, r.top)
        cv.scale(r.width() / icon.viewportWidth, r.height() / icon.viewportHeight)
        fun walk(g: VectorGroup) {
            for (n in g) when (n) {
                is VectorPath -> {
                    val path = PathParser().addPathNodes(n.pathData).toPath().asAndroidPath()
                    path.fillType = if (n.pathFillType == androidx.compose.ui.graphics.PathFillType.EvenOdd) Path.FillType.EVEN_ODD else Path.FillType.WINDING
                    cv.drawPath(path, p)
                }
                is VectorGroup -> walk(n)
            }
        }
        walk(icon.root)
        cv.restore()
    }

    fun register(style: Style, dark: Boolean) = registerImages(dark) { id, bitmap -> style.addImage(id, bitmap) }

    private var atlasTheme: MarkerTheme? = null
    private var atlas: Map<String, Bitmap> = emptyMap()
    private fun overlayImages(): Map<String, Bitmap> {
        val theme = AppSettings.markerTheme
        if (theme == atlasTheme) return atlas
        val images = buildMap {
            fun add(id: String, bitmap: Bitmap) { put(id, bitmap) }
            for (k in kinds) {
                add(k.name, dot(k.icon, k.color))
                add("search-${k.name}", placeBadge(k.icon, k.color, search = true))
            }
            add("search-cat-default", placeBadge(Icons.Filled.Place, c(0x2679D8), search = true))
            for ((key, glyph) in osmIcons) {
                val (icon, tint) = glyph
                add("open-$key", placeBadge(icon, c(tint.toLong()), search = false))
                add("search-open-$key", placeBadge(icon, c(tint.toLong()), search = true))
            }
            add("cat-default", dot(Icons.Filled.Place, c(0xE8453C)))
            add("pin-selected", selectedPin())
            add("pin-origin", originDot())
            add("road-pill", bubble(c(0x2DD4BF), c(0x0F766E)))
            add("cam-speed", speedCamera())
            add("cam-plate", plateCamera())
            add("cam-surveillance", badge(c(0x22863A)) { cv, _ -> drawIcon(cv, Icons.Filled.Videocam, RectF(12f, 12f, 34f, 34f), Color.WHITE) })
            add("traffic-light", trafficLight())
            add("road-stop", dot(Icons.Filled.StopCircle, c(0xC32E43)))
            add("road-crossing", dot(Icons.Filled.Train, c(0x91602C)))
            add("pedestrian-crossing", dot(Icons.AutoMirrored.Filled.DirectionsWalk, c(0x126782)))
            add("osm-drinking_water", dot(Icons.Filled.WaterDrop, c(0x227FA8)))
            add("osm-toilets", dot(Icons.Filled.Wc, c(0x546294)))
            add("indoor-elevator", dot(Icons.Filled.Elevator, c(0x256E73)))
            add("indoor-stairs", dot(Icons.Filled.Stairs, c(0x256E73)))
            add("osm-transit", dot(Icons.Filled.Tram, c(0x4D66BB)))
            add("osm-bicycle_parking", dot(Icons.Filled.PedalBike, c(0x227D6B)))
            // Live camera photos: sky blue for road cameras, violet for Windy's scenic webcams.
            add("livecam-road", dot(Icons.Filled.Videocam, c(0x0284C7)))
            add("livecam-web", dot(Icons.Filled.Landscape, c(0x7C3AED)))
            for (k in WazeAlert.Kind.entries) add("waze-${k.key}", waze(k))
            for (style in RouteArrowStyle.entries) add(style.sprite, turnHead(style))
            add("route-chevron", routeChevron())
            add("pin-stop", dot(Icons.Filled.RadioButtonChecked, c(0x5F6368)))
            add("mine-home", dot(Icons.Filled.Home, c(0x1A73E8)))
            add("mine-work", dot(Icons.Filled.Work, c(0x8D6E63)))
            add("mine-parking", dot(Icons.Filled.LocalParking, c(0x1967D2)))
            add("mine-saved", dot(Icons.Filled.Bookmark, c(0x0F9D58)))
        }
        atlasTheme = theme
        atlas = images
        return images
    }

    fun registerImages(dark: Boolean, add: (String, Bitmap) -> Unit) {
        overlayImages().forEach { (id, asset) -> add(id, asset) }
        for (marker in DriveMarker.entries) {
            val bitmap = drivePuck(marker, dark)
            add(marker.sprite, bitmap)
            add(marker.mapSprite, image(52f, 52f) { canvas ->
                canvas.drawBitmap(bitmap, null, RectF(0f, 0f, 52f, 52f), Paint(Paint.FILTER_BITMAP_FLAG))
            })
        }
        add("heading-cone", image(54f, 54f) { canvas ->
            val cone = Path().apply {
                moveTo(27f, 27f)
                arcTo(RectF(3f, 3f, 51f, 51f), -121f, 62f, false)
                close()
            }
            canvas.drawPath(cone, paint(c(0x16A8DE, 0.26f)))
        })
    }

    /** The head of the turn arrow drawn on the road: big and white with a teal edge, pointing up. */
    fun turnHead(style: RouteArrowStyle = RouteArrowStyle.OUTLINED) = image(64f, 60f) { cv ->
        val p = Path().apply {
            when (style) {
                RouteArrowStyle.OUTLINED -> { moveTo(32f, 8f); lineTo(55f, 45f); lineTo(9f, 45f); close() }
                RouteArrowStyle.SOLID -> { moveTo(32f, 7f); lineTo(56f, 47f); lineTo(32f, 35f); lineTo(8f, 47f); close() }
                RouteArrowStyle.OPEN -> { moveTo(11f, 40f); lineTo(32f, 15f); lineTo(53f, 40f) }
            }
        }
        val outline = paint(Color.parseColor(style.edge), Paint.Style.STROKE, if (style == RouteArrowStyle.OPEN) 14f else 8f)
            .apply { strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
        cv.drawPath(p, outline)
        val ink = Color.parseColor(style.fill)
        cv.drawPath(p, if (style == RouteArrowStyle.OPEN) paint(ink, Paint.Style.STROKE, 8f)
            .apply { strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND } else paint(ink))
    }

    fun speedCamera() = badge(c(0xEF6C00)) { cv, amber ->
        cv.drawRoundRect(RectF(15f, 12f, 31f, 32f), 3f, 3f, paint(Color.WHITE))
        cv.drawCircle(23f, 20f, 4.6f, paint(amber))
        cv.drawCircle(23f, 20f, 2f, paint(Color.WHITE))
        cv.drawRect(RectF(18f, 26.5f, 28f, 29.5f), paint(amber))
    }

    fun trafficLight() = image(14f, 30f) { cv ->
        cv.drawRoundRect(RectF(0f, 0f, 14f, 30f), 5f, 5f, paint(Color.WHITE))
        cv.drawRoundRect(RectF(1.5f, 1.5f, 12.5f, 28.5f), 4f, 4f, paint(c(0x263238)))
        listOf(0xFF3B30L, 0xFFB300L, 0x34C759L).forEachIndexed { i, col -> cv.drawOval(RectF(3.5f, 3.5f + i * 8, 10.5f, 10.5f + i * 8), paint(c(col))) }
    }

    fun plateCamera() = badge(c(0x7B1FA2)) { cv, purple ->
        val w = paint(Color.WHITE)
        cv.drawRoundRect(RectF(12f, 18f, 30f, 27f), 2f, 2f, w)
        cv.drawCircle(31f, 22.5f, 4.2f, w)
        cv.drawCircle(31f, 22.5f, 2f, paint(purple))
        val arm = paint(Color.WHITE, Paint.Style.STROKE, 2.4f).apply { strokeCap = Paint.Cap.BUTT }
        cv.drawLine(17f, 18f, 17f, 12f, arm)
        cv.drawLine(12f, 12f, 24f, 12f, arm)
    }

    fun wazeIcon(k: WazeAlert.Kind): ImageVector = when (k) {
        WazeAlert.Kind.POLICE -> Icons.Filled.Shield
        WazeAlert.Kind.ACCIDENT -> Icons.Filled.CarCrash
        WazeAlert.Kind.HAZARD -> Icons.Filled.Warning
        WazeAlert.Kind.WEATHER -> Icons.Filled.Cloud
        WazeAlert.Kind.ROADWORKS -> Icons.Filled.Construction
        WazeAlert.Kind.CLOSURE -> Icons.Filled.Block
        WazeAlert.Kind.JAM -> Icons.Filled.Traffic
    }

    fun wazeColor(k: WazeAlert.Kind): Long = when (k) {
        WazeAlert.Kind.POLICE -> 0x1E88E5
        WazeAlert.Kind.ACCIDENT, WazeAlert.Kind.JAM -> 0xD93025
        WazeAlert.Kind.HAZARD -> 0xF29900
        WazeAlert.Kind.WEATHER -> 0x00897B
        WazeAlert.Kind.ROADWORKS -> 0xE8710A
        WazeAlert.Kind.CLOSURE -> 0xB71C1C
    }

    fun waze(k: WazeAlert.Kind) = if (AppSettings.markerTheme == MarkerTheme.CLASSIC)
        badge(c(wazeColor(k))) { cv, _ -> drawIcon(cv, wazeIcon(k), RectF(12f, 12f, 34f, 34f), Color.WHITE) }
        else image(34.5f, 34.5f) { drawThemePlate(it, wazeIcon(k), c(wazeColor(k)), 34.5f) }

    /** A 46-unit badge drawn at 34.5 dp: white rim, colored field, then the glyph. */
    private fun badge(color: Int, glyph: (Canvas, Int) -> Unit) = image(34.5f, 34.5f) { cv ->
        cv.scale(0.75f, 0.75f)
        cv.drawRoundRect(RectF(2f, 2f, 44f, 44f), 11f, 11f, paint(Color.WHITE))
        cv.drawRoundRect(RectF(4.5f, 4.5f, 41.5f, 41.5f), 9f, 9f, paint(color))
        glyph(cv, color)
    }

    private fun dot(icon: ImageVector, color: Int) = image(28f, 28f) { cv ->
        if (drawThemePlate(cv, icon, color, 28f)) return@image
        val r = RectF(1.5f, 1.5f, 26.5f, 26.5f)
        cv.drawOval(r, paint(color))
        cv.drawOval(r, paint(Color.WHITE, Paint.Style.STROKE, 2f))
        drawIcon(cv, icon, RectF(7.5f, 7.5f, 20.5f, 20.5f), Color.WHITE)
    }

    /** Vela-style ambient POI pins, centered at the place so the tail doesn't shift the marker. */
    fun registerMapPlaces(style: Style, satellite: Boolean) =
        registerMapPlaces(satellite) { id, bitmap -> style.addImage(id, bitmap) }

    fun registerMapPlaces(satellite: Boolean, register: (String, Bitmap) -> Unit) {
        fun add(category: String, icon: ImageVector, categoryColor: Int) {
            register("poi-$category", ambientBadge(icon, categoryColor, satellite))
        }
        for (k in kinds) {
            val category = k.name.removePrefix("cat-")
            val color = when (category) {
                "food" -> c(0xE8710A)
                "shop" -> c(0x4285F4)
                "hotel" -> c(0xC2185B)
                "health" -> c(0xD93025)
                "transit" -> c(0x1A73E8)
                else -> k.color
            }
            add(category, k.icon, color)
        }
        add("edu", Icons.Filled.School, c(0x00897B))
        add("civic", Icons.Filled.AccountBalance, c(0x5F6368))
        add("culture", Icons.Filled.Museum, c(0x9334E6))
        add("sport", Icons.Filled.SportsSoccer, c(0xE37400))
        add("default", Icons.Filled.Place, c(0x5F6368))
    }

    private fun ambientBadge(icon: ImageVector, categoryColor: Int, satellite: Boolean = false) = image(29f, 29f) { cv ->
        val backing = if (satellite) Color.WHITE else c(0x9AA0A6)
        if (drawThemePlate(cv, icon, categoryColor, 29f)) return@image
        val body = paint(backing)
        val tail = Path().apply {
            moveTo(7f, 19f); lineTo(14.5f, 28f); lineTo(22f, 19f); close()
        }
        cv.drawPath(tail, body)
        cv.drawCircle(14.5f, 14.5f, 11f, body)
        cv.drawCircle(14.5f, 14.5f, 9f, paint(categoryColor))
        drawIcon(cv, icon, RectF(9f, 9f, 20f, 20f), Color.WHITE)
    }

    enum class Sample { GOOGLE, OSM, SEARCH, WAZE }

    fun sample(type: Sample): Bitmap = when (type) {
        Sample.GOOGLE -> ambientBadge(Icons.Filled.LocalCafe, c(0xE8710A))
        Sample.OSM -> placeBadge(Icons.Filled.WaterDrop, c(0x227FA8), search = false)
        Sample.SEARCH -> placeBadge(Icons.Filled.LocalGasStation, c(0x2679D8), search = true)
        Sample.WAZE -> waze(WazeAlert.Kind.POLICE)
    }

    fun stripBadge(icon: ImageVector, tint: Int) = image(32f, 32f) {
        if (!drawThemePlate(it, icon, tint, 32f)) drawIcon(it, icon, RectF(0f, 0f, 32f, 32f), tint)
    }

    private fun selectedPin() = image(34f, 46f) { cv ->
        val p = Path().apply {
            moveTo(17f, 44f)
            cubicTo(13f, 37f, 6f, 29f, 3.5f, 23f)
            arcTo(RectF(2f, 2f, 32f, 32f), 154.8f, 230.4f, false)
            cubicTo(28f, 29f, 21f, 37f, 17f, 44f)
            close()
        }
        cv.drawPath(p, paint(c(0xEA4335)))
        cv.drawPath(p, paint(c(0xA50E0E), Paint.Style.STROKE, 1.5f))
        cv.drawOval(RectF(12f, 12f, 22f, 22f), paint(c(0x7A0B0B)))
    }

    private fun originDot() = image(22f, 22f) { cv ->
        cv.drawOval(RectF(1f, 1f, 21f, 21f), paint(Color.WHITE))
        cv.drawOval(RectF(4f, 4f, 18f, 18f), paint(c(0x14B8A6), Paint.Style.STROKE, 4f))
    }

    private val vehicleArt = mutableMapOf<Int, Bitmap>()

    fun drivePuck(marker: DriveMarker, dark: Boolean, tilted: Boolean = false): Bitmap {
        val artwork = (if (tilted) marker.tilted else marker.top) ?: return puck(dark)
        return vehicleArt.getOrPut(artwork) {
            val sprite = BitmapFactory.decodeResource(KiteApp.app.resources, artwork)
            image(76f, 76f) { canvas ->
                canvas.drawBitmap(sprite, null, RectF(3f, 3f, 73f, 73f), Paint(Paint.FILTER_BITMAP_FLAG))
            }.also { sprite.recycle() }
        }
    }

    /** The car while navigating, in Kite's teal: just the arrow, with no disc or ring around it (the light look). */
    fun puck(@Suppress("UNUSED_PARAMETER") dark: Boolean) = image(76f, 76f) { cv ->
        val outline = Path().apply { moveTo(38f, 13f); lineTo(58f, 60f); lineTo(38f, 50f); lineTo(18f, 60f); close() }
        cv.save(); cv.translate(0f, 2f)
        cv.drawPath(outline, paint(c(0x000000, 0.2f), Paint.Style.STROKE, 8f))
        cv.restore()
        cv.drawPath(outline, paint(Color.WHITE, Paint.Style.STROKE, 5f))
        cv.drawPath(outline, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(38f, 13f, 52f, 60f, c(0x99F6E4), c(0x0D9488), Shader.TileMode.CLAMP)
        })
        val shade = Path().apply { moveTo(38f, 13f); lineTo(58f, 60f); lineTo(38f, 50f); close() }
        cv.drawPath(shade, paint(c(0x064E3B, 0.3f)))
    }

    /** A chevron along the route line, pointing the way you go. */
    fun routeChevron() = image(14f, 16f) { cv ->
        val p = Path().apply { moveTo(4f, 3f); lineTo(10f, 8f); lineTo(4f, 13f) }
        cv.drawPath(p, paint(c(0x0B3B36), Paint.Style.STROKE, 3f))
    }

    data class BadgeColors(val surface: Int, val text: Int, val accent: Int, val onAccent: Int) {
        companion object {
            val FALLBACK = BadgeColors(0xFFFAFCFD.toInt(), 0xFF1E2A35.toInt(), 0xFF0C646B.toInt(), Color.WHITE)
        }
    }

    fun badgeWidth(label: String): Float = badgeText(Color.WHITE, false).measureText(label) + 24f

    private fun badgeText(color: Int, numbered: Boolean) = paint(color).apply {
        typeface = Typeface.create("sans-serif-rounded", Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        textSize = if (numbered) 15f else 17f
    }

    /** The label and its filled card are a single symbol, so the entire card participates in collision detection. */
    fun mapBadge(label: String, selected: Boolean, colors: BadgeColors, numbered: Boolean): Bitmap {
        val fg = if (selected) colors.onAccent else colors.text
        val bg = if (selected) colors.accent else colors.surface
        val text = badgeText(fg, numbered)
        val w = if (numbered) 34f else text.measureText(label) + 24f
        val h = if (numbered) 33f else 36f
        return image(w, h) { cv ->
            val rect = RectF(2f, 1.5f, w - 2f, h - 4f)
            cv.drawRoundRect(RectF(2f, 3f, w - 2f, h - 1f), 12f, 12f, paint(0x350D1822))
            cv.drawRoundRect(rect, 12f, 12f, paint(bg))
            cv.drawRoundRect(rect, 12f, 12f, paint(if (selected) colors.onAccent else colors.accent,
                Paint.Style.STROKE, if (selected) 1.2f else 1.55f))
            cv.drawText(label, w / 2f, rect.centerY() - (text.ascent() + text.descent()) / 2f, text)
        }
    }

    private fun bubble(fill: Int, stroke: Int) = image(30f, 26f) { cv ->
        val r = RectF(1f, 1f, 29f, 25f)
        cv.drawRoundRect(r, 12f, 12f, paint(fill))
        cv.drawRoundRect(r, 12f, 12f, paint(stroke, Paint.Style.STROKE, 1.5f))
    }

    /** The puck again, for after a light/dark switch. */
    fun swapPuck(style: Style, dark: Boolean) { style.addImage("puck", puck(dark)) }

    @Suppress("unused") private val keep = PropertyFactory::class
}

internal val DriveMarker.mapSprite: String get() = "$sprite-map"
