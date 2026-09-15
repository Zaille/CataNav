package com.catanav.pdr

import com.catanav.domain.CalibrationTransformer
import com.catanav.domain.Calibration
import com.catanav.domain.MapPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * v2: the engine runs in map-local METERS (X = east, Y = north), completely
 * calibration-free. The synthetic walks from v1 are re-expressed in meters.
 */
class PdrEngineTest {

    private fun engine(stepLen: Double = 0.7) = PdrEngine(stepLen)

    @Test
    fun noPosition_untilFirstAnchor_andStepsIgnored() {
        val e = engine()
        assertNull(e.position.value)
        e.onStep(0.0)
        assertNull(e.position.value) // trip start IS a forced first anchor; no guessed origin
    }

    @Test
    fun stepNorth_movesPlusY() {
        val e = engine(0.7)
        e.anchor(100.0, 100.0)
        e.onStep(0.0)
        val p = e.position.value!!
        assertEquals(100.0, p.xMeters, 1e-9)
        assertEquals(100.7, p.yMeters, 1e-9) // Y = north
    }

    @Test
    fun stepEast_movesPlusX() {
        val e = engine(0.7)
        e.anchor(0.0, 0.0)
        e.onStep(90.0)
        val p = e.position.value!!
        assertEquals(0.7, p.xMeters, 1e-9)
        assertEquals(0.0, p.yMeters, 1e-9)
    }

    @Test
    fun stepSouthAndWest_signConventions() {
        val e = engine(1.0)
        e.anchor(0.0, 0.0)
        e.onStep(180.0)
        var p = e.position.value!!
        assertEquals(0.0, p.xMeters, 1e-9)
        assertEquals(-1.0, p.yMeters, 1e-9)
        e.onStep(270.0)
        p = e.position.value!!
        assertEquals(-1.0, p.xMeters, 1e-9)
        assertEquals(-1.0, p.yMeters, 1e-9)
    }

    @Test
    fun step45Degrees_handComputed() {
        val e = engine(0.7)
        e.anchor(0.0, 0.0)
        e.onStep(45.0)
        val p = e.position.value!!
        val comp = 0.7 * Math.sqrt(2.0) / 2.0
        assertEquals(comp, p.xMeters, 1e-9)
        assertEquals(comp, p.yMeters, 1e-9)
    }

    @Test
    fun scriptedSquareWalk_100Steps_returnsToOrigin() {
        val e = engine(0.72)
        e.anchor(50.0, 50.0)
        for (heading in listOf(0.0, 90.0, 180.0, 270.0)) {
            repeat(25) { e.onStep(heading) }
        }
        val p = e.position.value!!
        assertEquals("x after square walk", 50.0, p.xMeters, 1e-9)
        assertEquals("y after square walk", 50.0, p.yMeters, 1e-9)
        assertEquals(100, p.totalSteps)
        assertEquals(100 * 0.72, p.distanceSinceAnchorM, 1e-9)
    }

    @Test
    fun squareWalk_onA90DegreeRotatedMap_landsCorrectlyInImageSpace() {
        // Map plate rotated: north points image-RIGHT (northOffset = 90).
        val t = CalibrationTransformer(Calibration(metersPerPixel = 0.5, northOffsetDegrees = 90.0))
        val e = engine(1.0)
        // Anchor at image pixel (1000, 1000).
        val start = t.imageToMap(com.catanav.domain.ImagePoint(1000.0, 1000.0))
        e.anchor(start.xMeters, start.yMeters)

        // Walk 10 m north: on this plate north = image-right => +20 px in X.
        repeat(10) { e.onStep(0.0) }
        var img = t.mapToImage(MapPoint(e.position.value!!.xMeters, e.position.value!!.yMeters))
        assertEquals(1020.0, img.xPx, 1e-6)
        assertEquals(1000.0, img.yPx, 1e-6)

        // Then 10 m east: east = image-down => +20 px in Y.
        repeat(10) { e.onStep(90.0) }
        img = t.mapToImage(MapPoint(e.position.value!!.xMeters, e.position.value!!.yMeters))
        assertEquals(1020.0, img.xPx, 1e-6)
        assertEquals(1020.0, img.yPx, 1e-6)

        // Close the square: 10 m south, 10 m west -> back to the anchor pixel.
        repeat(10) { e.onStep(180.0) }
        repeat(10) { e.onStep(270.0) }
        img = t.mapToImage(MapPoint(e.position.value!!.xMeters, e.position.value!!.yMeters))
        assertEquals(1000.0, img.xPx, 1e-6)
        assertEquals(1000.0, img.yPx, 1e-6)
    }

    @Test
    fun manualStepBack_reversesForwardStep() {
        val e = engine(0.7)
        e.anchor(5.0, 5.0)
        e.onStep(123.0, direction = 1)
        e.onStep(123.0, direction = -1)
        val p = e.position.value!!
        assertEquals(5.0, p.xMeters, 1e-9)
        assertEquals(5.0, p.yMeters, 1e-9)
        // Distance walked still accumulates — walking back is still walking.
        assertEquals(1.4, p.distanceSinceAnchorM, 1e-9)
    }

    @Test
    fun anchor_resetsDistanceSinceAnchor_keepsStepCount() {
        val e = engine(0.7)
        e.anchor(0.0, 0.0)
        repeat(10) { e.onStep(90.0) }
        assertEquals(7.0, e.position.value!!.distanceSinceAnchorM, 1e-9)
        e.anchor(100.0, 200.0)
        val p = e.position.value!!
        assertEquals(0.0, p.distanceSinceAnchorM, 0.0)
        assertEquals(100.0, p.xMeters, 0.0)
        assertEquals(200.0, p.yMeters, 0.0)
        assertEquals(10, p.totalSteps)
    }

    @Test
    fun anchor_doesNotAutoCorrectHeading_unlessProvided() {
        val e = engine()
        e.anchor(0.0, 0.0)
        e.onStep(42.0)
        e.anchor(50.0, 50.0) // no heading passed
        assertEquals(42.0, e.position.value!!.headingDeg, 1e-9)
        e.anchor(60.0, 60.0, headingDeg = 210.0) // user confirmed facing on the dial
        assertEquals(210.0, e.position.value!!.headingDeg, 1e-9)
    }

    @Test
    fun stepWithNaNHeading_isIgnored() {
        val e = engine()
        e.anchor(10.0, 20.0)
        e.onStep(Double.NaN)
        val p = e.position.value!!
        assertEquals(10.0, p.xMeters, 0.0)
        assertEquals(20.0, p.yMeters, 0.0)
        assertEquals(0, p.totalSteps)
    }

    @Test
    fun restore_preservesDistanceAndSteps() {
        val e = engine()
        e.restore(xMeters = 3.0, yMeters = 4.0, headingDeg = 12.0, distanceSinceAnchorM = 55.5, totalSteps = 80)
        val p = e.position.value!!
        assertEquals(55.5, p.distanceSinceAnchorM, 0.0)
        assertEquals(80, p.totalSteps)
        assertEquals(3.0, p.xMeters, 0.0)
    }
}
