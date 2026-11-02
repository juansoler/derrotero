package com.derrotero.data.sd

import com.derrotero.data.resources.Route
import com.derrotero.data.resources.Waypoint
import com.derrotero.data.vessel.TrackPoint
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Minimal GPX 1.1 writer (OpenCPN and every chartplotter can read it).
 * Exports the track, the waypoints and the routes in a single file.
 */
object GpxWriter {

    private val ISO = DateTimeFormatter
        .ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        .withZone(ZoneOffset.UTC)

    fun write(
        out: OutputStream,
        waypoints: List<Waypoint>,
        routes: List<Route>,
        track: List<TrackPoint>,
        creator: String = "Derrotero",
    ) {
        val sb = StringBuilder(4096)
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<gpx version=\"1.1\" creator=\"").append(escape(creator)).append("\"\n")
        sb.append("     xmlns=\"http://www.topografix.com/GPX/1/1\"\n")
        sb.append("     xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n")
        sb.append("     xsi:schemaLocation=\"http://www.topografix.com/GPX/1/1 ")
        sb.append("http://www.topografix.com/GPX/1/1/gpx.xsd\">\n")
        sb.append("  <metadata><time>").append(ISO.format(Instant.now())).append("</time></metadata>\n")

        for (wp in waypoints) {
            sb.append("  <wpt lat=\"").append(fmt(wp.position.latitude))
                .append("\" lon=\"").append(fmt(wp.position.longitude)).append("\">\n")
            wp.name?.takeIf { it.isNotBlank() }?.let {
                sb.append("    <name>").append(escape(it)).append("</name>\n")
            }
            wp.description?.takeIf { it.isNotBlank() }?.let {
                sb.append("    <desc>").append(escape(it)).append("</desc>\n")
            }
            sb.append("  </wpt>\n")
        }

        for (route in routes) {
            sb.append("  <rte>\n")
            route.name?.takeIf { it.isNotBlank() }?.let {
                sb.append("    <name>").append(escape(it)).append("</name>\n")
            }
            route.description?.takeIf { it.isNotBlank() }?.let {
                sb.append("    <desc>").append(escape(it)).append("</desc>\n")
            }
            for (point in route.waypoints) {
                sb.append("    <rtept lat=\"").append(fmt(point.position.latitude))
                    .append("\" lon=\"").append(fmt(point.position.longitude)).append("\">\n")
                point.name?.takeIf { it.isNotBlank() }?.let {
                    sb.append("      <name>").append(escape(it)).append("</name>\n")
                }
                sb.append("    </rtept>\n")
            }
            sb.append("  </rte>\n")
        }

        if (track.size >= 2) {
            sb.append("  <trk>\n    <name>Estela</name>\n    <trkseg>\n")
            for (point in track) {
                sb.append("      <trkpt lat=\"").append(fmt(point.lat))
                    .append("\" lon=\"").append(fmt(point.lon)).append("\">")
                if (point.ts > 0) {
                    sb.append("<time>").append(ISO.format(Instant.ofEpochMilli(point.ts))).append("</time>")
                }
                sb.append("</trkpt>\n")
            }
            sb.append("    </trkseg>\n  </trk>\n")
        }

        sb.append("</gpx>\n")
        out.write(sb.toString().toByteArray(Charsets.UTF_8))
        out.flush()
    }

    private fun fmt(value: Double): String = String.format(Locale.US, "%.7f", value)

    private fun escape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}
