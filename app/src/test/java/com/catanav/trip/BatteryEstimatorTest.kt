package com.catanav.trip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BatteryEstimatorTest {

    @Test
    fun noEstimate_untilEnoughSignal() {
        val e = BatteryEstimator()
        assertNull(e.estimateRemainingMs())
        e.onSample(0, 100)
        assertNull("single sample", e.estimateRemainingMs())
        e.onSample(60_000, 100)
        assertNull("no drop yet", e.estimateRemainingMs())
        e.onSample(120_000, 99)
        assertNull("under 5 minutes of observation", e.estimateRemainingMs())
    }

    @Test
    fun linearDrain_extrapolates() {
        val e = BatteryEstimator()
        e.onSample(0, 100)
        e.onSample(30 * 60_000L, 95) // 5% in 30 min
        // 95% left at 6 min/% => 570 min.
        assertEquals(570L * 60_000, e.estimateRemainingMs())
    }

    @Test
    fun reset_clearsHistory() {
        val e = BatteryEstimator()
        e.onSample(0, 100)
        e.onSample(30 * 60_000L, 90)
        e.reset()
        assertNull(e.estimateRemainingMs())
    }

    @Test
    fun formatDuration_readable() {
        assertEquals("9h30", BatteryEstimator.formatDuration(570L * 60_000))
        assertEquals("45 min", BatteryEstimator.formatDuration(45L * 60_000))
        assertEquals("1h05", BatteryEstimator.formatDuration(65L * 60_000))
    }
}
