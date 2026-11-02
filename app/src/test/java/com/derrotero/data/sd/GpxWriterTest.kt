package com.derrotero.data.sd

import com.derrotero.data.resources.Position
import com.derrotero.data.resources.Route
import com.derrotero.data.resources.RouteWaypoint
import com.derrotero.data.resources.Waypoint
import com.derrotero.data.vessel.TrackPoint
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class GpxWriterTest {

    @Test
    fun `exports waypoints routes and track as valid gpx`() {
        val out = ByteArrayOutputStream()
        GpxWriter.write(
            out = out,
            waypoints = listOf(
                Waypoint(name = "A & B", position = Position(43.3, -2.95)),
            ),
            routes = listOf(
                Route(
                    name = "Ruta <1>",
                    waypoints = listOf(
                        RouteWaypoint(name = "W1", position = Position(43.4, -2.9)),
                        RouteWaypoint(name = "W2", position = Position(43.5, -2.8)),
                    ),
                ),
            ),
            track = listOf(
                TrackPoint(43.3, -2.95, 0L),
                TrackPoint(43.31, -2.94, 1_700_000_000_000L),
            ),
        )
        val xml = out.toString(Charsets.UTF_8)

        assertTrue(xml.startsWith("<?xml version=\"1.0\""))
        assertTrue(xml.contains("<gpx version=\"1.1\""))
        assertTrue(xml.contains("<wpt lat=\"43.3000000\" lon=\"-2.9500000\">"))
        assertTrue(xml.contains("A &amp; B"))
        assertTrue(xml.contains("Ruta &lt;1&gt;"))
        assertTrue(xml.contains("<rtept"))
        assertTrue(xml.contains("<trkseg>"))
        assertTrue(xml.contains("<trkpt"))
        assertTrue(xml.contains("<time>"))
        assertTrue(xml.trimEnd().endsWith("</gpx>"))
    }

    @Test
    fun `omits empty track`() {
        val out = ByteArrayOutputStream()
        GpxWriter.write(out, emptyList(), emptyList(), emptyList())
        val xml = out.toString(Charsets.UTF_8)
        assertTrue(!xml.contains("<trk>"))
    }
}
