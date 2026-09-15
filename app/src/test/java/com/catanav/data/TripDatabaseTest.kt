package com.catanav.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TripDatabaseTest {

    private lateinit var db: CataNavDatabase
    private lateinit var repo: TripRepository
    private lateinit var maps: MapRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, CataNavDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = TripRepository(db.tripDao(), db.trackPointDao(), db.tripMapDao())
        maps = MapRepository(db.mapDao(), db.anchorDao(), db.calibrationTestDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** Create a calibrated map and return its active version id. */
    private suspend fun calibratedVersion(name: String = "Test map"): Long {
        val mapId = maps.importMap(name, "/data/fake.png", 5000, 4000)
        return maps.calibrate(mapId, 0.5, 0.0)
    }

    @Test
    fun insertTrip_andPoints_roundTrip() = runBlocking {
        val versionId = calibratedVersion()
        val id = repo.startTrip(versionId, "Test trip", startTime = 1000L)
        repo.appendPoint(TrackPointEntity(tripId = id, xMeters = 10.0, yMeters = -20.0, timestampMs = 1000, source = PointSource.ANCHOR, headingDeg = 12.5, uncertaintyMeters = 0.0))
        repo.appendPoint(TrackPointEntity(tripId = id, xMeters = 10.7, yMeters = -19.3, timestampMs = 1500, source = PointSource.PDR, headingDeg = 45.0, uncertaintyMeters = 0.04))

        val points = repo.points(id)
        assertEquals(2, points.size)
        assertEquals(PointSource.ANCHOR, points[0].source)
        assertEquals(10.0, points[0].xMeters, 0.0)
        assertEquals(12.5, points[0].headingDeg!!, 0.0)
        assertEquals(PointSource.PDR, points[1].source)
        assertEquals(0.04, points[1].uncertaintyMeters!!, 0.0)
        assertEquals(versionId, repo.trip(id)!!.mapVersionId)
    }

    @Test
    fun interruptedTrip_isRecoveredAsResumable_withAllAutosavedPoints() = runBlocking {
        // A trip that was never finished — process death mid-trip (constraint #4).
        val versionId = calibratedVersion()
        val id = repo.startTrip(versionId, "Interrupted", startTime = 5000L)
        repo.appendPoint(TrackPointEntity(tripId = id, xMeters = 100.0, yMeters = 100.0, timestampMs = 5000, source = PointSource.ANCHOR, headingDeg = 0.0))
        for (i in 1..50) {
            repo.appendPoint(TrackPointEntity(tripId = id, xMeters = 100.0 + i * 0.7, yMeters = 100.0, timestampMs = 5000L + i * 600, source = PointSource.PDR, headingDeg = 90.0))
        }
        // "Restart": a fresh repository over the same database — nothing in memory.
        val restarted = TripRepository(db.tripDao(), db.trackPointDao(), db.tripMapDao())
        val resumable = restarted.resumableTrip()
        assertNotNull("interrupted trip must be resumable", resumable)
        assertEquals(id, resumable!!.id)
        assertNull(resumable.endTime)
        assertEquals("resume must know the trip's map version", versionId, resumable.mapVersionId)
        val points = restarted.points(id)
        assertEquals("all autosaved points survive", 51, points.size)
        val last = restarted.lastPoint(id)!!
        assertEquals(135.0, last.xMeters, 1e-9)
    }

    @Test
    fun finishedTrip_isNotResumable() = runBlocking {
        val versionId = calibratedVersion()
        val id = repo.startTrip(versionId, "Done", startTime = 100L)
        repo.finishTrip(id, endTime = 900L)
        assertNull(repo.resumableTrip())
        assertEquals(900L, repo.trip(id)!!.endTime)
    }

    @Test
    fun mostRecentUnfinishedTrip_wins() = runBlocking {
        val versionId = calibratedVersion()
        repo.startTrip(versionId, "Older", startTime = 100L)
        val newer = repo.startTrip(versionId, "Newer", startTime = 200L)
        assertEquals(newer, repo.resumableTrip()!!.id)
    }

    @Test
    fun deleteTrackOnly_keepsTripAndNotes() = runBlocking {
        val versionId = calibratedVersion()
        val id = repo.startTrip(versionId, "Keep me", startTime = 1L)
        repo.updateTrip(repo.trip(id)!!.copy(notes = "entrance: rue X"))
        repo.appendPoint(TrackPointEntity(tripId = id, xMeters = 1.0, yMeters = 2.0, timestampMs = 1, source = PointSource.ANCHOR))
        repo.deleteTrackOnly(id)
        assertEquals(0, repo.points(id).size)
        assertEquals("entrance: rue X", repo.trip(id)!!.notes)
    }

    @Test
    fun deleteTrip_cascadesPoints() = runBlocking {
        val versionId = calibratedVersion()
        val id = repo.startTrip(versionId, "Gone", startTime = 1L)
        repo.appendPoint(TrackPointEntity(tripId = id, xMeters = 1.0, yMeters = 2.0, timestampMs = 1, source = PointSource.ANCHOR))
        repo.deleteTrip(id)
        assertNull(repo.trip(id))
        assertEquals(0, repo.points(id).size)
    }

    @Test
    fun deleteMap_cascadesEverything() = runBlocking {
        val mapId = maps.importMap("Doomed", "/data/fake.png", 100, 100)
        val versionId = maps.calibrate(mapId, 1.0, 0.0)
        val tripId = repo.startTrip(versionId, "On doomed map", startTime = 1L)
        maps.addAnchor(AnchorEntity(mapVersionId = versionId, xMeters = 1.0, yMeters = 1.0, name = "A", createdAt = 1L))
        maps.delete(mapId)
        assertNull(repo.trip(tripId))
        assertEquals(0, maps.anchors(versionId).size)
    }

    @Test
    fun tripsFlow_ordersNewestFirst() = runBlocking {
        val versionId = calibratedVersion()
        repo.startTrip(versionId, "A", startTime = 100L)
        repo.startTrip(versionId, "B", startTime = 300L)
        repo.startTrip(versionId, "C", startTime = 200L)
        val names = repo.trips().first().map { it.name }
        assertEquals(listOf("B", "C", "A"), names)
    }

    @Test
    fun tripCountForMap_countsAcrossVersions() = runBlocking {
        val mapId = maps.importMap("Multi", "/data/fake.png", 100, 100)
        val v1 = maps.calibrate(mapId, 1.0, 0.0)
        repo.startTrip(v1, "on v1", startTime = 1L)
        val v2 = maps.calibrate(mapId, 1.1, 0.0) // recalibration
        repo.startTrip(v2, "on v2", startTime = 2L)
        assertEquals(2, repo.tripCountForMap(mapId))
    }
}
