package com.catanav.data

import android.content.Context
import com.catanav.map.CatacombsRaster
import kotlinx.coroutines.flow.first

/**
 * One-time seed of the Paris Catacombs reference map — the map that WAS the whole v1
 * app, now just the first preconfigured example. Its calibration lives here as DATA
 * (map version + calibration rows), never as constants in any engine:
 * 7000x7000 px, 231 px = 100 m (0.433 m/px), north-up (offset 0).
 *
 * CAVEAT preserved from v1: this plate is a stitched historical composite, not an
 * orthophoto — the scale is accurate on average, not survey-grade uniform locally.
 * Manual re-anchoring exists precisely to bound the resulting error; nothing may
 * assume uniform local scale.
 */
object CatacombsSeeder {

    const val SEED_MAP_NAME = "Paris Catacombs (reference)"

    /** Idempotent: seeds only when no map with the seed name exists yet. Call on IO. */
    suspend fun seedIfNeeded(context: Context, maps: MapRepository) {
        // One-shot check without collecting the flow forever:
        val current = maps.maps().first()
        if (current.any { it.name == SEED_MAP_NAME }) return

        val file = CatacombsRaster.ensureLocalFile(context)
        val mapId = maps.importMap(
            name = SEED_MAP_NAME,
            imagePath = file.absolutePath,
            widthPx = CatacombsRaster.WIDTH_PX,
            heightPx = CatacombsRaster.HEIGHT_PX,
        )
        maps.calibrate(
            mapId = mapId,
            metersPerPixel = CatacombsRaster.METERS_PER_PIXEL,
            northOffsetDegrees = CatacombsRaster.NORTH_OFFSET_DEGREES,
            method = CalibrationMethod.TWO_POINT,
        )
    }
}
