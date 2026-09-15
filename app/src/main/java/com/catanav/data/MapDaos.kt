package com.catanav.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface MapDao {
    @Insert
    suspend fun insertMap(map: MapDefinitionEntity): Long

    @Update
    suspend fun updateMap(map: MapDefinitionEntity)

    @Query("SELECT * FROM maps WHERE id = :id")
    suspend fun mapById(id: Long): MapDefinitionEntity?

    @Query("SELECT * FROM maps ORDER BY createdAt DESC")
    fun maps(): Flow<List<MapDefinitionEntity>>

    @Query("SELECT * FROM maps ORDER BY createdAt DESC")
    suspend fun mapsOnce(): List<MapDefinitionEntity>

    @Query("DELETE FROM maps WHERE id = :id")
    suspend fun deleteMap(id: Long)

    @Query("UPDATE maps SET name = :name WHERE id = :id")
    suspend fun renameMap(id: Long, name: String)

    @Insert
    suspend fun insertVersion(version: MapVersionEntity): Long

    @Update
    suspend fun updateVersion(version: MapVersionEntity)

    @Query("SELECT * FROM map_versions WHERE id = :id")
    suspend fun versionById(id: Long): MapVersionEntity?

    @Query("SELECT * FROM map_versions WHERE mapId = :mapId ORDER BY createdAt DESC")
    suspend fun versionsForMap(mapId: Long): List<MapVersionEntity>

    @Insert
    suspend fun insertCalibration(calibration: MapCalibrationEntity): Long

    @Query("SELECT * FROM map_calibrations WHERE mapVersionId = :versionId ORDER BY createdAt DESC LIMIT 1")
    suspend fun calibrationForVersion(versionId: Long): MapCalibrationEntity?

    /**
     * Recalibration NEVER mutates an existing version: it snapshots a new version
     * (same raster) with its own calibration and points the map's activeVersionId at
     * it. Old trips keep referencing their original version untouched.
     */
    @Transaction
    suspend fun calibrate(
        mapId: Long,
        metersPerPixel: Double,
        northOffsetDegrees: Double,
        method: CalibrationMethod,
        now: Long,
    ): Long {
        val map = mapById(mapId) ?: error("map $mapId not found")
        val versionId = insertVersion(
            MapVersionEntity(
                mapId = mapId,
                imageUri = map.imageUri,
                widthPx = map.widthPx,
                heightPx = map.heightPx,
                createdAt = now,
            ),
        )
        val calibrationId = insertCalibration(
            MapCalibrationEntity(
                mapVersionId = versionId,
                metersPerPixel = metersPerPixel,
                northOffsetDegrees = northOffsetDegrees,
                method = method,
                createdAt = now,
            ),
        )
        updateVersion(versionById(versionId)!!.copy(calibrationId = calibrationId))
        updateMap(map.copy(activeVersionId = versionId))
        return versionId
    }
}

@Dao
interface AnchorDao {
    @Insert
    suspend fun insert(anchor: AnchorEntity): Long

    @Query("SELECT * FROM anchors WHERE mapVersionId = :versionId ORDER BY createdAt DESC")
    suspend fun forVersion(versionId: Long): List<AnchorEntity>

    @Query("SELECT * FROM anchors WHERE mapVersionId = :versionId AND name IS NOT NULL ORDER BY name")
    suspend fun namedForVersion(versionId: Long): List<AnchorEntity>

    @Query("DELETE FROM anchors WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface CalibrationTestDao {
    @Insert
    suspend fun insert(result: CalibrationTestResultEntity): Long

    @Query("SELECT * FROM calibration_tests ORDER BY timestampMs DESC")
    suspend fun history(): List<CalibrationTestResultEntity>
}
