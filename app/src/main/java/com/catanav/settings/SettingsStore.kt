package com.catanav.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.catanav.anchor.DriftCalibrator
import com.catanav.pdr.PdrEngine
import com.catanav.pdr.SensorHub
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "catanav_settings")

/**
 * Per-device persisted settings. Drift calibration persists ACROSS trips here (locked
 * decision) — every real trip refines it — with a reset action in Settings.
 */
class SettingsStore(private val context: Context) {

    private object Keys {
        val STEP_LENGTH = doublePreferencesKey("step_length_m")
        val ORIENTATION_OFFSET = doublePreferencesKey("orientation_offset_deg")
        val DRIFT_RATE = doublePreferencesKey("drift_rate")
        val DRIFT_SAMPLES = intPreferencesKey("drift_sample_count")
        val HEADING_SOURCE = stringPreferencesKey("heading_source")
        val RED_MODE = booleanPreferencesKey("red_mode")
        val PRIVACY_NOTE_SHOWN = booleanPreferencesKey("privacy_note_shown")
    }

    val stepLengthMeters: Flow<Double> =
        context.dataStore.data.map { it[Keys.STEP_LENGTH] ?: PdrEngine.DEFAULT_STEP_LENGTH_M }

    suspend fun setStepLengthMeters(v: Double) {
        context.dataStore.edit { it[Keys.STEP_LENGTH] = v }
    }

    /**
     * Device profile: fixed angle (deg) between the phone's Y axis and the walking
     * direction ("phone held in walking direction" is the assumption; this corrects a
     * habitual tilt). Applied by TripSession to every sensor heading.
     */
    val orientationOffsetDegrees: Flow<Double> =
        context.dataStore.data.map { it[Keys.ORIENTATION_OFFSET] ?: 0.0 }

    suspend fun setOrientationOffsetDegrees(v: Double) {
        context.dataStore.edit { it[Keys.ORIENTATION_OFFSET] = v.coerceIn(-180.0, 180.0) }
    }

    val redMode: Flow<Boolean> = context.dataStore.data.map { it[Keys.RED_MODE] ?: false }

    suspend fun setRedMode(v: Boolean) {
        context.dataStore.edit { it[Keys.RED_MODE] = v }
    }

    val headingSource: Flow<SensorHub.HeadingSource> = context.dataStore.data.map {
        when (it[Keys.HEADING_SOURCE]) {
            SensorHub.HeadingSource.COMPLEMENTARY_FILTER.name -> SensorHub.HeadingSource.COMPLEMENTARY_FILTER
            else -> SensorHub.HeadingSource.ROTATION_VECTOR
        }
    }

    suspend fun setHeadingSource(v: SensorHub.HeadingSource) {
        context.dataStore.edit { it[Keys.HEADING_SOURCE] = v.name }
    }

    val privacyNoteShown: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.PRIVACY_NOTE_SHOWN] ?: false }

    suspend fun setPrivacyNoteShown() {
        context.dataStore.edit { it[Keys.PRIVACY_NOTE_SHOWN] = true }
    }

    suspend fun loadDriftCalibrator(): DriftCalibrator {
        val prefs = context.dataStore.data.first()
        return DriftCalibrator(
            rate = prefs[Keys.DRIFT_RATE] ?: DriftCalibrator.DEFAULT_RATE,
            sampleCount = prefs[Keys.DRIFT_SAMPLES] ?: 0,
        )
    }

    suspend fun saveDriftCalibrator(c: DriftCalibrator) {
        context.dataStore.edit {
            it[Keys.DRIFT_RATE] = c.rate
            it[Keys.DRIFT_SAMPLES] = c.sampleCount
        }
    }

    suspend fun resetDriftCalibration() {
        context.dataStore.edit {
            it[Keys.DRIFT_RATE] = DriftCalibrator.DEFAULT_RATE
            it[Keys.DRIFT_SAMPLES] = 0
        }
    }
}
