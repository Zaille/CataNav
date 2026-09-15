package com.catanav.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.catanav.CataNavApp
import com.catanav.R
import com.catanav.data.MapDefinitionEntity
import com.catanav.data.MapImporter
import com.catanav.databinding.ActivityMapsBinding
import com.catanav.databinding.ItemMapBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Home for map management: every stored map with its calibration status and trip
 * count; import (SAF), rename, recalibrate (via the version model), delete.
 */
class MapsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMapsBinding
    private val locator get() = CataNavApp.instance.locator
    private val adapter = MapAdapter(
        onClick = { row -> openMap(row) },
        onLongClick = { row -> showActions(row) },
    )

    data class MapRow(
        val map: MapDefinitionEntity,
        val calibrated: Boolean,
        val tripCount: Int,
    )

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri ?: return@registerForActivityResult
            lifecycleScope.launch {
                try {
                    val imported = withContext(Dispatchers.IO) {
                        MapImporter.import(applicationContext, uri)
                    }
                    askName { name ->
                        lifecycleScope.launch {
                            val mapId = locator.mapRepository.importMap(
                                name, imported.path, imported.widthPx, imported.heightPx,
                            )
                            // A new map is unusable until calibrated — go straight there.
                            startActivity(
                                Intent(this@MapsActivity, CalibrateMapActivity::class.java)
                                    .putExtra(CalibrateMapActivity.EXTRA_MAP_ID, mapId),
                            )
                        }
                    }
                } catch (e: Exception) {
                    Toast.makeText(this@MapsActivity, R.string.import_failed, Toast.LENGTH_LONG).show()
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMapsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.mapList.layoutManager = LinearLayoutManager(this)
        binding.mapList.adapter = adapter

        binding.btnImport.setOnClickListener {
            importLauncher.launch(arrayOf("image/*"))
        }
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                locator.mapRepository.maps().collect { maps ->
                    val rows = maps.map { m ->
                        MapRow(
                            map = m,
                            calibrated = m.activeVersionId != null,
                            tripCount = locator.repository.tripCountForMap(m.id),
                        )
                    }
                    adapter.submit(rows)
                }
            }
        }
    }

    private fun openMap(row: MapRow) {
        if (!row.calibrated) {
            // Clear state, not a crash: an uncalibrated map cannot start a trip.
            MaterialAlertDialogBuilder(this)
                .setMessage(R.string.map_needs_calibration_message)
                .setPositiveButton(R.string.action_calibrate) { _, _ -> calibrate(row) }
                .setNegativeButton(R.string.btn_cancel, null)
                .show()
            return
        }
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_MAP_ID, row.map.id),
        )
    }

    private fun showActions(row: MapRow) {
        val actions = arrayOf(
            getString(R.string.action_rename),
            getString(R.string.action_calibrate),
            getString(R.string.action_delete),
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(row.map.name)
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> rename(row)
                    1 -> calibrate(row)
                    2 -> confirmDelete(row)
                }
            }
            .show()
    }

    private fun calibrate(row: MapRow) {
        startActivity(
            Intent(this, CalibrateMapActivity::class.java)
                .putExtra(CalibrateMapActivity.EXTRA_MAP_ID, row.map.id),
        )
    }

    private fun rename(row: MapRow) {
        askName(initial = row.map.name) { name ->
            lifecycleScope.launch { locator.mapRepository.rename(row.map.id, name) }
        }
    }

    private fun confirmDelete(row: MapRow) {
        MaterialAlertDialogBuilder(this)
            .setMessage(getString(R.string.delete_map_message, row.map.name))
            .setPositiveButton(R.string.action_delete) { _, _ ->
                lifecycleScope.launch { locator.mapRepository.delete(row.map.id) }
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun askName(initial: String = "", onName: (String) -> Unit) {
        val input = EditText(this).apply {
            hint = getString(R.string.map_name_hint)
            setText(initial)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.map_name_hint)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = input.text.toString().ifBlank { getString(R.string.unnamed_map) }
                onName(name)
            }
            .show()
    }

    private class MapAdapter(
        private val onClick: (MapRow) -> Unit,
        private val onLongClick: (MapRow) -> Unit,
    ) : RecyclerView.Adapter<MapAdapter.Holder>() {

        private val items = mutableListOf<MapRow>()

        fun submit(rows: List<MapRow>) {
            items.clear()
            items.addAll(rows)
            notifyDataSetChanged()
        }

        class Holder(val binding: ItemMapBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(ItemMapBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val row = items[position]
            holder.binding.mapName.text = row.map.name
            val ctx = holder.binding.root.context
            val status = if (row.calibrated) {
                ctx.getString(R.string.map_calibrated)
            } else {
                ctx.getString(R.string.map_needs_calibration)
            }
            holder.binding.mapMeta.text = ctx.getString(
                R.string.map_row_meta, status, row.tripCount,
                row.map.widthPx, row.map.heightPx,
            )
            holder.binding.root.setOnClickListener { onClick(row) }
            holder.binding.root.setOnLongClickListener { onLongClick(row); true }
        }
    }
}
