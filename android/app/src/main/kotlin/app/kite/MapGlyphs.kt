package app.kite

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.RectF

/** The selected glyphs from Lucide and Material Icons Rounded, packed at build time as alpha masks. */
internal object MapGlyphs {
    private const val CELL = 96
    private const val COLUMNS = 8
    private val assets get() = KiteApp.app.assets
    private val indices by lazy {
        assets.open("map-icons/glyphs.txt").bufferedReader().useLines { lines ->
            lines.mapIndexed { index, name -> name to index }.toMap()
        }
    }
    private val atlases = mutableMapOf<String, Bitmap>()

    fun draw(theme: MarkerTheme, canvas: Canvas, vectorName: String, bounds: RectF, tint: Int): Boolean {
        val pack = theme.assetPack ?: return false
        val index = indices[vectorName.substringAfterLast('.')] ?: return false
        val atlas = atlases.getOrPut(pack) {
            assets.open("map-icons/$pack/atlas.png").use { requireNotNull(BitmapFactory.decodeStream(it)) }
        }
        val x = index % COLUMNS * CELL
        val y = index / COLUMNS * CELL
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = PorterDuffColorFilter(tint, PorterDuff.Mode.SRC_IN)
        }
        canvas.drawBitmap(atlas, Rect(x, y, x + CELL, y + CELL), bounds, paint)
        return true
    }
}
