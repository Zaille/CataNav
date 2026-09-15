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
import com.catanav.data.CalibrationTestResultEntity
import com.catanav.databinding.ActivityCalibrationTestBinding
import com.catanav.domain.CalibrationMath
import com.catanav.pdr.StepDetector
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.sqrt

/**
 * Calibration TEST: walk a known distance, compare it with the PDR estimate
 * (steps x calibrated step length) and store the % error in the device profile's test
 * history. Validates the whole device setup — not just the scale math.
 */
class CalibrationTestActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var binding: ActivityCalibrationTestBinding
    private val locator get() = CataNavApp.instance.locator
    private val detector = StepDetector()
    private var counting = false
    private var steps = 0
    private var stepLengthM = 0.7

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCalibrationTestBinding.inflate(layoutInflater)
        setContentView(binding.root)

        lifecycleScope.launch {
            stepLengthM = locator.settings.stepLengthMeters.first()
            binding.testIntro.text = getString(R.string.test_intro, stepLengthM)
            renderHistory()
        }

        binding.btnTestWalk.setOnClickListener {
            if (counting) stopCounting() else startCounting()
        }
        binding.btnTestSave.setOnClickListener { evaluate() }
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
        binding.btnTestWalk.text = getString(R.string.calibration_stop)
        sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
        updateCount()
    }

    private fun stopCounting() {
        counting = false
        binding.btnTestWalk.text = getString(R.string.calibration_start)
        (getSystemService(Context.SENSOR_SERVICE) as SensorManager).unregisterListener(this)
    }

    private fun evaluate() {
        if (counting) stopCounting()
        if (steps == 0) {
            Toast.makeText(this, R.string.calibration_need_steps, Toast.LENGTH_SHORT).show()
            return
        }
        val known = binding.testDistance.text.toString().replace(',', '.').toDoubleOrNull()
        if (known == null || known <= 0) {
            binding.testDistance.error = getString(R.string.test_known_distance_hint)
            return
        }
        val estimated = CalibrationMath.estimatedDistance(steps, stepLengthM)
        val errorPct = CalibrationMath.testErrorPercent(known, estimated)
        binding.testResult.text = getString(R.string.test_result, estimated, known, errorPct)
        lifecycleScope.launch {
            locator.mapRepository.recordCalibrationTest(
                CalibrationTestResultEntity(
                    timestampMs = System.currentTimeMillis(),
                    knownDistanceM = known,
                    estimatedDistanceM = estimated,
                    errorPercent = errorPct,
                    stepLengthUsedM = stepLengthM,
                ),
            )
            renderHistory()
        }
    }

    private suspend fun renderHistory() {
        val history = locator.mapRepository.calibrationTestHistory()
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT)
        binding.testHistory.text = if (history.isEmpty()) {
            getString(R.string.test_history_empty)
        } else {
            history.take(10).joinToString("\n") { r ->
                String.format(
                    Locale.ROOT,
                    "%s — %.1f m vs %.1f m (%+.1f%%)",
                    fmt.format(Date(r.timestampMs)),
                    r.estimatedDistanceM, r.knownDistanceM, r.errorPercent,
                )
            }
        }
    }

    private fun updateCount() {
        binding.testStepCount.text = getString(R.string.calibration_steps_counted, steps)
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
