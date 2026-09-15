package com.catanav.export

import kotlin.math.cos

/**
 * APPROXIMATE pixel → lat/lon conversion for the GPX export, via an equirectangular
 * local-tangent-plane approximation around one user-entered surface reference (the
 * entrance): the map is north-up (0° offset), +X pixel = east, +Y pixel = south.
 *
 * This is deliberately labeled approximate everywhere it surfaces: the plate is a
 * stitched historical composite (see MapCalibration), and the tangent-plane math
 * itself ignores ellipsoidal effects. Fine over a few km; never survey-grade.
 */
object GeoRef {

    /** Mean Earth radius, meters (spherical model — consistent with "approximate"). */
    const val EARTH_RADIUS_M = 6371000.0

    /**
     * @param refXPx/refYPx pixel of the known surface point (the trip's start anchor)
     * @param refLat/refLon its user-entered latitude/longitude in degrees
     * @return [lat, lon] in degrees
     */
    fun pixelToLatLon(
        xPx: Double,
        yPx: Double,
        refXPx: Double,
        refYPx: Double,
        refLat: Double,
        refLon: Double,
        metersPerPixel: Double,
    ): DoubleArray {
        val eastM = (xPx - refXPx) * metersPerPixel
        val northM = -(yPx - refYPx) * metersPerPixel // +Y pixels point south
        val lat = refLat + Math.toDegrees(northM / EARTH_RADIUS_M)
        val lon = refLon + Math.toDegrees(eastM / (EARTH_RADIUS_M * cos(Math.toRadians(refLat))))
        return doubleArrayOf(lat, lon)
    }
}
