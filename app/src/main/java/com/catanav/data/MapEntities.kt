package com.catanav.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * v2 map model: MapDefinition -> MapVersion -> MapCalibration. Trips reference the
 * exact MAP VERSION they ran on, so recalibrating or replacing a map creates a new
 * version and never invalidates history.
 */
@Entity(tableName = "maps")
data class MapDefinitionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** App-private file path of the raster (copied on import — SAF URIs don't persist). */
    val imageUri: String,
    val widthPx: Int,
    val heightPx: Int,
    val createdAt: Long,
    /** Null until the map has been calibrated at least once. */
    val activeVersionId: Long? = null,
)

@Entity(
    tableName = "map_versions",
    foreignKeys = [
        ForeignKey(
            entity = MapDefinitionEntity::class,
            parentColumns = ["id"],
            childColumns = ["mapId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("mapId")],
)
data class MapVersionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mapId: Long,
    val imageUri: String,
    val widthPx: Int,
    val heightPx: Int,
    val createdAt: Long,
    /** Filled right after the paired calibration row is inserted. */
    val calibrationId: Long? = null,
)

/**
 * How a calibration was obtained. Only TWO_POINT is implemented in v2;
 * MULTI_POINT (affine) and GEOREFERENCED are reserved for future work.
 */
enum class CalibrationMethod { TWO_POINT, MULTI_POINT, GEOREFERENCED }

@Entity(
    tableName = "map_calibrations",
    foreignKeys = [
        ForeignKey(
            entity = MapVersionEntity::class,
            parentColumns = ["id"],
            childColumns = ["mapVersionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("mapVersionId")],
)
data class MapCalibrationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mapVersionId: Long,
    val metersPerPixel: Double,
    /** Degrees CLOCKWISE from image-up to true north — see domain/Coordinates.kt. */
    val northOffsetDegrees: Double,
    val method: CalibrationMethod,
    val createdAt: Long,
)

/** Named, reusable anchors ("Junction 14", "Entrance") — per map VERSION, in meters. */
@Entity(
    tableName = "anchors",
    foreignKeys = [
        ForeignKey(
            entity = MapVersionEntity::class,
            parentColumns = ["id"],
            childColumns = ["mapVersionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("mapVersionId")],
)
data class AnchorEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mapVersionId: Long,
    val xMeters: Double,
    val yMeters: Double,
    val name: String? = null,
    val notes: String? = null,
    val createdAt: Long,
)

/**
 * DEVICE-profile calibration-test history (user walks a known distance; PDR estimate
 * compared). Describes the user/device — never the map; kept strictly separate from
 * map calibration.
 */
@Entity(tableName = "calibration_tests")
data class CalibrationTestResultEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestampMs: Long,
    val knownDistanceM: Double,
    val estimatedDistanceM: Double,
    val errorPercent: Double,
    val stepLengthUsedM: Double,
)
