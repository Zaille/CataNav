package com.catanav.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A trip references the exact MAP VERSION it ran on — replacing or recalibrating a map
 * creates a new version and never invalidates history.
 */
@Entity(
    tableName = "trips",
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
data class TripEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mapVersionId: Long,
    val startTime: Long,
    /** Null while the trip is live — a non-null endTime is what "finished" means. */
    val endTime: Long? = null,
    val name: String = "",
    /** Free text: entrance used, planned route, who knows you're down there. */
    val notes: String = "",
)

enum class PointSource { PDR, ANCHOR }

@Entity(
    tableName = "track_points",
    foreignKeys = [
        ForeignKey(
            entity = TripEntity::class,
            parentColumns = ["id"],
            childColumns = ["tripId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("tripId")],
)
data class TrackPointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: Long,
    /** MAP-LOCAL METRIC coordinates (meters; X = east, Y = north) — the engine's space. */
    val xMeters: Double,
    val yMeters: Double,
    val timestampMs: Long,
    @ColumnInfo(name = "source") val source: PointSource,
    /** Heading at this point, degrees 0=N clockwise; null when unknown. */
    val headingDeg: Double? = null,
    /** Uncertainty radius (m) at the moment the point was recorded. */
    val uncertaintyMeters: Double? = null,
)
