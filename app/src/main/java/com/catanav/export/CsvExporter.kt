package com.catanav.export

import com.catanav.data.TrackPointEntity
import com.catanav.data.TripEntity
import com.catanav.domain.CoordinateTransformer
import com.catanav.domain.MapPoint
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Canonical CSV export — METRIC coordinates are authoritative; pixel columns are
 * derived through the CoordinateTransformer at export time. ANCHOR rows show exactly
 * where and by how much drift was corrected.
 */
object CsvExporter {

    const val HEADER = "trip_id,map_id,map_version_id,timestamp,x_meters,y_meters," +
        "x_pixels,y_pixels,heading_degrees,uncertainty_meters,source"

    private val TIME_FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

    fun export(
        trip: TripEntity,
        mapId: Long,
        points: List<TrackPointEntity>,
        transformer: CoordinateTransformer,
    ): String {
        val sb = StringBuilder(HEADER).append('\n')
        for (p in points) {
            val px = transformer.mapToImage(MapPoint(p.xMeters, p.yMeters))
            sb.append(trip.id).append(',')
                .append(mapId).append(',')
                .append(trip.mapVersionId).append(',')
                .append(TIME_FORMAT.format(Instant.ofEpochMilli(p.timestampMs))).append(',')
                .append(fmt(p.xMeters)).append(',')
                .append(fmt(p.yMeters)).append(',')
                .append(fmt(px.xPx)).append(',')
                .append(fmt(px.yPx)).append(',')
                .append(p.headingDeg?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "").append(',')
                .append(p.uncertaintyMeters?.let { String.format(Locale.ROOT, "%.2f", it) } ?: "").append(',')
                .append(p.source.name)
                .append('\n')
        }
        return sb.toString()
    }

    /** Full precision but human-friendly: no scientific notation, no trailing zeros. */
    private fun fmt(v: Double): String {
        val s = String.format(Locale.ROOT, "%.6f", v).trimEnd('0').trimEnd('.')
        return if (s == "-0") "0" else s
    }
}
