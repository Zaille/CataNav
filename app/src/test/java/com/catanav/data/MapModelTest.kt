package com.catanav.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Trip -> MapVersion integrity contract: recalibrating a map creates a NEW version
 * and leaves old trips (and their version's calibration) untouched.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MapModelTest {

    private lateinit var db: CataNavDatabase
    private lateinit var maps: MapRepository
    private lateinit var trips: TripRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, CataNavDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        maps = MapRepository(db.mapDao(), db.anchorDao(), db.calibrationTestDao())
        trips = TripRepository(db.tripDao(), db.trackPointDao(), db.tripMapDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun importedMap_hasNoActiveVersion_untilCalibrated() = runBlocking {
        val mapId = maps.importMap("Fresh import", "/data/img.png", 3000, 2000)
        val map = maps.map(mapId)!!
        assertNull("uncalibrated map cannot navigate", map.activeVersionId)
        assertNull(maps.activeMap(mapId))
    }

    @Test
    fun calibrate_createsVersionWithCalibration_andActivatesIt() = runBlocking {
        val mapId = maps.importMap("Cave", "/data/img.png", 3000, 2000)
        val versionId = maps.calibrate(mapId, 0.25, 37.5)
        val active = maps.activeMap(mapId)
        assertNotNull(active)
        assertEquals(versionId, active!!.version.id)
        assertEquals(0.25, active.calibrationEntity.metersPerPixel, 0.0)
        assertEquals(37.5, active.calibrationEntity.northOffsetDegrees, 0.0)
        assertEquals(CalibrationMethod.TWO_POINT, active.calibrationEntity.method)
        assertEquals("version records its calibration id", active.calibrationEntity.id, active.version.calibrationId)
        // The version snapshots the raster info.
        assertEquals(3000, active.version.widthPx)
        assertEquals("/data/img.png", active.version.imageUri)
    }

    @Test
    fun recalibration_createsNewVersion_oldTripsUntouched() = runBlocking {
        val mapId = maps.importMap("Cave", "/data/img.png", 3000, 2000)
        val v1 = maps.calibrate(mapId, 0.25, 0.0)
        val tripId = trips.startTrip(v1, "old trip", startTime = 1L)

        val v2 = maps.calibrate(mapId, 0.30, 15.0) // user recalibrates
        assertNotEquals(v1, v2)

        // Active version moved...
        assertEquals(v2, maps.map(mapId)!!.activeVersionId)
        // ...but the old trip still references v1, whose calibration is intact.
        val oldTrip = trips.trip(tripId)!!
        assertEquals(v1, oldTrip.mapVersionId)
        val oldBound = maps.boundMapForVersion(v1)!!
        assertEquals(0.25, oldBound.calibrationEntity.metersPerPixel, 0.0)
        assertEquals(0.0, oldBound.calibrationEntity.northOffsetDegrees, 0.0)
    }

    @Test
    fun namedAnchors_perVersion_reusableAndListed() = runBlocking {
        val mapId = maps.importMap("Cave", "/data/img.png", 3000, 2000)
        val v1 = maps.calibrate(mapId, 0.25, 0.0)
        maps.addAnchor(AnchorEntity(mapVersionId = v1, xMeters = 10.0, yMeters = 20.0, name = "Junction 14", createdAt = 1L))
        maps.addAnchor(AnchorEntity(mapVersionId = v1, xMeters = 0.0, yMeters = 0.0, name = null, createdAt = 2L))
        maps.addAnchor(AnchorEntity(mapVersionId = v1, xMeters = 5.0, yMeters = 5.0, name = "Entrance", createdAt = 3L))

        assertEquals(3, maps.anchors(v1).size)
        val named = maps.namedAnchors(v1)
        assertEquals(listOf("Entrance", "Junction 14"), named.map { it.name })

        // Anchors belong to a version: a recalibrated version starts empty.
        val v2 = maps.calibrate(mapId, 0.30, 0.0)
        assertTrue(maps.anchors(v2).isEmpty())
    }

    @Test
    fun calibrationTestHistory_roundTrips() = runBlocking {
        maps.recordCalibrationTest(
            CalibrationTestResultEntity(
                timestampMs = 42L,
                knownDistanceM = 50.0,
                estimatedDistanceM = 52.5,
                errorPercent = 5.0,
                stepLengthUsedM = 0.75,
            ),
        )
        val history = maps.calibrationTestHistory()
        assertEquals(1, history.size)
        assertEquals(5.0, history[0].errorPercent, 0.0)
        assertEquals(0.75, history[0].stepLengthUsedM, 0.0)
    }

    @Test
    fun firstCalibratedMap_skipsUncalibratedOnes() = runBlocking {
        maps.importMap("Uncalibrated", "/a.png", 10, 10)
        val mapId = maps.importMap("Calibrated", "/b.png", 10, 10)
        maps.calibrate(mapId, 1.0, 0.0)
        assertEquals("Calibrated", maps.firstCalibratedMap()!!.map.name)
    }
}
