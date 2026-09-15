package com.catanav.trip

/**
 * Rough remaining-tracking-time estimate from the battery drain observed during THIS
 * trip (no charging underground, so drain is monotonic). Pure and unit-testable.
 */
class BatteryEstimator {

    private var firstSampleTimeMs: Long = -1
    private var firstSampleLevel: Int = -1
    private var lastLevel: Int = -1

    fun onSample(timeMs: Long, levelPercent: Int) {
        if (firstSampleTimeMs < 0) {
            firstSampleTimeMs = timeMs
            firstSampleLevel = levelPercent
        }
        lastLevel = levelPercent
        lastSampleTimeMs = timeMs
    }

    private var lastSampleTimeMs: Long = -1

    /**
     * Milliseconds of tracking left at the observed drain rate, or null while there
     * is not yet enough signal (needs >= 1% drop over >= 5 minutes).
     */
    fun estimateRemainingMs(): Long? {
        if (firstSampleTimeMs < 0 || lastLevel < 0) return null
        val dropped = firstSampleLevel - lastLevel
        val elapsed = lastSampleTimeMs - firstSampleTimeMs
        if (dropped < 1 || elapsed < 5 * 60_000L) return null
        val msPerPercent = elapsed.toDouble() / dropped
        return (lastLevel * msPerPercent).toLong()
    }

    fun reset() {
        firstSampleTimeMs = -1
        firstSampleLevel = -1
        lastLevel = -1
        lastSampleTimeMs = -1
    }

    companion object {
        const val LOW_BATTERY_THRESHOLD = 20

        fun formatDuration(ms: Long): String {
            val totalMin = ms / 60_000
            val h = totalMin / 60
            val m = totalMin % 60
            return if (h > 0) "${h}h${m.toString().padStart(2, '0')}" else "${m} min"
        }
    }
}
