package com.catanav.ui

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.catanav.CataNavApp
import com.catanav.R
import com.catanav.databinding.ActivityCalibrationBinding
import com.catanav.pdr.StepDetector
import kotlinx.coroutines.launch
import kotlin.math.sqrt

/**
 * DEVICE-profile calibration (never map calibration): walk a measured 20-50 m straight
 * distance above ground at normal pace, enter the distance, the app derives
 * step length = distance / steps. Re-runnable from Settings. This screen + manual
 * re-anchoring fully replace any GPS calibration path.
 */
class StepCalibrationActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var binding: ActivityCalibrationBinding
    private val detector = StepDetector()
    private var counting = false
    private var steps = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCalibrationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnWalk.setOnClickListener {
            if (counting) stopCounting() else startCounting()
        }
        binding.btnSave.setOnClickListener { save() }
        updateCount()
    }

    private fun startCounting() {
        val sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sm.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        if (sensor == null) {
            Toast.makeText(this, R.string.status_manual_mode, Toast.LENGTH_LONG).show()
            return
        }
        detector.reset()
        steps = 0
        counting = true
        binding.btnWalk.text = getString(R.string.calibration_stop)
        sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
        updateCount()
    }

    private fun stopCounting() {
        counting = false
        binding.btnWalk.text = getString(R.string.calibration_start)
        (getSystemService(Context.SENSOR_SERVICE) as SensorManager).unregisterListener(this)
    }

    private fun save() {
        if (steps == 0) {
            Toast.makeText(this, R.string.calibration_need_steps, Toast.LENGTH_SHORT).show()
            return
        }
        val distance = binding.inputDistance.text.toString().replace(',', '.').toDoubleOrNull()
        if (distance == null || distance < 5.0 || distance > 200.0) {
            binding.inputDistance.error = getString(R.string.calibration_distance_hint)
            return
        }
        val stepLen = (distance / steps).coerceIn(0.3, 1.5)
        lifecycleScope.launch {
            CataNavApp.instance.locator.settings.setStepLengthMeters(stepLen)
            CataNavApp.instance.locator.tripSession.engine.stepLengthMeters = stepLen
            Toast.makeText(
                this@StepCalibrationActivity,
                getString(R.string.calibration_result, stepLen),
                Toast.LENGTH_LONG,
            ).show()
            finish()
        }
    }

    private fun updateCount() {
        binding.stepCount.text = getString(R.string.calibration_steps_counted, steps)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!counting || event.sensor.type != Sensor.TYPE_LINEAR_ACCELERATION) return
        val m = sqrt(
            (event.values[0] * event.values[0] +
                event.values[1] * event.values[1] +
                event.values[2] * event.values[2]).toDouble(),
        )
        if (detector.onSample(event.timestamp / 1_000_000, m)) {
            steps++
            runOnUiThread { updateCount() }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onPause() {
        if (counting) stopCounting()
        super.onPause()
    }
}
