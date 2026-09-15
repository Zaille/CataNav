package com.catanav.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TripDao {
    @Insert
    suspend fun insert(trip: TripEntity): Long

    @Update
    suspend fun update(trip: TripEntity)

    @Query("SELECT * FROM trips WHERE id = :id")
    suspend fun byId(id: Long): TripEntity?

    @Query("SELECT * FROM trips ORDER BY startTime DESC")
    fun all(): Flow<List<TripEntity>>

    /**
     * A trip whose endTime is still null was interrupted (crash, battery pull) —
     * hard-constraint #4 requires it to come back as resumable on restart.
     */
    @Query("SELECT * FROM trips WHERE endTime IS NULL ORDER BY startTime DESC LIMIT 1")
    suspend fun unfinished(): TripEntity?

    @Query("UPDATE trips SET endTime = :endTime WHERE id = :id")
    suspend fun finish(id: Long, endTime: Long)

    @Query("DELETE FROM trips WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface TrackPointDao {
    /** Called once per generated point — this IS the autosave path. */
    @Insert
    suspend fun insert(point: TrackPointEntity): Long

    @Query("SELECT * FROM track_points WHERE tripId = :tripId ORDER BY timestampMs ASC, id ASC")
    suspend fun forTrip(tripId: Long): List<TrackPointEntity>

    @Query("SELECT * FROM track_points WHERE tripId = :tripId ORDER BY timestampMs ASC, id ASC")
    fun forTripFlow(tripId: Long): Flow<List<TrackPointEntity>>

    @Query("SELECT * FROM track_points WHERE tripId = :tripId ORDER BY timestampMs DESC, id DESC LIMIT 1")
    suspend fun lastForTrip(tripId: Long): TrackPointEntity?

    @Query("SELECT COUNT(*) FROM track_points WHERE tripId = :tripId")
    suspend fun countForTrip(tripId: Long): Int

    /** "Delete tracks only" — keeps the trip row and its notes. */
    @Query("DELETE FROM track_points WHERE tripId = :tripId")
    suspend fun deleteForTrip(tripId: Long)
}

/** Extra trip queries that join the map model. */
@Dao
interface TripMapDao {
    @Query(
        "SELECT COUNT(*) FROM trips WHERE mapVersionId IN " +
            "(SELECT id FROM map_versions WHERE mapId = :mapId)",
    )
    suspend fun tripCountForMap(mapId: Long): Int
}
