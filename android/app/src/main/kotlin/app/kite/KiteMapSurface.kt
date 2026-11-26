package app.kite

import android.content.Context
import android.view.MotionEvent
import android.graphics.PointF
import android.view.HapticFeedbackConstants
import android.os.SystemClock
import android.util.Log
import android.widget.FrameLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.mapbox.maps.MapView as StandardMapView
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView

/** Keeps touch delivery on the visible map while the Standard renderer loads or falls back. */
class KiteMapSurface(
    context: Context, private val lifecycle: Lifecycle, private val kite: KiteMap, private val scenery: MapboxScenery,
    private val model: AppModel,
) : FrameLayout(context) {
    private val classic = MapView(context, MapLibreMapOptions.createFromAttributes(context).textureMode(true))
    private var standard: StandardMapView? = null
    private var activeToken: String? = null
    private var useStandard = false
    private var destroyed = false
    private var requestedLook: MapboxScenery.Look? = null
    private val startStandard = Runnable {
        val token = activeToken ?: return@Runnable
        val look = requestedLook ?: return@Runnable
        if (destroyed || standard != null) return@Runnable
        try {
            scenery.setLook(look)
            standard = scenery.attach(context, token).also { map ->
                map.alpha = 0f
                addView(map, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            }
        } catch (e: RuntimeException) {
            Log.w("KiteMapSurface", "Mapbox initialization failed", e)
            scenery.abortAttach()
            standard = null
        }
    }
    private val observer = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_START -> classic.onStart()
            Lifecycle.Event.ON_RESUME -> {
                classic.onResume()
                postOnAnimation {
                    if (!destroyed && !model.glide.mapTouched) model.glide.restoreFollowingCamera()
                }
            }
            Lifecycle.Event.ON_PAUSE -> classic.onPause()
            Lifecycle.Event.ON_STOP -> classic.onStop()
            Lifecycle.Event.ON_DESTROY -> dispose()
            else -> Unit
        }
    }

    init {
        addView(classic, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        classic.onCreate(null)
        classic.setMaximumFps(60)
        kite.attach(classic)
        lifecycle.addObserver(observer)
    }

    fun show(token: String?, ready: Boolean, look: MapboxScenery.Look) {
        if (destroyed) return
        requestedLook = look
        if (token != activeToken) {
            removeCallbacks(startStandard)
            removeStandard()
            activeToken = token
            // Let the fallback attach and produce its first frame before the second renderer starts.
            if (token != null) postDelayed(startStandard, 240)
        }
        val showStandard = ready && standard != null
        val rendererChanged = useStandard != showStandard
        if (rendererChanged) classic.setMaximumFps(if (showStandard) 4 else 60)
        useStandard = showStandard
        standard?.alpha = if (showStandard) 1f else 0f
        classic.alpha = if (showStandard) 0f else 1f
        if (rendererChanged) {
            kite.refreshScenery()
            model.glide.restoreFollowingCamera()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != 0 && h != 0) postOnAnimation {
            if (!destroyed) {
                val density = resources.displayMetrics.density
                model.glide.resize(w / density, h / density)
                model.glide.restoreFollowingCamera()
            }
        }
    }

    private data class Hold(val id1: Int, val id2: Int, val p1: PointF, val p2: PointF, val downAt: Long)
    private var hold: Hold? = null
    private var measured = false
    private val finishHold = Runnable {
        val h = hold ?: return@Runnable
        hold = null
        fun at(p: PointF) = if (useStandard) scenery.pointAt(p.x, p.y) else kite.pointAt(p.x, p.y)
        val a = at(h.p1) ?: return@Runnable
        val b = at(h.p2) ?: return@Runnable
        model.mapNavigator?.following = false
        model.browseFollowing = false
        model.measurement = a to b
        measured = true
        val event = MotionEvent.obtain(h.downAt, SystemClock.uptimeMillis(), MotionEvent.ACTION_CANCEL, h.p1.x, h.p1.y, 0)
        try { deliver(event) } finally { event.recycle() }
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    private fun cancelHold() {
        hold = null
        removeCallbacks(finishHold)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> measured = false
            MotionEvent.ACTION_POINTER_DOWN -> {
                cancelHold()
                if (event.pointerCount == 2 && !measured && !model.placingStop && model.streetView == null) {
                    hold = Hold(event.getPointerId(0), event.getPointerId(1),
                        PointF(event.getX(0), event.getY(0)), PointF(event.getX(1), event.getY(1)), event.downTime)
                    postDelayed(finishHold, 550L)
                }
            }
            MotionEvent.ACTION_MOVE -> hold?.let { h ->
                val slop = 9f * resources.displayMetrics.density
                fun moved(id: Int, from: PointF): Boolean {
                    val i = event.findPointerIndex(id)
                    return i < 0 || kotlin.math.abs(event.getX(i) - from.x) > slop || kotlin.math.abs(event.getY(i) - from.y) > slop
                }
                if (moved(h.id1, h.p1) || moved(h.id2, h.p2)) cancelHold()
            }
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> cancelHold()
        }
        return measured || deliver(event)
    }

    private fun deliver(event: MotionEvent): Boolean {
        val target = if (useStandard) standard else null
        if (target == null) return classic.dispatchTouchEvent(event)
        scenery.touch(event)
        return target.dispatchTouchEvent(event)
    }

    private fun removeStandard() {
        val map = standard ?: return
        scenery.detach(map)
        removeView(map)
        map.onDestroy()
        standard = null
    }

    fun dispose() {
        if (destroyed) return
        destroyed = true
        cancelHold()
        lifecycle.removeObserver(observer)
        removeCallbacks(startStandard)
        kite.detach(classic)
        removeStandard()
        classic.onDestroy()
    }
}
