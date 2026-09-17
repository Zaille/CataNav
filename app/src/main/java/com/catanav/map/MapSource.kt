package com.catanav.map

import android.graphics.BitmapRegionDecoder
import java.io.File

/**
 * Where a map raster comes from — the RASTER ONLY. Calibration lives in the map model
 * (MapVersion -> MapCalibration) and meets pixels exclusively through the
 * CoordinateTransformer. Kept as a small interface so a v2 tile-pyramid source could
 * be swapped in without touching the view (documented future idea — NOT implemented).
 */
interface MapSource {
    val widthPx: Int
    val heightPx: Int

    /** A fresh decoder; caller owns and must recycle it. */
    fun newRegionDecoder(): BitmapRegionDecoder
}

/** Any image file previously copied into app-private storage (imported maps). */
class FileMapSource(
    private val file: File,
    override val widthPx: Int,
    override val heightPx: Int,
) : MapSource {
    override fun newRegionDecoder(): BitmapRegionDecoder {
        @Suppress("DEPRECATION") // non-deprecated overloads need API 31+; minSdk is 26
        return BitmapRegionDecoder.newInstance(file.absolutePath, false)
            ?: error("could not open region decoder for ${file.name}")
    }
}
