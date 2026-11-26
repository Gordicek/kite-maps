package app.kite

import android.app.Activity
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.view.Surface
import app.kite.core.Geo
import kotlin.math.atan2
import kotlin.math.hypot

internal class PhoneCompass(private val activity: Activity, private val changed: (Double) -> Unit) : SensorEventListener {
    private val manager = activity.getSystemService(SensorManager::class.java)
    private val sensor = manager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        ?: manager?.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)
    private val matrix = FloatArray(9)
    private val adjusted = FloatArray(9)
    private val gravity = FloatArray(3)
    private val magnetic = FloatArray(3)
    private var hasGravity = false
    private var hasMagnetic = false
    private var lastAt = 0L
    private var last = Double.NaN
    private var declination = 0f
    private var declinationAt = 0L

    fun start() {
        val sensors = manager ?: return
        if (sensor != null && sensors.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)) return
        sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        sensors.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
    }
    fun stop() {
        manager?.unregisterListener(this)
        last = Double.NaN
        hasGravity = false
        hasMagnetic = false
    }

    @Suppress("DEPRECATION")
    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                for (i in gravity.indices) gravity[i] = if (hasGravity) 0.8f * gravity[i] + 0.2f * event.values[i] else event.values[i]
                hasGravity = true
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                event.values.copyInto(magnetic, endIndex = 3)
                hasMagnetic = true
            }
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastAt < 62) return
        val dt = (now - lastAt).coerceIn(62L, 400L)
        lastAt = now
        if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR || event.sensor.type == Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR) {
            SensorManager.getRotationMatrixFromVector(matrix, event.values)
        } else if (!hasGravity || !hasMagnetic || !SensorManager.getRotationMatrix(matrix, null, gravity, magnetic)) return
        if (declinationAt == 0L || now - declinationAt > 60_000) {
            LocationService.last?.let {
                declination = GeomagneticField(it.lat.toFloat(), it.lng.toFloat(), it.altitude.toFloat(), System.currentTimeMillis()).declination
                declinationAt = now
            }
        }
        val (x, y) = when (activity.windowManager.defaultDisplay.rotation) {
            Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
            Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
            Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
            else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
        }
        if (!SensorManager.remapCoordinateSystem(matrix, x, y, adjusted)) return
        val azimuth = displayAzimuth(adjusted[1], adjusted[4], -adjusted[2], -adjusted[5]) ?: return
        val heading = (azimuth + declination + 360) % 360
        val eased = if (last.isNaN()) heading else
            (last + NavGlide.shortestTurn(last, heading) * (1 - kotlin.math.exp(-dt / 90.0)) + 360) % 360
        if (last.isNaN() || Geo.diff(last, eased) >= 0.8) {
            last = eased
            changed(eased)
        }
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    companion object {
        internal fun displayAzimuth(topEast: Float, topNorth: Float, faceEast: Float, faceNorth: Float): Double? {
            // The top edge defines heading when flat. Near vertical, use the viewing direction
            // through the back of the phone so the top edge does not flip the compass by 180°.
            val top = hypot(topEast, topNorth)
            val blend = ((0.49f - top) / 0.30f).coerceIn(0f, 1f)
            val east = topEast * (1 - blend) + faceEast * blend
            val north = topNorth * (1 - blend) + faceNorth * blend
            if (hypot(east, north) < 0.0001f) return null
            return Math.toDegrees(atan2(east.toDouble(), north.toDouble()))
        }
    }
}
