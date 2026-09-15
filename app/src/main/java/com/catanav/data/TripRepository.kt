package com.catanav.data

import kotlinx.coroutines.flow.Flow

class TripRepository(
    private val tripDao: TripDao,
    private val pointDao: TrackPointDao,
    private val tripMapDao: TripMapDao,
) {
    suspend fun startTrip(mapVersionId: Long, name: String, startTime: Long): Long =
        tripDao.insert(TripEntity(mapVersionId = mapVersionId, startTime = startTime, name = name))

    suspend fun tripCountForMap(mapId: Long): Int = tripMapDao.tripCountForMap(mapId)

    /** Autosave path — one insert per generated point (hard-constraint #4). */
    suspend fun appendPoint(point: TrackPointEntity) {
        pointDao.insert(point)
    }

    suspend fun finishTrip(tripId: Long, endTime: Long) = tripDao.finish(tripId, endTime)

    suspend fun resumableTrip(): TripEntity? = tripDao.unfinished()

    suspend fun trip(id: Long): TripEntity? = tripDao.byId(id)

    fun trips(): Flow<List<TripEntity>> = tripDao.all()

    suspend fun points(tripId: Long): List<TrackPointEntity> = pointDao.forTrip(tripId)

    fun pointsFlow(tripId: Long): Flow<List<TrackPointEntity>> = pointDao.forTripFlow(tripId)

    suspend fun lastPoint(tripId: Long): TrackPointEntity? = pointDao.lastForTrip(tripId)

    suspend fun updateTrip(trip: TripEntity) = tripDao.update(trip)

    suspend fun deleteTrackOnly(tripId: Long) = pointDao.deleteForTrip(tripId)

    suspend fun deleteTrip(tripId: Long) = tripDao.delete(tripId) // points cascade
}
