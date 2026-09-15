package com.catanav.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * The locked convention under test:
 * northOffsetDegrees = angle CLOCKWISE (on screen) from image-up to true north.
 * Map space: meters, X = east, Y = north; origin shared with the image origin.
 */
class CoordinateTransformerTest {

    private fun transformer(mpp: Double, north: Double) =
        CalibrationTransformer(Calibration(mpp, north))

    private fun assertMap(p: MapPoint, x: Double, y: Double, eps: Double = 1e-9) {
        assertEquals("xMeters", x, p.xMeters, eps)
        assertEquals("yMeters", y, p.yMeters, eps)
    }

    private fun assertImage(p: ImagePoint, x: Double, y: Double, eps: Double = 1e-9) {
        assertEquals("xPx", x, p.xPx, eps)
        assertEquals("yPx", y, p.yPx, eps)
    }

    // ---- offset 0: north-up plate ------------------------------------------------

    @Test
    fun northUp_imageRightIsEast_imageDownIsSouth() {
        val t = transformer(0.5, 0.0)
        assertMap(t.imageToMap(ImagePoint(100.0, 0.0)), 50.0, 0.0)   // right -> east
        assertMap(t.imageToMap(ImagePoint(0.0, 100.0)), 0.0, -50.0)  // down -> south
        assertMap(t.imageToMap(ImagePoint(0.0, 0.0)), 0.0, 0.0)      // shared origin
    }

    @Test
    fun northUp_inverse() {
        val t = transformer(0.5, 0.0)
        assertImage(t.mapToImage(MapPoint(50.0, 0.0)), 100.0, 0.0)
        assertImage(t.mapToImage(MapPoint(0.0, 50.0)), 0.0, -100.0) // north = image-up
    }

    // ---- offset 90: north points image-right ---------------------------------------

    @Test
    fun offset90_imageRightIsNorth_imageDownIsEast() {
        val t = transformer(2.0, 90.0)
        assertMap(t.imageToMap(ImagePoint(10.0, 0.0)), 0.0, 20.0)  // right -> north
        assertMap(t.imageToMap(ImagePoint(0.0, 10.0)), 20.0, 0.0)  // down -> east
    }

    // ---- offset 180: north points image-down ----------------------------------------

    @Test
    fun offset180_imageDownIsNorth_imageRightIsWest() {
        val t = transformer(1.0, 180.0)
        assertMap(t.imageToMap(ImagePoint(0.0, 10.0)), 0.0, 10.0)   // down -> north
        assertMap(t.imageToMap(ImagePoint(10.0, 0.0)), -10.0, 0.0)  // right -> west
    }

    // ---- arbitrary angle: hand-derived decomposition ----------------------------------

    @Test
    fun offset37_5_matchesRotateThenFlipDecomposition() {
        // Independent derivation: convert the image vector to display-math axes
        // (x right, y up), then project onto east = (cosθ, -sinθ), north = (sinθ, cosθ).
        val theta = Math.toRadians(37.5)
        val mpp = 0.433
        val t = transformer(mpp, 37.5)
        val samples = listOf(ImagePoint(123.0, -47.5), ImagePoint(1.0, 0.0), ImagePoint(0.0, 1.0), ImagePoint(-3.25, 8.0))
        for (p in samples) {
            val qx = p.xPx * mpp
            val qy = -p.yPx * mpp // display-math: y up
            val east = qx * cos(theta) + qy * -sin(theta)
            val north = qx * sin(theta) + qy * cos(theta)
            val m = t.imageToMap(p)
            assertEquals("east for $p", east, m.xMeters, 1e-9)
            assertEquals("north for $p", north, m.yMeters, 1e-9)
        }
    }

    // ---- round trips ---------------------------------------------------------------------

    @Test
    fun roundTrips_atAllTestAngles() {
        for (north in listOf(0.0, 90.0, 180.0, 37.5, 270.0, 359.9)) {
            val t = transformer(0.433, north)
            for (p in listOf(
                ImagePoint(0.0, 0.0),
                ImagePoint(6999.0, 6999.0),
                ImagePoint(42.5, 4321.75),
                ImagePoint(-10.0, 3.0),
            )) {
                val back = t.mapToImage(t.imageToMap(p))
                assertEquals("x roundtrip @$north", p.xPx, back.xPx, 1e-6)
                assertEquals("y roundtrip @$north", p.yPx, back.yPx, 1e-6)
            }
            for (m in listOf(MapPoint(0.0, 0.0), MapPoint(-321.5, 777.7), MapPoint(1e4, -1e4))) {
                val back = t.imageToMap(t.mapToImage(m))
                assertEquals("east roundtrip @$north", m.xMeters, back.xMeters, 1e-6)
                assertEquals("north roundtrip @$north", m.yMeters, back.yMeters, 1e-6)
            }
        }
    }

    @Test
    fun distancesArePreserved_timesMetersPerPixel() {
        val t = transformer(0.433, 37.5)
        val a = t.imageToMap(ImagePoint(100.0, 100.0))
        val b = t.imageToMap(ImagePoint(331.0, 100.0)) // 231 px apart
        val d = kotlin.math.hypot(b.xMeters - a.xMeters, b.yMeters - a.yMeters)
        assertEquals(231.0 * 0.433, d, 1e-9)
    }

    @Test(expected = IllegalArgumentException::class)
    fun zeroMetersPerPixel_rejected() {
        Calibration(0.0, 0.0)
    }
}
