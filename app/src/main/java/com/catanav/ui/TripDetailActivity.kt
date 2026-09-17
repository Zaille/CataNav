package com.catanav.ui

import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.catanav.CataNavApp
import com.catanav.R
import com.catanav.data.ActiveMap
import com.catanav.data.PointSource
import com.catanav.data.TripEntity
import com.catanav.databinding.ActivityTripDetailBinding
import com.catanav.domain.MapPoint
import com.catanav.export.CsvExporter
import com.catanav.map.FileMapSource
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * Trip detail: path overlaid on the trip's OWN map version (recalibrating the map
 * never changes how an old trip renders), notes, canonical CSV export via SAF, delete.
 */
class TripDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TRIP_ID = "trip_id"
    }

    private lateinit var binding: ActivityTripDetailBinding
    private val locator get() = CataNavApp.instance.locator
    private var trip: TripEntity? = null
    private var bound: ActiveMap? = null
    private var pendingExport: String? = null

    private val csvLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
            uri?.let { writePendingExport(it) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        val redMode = runBlocking { locator.settings.redMode.first() }
        if (redMode) setTheme(R.style.Theme_CataNav_Red)
        super.onCreate(savedInstanceState)
        binding = ActivityTripDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.detailMap.redMode = redMode

        val tripId = intent.getLongExtra(EXTRA_TRIP_ID, -1)
        lifecycleScope.launch { loadTrip(tripId) }

        binding.btnExportCsv.setOnClickListener {
            lifecycleScope.launch {
                val t = trip ?: return@launch
                val b = bound ?: return@launch
                val points = locator.repository.points(t.id)
                pendingExport = CsvExporter.export(t, b.map.id, points, b.transformer())
                csvLauncher.launch(sanitize(t.name.ifBlank { "trip_${t.id}" }) + ".csv")
            }
        }

        binding.btnSaveMeta.setOnClickListener {
            lifecycleScope.launch {
                val t = trip ?: return@launch
                val updated = t.copy(notes = binding.notes.text?.toString() ?: "")
                locator.repository.updateTrip(updated)
                trip = updated
                Toast.makeText(this@TripDetailActivity, android.R.string.ok, Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnDelete.setOnClickListener { confirmDelete() }
    }

    private suspend fun loadTrip(tripId: Long) {
        val t = locator.repository.trip(tripId) ?: run { finish(); return }
        trip = t
        title = t.name
        binding.notes.setText(t.notes)

        val b = locator.mapRepository.boundMapForVersion(t.mapVersionId) ?: run {
            Toast.makeText(this, R.string.map_needs_calibration_message, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        bound = b
        binding.mapNameLabel.text = getString(R.string.trip_on_map, b.map.name)
        binding.detailMap.transformer = b.transformer()
        binding.detailMap.setMapSource(
            FileMapSource(File(b.version.imageUri), b.version.widthPx, b.version.heightPx),
        )

        val points = locator.repository.points(tripId)
        binding.detailMap.setTrailMeters(points.map { MapPoint(it.xMeters, it.yMeters) })
        binding.detailMap.setAnchorsMeters(
            points.filter { it.source == PointSource.ANCHOR }
                .map { MapPoint(it.xMeters, it.yMeters) to null },
        )
        points.firstOrNull()?.let {
            val px = b.transformer().mapToImage(MapPoint(it.xMeters, it.yMeters))
            binding.detailMap.centerOn(px.xPx, px.yPx)
        }
    }

    private fun writePendingExport(uri: Uri) {
        val content = pendingExport ?: return
        pendingExport = null
        lifecycleScope.launch(Dispatchers.IO) {
            contentResolver.openOutputStream(uri)?.use { out ->
                out.write(content.toByteArray(Charsets.UTF_8))
            }
        }
    }

    private fun confirmDelete() {
        val t = trip ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_trip)
            .setItems(
                arrayOf(getString(R.string.delete_track_only), getString(R.string.delete_all)),
            ) { _, which ->
                lifecycleScope.launch {
                    if (which == 0) {
                        locator.repository.deleteTrackOnly(t.id)
                        loadTrip(t.id)
                    } else {
                        locator.repository.deleteTrip(t.id)
                        finish()
                    }
                }
            }
            .show()
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._ -]"), "_").trim().replace(' ', '_')
}
