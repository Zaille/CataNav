package com.catanav.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.catanav.BuildConfig
import com.catanav.CataNavApp
import com.catanav.R
import com.catanav.databinding.ActivitySettingsBinding
import com.catanav.pdr.SensorHub
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private val settings get() = CataNavApp.instance.locator.settings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        lifecycleScope.launch {
            when (settings.headingSource.first()) {
                SensorHub.HeadingSource.ROTATION_VECTOR -> binding.headingRotation.isChecked = true
                SensorHub.HeadingSource.COMPLEMENTARY_FILTER -> binding.headingComplementary.isChecked = true
            }
            binding.redModeSwitch.isChecked = settings.redMode.first()
            val offset = settings.orientationOffsetDegrees.first()
            binding.orientationSeek.progress = (offset + 180).toInt()
            binding.orientationLabel.text = getString(R.string.settings_orientation_offset, offset.toInt())
        }

        binding.orientationSeek.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar?, p: Int, fromUser: Boolean) {
                val deg = p - 180.0
                binding.orientationLabel.text =
                    getString(R.string.settings_orientation_offset, deg.toInt())
                if (fromUser) {
                    lifecycleScope.launch {
                        settings.setOrientationOffsetDegrees(deg)
                        CataNavApp.instance.locator.tripSession.orientationOffsetDeg = deg
                    }
                }
            }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar?) = Unit
            override fun onStopTrackingTouch(sb: android.widget.SeekBar?) = Unit
        })

        binding.headingGroup.setOnCheckedChangeListener { _, checkedId ->
            lifecycleScope.launch {
                settings.setHeadingSource(
                    if (checkedId == binding.headingComplementary.id) {
                        SensorHub.HeadingSource.COMPLEMENTARY_FILTER
                    } else {
                        SensorHub.HeadingSource.ROTATION_VECTOR
                    },
                )
            }
        }

        binding.redModeSwitch.setOnCheckedChangeListener { _, checked ->
            lifecycleScope.launch { settings.setRedMode(checked) }
        }

        binding.btnResetDrift.setOnClickListener {
            lifecycleScope.launch {
                settings.resetDriftCalibration()
                Toast.makeText(this@SettingsActivity, R.string.settings_drift_reset_done, Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnCalibration.setOnClickListener {
            startActivity(Intent(this, StepCalibrationActivity::class.java))
        }

        binding.btnCalibrationTest.setOnClickListener {
            startActivity(Intent(this, CalibrationTestActivity::class.java))
        }

        binding.aboutText.text =
            "CataNav ${BuildConfig.VERSION_NAME}\n\n" + getString(R.string.privacy_note)
    }
}
