package com.catanav.export

import com.catanav.data.PointSource
import com.catanav.data.TrackPointEntity
import com.catanav.data.TripEntity
import com.catanav.domain.Calibration
import com.catanav.domain.CalibrationTransformer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Golden tests for the canonical v2 CSV: metric coordinates authoritative, pixel
 * columns derived through the CoordinateTransformer at export time.
 */
class CsvExporterTest {

    // North-up, 0.5 m/px: pixels = meters*2 in X, meters*-2 in Y.
    private val transformer = CalibrationTransformer(Calibration(0.5, 0.0))
    private val trip = TripEntity(id = 7, mapVersionId = 3, startTime = 0, endTime = 1, name = "T")
    private val mapId = 2L

    // 1700000000000 ms = 2023-11-14T22:13:20.000Z
    private val t0 = 1_700_000_000_000L

    @Test
    fun emptyTrip_headerOnly() {
        assertEquals(
            CsvExporter.HEADER + "\n",
            CsvExporter.export(trip, mapId, emptyList(), transformer),
        )
    }

    @Test
    fun goldenRows() {
        val points = listOf(
            TrackPointEntity(tripId = 7, xMeters = 50.0, yMeters = -100.0, timestampMs = t0, source = PointSource.ANCHOR, headingDeg = 0.0, uncertaintyMeters = 0.0),
            // 0.7 m east: x_px = 50.7*2 = 101.4 ; y_px = -(-100)*2 = 200
            TrackPointEntity(tripId = 7, xMeters = 50.7, yMeters = -100.0, timestampMs = t0 + 1000, source = PointSource.PDR, headingDeg = 90.0, uncertaintyMeters = 0.035),
            // unknown heading/uncertainty -> empty fields
            TrackPointEntity(tripId = 7, xMeters = 50.7, yMeters = -99.3, timestampMs = t0 + 2000, source = PointSource.PDR, headingDeg = null, uncertaintyMeters = null),
        )
        val expected = CsvExporter.HEADER + "\n" +
            "7,2,3,2023-11-14T22:13:20.000Z,50,-100,100,200,0.0,0.00,ANCHOR\n" +
            "7,2,3,2023-11-14T22:13:21.000Z,50.7,-100,101.4,200,90.0,0.04,PDR\n" +
            "7,2,3,2023-11-14T22:13:22.000Z,50.7,-99.3,101.4,198.6,,,PDR\n"
        assertEquals(expected, CsvExporter.export(trip, mapId, points, transformer))
    }

    @Test
    fun pixelColumns_respectNorthOffset() {
        // northOffset 90: north = image-right. A point 10 m north of the origin must
        // land at +20 px in X (0.5 m/px), 0 in Y.
        val rotated = CalibrationTransformer(Calibration(0.5, 90.0))
        val points = listOf(
            TrackPointEntity(tripId = 7, xMeters = 0.0, yMeters = 10.0, timestampMs = t0, source = PointSource.PDR, headingDeg = null, uncertaintyMeters = null),
        )
        val row = CsvExporter.export(trip, mapId, points, rotated).trim().lines()[1]
        val cols = row.split(",")
        assertEquals("0", cols[4])      // x_meters
        assertEquals("10", cols[5])     // y_meters
        assertEquals("20", cols[6])     // x_pixels
        assertEquals("0", cols[7])      // y_pixels (up to rounding of -0)
    }

    @Test
    fun metricColumns_areAuthoritative_fullPrecision() {
        val points = listOf(
            TrackPointEntity(tripId = 7, xMeters = 1234.567891, yMeters = 0.000001, timestampMs = t0, source = PointSource.PDR, headingDeg = null, uncertaintyMeters = null),
        )
        val row = CsvExporter.export(trip, mapId, points, transformer).trim().lines()[1]
        assertTrue(row.contains(",1234.567891,0.000001,"))
    }
}
