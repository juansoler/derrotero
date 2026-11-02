package com.derrotero.data.sd

import android.util.Xml
import com.derrotero.data.resources.Position
import com.derrotero.data.resources.Route
import com.derrotero.data.resources.RouteWaypoint
import com.derrotero.data.resources.Waypoint
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream

/**
 * Small GPX reader for the user data found on chartplotter SD cards (waypoints and routes).
 * Imported items can be pushed to SignalK through [com.derrotero.data.resources.ResourceRepository].
 */
class GpxParser {

    data class Result(
        val waypoints: List<Waypoint> = emptyList(),
        val routes: List<Route> = emptyList(),
    )

    fun parse(input: InputStream): Result {
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(input, null)
        }

        val waypoints = mutableListOf<Waypoint>()
        val routes = mutableListOf<Route>()

        var pendingWpt: DoubleArray? = null
        var pendingWptName: String? = null
        var routeName: String? = null
        val routePoints = mutableListOf<RouteWaypoint>()
        var inRoute = false

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name.lowercase()) {
                    "wpt" -> {
                        pendingWpt = doubleArrayOf(
                            parser.getAttributeValue(null, "lat").toDoubleOrNull() ?: Double.NaN,
                            parser.getAttributeValue(null, "lon").toDoubleOrNull() ?: Double.NaN,
                        )
                        pendingWptName = null
                    }
                    "rte" -> {
                        inRoute = true
                        routeName = null
                        routePoints.clear()
                    }
                    "rtept" -> {
                        val lat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull()
                        val lon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull()
                        if (lat != null && lon != null) {
                            routePoints.add(RouteWaypoint(position = Position(lat, lon)))
                        }
                    }
                }
                XmlPullParser.TEXT -> {
                    val text = parser.text?.trim().orEmpty()
                    if (text.isNotEmpty()) {
                        if (pendingWpt != null && pendingWptName == null) pendingWptName = text
                        else if (inRoute && routeName == null) routeName = text
                    }
                }
                XmlPullParser.END_TAG -> when (parser.name.lowercase()) {
                    "wpt" -> {
                        val p = pendingWpt
                        if (p != null && !p[0].isNaN() && !p[1].isNaN()) {
                            waypoints.add(
                                Waypoint(name = pendingWptName, position = Position(p[0], p[1])),
                            )
                        }
                        pendingWpt = null
                        pendingWptName = null
                    }
                    "rte" -> {
                        if (routePoints.isNotEmpty()) {
                            routes.add(Route(name = routeName, waypoints = routePoints.toList()))
                        }
                        inRoute = false
                        routeName = null
                        routePoints.clear()
                    }
                }
            }
            event = parser.next()
        }
        return Result(waypoints, routes)
    }
}
