package com.catanav.data

import com.catanav.domain.Calibration
import com.catanav.domain.CalibrationTransformer
import com.catanav.domain.CoordinateTransformer
import kotlinx.coroutines.flow.Flow

/** Everything a screen needs to render/navigate one calibrated map version. */
data class ActiveMap(
    val map: MapDefinitionEntity,
    val version: MapVersionEntity,
    val calibrationEntity: MapCalibrationEntity,
) {
    val calibration: Calibration
        get() = Calibration(calibrationEntity.metersPerPixel, calibrationEntity.northOffsetDegrees)

    fun transformer(): CoordinateTransformer = CalibrationTransformer(calibration)
}

class MapRepository(
    private val mapDao: MapDao,
    private val anchorDao: AnchorDao,
    private val calibrationTestDao: CalibrationTestDao,
) {
    suspend fun importMap(name: String, imagePath: String, widthPx: Int, heightPx: Int): Long =
        mapDao.insertMap(
            MapDefinitionEntity(
                name = name,
                imageUri = imagePath,
                widthPx = widthPx,
                heightPx = heightPx,
                createdAt = System.currentTimeMillis(),
            ),
        )

    fun maps(): Flow<List<MapDefinitionEntity>> = mapDao.maps()

    suspend fun map(id: Long): MapDefinitionEntity? = mapDao.mapById(id)

    suspend fun rename(id: Long, name: String) = mapDao.renameMap(id, name)

    suspend fun delete(id: Long) = mapDao.deleteMap(id) // versions/calibrations/anchors cascade

    suspend fun calibrate(
        mapId: Long,
        metersPerPixel: Double,
        northOffsetDegrees: Double,
        method: CalibrationMethod = CalibrationMethod.TWO_POINT,
    ): Long = mapDao.calibrate(mapId, metersPerPixel, northOffsetDegrees, method, System.currentTimeMillis())

    /** Null while the map has never been calibrated — trips cannot start then. */
    suspend fun activeMap(mapId: Long): ActiveMap? {
        val map = mapDao.mapById(mapId) ?: return null
        val versionId = map.activeVersionId ?: return null
        val version = mapDao.versionById(versionId) ?: return null
        val calibration = mapDao.calibrationForVersion(versionId) ?: return null
        return ActiveMap(map, version, calibration)
    }

    suspend fun mapVersion(versionId: Long): MapVersionEntity? = mapDao.versionById(versionId)

    /**
     * Context for a SPECIFIC version (not necessarily the active one) — used when
     * viewing or resuming an old trip after the map was recalibrated.
     */
    suspend fun boundMapForVersion(versionId: Long): ActiveMap? {
        val version = mapDao.versionById(versionId) ?: return null
        val calibration = mapDao.calibrationForVersion(versionId) ?: return null
        val map = mapDao.mapById(version.mapId) ?: return null
        return ActiveMap(map, version, calibration)
    }

    /** First calibrated map, if any — the default the navigation screen falls back to. */
    suspend fun firstCalibratedMap(): ActiveMap? {
        for (m in mapDao.mapsOnce()) {
            val id = m.activeVersionId ?: continue
            val v = mapDao.versionById(id) ?: continue
            val c = mapDao.calibrationForVersion(id) ?: continue
            return ActiveMap(m, v, c)
        }
        return null
    }

    suspend fun calibrationForVersion(versionId: Long): MapCalibrationEntity? =
        mapDao.calibrationForVersion(versionId)

    // ---- anchors -----------------------------------------------------------------

    suspend fun addAnchor(anchor: AnchorEntity): Long = anchorDao.insert(anchor)

    suspend fun anchors(versionId: Long): List<AnchorEntity> = anchorDao.forVersion(versionId)

    suspend fun namedAnchors(versionId: Long): List<AnchorEntity> =
        anchorDao.namedForVersion(versionId)

    suspend fun deleteAnchor(id: Long) = anchorDao.delete(id)

    // ---- device calibration-test history --------------------------------------------

    suspend fun recordCalibrationTest(result: CalibrationTestResultEntity): Long =
        calibrationTestDao.insert(result)

    suspend fun calibrationTestHistory(): List<CalibrationTestResultEntity> =
        calibrationTestDao.history()
}
