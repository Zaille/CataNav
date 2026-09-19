package com.catanav.anchor

import com.catanav.pdr.PdrEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StrideCalibratorTest {

    private val c = StrideCalibrator()

    @Test
    fun straightLeg_tappedFurtherAlongTrack_lengthensStride() {
        // Predicted 100 m east from the anchor, user taps 115 m east: strides were 15 % short.
        val adj = c.onReanchor(0.0, 0.0, 100.0, 0.0, 115.0, 0.0, 100.0, 0.70)
        assertNotNull(adj)
        assertEquals(1.15, adj!!.measuredRatio, 1e-9)
        // gain 0.35 => 0.70 * (1 + 0.35 * 0.15)
        assertEquals(0.70 * 1.0525, adj.afterM, 1e-9)
        assertEquals(0.70, adj.beforeM, 1e-9)
    }

    @Test
    fun straightLeg_tappedShort_shortensStride() {
        val adj = c.onReanchor(0.0, 0.0, 0.0, 50.0, 0.0, 45.0, 50.0, 0.80)
        assertNotNull(adj)
        assertEquals(0.90, adj!!.measuredRatio, 1e-9)
        assertTrue(adj.afterM < 0.80)
    }

    @Test
    fun lateralError_isHeadingNotStride() {
        // Tapped 5 m north of a 100 m eastward prediction: pure heading error.
        val adj = c.onReanchor(0.0, 0.0, 100.0, 0.0, 100.0, 5.0, 100.0, 0.70)
        assertNotNull(adj)
        assertEquals(1.0, adj!!.measuredRatio, 1e-9)
        assertEquals(0.70, adj.afterM, 1e-9)
    }

    @Test
    fun shortLeg_isIgnored() {
        assertNull(c.onReanchor(0.0, 0.0, 10.0, 0.0, 12.0, 0.0, 10.0, 0.70))
    }

    @Test
    fun windingLeg_isIgnored() {
        // Walked 100 m but ended only 40 m from the anchor: path was not straight.
        assertNull(c.onReanchor(0.0, 0.0, 40.0, 0.0, 46.0, 0.0, 100.0, 0.70))
    }

    @Test
    fun hugeLateralError_isIgnored() {
        assertNull(c.onReanchor(0.0, 0.0, 30.0, 0.0, 30.0, 20.0, 30.0, 0.70))
    }

    @Test
    fun tapBehindAnchor_isIgnored() {
        assertNull(c.onReanchor(0.0, 0.0, 30.0, 0.0, -5.0, 0.0, 30.0, 0.70))
    }

    @Test
    fun absurdRatio_isClampedPerSample() {
        // Tapped at 3x the predicted distance: ratio clamped to 1.3 before the gain.
        val adj = c.onReanchor(0.0, 0.0, 20.0, 0.0, 60.0, 0.0, 20.0, 0.70)
        assertNotNull(adj)
        assertEquals(0.70 * (1 + 0.35 * 0.3), adj!!.afterM, 1e-9)
    }

    @Test
    fun result_staysWithinHumanStride() {
        val adj = c.onReanchor(0.0, 0.0, 20.0, 0.0, 30.0, 0.0, 20.0, 1.48)
        assertEquals(StrideCalibrator.MAX_STEP_M, adj!!.afterM, 1e-9)
    }

    @Test
    fun repeatedReanchors_convergeOnTrueStride() {
        // True stride 0.80 m, engine starts at 0.65 m. Each leg: 60 real steps in a
        // straight line east; the user taps the true position.
        val engine = PdrEngine(stepLengthMeters = 0.65)
        var trueX = 0.0
        engine.anchor(0.0, 0.0)
        repeat(6) {
            repeat(60) { engine.onStep(90.0) }
            trueX += 60 * 0.80
            val p = engine.position.value!!
            val adj = c.onReanchor(
                p.anchorXMeters, p.anchorYMeters, p.xMeters, p.yMeters,
                trueX, 0.0, p.distanceSinceAnchorM, engine.stepLengthMeters,
            )
            assertNotNull(adj)
            engine.stepLengthMeters = adj!!.afterM
            engine.anchor(trueX, 0.0)
        }
        assertEquals(0.80, engine.stepLengthMeters, 0.02)
    }
}
