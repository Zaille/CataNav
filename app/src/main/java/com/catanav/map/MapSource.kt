package com.catanav.map

import android.content.Context
import android.graphics.BitmapRegionDecoder
import java.io.File
import java.io.FileOutputStream

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

/** Any image file previously copied into app-private storage (imported maps, seeds). */
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

/**
 * The Paris Catacombs reference raster: assets/catacombs_map.jpg when bundled,
 * otherwise a generated full-size placeholder (see PlaceholderMapGenerator). Used by
 * the one-time seeder; the seeded MapDefinition then points at the resolved file.
 */
object CatacombsRaster {

    const val ASSET_NAME = "catacombs_map.jpg"
    const val WIDTH_PX = 7000
    const val HEIGHT_PX = 7000

    /** 231 px = 100 m on the printed scale bar. */
    const val METERS_PER_PIXEL = 0.433

    /** Compass rose confirms the plate is north-up. */
    const val NORTH_OFFSET_DEGREES = 0.0

    private fun mapsDir(context: Context): File =
        File(context.filesDir, "maps").apply { mkdirs() }

    /** Resolves (copying/generating if needed) the raster file. Blocking — call on IO. */
    fun ensureLocalFile(context: Context): File {
        val dir = mapsDir(context)
        val assetCopy = File(dir, "catacombs_map.jpg")
        if (assetCopy.exists()) return assetCopy
        val hasAsset = try {
            context.assets.open(ASSET_NAME).use { true }
        } catch (_: java.io.IOException) {
            false
        }
        if (hasAsset) {
            val tmp = File(dir, "catacombs_map.jpg.tmp")
            context.assets.open(ASSET_NAME).use { input ->
                FileOutputStream(tmp).use { input.copyTo(it) }
            }
            if (!tmp.renameTo(assetCopy)) error("could not move map into place")
            return assetCopy
        }
        val placeholder = File(dir, "placeholder_catacombs.png")
        if (!placeholder.exists()) {
            val tmp = File(dir, "placeholder.tmp")
            FileOutputStream(tmp).buffered().use { out ->
                PlaceholderMapGenerator.generate(out, WIDTH_PX, HEIGHT_PX, METERS_PER_PIXEL)
            }
            if (!tmp.renameTo(placeholder)) error("could not move placeholder into place")
        }
        return placeholder
    }
}
