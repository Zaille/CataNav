package com.catanav.domain

import kotlin.math.cos
import kotlin.math.sin

/**
 * The three coordinate spaces of CataNav v2 — and THE ONLY place pixels and meters meet.
 *
 * 1. IMAGE coordinates ([ImagePoint]): raster pixels, origin top-left, X right, Y down.
 * 2. MAP-LOCAL physical coordinates ([MapPoint]): meters, X = east, Y = north. This is
 *    the PDR engine's space; everything navigational (positions, anchors, tracks,
 *    uncertainty) lives here.
 * 3. Geographic lat/lon: optional FUTURE layer only (see export/GeoRef.kt seam); the
 *    engine never depends on it.
 *
 * LOCKED CONVENTION (documented once, used everywhere):
 * [Calibration.northOffsetDegrees] is the angle, in degrees CLOCKWISE (as displayed on
 * screen), from image-up to true north. A north-up map has offset 0. With offset 90,
 * north points toward image-right.
 *
 * The map origin (0 m, 0 m) coincides with the image origin (0 px, 0 px); map
 * coordinates inside the image therefore have negative Y on a north-up map (image Y
 * grows southward). Nothing depends on the origin choice — only on differences.
 */
data class ImagePoint(val xPx: Double, val yPx: Double)

data class MapPoint(val xMeters: Double, val yMeters: Double)

/** Pure calibration values for one map version (persisted per MapVersion in Room). */
data class Calibration(
    val metersPerPixel: Double,
    val northOffsetDegrees: Double,
) {
    init {
        require(metersPerPixel > 0) { "metersPerPixel must be > 0" }
    }
}

interface CoordinateTransformer {
    fun imageToMap(point: ImagePoint): MapPoint // px -> meters
    fun mapToImage(point: MapPoint): ImagePoint // meters -> px
}

/**
 * Transformer derived from a [Calibration].
 *
 * With θ = northOffsetDegrees, the image→map transform is the symmetric matrix
 *
 *   M = mpp · | cosθ  sinθ |        map = M · (ixPx, iyPx)
 *             | sinθ -cosθ |
 *
 * (a rotation combined with the Y flip between image-down and map-north; M is its own
 * inverse up to the mpp² factor, so mapToImage uses M / mpp²).
 *
 * Sanity anchors for the convention:
 *  θ = 0:   image-right = east, image-down = south (north-up plate).
 *  θ = 90:  image-right = north, image-down = east.
 *  θ = 180: image-down = north.
 */
class CalibrationTransformer(val calibration: Calibration) : CoordinateTransformer {

    private val mpp = calibration.metersPerPixel
    private val c = cos(Math.toRadians(calibration.northOffsetDegrees))
    private val s = sin(Math.toRadians(calibration.northOffsetDegrees))

    override fun imageToMap(point: ImagePoint): MapPoint = MapPoint(
        xMeters = mpp * (point.xPx * c + point.yPx * s),
        yMeters = mpp * (point.xPx * s - point.yPx * c),
    )

    override fun mapToImage(point: MapPoint): ImagePoint = ImagePoint(
        xPx = (point.xMeters * c + point.yMeters * s) / mpp,
        yPx = (point.xMeters * s - point.yMeters * c) / mpp,
    )
}
