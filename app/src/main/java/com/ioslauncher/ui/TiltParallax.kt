package com.ioslauncher.ui

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

/**
 * Device-tilt reader for the iOS-style parallax and glass lensing. Reports tilt relative to how the
 * phone is being held (a slowly adapting baseline), in -1..1 on each axis.
 */
class TiltParallax(context: Context, private val onTilt: (Float, Float) -> Unit) : SensorEventListener {
    private val sm = context.getSystemService(SensorManager::class.java)
    private val sensor = sm.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private var baseX = Float.NaN; private var baseY = 0f
    private var x = 0f; private var y = 0f
    private var running = false

    fun start() {
        if (running || sensor == null) return
        running = true
        baseX = Float.NaN
        sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
    }

    fun stop() {
        if (!running) return
        running = false
        sm.unregisterListener(this)
        // Keep the current offset: snapping to 0 here moved the whole grid while an app was opening.
        // On the next start the baseline resets, so the offset eases back to neutral smoothly.
    }

    override fun onSensorChanged(e: SensorEvent) {
        val gx = e.values[0]; val gy = e.values[1]
        if (baseX.isNaN()) { baseX = gx; baseY = gy }
        // Baseline follows slowly so holding the phone at any angle becomes "neutral".
        baseX += (gx - baseX) * 0.015f; baseY += (gy - baseY) * 0.015f
        val tx = ((gx - baseX) / 2.5f).coerceIn(-1f, 1f)
        val ty = ((gy - baseY) / 2.5f).coerceIn(-1f, 1f)
        x += (tx - x) * 0.18f; y += (ty - y) * 0.18f
        onTilt(-x, y)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
