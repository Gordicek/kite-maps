package app.kite.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.kite.*
import app.kite.core.*
import kotlinx.coroutines.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Street View shares Kite's map, including its provider, layers, and touch controls. */
@Composable
fun StreetViewScreen(model: AppModel, req: StreetViewRequest, mapHeight: androidx.compose.ui.unit.Dp, showMapbox: Boolean) {
    val context = LocalContext.current
    var pano by remember(req.pano.id) { mutableStateOf(req.pano) }
    var heading by remember(req.pano.id) { mutableDoubleStateOf(req.heading) }
    var changing by remember { mutableStateOf(false) }
    var failedStep by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<Job?>(null) }
    val visited = remember(req.pano.id) { linkedMapOf(req.pano.id to req.pano) }
    val scope = rememberCoroutineScope()
    val renderer = remember(pano.id) { PanoRenderer(pano, heading) }

    fun move(around: LatLng, link: StreetView.Link? = null) {
        pending?.cancel()
        changing = true
        failedStep = false
        pending = scope.launch {
            val result = if (link != null) {
                visited[link.id] ?: run {
                    val nearby = StreetView.find(link.at, 12)
                    if (nearby?.id == link.id) nearby else StreetView.byId(link.id)
                        ?: nearby?.takeIf { it.location.distanceTo(link.at) < 14 }
                }
            } else StreetView.find(around, 55)?.takeIf { it.location.distanceTo(around) <= 90 }
            if (result != null) {
                visited[result.id] = result
                if (visited.size > 14) visited.remove(visited.keys.first())
                if (result.id != pano.id) {
                    heading = link?.bearing ?: Geo.bearing(pano.location, result.location)
                    pano = result
                } else model.centerStreetView(pano.location)
            } else failedStep = true
            changing = false
        }
    }

    LaunchedEffect(model.streetViewTap) {
        val pick = model.streetViewTap?.first ?: return@LaunchedEffect
        val link = pano.links.minByOrNull { it.at.distanceTo(pick) }?.takeIf { it.at.distanceTo(pick) < 12 }
        move(pick, link)
    }
    LaunchedEffect(pano.id) {
        delay(65)
        model.centerStreetView(pano.location, keepZoom = pano.id != req.pano.id)
    }
    val frameHeading = (heading / 2.0).roundToInt() * 2
    LaunchedEffect(pano.id, frameHeading) { model.updateStreetViewFrame(pano, frameHeading.toDouble()) }

    androidx.activity.compose.BackHandler(onBack = model::closeStreetView)
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().weight(1f).background(Color.Black)) {
            key(pano.id) {
                AndroidView(modifier = Modifier.fillMaxSize(), factory = { c ->
                    GLSurfaceView(c).apply {
                        setEGLContextClientVersion(2)
                        preserveEGLContextOnPause = true
                        setRenderer(renderer)
                        renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
                        renderer.view = this
                    }
                }, onReset = null, onRelease = { it.onPause() })
                Box(Modifier.fillMaxSize().pointerInput(renderer) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        heading = renderer.gesture(pan.x, pan.y, zoom, size.width.toFloat())
                    }
                })
                DisposableEffect(renderer) { onDispose { renderer.dispose() } }
            }
            if (!renderer.ready || changing) Box(Modifier.align(Alignment.Center), contentAlignment = Alignment.Center) {
                if (renderer.loading || changing) CircularProgressIndicator(color = Color.White)
                else Text(localizeLabel("Panorama unavailable"), color = Color.White)
            }
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp).padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilledTonalIconButton(model::closeStreetView) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close Street View")
                }
                Column(Modifier.weight(1f).clip(RoundedCornerShape(16.dp))
                    .background(Color.Black.copy(alpha = 0.72f)).padding(horizontal = 12.dp, vertical = 7.dp)) {
                    Text(req.name, fontSize = KType.subheadline, fontWeight = FontWeight.SemiBold, color = Color.White,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(pano.credit?.takeIf(String::isNotBlank) ?: "Street View · Google", fontSize = KType.caption,
                        color = Color.White.copy(alpha = 0.85f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                FilledTonalIconButton({
                    context.open("https://www.google.com/maps/@?api=1&map_action=pano&pano=${pano.id}&heading=${heading.toInt()}")
                }) { Icon(Icons.AutoMirrored.Filled.OpenInNew, "Open in Google Maps") }
            }
            Row(Modifier.align(Alignment.BottomCenter).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val shown = listOf(0.0 to "Forward", -90.0 to "Left", 90.0 to "Right", 180.0 to "Back").mapNotNull { (delta, label) ->
                    val turn = (heading + delta + 360) % 360
                    pano.links.minByOrNull { Geo.diff(it.bearing, turn) }
                        ?.takeIf { Geo.diff(it.bearing, turn) <= 48 }?.let { label to it }
                }.distinctBy { it.second.id }
                for ((label, link) in shown) FilledTonalButton({ move(link.at, link) }, enabled = !changing,
                    contentPadding = PaddingValues(horizontal = 10.dp)) { Text(label) }
            }
        }
        // Only these controls consume touches. The map underneath gets pan, pinch, and tap.
        Box(Modifier.fillMaxWidth().height(mapHeight)) {
            Row(Modifier.align(Alignment.TopEnd).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                FilledTonalIconButton({ model.centerStreetView(pano.location) }) {
                    Icon(Icons.Filled.MyLocation, "Center panorama on map")
                }
                FilledTonalIconButton({ model.streetViewMapExpanded = !model.streetViewMapExpanded }) {
                    Icon(if (model.streetViewMapExpanded) Icons.Filled.CloseFullscreen else Icons.Filled.OpenInFull,
                        if (model.streetViewMapExpanded) "Shrink map" else "Enlarge map")
                }
            }
            if (failedStep) Text(localizeLabel("No Street View nearby"), color = primaryText,
                modifier = Modifier.align(Alignment.TopStart).padding(10.dp).clip(CircleShape).background(interfaceSheet)
                    .padding(horizontal = 10.dp, vertical = 6.dp))
            if (showMapbox) MapboxAttribution(Modifier.align(Alignment.BottomStart).navigationBarsPadding().padding(8.dp))
        }
    }
}

/**
 * Street View drawn by the app itself: the panorama's tiles stitched into one picture, then each
 * pixel on screen looks up the right spot of it (no sphere needed). A low-detail image comes
 * first, then a sharp one.
 */
class PanoRenderer(private val pano: StreetView.Panorama, startHeading: Double) : GLSurfaceView.Renderer {
    var view: GLSurfaceView? = null
    @Volatile private var heading = startHeading
    @Volatile private var pitch = 0.0
    @Volatile private var fov = 80.0
    @Volatile private var pending: Bitmap? = null
    var loading by mutableStateOf(true)
        private set
    var ready by mutableStateOf(false)
        private set
    private var program = 0
    private var texture = 0
    private var hasImage = false
    private var aspect = 1f
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val quad = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0)
    }

    init {
        scope.launch {
            for (zoom in listOf(2, 3)) {
                // The sharper one only replaces the first if every tile came: a dropped signal
                // mustn't turn a working view half black.
                val img = stitch(zoom, complete = zoom > 2) ?: continue
                synchronized(this@PanoRenderer) {
                    if (!isActive) {
                        img.recycle()
                        return@launch
                    }
                    pending?.recycle()
                    pending = img
                }
                view?.requestRender()
                withContext(Dispatchers.Main) { ready = true }
            }
            withContext(Dispatchers.Main) { loading = false }
        }
    }

    fun dispose() {
        scope.cancel()
        synchronized(this) {
            pending?.recycle()
            pending = null
        }
        view = null
    }

    /** One screen width turns by about the field of view; a drag down looks up, like the iPhone app. */
    fun gesture(dx: Float, dy: Float, zoom: Float, width: Float): Double {
        val perPx = fov / width.coerceAtLeast(1f)
        heading = ((heading - dx * perPx) % 360 + 360) % 360
        pitch = (pitch + dy * perPx).coerceIn(-80.0, 80.0)
        if (zoom != 1f) fov = (fov / zoom).coerceIn(30.0, 100.0)
        view?.requestRender()
        return heading
    }

    /** All of the panorama's tiles at [zoom], drawn into one picture. */
    private suspend fun stitch(zoom: Int, complete: Boolean = false): Bitmap? = coroutineScope {
        val (w, h) = pano.size(zoom)
        if (w.toLong() * h > 10_000_000L) return@coroutineScope null
        val size = pano.tileSize
        val cols = ceil(w / size.toDouble()).toInt()
        val rows = ceil(h / size.toDouble()).toInt()
        val tiles = (0 until rows).flatMap { y -> (0 until cols).map { x -> Triple(x, y, async {
            Http.getOrNull(StreetView.tileUrl(pano, x, y, zoom), StreetView.imageHeaders)?.takeIf { it.ok }?.body?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        }) } }.mapNotNull { (x, y, d) -> d.await()?.let { Triple(x, y, it) } }
        if (tiles.isEmpty() || (complete && tiles.size < rows * cols)) {
            tiles.forEach { it.third.recycle() }
            return@coroutineScope null
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(out)
        cv.drawColor(android.graphics.Color.BLACK)
        for ((x, y, b) in tiles) { cv.drawBitmap(b, null, Rect(x * size, y * size, x * size + size, y * size + size), null); b.recycle() }
        out
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        program = link(VERTEX, FRAGMENT)
        val t = IntArray(1); GLES20.glGenTextures(1, t, 0); texture = t[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        hasImage = false
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        aspect = width.toFloat() / height.coerceAtLeast(1)
    }

    override fun onDrawFrame(gl: GL10?) {
        val upload = synchronized(this) { pending.also { pending = null } }
        upload?.let { b ->
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, b, 0)
            b.recycle()
            hasImage = true
        }
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        if (!hasImage) return
        GLES20.glUseProgram(program)
        val pos = GLES20.glGetAttribLocation(program, "pos")
        GLES20.glEnableVertexAttribArray(pos)
        GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 0, quad)
        // Half the field of view across the shorter side, like a camera's.
        val t = Math.tan(Math.toRadians(fov / 2)).toFloat()
        val tx = if (aspect >= 1) t * aspect else t
        val ty = if (aspect >= 1) t else t / aspect
        GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "span"), tx, ty)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "yaw"), Math.toRadians(heading - pano.heading).toFloat())
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "pitch"), Math.toRadians(pitch).toFloat())
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "pano"), 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun link(v: String, f: String): Int {
        fun shader(type: Int, src: String) = GLES20.glCreateShader(type).also { GLES20.glShaderSource(it, src); GLES20.glCompileShader(it) }
        return GLES20.glCreateProgram().also { p ->
            GLES20.glAttachShader(p, shader(GLES20.GL_VERTEX_SHADER, v))
            GLES20.glAttachShader(p, shader(GLES20.GL_FRAGMENT_SHADER, f))
            GLES20.glLinkProgram(p)
        }
    }

    companion object {
        private const val VERTEX = """
attribute vec2 pos;
varying vec2 ndc;
void main() { ndc = pos; gl_Position = vec4(pos, 0.0, 1.0); }
"""
        // The ray through each pixel, turned by where you look, gives a compass heading and a height:
        // the panorama's own heading sits in the middle of the picture.
        private const val FRAGMENT = """
precision highp float;
varying vec2 ndc;
uniform sampler2D pano;
uniform vec2 span;
uniform float yaw;
uniform float pitch;
const float PI = 3.14159265;
void main() {
    vec3 d = normalize(vec3(ndc.x * span.x, ndc.y * span.y, 1.0));
    float cp = cos(pitch), sp = sin(pitch);
    d = vec3(d.x, d.y * cp + d.z * sp, -d.y * sp + d.z * cp);
    float lon = atan(d.x, d.z) + yaw;
    float lat = asin(clamp(d.y, -1.0, 1.0));
    vec2 uv = vec2(fract(lon / (2.0 * PI) + 0.5), 0.5 - lat / PI);
    gl_FragColor = texture2D(pano, uv);
}
"""
    }
}
