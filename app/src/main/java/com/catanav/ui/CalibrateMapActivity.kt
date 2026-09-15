package com.catanav.ui

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.catanav.CataNavApp
import com.catanav.R
import com.catanav.databinding.ActivityCalibrateMapBinding
import com.catanav.domain.CalibrationMath
import com.catanav.domain.ImagePoint
import com.catanav.map.FileMapSource
import com.catanav.map.MapView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import java.io.File

/**
 * Two-point map calibration:
 *  1. tap point A (crosshair, zoomable)   2. tap point B
 *  3. enter the real A-B distance in meters
 *  4. rotate the arrow until it points to TRUE NORTH as drawn on the map, confirm
 *  5. save -> metersPerPixel = distance / pixelDistance, northOffsetDegrees per the
 *     locked convention (degrees clockwise from image-up to north).
 * Saving goes through the version model: a NEW MapVersion + MapCalibration, so
 * recalibrating never touches trips recorded on earlier versions.
 */
class CalibrateMapActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MAP_ID = "map_id"
    }

    private enum class Step { PICK_A, PICK_B, NORTH }

    private lateinit var binding: ActivityCalibrateMapBinding
    private val locator get() = CataNavApp.instance.locator

    private var mapId: Long = -1
    private var step = Step.PICK_A
    private var pointA: ImagePoint? = null
    private var pointB: ImagePoint? = null
    private var distanceMeters: Double? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCalibrateMapBinding.inflate(layoutInflater)
        setContentView(binding.root)

        mapId = intent.getLongExtra(EXTRA_MAP_ID, -1)
        lifecycleScope.launch {
            val map = locator.mapRepository.map(mapId) ?: run { finish(); return@launch }
            binding.calibrateMap.setMapSource(
                FileMapSource(File(map.imageUri), map.widthPx, map.heightPx),
            )
        }

        binding.calibrateMap.onMapTap = { x, y ->
            if (step == Step.PICK_A || step == Step.PICK_B) {
                binding.calibrateMap.pendingAnchor = x to y
                binding.btnNext.isEnabled = true
            }
        }

        binding.northSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (step == Step.NORTH) updateNorthArrow(progress.toDouble())
            }
            override fun onStartTrackingTouch(sb: SeekBar?) = Unit
            override fun onStopTrackingTouch(sb: SeekBar?) = Unit
        })

        binding.btnNext.setOnClickListener { onNext() }
        binding.btnCancel.setOnClickListener { finish() }
        applyStep()
    }

    private fun applyStep() {
        binding.northSeek.visibility = if (step == Step.NORTH) View.VISIBLE else View.GONE
        binding.calibrateMap.crosshairMode = step != Step.NORTH
        binding.instruction.text = getString(
            when (step) {
                Step.PICK_A -> R.string.calibrate_pick_a
                Step.PICK_B -> R.string.calibrate_pick_b
                Step.NORTH -> R.string.calibrate_north
            },
        )
        binding.btnNext.text = getString(
            if (step == Step.NORTH) R.string.calibrate_save else R.string.calibrate_next,
        )
        binding.btnNext.isEnabled = step == Step.NORTH
        if (step == Step.NORTH) updateNorthArrow(binding.northSeek.progress.toDouble())
    }

    private fun onNext() {
        when (step) {
            Step.PICK_A -> {
                val (x, y) = binding.calibrateMap.pendingAnchor ?: return
                pointA = ImagePoint(x, y)
                markPicked()
                step = Step.PICK_B
                applyStep()
            }
            Step.PICK_B -> {
                val (x, y) = binding.calibrateMap.pendingAnchor ?: return
                val b = ImagePoint(x, y)
                val a = pointA ?: return
                val px = Math.hypot(b.xPx - a.xPx, b.yPx - a.yPx)
                if (px < CalibrationMath.MIN_PIXEL_DISTANCE) {
                    Toast.makeText(this, R.string.calibrate_points_too_close, Toast.LENGTH_LONG).show()
                    return
                }
                pointB = b
                markPicked()
                askDistance()
            }
            Step.NORTH -> save()
        }
    }

    private fun markPicked() {
        val markers = mutableListOf<MapView.Marker>()
        pointA?.let { markers.add(MapView.Marker(it.xPx, it.yPx, "A")) }
        pointB?.let { markers.add(MapView.Marker(it.xPx, it.yPx, "B")) }
        binding.calibrateMap.anchors = markers
        binding.calibrateMap.pendingAnchor = null
        binding.btnNext.isEnabled = false
    }

    private fun askDistance() {
        val input = EditText(this).apply {
            hint = getString(R.string.calibrate_distance_hint)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.calibrate_distance_hint)
            .setView(input)
            .setCancelable(false)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val d = input.text.toString().replace(',', '.').toDoubleOrNull()
                if (d == null || d <= 0) {
                    Toast.makeText(this, R.string.calibrate_distance_hint, Toast.LENGTH_SHORT).show()
                    askDistance()
                } else {
                    distanceMeters = d
                    step = Step.NORTH
                    applyStep()
                }
            }
            .setNegativeButton(R.string.btn_cancel) { _, _ ->
                step = Step.PICK_B
                applyStep()
            }
            .show()
    }

    /**
     * The arrow is drawn at the midpoint of A-B. The seek value is the arrow angle on
     * SCREEN; subtracting the view's display rotation converts it to the image-space
     * angle, which IS the stored northOffsetDegrees (locked convention).
     */
    private fun updateNorthArrow(seekScreenDeg: Double) {
        val a = pointA ?: return
        val b = pointB ?: return
        val vpRotation = binding.calibrateMap.viewport?.rotationDeg ?: 0.0
        val arrowImageDeg = CalibrationMath.northOffsetFromArrow(seekScreenDeg, vpRotation)
        binding.calibrateMap.setPosition(
            (a.xPx + b.xPx) / 2.0,
            (a.yPx + b.yPx) / 2.0,
            arrowImageDeg,
            0.0,
        )
        binding.instruction.text =
            getString(R.string.calibrate_north) + "  (${arrowImageDeg.toInt()}°)"
    }

    private fun save() {
        val a = pointA ?: return
        val b = pointB ?: return
        val d = distanceMeters ?: return
        val vpRotation = binding.calibrateMap.viewport?.rotationDeg ?: 0.0
        val northOffset =
            CalibrationMath.northOffsetFromArrow(binding.northSeek.progress.toDouble(), vpRotation)
        val mpp = CalibrationMath.metersPerPixel(a, b, d)
        lifecycleScope.launch {
            locator.mapRepository.calibrate(mapId, mpp, northOffset)
            Toast.makeText(
                this@CalibrateMapActivity,
                getString(R.string.calibrate_saved, mpp),
                Toast.LENGTH_LONG,
            ).show()
            finish()
        }
    }
}
