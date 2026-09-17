package com.catanav.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MapViewportTest {

    private fun vp(scale: Double = 1.0, rot: Double = 0.0, ox: Double = 0.0, oy: Double = 0.0): MapViewport =
        MapViewport(7000, 7000).apply { set(scale, rot, ox, oy) }

    @Test
    fun identityTransform_mapsPointsUnchanged() {
        val v = vp()
        val s = v.imageToScreen(1234.0, 567.0)
        assertEquals(1234.0, s[0], 1e-9)
        assertEquals(567.0, s[1], 1e-9)
    }

    @Test
    fun scaleAndOffset_mapCorrectly() {
        val v = vp(scale = 0.5, ox = 100.0, oy = -50.0)
        val s = v.imageToScreen(1000.0, 2000.0)
        assertEquals(600.0, s[0], 1e-9)   // 0.5*1000 + 100
        assertEquals(950.0, s[1], 1e-9)   // 0.5*2000 - 50
    }

    @Test
    fun rotation90_rotatesAsExpected() {
        // screen = R(90) * image: (x,y) -> (-y, x)
        val v = vp(rot = 90.0)
        val s = v.imageToScreen(100.0, 0.0)
        assertEquals(0.0, s[0], 1e-6)
        assertEquals(100.0, s[1], 1e-6)
    }

    @Test
    fun roundTrip_screenToImage_inverts_imageToScreen() {
        val v = vp(scale = 0.37, rot = 123.4, ox = 250.0, oy = -80.0)
        for ((ix, iy) in listOf(0.0 to 0.0, 6999.0 to 6999.0, 42.5 to 4321.75)) {
            val s = v.imageToScreen(ix, iy)
            val back = v.screenToImage(s[0], s[1])
            assertEquals(ix, back[0], 1e-6)
            assertEquals(iy, back[1], 1e-6)
        }
    }

    @Test
    fun visibleImageRect_identity_clampsToViewSize() {
        val v = vp()
        val r = v.visibleImageRect(1080, 1920)
        assertEquals(0.0, r.left, 0.0)
        assertEquals(0.0, r.top, 0.0)
        assertEquals(1080.0, r.right, 1.0)
        assertEquals(1920.0, r.bottom, 1.0)
    }

    @Test
    fun visibleImageRect_clampsToImageBounds() {
        // Offset far positive: view sees area left/above of image origin only.
        val v = vp(scale = 1.0, ox = 10000.0, oy = 10000.0)
        val r = v.visibleImageRect(1080, 1920)
        assertTrue(r.isEmpty)
    }

    @Test
    fun visibleImageRect_withRotation_isBoundingBoxSuperset() {
        val v = MapViewport(7000, 7000)
        // Center the view on the image middle, zoomed out, rotated 45deg.
        v.set(0.2, 45.0, 0.0, 0.0)
        // Whatever the transform, every screen corner's image-space preimage
        // must fall inside the (clamped) rect.
        val r = v.visibleImageRect(1000, 1000)
        for (corner in listOf(0.0 to 0.0, 1000.0 to 0.0, 0.0 to 1000.0, 1000.0 to 1000.0)) {
            val img = v.screenToImage(corner.first, corner.second)
            val x = img[0].coerceIn(0.0, 7000.0)
            val y = img[1].coerceIn(0.0, 7000.0)
            assertTrue("x=$x not in [${r.left},${r.right}]", x >= r.left - 1 && x <= r.right + 1)
            assertTrue("y=$y not in [${r.top},${r.bottom}]", y >= r.top - 1 && y <= r.bottom + 1)
        }
    }

    @Test
    fun fitScale_letterboxes() {
        val v = MapViewport(7000, 7000)
        assertEquals(1080.0 / 7000.0, v.fitScale(1080, 1920), 1e-12)
    }

    @Test
    fun clampScale_boundsBetweenFitAndMaxScale() {
        val v = MapViewport(7000, 7000)
        val fit = v.fitScale(1080, 1920)
        assertEquals(fit, v.clampScale(0.0001, 1080, 1920), 1e-12)
        assertEquals(MapViewport.MAX_SCALE, v.clampScale(50.0, 1080, 1920), 1e-12)
        val mid = (fit + MapViewport.MAX_SCALE) / 2
        assertEquals(mid, v.clampScale(mid, 1080, 1920), 1e-12)
    }

    @Test
    fun applyGesture_keepsFocalImagePointUnderFinger() {
        val v = vp(scale = 0.5, ox = 100.0, oy = 100.0)
        val before = v.screenToImage(540.0, 960.0)
        v.applyGesture(540.0, 960.0, 1.5, 30.0, 0.0, 0.0, 1080, 1920)
        val after = v.screenToImage(540.0, 960.0)
        assertEquals(before[0], after[0], 1e-6)
        assertEquals(before[1], after[1], 1e-6)
    }

    @Test
    fun chooseSampleSize_powersOfTwo() {
        assertEquals(1, MapViewport.chooseSampleSize(1.0))
        assertEquals(1, MapViewport.chooseSampleSize(2.5))
        assertEquals(1, MapViewport.chooseSampleSize(0.6))   // 1/0.6 = 1.67 < 2
        assertEquals(2, MapViewport.chooseSampleSize(0.5))
        assertEquals(2, MapViewport.chooseSampleSize(0.26))  // 1/0.26 = 3.85 < 4
        assertEquals(4, MapViewport.chooseSampleSize(0.25))
        assertEquals(8, MapViewport.chooseSampleSize(0.1))   // 1/0.1 = 10 -> 8
        assertEquals(32, MapViewport.chooseSampleSize(0.0001)) // capped
    }

    @Test
    fun normalizeDeg_wrapsInto0To360() {
        assertEquals(0.0, MapViewport.normalizeDeg(360.0), 1e-12)
        assertEquals(350.0, MapViewport.normalizeDeg(-10.0), 1e-12)
        assertEquals(10.0, MapViewport.normalizeDeg(730.0), 1e-12)
    }
}
