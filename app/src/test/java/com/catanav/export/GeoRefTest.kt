package com.catanav.export

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Golden tests: known anchor, known pixel offset, hand-computed expected coordinates
 * (equirectangular tangent plane, R = 6371000 m).
 */
class GeoRefTest {

    // Reference: pixel (1000, 1000) is the entrance at 48.85 N, 2.34 E (Paris).
    private val refX = 1000.0
    private val refY = 1000.0
    private val refLat = 48.85
    private val refLon = 2.34
    private val mpp = 0.433

    @Test
    fun referencePixel_mapsToReferenceCoordinates() {
        val ll = GeoRef.pixelToLatLon(refX, refY, refX, refY, refLat, refLon, mpp)
        assertEquals(refLat, ll[0], 0.0)
        assertEquals(refLon, ll[1], 0.0)
    }

    @Test
    fun eastOffset_231px_is100mEast() {
        // 231 px * 0.433 m/px = 100.023 m east.
        // dlon = 100.023 / (6371000 * cos(48.85 deg)) rad
        //      = 100.023 / (6371000 * 0.6580354...) = 2.386088e-5 rad = 1.3671361e-3 deg
        val ll = GeoRef.pixelToLatLon(refX + 231.0, refY, refX, refY, refLat, refLon, mpp)
        assertEquals("latitude unchanged going east", refLat, ll[0], 1e-12)
        assertEquals(2.34 + 1.3671e-3, ll[1], 1e-6)
    }

    @Test
    fun northOffset_231px_is100mNorth_minusYConvention() {
        // -231 px in Y (up on the map) = +100.023 m north.
        // dlat = 100.023 / 6371000 rad = 1.5700518e-5 rad = 8.9957e-4 deg
        val ll = GeoRef.pixelToLatLon(refX, refY - 231.0, refX, refY, refLat, refLon, mpp)
        assertEquals(48.85 + 8.9957e-4, ll[0], 1e-6)
        assertEquals("longitude unchanged going north", refLon, ll[1], 1e-12)
    }

    @Test
    fun southEastOffset_signsCorrect() {
        // +Y pixels = south (lat decreases), +X pixels = east (lon increases).
        val ll = GeoRef.pixelToLatLon(refX + 100.0, refY + 100.0, refX, refY, refLat, refLon, mpp)
        org.junit.Assert.assertTrue("south of reference", ll[0] < refLat)
        org.junit.Assert.assertTrue("east of reference", ll[1] > refLon)
    }

    @Test
    fun roundTripDistance_isConsistent() {
        // 1000 px east at 0.433 m/px = 433 m; recover the distance from the
        // longitude delta to within numerical noise.
        val ll = GeoRef.pixelToLatLon(refX + 1000.0, refY, refX, refY, refLat, refLon, mpp)
        val backM = Math.toRadians(ll[1] - refLon) *
            GeoRef.EARTH_RADIUS_M * Math.cos(Math.toRadians(refLat))
        assertEquals(433.0, backM, 1e-6)
    }
}
