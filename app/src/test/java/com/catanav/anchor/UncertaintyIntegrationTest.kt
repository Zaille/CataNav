package com.catanav.anchor

import com.catanav.pdr.PdrEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The uncertainty circle contract (v2, all in meters): radius = distance_since_anchor
 * x drift_rate, grows with every step, resets to zero on EVERY anchor event (trip
 * start included).
 */
class UncertaintyIntegrationTest {

    @Test
    fun radiusGrowsWithWalk_andResetsOnAnchor() {
        val engine = PdrEngine(stepLengthMeters = 0.7)
        val drift = DriftCalibrator() // default 0.05

        fun radius(): Double =
            drift.uncertaintyRadiusMeters(engine.position.value?.distanceSinceAnchorM ?: 0.0)

        // Trip start = first anchor: radius starts at zero.
        engine.anchor(1500.0, 1500.0)
        assertEquals(0.0, radius(), 0.0)

        // 100 steps x 0.7 m = 70 m walked -> radius 3.5 m at 5%.
        repeat(100) { engine.onStep(90.0) }
        assertEquals(3.5, radius(), 1e-9)

        // Re-anchor: the user taps their true position; radius collapses to zero.
        engine.anchor(1570.0, 1500.0)
        assertEquals(0.0, radius(), 0.0)

        // And grows again from the new anchor.
        repeat(10) { engine.onStep(0.0) }
        assertTrue(radius() > 0.0)
        assertEquals(0.35, radius(), 1e-9)
    }

    @Test
    fun reanchorFeedsDrift_andSubsequentRadiiUseTheRefinedRate() {
        val engine = PdrEngine(stepLengthMeters = 1.0)
        val drift = DriftCalibrator()

        engine.anchor(0.0, 0.0)
        repeat(100) { engine.onStep(90.0) } // 100 m east
        val predicted = engine.position.value!!

        // User taps 8 m north of the prediction: sample = 8/100 = 0.08.
        val tappedX = predicted.xMeters
        val tappedY = predicted.yMeters + 8.0
        drift.onReanchor(
            predictedXM = predicted.xMeters,
            predictedYM = predicted.yMeters,
            actualXM = tappedX,
            actualYM = tappedY,
            distanceWalkedM = predicted.distanceSinceAnchorM,
        )
        assertEquals(0.08, drift.rate, 1e-9)

        engine.anchor(tappedX, tappedY)
        repeat(50) { engine.onStep(0.0) } // 50 m
        assertEquals(4.0, drift.uncertaintyRadiusMeters(engine.position.value!!.distanceSinceAnchorM), 1e-9)
    }
}
