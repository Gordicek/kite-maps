package app.kite.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import app.kite.AppSettings
import app.kite.LocalDark
import app.kite.MapColor
import app.kite.MapIcons
import app.kite.MapPalette
import app.kite.mapPalette

private fun previewColor(hex: String) = Color(android.graphics.Color.parseColor(hex))

/** Sample geography keeps palette and size previews local, independent of the map SDK and location. */
@Composable
internal fun MapAppearanceScene(palette: MapPalette, modifier: Modifier = Modifier) {
    Canvas(modifier.clip(RoundedCornerShape(15.dp))) {
        fun path(vararg points: Pair<Float, Float>): Path = Path().apply {
            points.forEachIndexed { i, (x, y) ->
                if (i == 0) moveTo(x * size.width, y * size.height)
                else lineTo(x * size.width, y * size.height)
            }
        }
        fun ink(field: MapColor) = previewColor(palette[field])
        val land = if (palette.horizon && palette[MapColor.LAND] == MapPalette.horizonNight[MapColor.LAND])
            Color(0xFF282D35) else ink(MapColor.LAND)
        val building = if (palette.horizon && palette[MapColor.BUILDINGS] == MapPalette.horizonNight[MapColor.BUILDINGS])
            Color(0xFF3D495A) else ink(MapColor.BUILDINGS)
        val unit = minOf(size.width, size.height)
        fun road(road: Path, color: Color, width: Float) {
            drawPath(road, land.copy(alpha = 0.7f), style = Stroke(unit * (width + 0.018f),
                cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawPath(road, color, style = Stroke(unit * width, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        drawRect(land)
        drawPath(path(0.74f to 0f, 0.70f to 0.23f, 0.84f to 0.43f, 0.82f to 0.70f, 0.98f to 1f,
            1f to 1f, 1f to 0f).apply { close() }, ink(MapColor.WATER))
        drawPath(path(0f to 0.77f, 0.13f to 0.72f, 0.27f to 0.80f, 0.33f to 1f, 0f to 1f).apply { close() }, ink(MapColor.PARKS))
        drawPath(path(0.56f to 0f, 0.63f to 0f, 0.60f to 0.28f, 0.49f to 0.24f).apply { close() }, ink(MapColor.PARKS))
        for (row in 0..4) {
            for (column in 0..6) {
                val x = 0.05f + column * 0.085f + if (row % 2 == 0) 0f else 0.018f
                val y = 0.11f + row * 0.164f + (column % 3) * 0.021f
                if (row >= 4 && column < 3 || row == 0 && column > 4) continue
                drawRect(building, Offset(x * size.width, y * size.height),
                    Size((0.034f + column % 2 * 0.016f) * size.width, (0.055f + row % 2 * 0.027f) * size.height))
            }
        }
        val streets = ink(MapColor.STREETS)
        road(path(0f to 0.33f, 0.22f to 0.41f, 0.50f to 0.28f, 0.78f to 0.37f, 1f to 0.39f), streets, 0.041f)
        road(path(0.22f to 0f, 0.23f to 0.41f, 0.30f to 0.64f, 0.56f to 1f), streets, 0.030f)
        road(path(0.47f to 0f, 0.41f to 0.43f, 0.40f to 0.66f, 0.36f to 1f), streets, 0.040f)
        road(path(0.65f to 0f, 0.60f to 0.26f, 0.71f to 0.61f, 0.79f to 1f), streets, 0.032f)
        road(path(0f to 0.68f, 0.30f to 0.57f, 0.71f to 0.61f, 1f to 0.60f), ink(MapColor.HIGHWAYS), 0.068f)
        val route = path(0.36f to 0.98f, 0.40f to 0.59f, 0.30f to 0.57f, 0.23f to 0.41f,
            0.50f to 0.28f, 0.73f to 0.35f)
        road(route, ink(MapColor.ROUTE), 0.039f)
        routePoint(Offset(size.width * 0.73f, size.height * 0.35f), ink(MapColor.ROUTE), unit * 0.045f)
    }
}

private fun DrawScope.routePoint(center: Offset, color: Color, radius: Float) {
    drawCircle(color, radius, center)
    drawCircle(Color.White, radius * 0.44f, center)
}

@Composable
internal fun MapPinPreview(sample: MapIcons.Sample, percent: Int, modifier: Modifier = Modifier) {
    val theme = AppSettings.markerTheme
    val density = LocalDensity.current
    val image = remember(sample, theme) { MapIcons.sample(sample).asImageBitmap() }
    val width = with(density) { image.width.toDp() }
    Box(modifier, contentAlignment = Alignment.Center) {
        Image(image, null, Modifier.size(width * (percent / 100f)))
    }
}

@Composable
internal fun RouteArrowPreview(modifier: Modifier = Modifier) {
    val palette = mapPalette(LocalDark.current)
    val style = AppSettings.routeArrowStyle
    val bitmap = remember(style) { MapIcons.turnHead(style).asImageBitmap() }
    Box(modifier.height(92.dp), contentAlignment = Alignment.Center) {
        MapAppearanceScene(palette, Modifier.fillMaxSize())
        Image(bitmap, null, Modifier.size(36.dp))
    }
}
