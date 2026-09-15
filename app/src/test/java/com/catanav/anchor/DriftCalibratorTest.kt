package com.catanav.anchor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DriftCalibratorTest {

    @Test
    fun defaultRate_isFivePercent() {
        val c = DriftCalibrator()
        assertEquals(0.05, c.rate, 1e-12)
        assertEquals(5.0, c.uncertaintyRadiusMeters(100.0), 1e-9)
    }

    @Test
    fun runningAverage_overSamples() {
        val c = DriftCalibrator()
        // Walked 100 m, predicted vs tapped position differ by 10 m => sample 0.10.
        assertTrue(c.onReanchor(0.0, 0.0, 10.0, 0.0, 100.0))
        // avg(0.05 * 0 samples... first sample: (0.05*0 + 0.10)/1) — count was 0, so
        // the prior default is REPLACED by the first real observation:
        assertEquals(0.10, c.rate, 1e-9)
        assertEquals(1, c.sampleCount)
        // Second sample: 2 m error over 100 m => 0.02; average (0.10 + 0.02) / 2.
        assertTrue(c.onReanchor(0.0, 0.0, 0.0, 2.0, 100.0))
        assertEquals(0.06, c.rate, 1e-9)
        assertEquals(2, c.sampleCount)
    }

    @Test
    fun persistedStateRoundTrip_viaConstructor() {
        val c = DriftCalibrator()
        c.onReanchor(0.0, 0.0, 10.0, 0.0, 100.0)
        c.onReanchor(0.0, 0.0, 0.0, 2.0, 100.0)
        // Simulate app restart: state persisted (DataStore) and restored.
        val restored = DriftCalibrator(rate = c.rate, sampleCount = c.sampleCount)
        restored.onReanchor(0.0, 0.0, 6.0, 0.0, 100.0) // sample 0.06
        assertEquals((0.10 + 0.02 + 0.06) / 3, restored.rate, 1e-9)
        assertEquals(3, restored.sampleCount)
    }

    @Test
    fun shortWalks_areIgnored_tapNoiseNotDrift() {
        val c = DriftCalibrator()
        assertFalse(c.onReanchor(0.0, 0.0, 3.0, 0.0, 5.0))
        assertEquals(0.05, c.rate, 1e-12)
        assertEquals(0, c.sampleCount)
    }

    @Test
    fun samplesAreClamped_absurdCorrectionCannotPoisonTheRate() {
        val c = DriftCalibrator()
        // 500 m "error" over 20 m walked => raw 25.0, clamped to MAX_RATE.
        c.onReanchor(0.0, 0.0, 500.0, 0.0, 20.0)
        assertEquals(DriftCalibrator.MAX_RATE, c.rate, 1e-12)
    }

    @Test
    fun reset_restoresDefault() {
        val c = DriftCalibrator()
        c.onReanchor(0.0, 0.0, 30.0, 0.0, 100.0)
        c.reset()
        assertEquals(DriftCalibrator.DEFAULT_RATE, c.rate, 1e-12)
        assertEquals(0, c.sampleCount)
    }

    @Test
    fun radiusGrowsWithDistance_resetLogicLivesInEngine() {
        val c = DriftCalibrator()
        assertEquals(0.0, c.uncertaintyRadiusMeters(0.0), 0.0)
        assertEquals(2.5, c.uncertaintyRadiusMeters(50.0), 1e-9)
        assertEquals(5.0, c.uncertaintyRadiusMeters(100.0), 1e-9)
    }
}
