package com.catanav.map

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Pure (Android-free) viewport math for the map view: the mapping between image pixel
 * coordinates and screen coordinates under pan / zoom / view-only rotation.
 *
 * screen = R(rotationDeg) * scale * image + (offsetX, offsetY)
 *
 * Rotation is VIEW-ONLY: everything stored or computed by the PDR engine stays in
 * north-up image pixel space; only this transform (and therefore only what the user
 * sees) rotates.
 */
class MapViewport(
    val imageWidth: Int,
    val imageHeight: Int,
) {
    var scale: Double = 1.0
        private set
    var rotationDeg: Double = 0.0
        private set
    var offsetX: Double = 0.0
        private set
    var offsetY: Double = 0.0
        private set

    /** Axis-aligned rectangle in image pixel space. */
    data class ImageRect(val left: Double, val top: Double, val right: Double, val bottom: Double) {
        val width: Double get() = right - left
        val height: Double get() = bottom - top
        val isEmpty: Boolean get() = right <= left || bottom <= top
    }

    fun set(scale: Double, rotationDeg: Double, offsetX: Double, offsetY: Double) {
        this.scale = scale
        this.rotationDeg = rotationDeg
        this.offsetX = offsetX
        this.offsetY = offsetY
    }

    fun imageToScreen(ix: Double, iy: Double): DoubleArray {
        val r = Math.toRadians(rotationDeg)
        val c = cos(r)
        val s = sin(r)
        val x = scale * (c * ix - s * iy) + offsetX
        val y = scale * (s * ix + c * iy) + offsetY
        return doubleArrayOf(x, y)
    }

    fun screenToImage(sx: Double, sy: Double): DoubleArray {
        val r = Math.toRadians(rotationDeg)
        val c = cos(r)
        val s = sin(r)
        val dx = (sx - offsetX) / scale
        val dy = (sy - offsetY) / scale
        // Inverse rotation
        val ix = c * dx + s * dy
        val iy = -s * dx + c * dy
        return doubleArrayOf(ix, iy)
    }

    /**
     * Bounding box, in image pixel space, of what the (0,0)-(viewW,viewH) screen rect
     * can see — clamped to the image bounds. This is the region handed to
     * BitmapRegionDecoder, so it must be a superset of the truly visible pixels
     * (bounding box of the rotated quad, which it is by construction).
     */
    fun visibleImageRect(viewW: Int, viewH: Int): ImageRect {
        val corners = arrayOf(
            screenToImage(0.0, 0.0),
            screenToImage(viewW.toDouble(), 0.0),
            screenToImage(0.0, viewH.toDouble()),
            screenToImage(viewW.toDouble(), viewH.toDouble()),
        )
        var l = Double.MAX_VALUE
        var t = Double.MAX_VALUE
        var r = -Double.MAX_VALUE
        var b = -Double.MAX_VALUE
        for (cnr in corners) {
            l = min(l, cnr[0]); r = max(r, cnr[0])
            t = min(t, cnr[1]); b = max(b, cnr[1])
        }
        return ImageRect(
            left = max(0.0, floor(l)),
            top = max(0.0, floor(t)),
            right = min(imageWidth.toDouble(), ceil(r)),
            bottom = min(imageHeight.toDouble(), ceil(b)),
        )
    }

    /** Scale that fits the whole image inside the view (letterboxed). */
    fun fitScale(viewW: Int, viewH: Int): Double =
        min(viewW.toDouble() / imageWidth, viewH.toDouble() / imageHeight)

    /**
     * Clamp [candidate] between fit-to-screen and maximum zoom ([MAX_SCALE]).
     * If the view is larger than the image, maximum zoom is the minimum instead.
     */
    fun clampScale(candidate: Double, viewW: Int, viewH: Int): Double {
        val lo = min(fitScale(viewW, viewH), MAX_SCALE)
        val hi = max(fitScale(viewW, viewH), MAX_SCALE)
        return min(max(candidate, lo), hi)
    }

    /** Apply a user gesture delta around a screen-space focal point. */
    fun applyGesture(
        focusSx: Double,
        focusSy: Double,
        scaleFactor: Double,
        rotationDeltaDeg: Double,
        panDx: Double,
        panDy: Double,
        viewW: Int,
        viewH: Int,
    ) {
        // Image point currently under the focal point must stay under it after
        // scaling/rotating about that point.
        val anchorImage = screenToImage(focusSx, focusSy)
        val newScale = clampScale(scale * scaleFactor, viewW, viewH)
        rotationDeg = normalizeDeg(rotationDeg + rotationDeltaDeg)
        scale = newScale
        val mapped = imageToScreen(anchorImage[0], anchorImage[1])
        offsetX += focusSx - mapped[0] + panDx
        offsetY += focusSy - mapped[1] + panDy
    }

    companion object {
        const val MAX_SCALE: Double = 5.0

        /**
         * Largest power-of-two BitmapFactory sample size that still decodes at least
         * one image pixel per screen pixel at the given [scale] (screen px per image px).
         * scale >= 1 → 1; scale 0.5 → 2; scale 0.26 → 2; scale 0.25 → 4; capped at 32.
         */
        fun chooseSampleSize(scale: Double): Int {
            if (scale >= 1.0) return 1
            var sample = 1
            while (sample * 2 <= (1.0 / scale) && sample < 32) {
                sample *= 2
            }
            return sample
        }

        fun normalizeDeg(deg: Double): Double {
            var d = deg % 360.0
            if (d < 0) d += 360.0
            return d
        }
    }
}
