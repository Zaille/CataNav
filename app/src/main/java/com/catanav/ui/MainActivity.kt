package com.catanav.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.catanav.CataNavApp
import com.catanav.R
import com.catanav.data.ActiveMap
import com.catanav.data.AnchorEntity
import com.catanav.data.PointSource
import com.catanav.databinding.ActivityMainBinding
import com.catanav.databinding.DialogHeadingDialBinding
import com.catanav.domain.MapPoint
import com.catanav.map.FileMapSource
import com.catanav.map.MapView
import com.catanav.pdr.HeadingConfidence
import com.catanav.pdr.SensorHub
import com.catanav.service.TrackingService
import com.catanav.trip.BatteryEstimator
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.hypot

/**
 * Navigation screen — works on ANY calibrated map version (passed by MapsActivity, or
 * resolved from the running session / first calibrated map).
 */
class MainActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MAP_ID = "map_id"
    }

    private enum class Mode { NORMAL, AWAITING_START_ANCHOR, AWAITING_REANCHOR }

    private lateinit var binding: ActivityMainBinding
    private val locator get() = CataNavApp.instance.locator
    private val session get() = locator.tripSession

    private var boundMap: ActiveMap? = null
    private var namedAnchors: List<AnchorEntity> = emptyList()
    private var mode = Mode.NORMAL
    private var pendingAnchor: Pair<Double, Double>? = null
    private var pendingAnchorName: String? = null
    private var retraceOn = false
    private var retraceToAnchorOnly = false
    private var redMode = false
    private var lastHeadingReliable = true
    private var centeredOnce = false
    private var isMeasuring = false
    private var measurePointA: Pair<Double, Double>? = null
    private var measurePointB: Pair<Double, Double>? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            // Denials are allowed: SensorHub degrades to manual mode; the status
            // line tells the user their capability level.
            pendingStartAfterPermissions?.invoke()
            pendingStartAfterPermissions = null
        }
    private var pendingStartAfterPermissions: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        // Red mode must be applied before inflation; DataStore's first read is cheap.
        redMode = runBlocking { locator.settings.redMode.first() }
        if (redMode) setTheme(R.style.Theme_CataNav_Red)
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.mapView.redMode = redMode

        binding.mapView.onMapTap = { x, y ->
            if (isMeasuring) {
                val bound = boundMap
                if (bound != null) {
                    if (measurePointA == null) {
                        measurePointA = x to y
                        measurePointB = null
                        binding.mapView.measurePoints = listOf(x to y)
                        binding.measureResult.text = getString(R.string.measure_pick_second)
                    } else if (measurePointB == null) {
                        val a = measurePointA!!
                        val distPx = hypot(x - a.first, y - a.second)
                        val distMeters = distPx * bound.calibration.metersPerPixel
                        val resultStr = getString(R.string.measure_result, distMeters)
                        Toast.makeText(this@MainActivity, resultStr, Toast.LENGTH_SHORT).show()
                        exitMeasureMode()
                    }
                }
            } else if (mode != Mode.NORMAL) {
                pendingAnchor = x to y
                pendingAnchorName = null
                binding.mapView.pendingAnchor = x to y
                refreshButtons()
            }
        }

        binding.btnStartStop.setOnClickListener { onPrimaryButton() }
        binding.btnReanchor.setOnClickListener { onReanchorButton() }
        binding.btnRetrace.setOnClickListener { onRetraceButton() }
        binding.btnMenu.setOnClickListener { showMenu() }

        binding.headingDial.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) session.setManualHeading(progress.toDouble())
                binding.headingDialLabel.text =
                    getString(R.string.manual_heading_dial) + ": ${progress}°"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        binding.btnStepForward.setOnClickListener { session.manualStep(1) }
        binding.btnStepBack.setOnClickListener { session.manualStep(-1) }

        lifecycleScope.launch {
            if (!bindMap()) return@launch
            observeSession()
            maybeShowPrivacyNote()
            maybeOfferResume()
        }
    }

    /** Resolve and bind the map version this screen navigates on. */
    private suspend fun bindMap(): Boolean {
        val bound = when {
            session.isActive -> session.boundMap.value
            intent.hasExtra(EXTRA_MAP_ID) && intent.getLongExtra(EXTRA_MAP_ID, -1) >= 0 ->
                locator.mapRepository.activeMap(intent.getLongExtra(EXTRA_MAP_ID, -1))
            else -> locator.mapRepository.firstCalibratedMap()
        }
        if (bound == null) {
            // Gating: no calibrated map = no navigation. Clear state, never a crash.
            Toast.makeText(this, R.string.map_needs_calibration_message, Toast.LENGTH_LONG).show()
            startActivity(Intent(this, MapsActivity::class.java))
            finish()
            return false
        }
        boundMap = bound
        if (!session.isActive) session.bind(bound)
        binding.mapView.transformer = bound.transformer()
        // The raster was already copied into app-private storage at import/seed time.
        val file = File(bound.version.imageUri)
        binding.mapView.setMapSource(FileMapSource(file, bound.version.widthPx, bound.version.heightPx))
        title = bound.map.name
        namedAnchors = locator.mapRepository.namedAnchors(bound.version.id)
        return true
    }

    // ---- observers -----------------------------------------------------------------

    private fun observeSession() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    session.trail.collect { points ->
                        binding.mapView.setTrailMeters(points.map { MapPoint(it.xMeters, it.yMeters) })
                        renderAnchors(points.filter { it.source == PointSource.ANCHOR })
                        if (retraceOn) updateRetrace()
                    }
                }
                launch {
                    session.engine.position.collect { p ->
                        if (p == null) {
                            binding.mapView.clearPosition()
                            updateNavInfo()
                        } else {
                            binding.mapView.setPositionMeters(
                                MapPoint(p.xMeters, p.yMeters),
                                p.headingDeg,
                                session.uncertaintyRadiusMeters(),
                            )
                            updateNavInfo()
                            if (p.isAnchor || !centeredOnce) {
                                centeredOnce = true
                                binding.mapView.centerOn(binding.mapView.positionX, binding.mapView.positionY)
                            }
                        }
                    }
                }
                launch {
                    combine(
                        TrackingService.sensorHealth,
                        session.tripId,
                    ) { health, tripId -> health to tripId }.collect { (health, tripId) ->
                        updateStatusLine(health, tripId != null)
                        refreshButtons()
                    }
                }
                launch {
                    combine(
                        TrackingService.batteryPercent,
                        TrackingService.batteryEstimateMs,
                        session.tripId,
                    ) { pct, est, tripId -> Triple(pct, est, tripId) }.collect { (pct, est, tripId) ->
                        val show = tripId != null && pct != null &&
                            pct <= BatteryEstimator.LOW_BATTERY_THRESHOLD
                        binding.batteryLine.visibility = if (show) View.VISIBLE else View.GONE
                        if (show) {
                            val estText = est?.let { BatteryEstimator.formatDuration(it) } ?: "—"
                            binding.batteryLine.text = getString(R.string.battery_warning, pct, estText)
                        }
                    }
                }
                launch {
                    session.lastAnchorName.collect { updateNavInfo() }
                }
                launch {
                    locator.settings.redMode.collect { v ->
                        if (v != redMode) recreate()
                    }
                }
            }
        }
    }

    private fun renderAnchors(trailAnchors: List<com.catanav.data.TrackPointEntity>) {
        val markers = mutableListOf<Pair<MapPoint, String?>>()
        for (a in trailAnchors) markers.add(MapPoint(a.xMeters, a.yMeters) to null)
        for (n in namedAnchors) markers.add(MapPoint(n.xMeters, n.yMeters) to n.name)
        binding.mapView.setAnchorsMeters(markers)
    }

    /** Always-visible: last anchor (name if any), distance since anchor, ±uncertainty. */
    private fun updateNavInfo() {
        val p = session.engine.position.value
        if (!session.isActive || p == null) {
            binding.navInfo.visibility = View.GONE
            return
        }
        binding.navInfo.visibility = View.VISIBLE
        val anchorName = session.lastAnchorName.value ?: getString(R.string.nav_anchor_unnamed)
        binding.navInfo.text = getString(
            R.string.nav_info_line,
            anchorName,
            p.distanceSinceAnchorM,
            session.uncertaintyRadiusMeters(),
        )
    }

    private fun updateStatusLine(health: SensorHub.Health?, active: Boolean) {
        val text = when {
            !active -> getString(R.string.status_idle)
            health == null -> getString(R.string.status_idle)
            health.capability == SensorHub.Capability.NO_HEADING ->
                getString(R.string.status_no_heading)
            health.capability == SensorHub.Capability.MANUAL_MODE ->
                getString(R.string.status_manual_mode)
            else -> {
                val h = session.currentHeadingDeg
                val headingText = if (h.isNaN()) "—" else "${h.toInt()}°"
                val confText = when (health.headingConfidence) {
                    HeadingConfidence.HIGH -> getString(R.string.confidence_high)
                    HeadingConfidence.MEDIUM -> getString(R.string.confidence_medium)
                    HeadingConfidence.LOW -> getString(R.string.confidence_low)
                    HeadingConfidence.UNRELIABLE -> getString(R.string.confidence_unreliable)
                }
                getString(R.string.status_heading_line, headingText, confText)
            }
        }
        binding.statusLine.text = text

        val manualVisible = active && health?.capability == SensorHub.Capability.MANUAL_MODE
        binding.manualControls.visibility = if (manualVisible) View.VISIBLE else View.GONE

        // Figure-8 recalibration prompt on the reliable -> unreliable transition:
        // PDR keeps running; uncertainty grows faster; never silently trust a bad compass.
        val reliable = health?.headingReliable ?: true
        if (active && lastHeadingReliable && !reliable) {
            Snackbar.make(binding.root, R.string.recalibrate_prompt, Snackbar.LENGTH_LONG).show()
        }
        lastHeadingReliable = reliable
    }

    // ---- buttons -------------------------------------------------------------------

    private fun refreshButtons() {
        when (mode) {
            Mode.NORMAL -> {
                val active = session.isActive
                binding.btnStartStop.text =
                    getString(if (active) R.string.btn_stop_trip else R.string.btn_start_trip)
                binding.btnStartStop.isEnabled = true
                binding.btnReanchor.text = getString(R.string.btn_reanchor)
                binding.btnReanchor.isEnabled = active
                binding.btnRetrace.isEnabled = active
                binding.anchorHint.visibility = View.GONE
            }
            Mode.AWAITING_START_ANCHOR, Mode.AWAITING_REANCHOR -> {
                binding.btnStartStop.text = getString(R.string.btn_confirm_anchor)
                binding.btnStartStop.isEnabled = pendingAnchor != null
                binding.btnReanchor.text = getString(R.string.btn_cancel)
                binding.btnReanchor.isEnabled = true
                binding.btnRetrace.isEnabled = false
                binding.anchorHint.visibility = View.VISIBLE
                binding.anchorHint.text = getString(
                    if (mode == Mode.AWAITING_START_ANCHOR) R.string.start_anchor_hint
                    else R.string.anchor_hint,
                )
            }
        }
    }

    private fun onPrimaryButton() {
        when (mode) {
            Mode.NORMAL -> if (session.isActive) confirmStopTrip() else beginStartFlow()
            Mode.AWAITING_START_ANCHOR -> confirmAnchor(isStart = true)
            Mode.AWAITING_REANCHOR -> confirmAnchor(isStart = false)
        }
    }

    private fun onReanchorButton() {
        when (mode) {
            Mode.NORMAL -> beginReanchorFlow()
            else -> exitAnchorMode() // acts as Cancel
        }
    }

    private fun beginStartFlow() {
        val wanted = mutableListOf(Manifest.permission.ACTIVITY_RECOGNITION)
        if (Build.VERSION.SDK_INT >= 33) wanted += Manifest.permission.POST_NOTIFICATIONS
        pendingStartAfterPermissions = { enterAnchorMode(Mode.AWAITING_START_ANCHOR) }
        permissionLauncher.launch(wanted.toTypedArray())
    }

    /** Re-anchor: tap the map, or jump straight to a stored named anchor. */
    private fun beginReanchorFlow() {
        if (namedAnchors.isEmpty()) {
            enterAnchorMode(Mode.AWAITING_REANCHOR)
            return
        }
        val items = mutableListOf(getString(R.string.reanchor_tap_map))
        items.addAll(namedAnchors.map { it.name ?: "?" })
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.btn_reanchor)
            .setItems(items.toTypedArray()) { _, which ->
                if (which == 0) {
                    enterAnchorMode(Mode.AWAITING_REANCHOR)
                } else {
                    val anchor = namedAnchors[which - 1]
                    val t = boundMap?.transformer() ?: return@setItems
                    val px = t.mapToImage(MapPoint(anchor.xMeters, anchor.yMeters))
                    enterAnchorMode(Mode.AWAITING_REANCHOR)
                    pendingAnchor = px.xPx to px.yPx
                    pendingAnchorName = anchor.name
                    binding.mapView.pendingAnchor = px.xPx to px.yPx
                    binding.mapView.centerOn(px.xPx, px.yPx)
                    refreshButtons()
                }
            }
            .show()
    }

    private fun startMeasureDistance() {
        if (mode != Mode.NORMAL) {
            exitAnchorMode()
        }
        isMeasuring = true
        measurePointA = null
        measurePointB = null
        binding.mapView.measurePoints = emptyList()
        binding.measureResult.visibility = View.VISIBLE
        binding.measureResult.text = getString(R.string.measure_pick_first)
    }

    private fun exitMeasureMode() {
        isMeasuring = false
        measurePointA = null
        measurePointB = null
        binding.mapView.measurePoints = emptyList()
        binding.measureResult.visibility = View.GONE
    }

    private fun enterAnchorMode(m: Mode) {
        exitMeasureMode()
        mode = m
        pendingAnchor = null
        pendingAnchorName = null
        binding.mapView.pendingAnchor = null
        binding.mapView.crosshairMode = true
        refreshButtons()
    }

    private fun exitAnchorMode() {
        mode = Mode.NORMAL
        pendingAnchor = null
        pendingAnchorName = null
        binding.mapView.pendingAnchor = null
        binding.mapView.crosshairMode = false
        refreshButtons()
    }

    /** Optional anchor name + optional facing confirmation (never auto-corrected). */
    private fun confirmAnchor(isStart: Boolean) {
        val (x, y) = pendingAnchor ?: return
        val nameInput = EditText(this).apply {
            hint = getString(R.string.anchor_name_hint)
            setText(pendingAnchorName ?: "")
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.facing_prompt)
            .setView(nameInput)
            .setPositiveButton(R.string.facing_use_sensor) { _, _ ->
                applyAnchor(isStart, x, y, null, nameInput.text.toString().ifBlank { null })
            }
            .setNeutralButton(R.string.facing_set_manual) { _, _ ->
                showHeadingDial { deg ->
                    applyAnchor(isStart, x, y, deg, nameInput.text.toString().ifBlank { null })
                }
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun showHeadingDial(onPicked: (Double) -> Unit) {
        val dialog = DialogHeadingDialBinding.inflate(layoutInflater)
        dialog.dialLabel.text = "0°"
        dialog.dialSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                dialog.dialLabel.text = "${p}°"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) = Unit
            override fun onStopTrackingTouch(sb: SeekBar?) = Unit
        })
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.facing_prompt)
            .setView(dialog.root)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                onPicked(dialog.dialSeek.progress.toDouble())
            }
            .show()
    }

    private fun applyAnchor(
        isStart: Boolean,
        xPx: Double,
        yPx: Double,
        headingDeg: Double?,
        anchorName: String?,
    ) {
        val meters = binding.mapView.imageTapToMeters(xPx, yPx) ?: return
        lifecycleScope.launch {
            if (isStart) {
                val name = "Trip " + SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(Date())
                session.startTrip(name, meters, headingDeg, anchorName)
                startTrackingService()
            } else {
                val adjustment = session.reanchor(meters, headingDeg, anchorName)
                if (adjustment != null && kotlin.math.abs(adjustment.afterM - adjustment.beforeM) >= 0.005) {
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.reanchor_stride_adjusted, adjustment.beforeM, adjustment.afterM),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
            boundMap?.let { namedAnchors = locator.mapRepository.namedAnchors(it.version.id) }
            exitAnchorMode()
        }
    }

    private fun confirmStopTrip() {
        MaterialAlertDialogBuilder(this)
            .setMessage(R.string.btn_stop_trip)
            .setPositiveButton(R.string.btn_stop_trip) { _, _ ->
                lifecycleScope.launch {
                    session.stopTrip()
                    stopTrackingService()
                    retraceOn = false
                    binding.mapView.retraceTrail = emptyList()
                    refreshButtons()
                    updateNavInfo()
                }
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun onRetraceButton() {
        if (retraceOn) {
            retraceOn = false
            binding.mapView.retraceTrail = emptyList()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.btn_retrace)
            .setItems(
                arrayOf(getString(R.string.retrace_to_anchor), getString(R.string.retrace_to_start)),
            ) { _, which ->
                retraceOn = true
                retraceToAnchorOnly = which == 0
                updateRetrace()
                Snackbar.make(binding.root, R.string.retrace_on, Snackbar.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun updateRetrace() {
        binding.mapView.setRetraceMeters(
            session.retracePath(retraceToAnchorOnly).map { MapPoint(it.xMeters, it.yMeters) },
        )
    }

    // ---- service / lifecycle glue -----------------------------------------------------

    private fun startTrackingService() {
        val intent = Intent(this, TrackingService::class.java).setAction(TrackingService.ACTION_START)
        startForegroundService(intent)
    }

    private fun stopTrackingService() {
        startService(Intent(this, TrackingService::class.java).setAction(TrackingService.ACTION_STOP))
    }

    private fun maybeOfferResume() {
        lifecycleScope.launch {
            if (session.isActive) {
                if (!TrackingService.isRunning) startTrackingService()
                refreshButtons()
                return@launch
            }
            val unfinished = locator.repository.resumableTrip() ?: return@launch
            MaterialAlertDialogBuilder(this@MainActivity)
                .setMessage(R.string.resume_trip_prompt)
                .setPositiveButton(R.string.resume) { _, _ ->
                    lifecycleScope.launch {
                        // Resume on the trip's OWN map version, not the active one.
                        val tripBound = locator.mapRepository.boundMapForVersion(unfinished.mapVersionId)
                        if (tripBound == null) {
                            Toast.makeText(this@MainActivity, R.string.map_needs_calibration_message, Toast.LENGTH_LONG).show()
                            return@launch
                        }
                        boundMap = tripBound
                        session.bind(tripBound)
                        binding.mapView.transformer = tripBound.transformer()
                        binding.mapView.setMapSource(
                            FileMapSource(
                                File(tripBound.version.imageUri),
                                tripBound.version.widthPx,
                                tripBound.version.heightPx,
                            ),
                        )
                        session.resumeTrip(unfinished)
                        startTrackingService()
                        refreshButtons()
                    }
                }
                .setNegativeButton(R.string.discard) { _, _ ->
                    lifecycleScope.launch {
                        locator.repository.finishTrip(unfinished.id, System.currentTimeMillis())
                    }
                }
                .show()
        }
    }

    private fun maybeShowPrivacyNote() {
        lifecycleScope.launch {
            if (!locator.settings.privacyNoteShown.first()) {
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setMessage(R.string.privacy_note)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        lifecycleScope.launch { locator.settings.setPrivacyNoteShown() }
                    }
                    .show()
            }
        }
    }

    private fun showMenu() {
        val items = arrayOf(
            getString(R.string.title_maps),
            getString(R.string.title_history),
            getString(R.string.btn_measure_distance),
            getString(R.string.title_anchors),
            getString(R.string.title_calibration),
            getString(R.string.title_settings),
        )
        MaterialAlertDialogBuilder(this)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> startActivity(Intent(this, MapsActivity::class.java))
                    1 -> startActivity(Intent(this, HistoryActivity::class.java))
                    2 -> startMeasureDistance()
                    3 -> showAnchorHistory()
                    4 -> startActivity(Intent(this, StepCalibrationActivity::class.java))
                    5 -> startActivity(Intent(this, SettingsActivity::class.java))
                }
            }
            .show()
    }

    private fun showAnchorHistory() {
        lifecycleScope.launch {
            val bound = boundMap ?: return@launch
            val anchors = locator.mapRepository.anchors(bound.version.id)
            if (anchors.isEmpty()) {
                Toast.makeText(this@MainActivity, R.string.no_anchors_yet, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val labels = anchors.map { a ->
                val date = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(Date(a.createdAt))
                "${a.name ?: getString(R.string.nav_anchor_unnamed)} — $date"
            }
            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle(R.string.title_anchors)
                .setItems(labels.toTypedArray()) { _, which ->
                    val a = anchors[which]
                    val t = bound.transformer()
                    val px = t.mapToImage(MapPoint(a.xMeters, a.yMeters))
                    binding.mapView.centerOn(px.xPx, px.yPx)
                }
                .show()
        }
    }
}
