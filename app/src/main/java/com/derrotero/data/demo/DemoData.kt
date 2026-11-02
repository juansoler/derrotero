package com.derrotero.data.demo

import com.derrotero.data.resources.Position
import com.derrotero.data.resources.Route
import com.derrotero.data.resources.RouteWaypoint
import com.derrotero.data.resources.Waypoint

/**
 * Static waypoints/routes used by demo mode (no SignalK server needed).
 */
object DemoData {

    val waypoints = listOf(
        Waypoint(name = "Marina", position = Position(43.3230, -2.9820), id = "demo-wp-1"),
        Waypoint(name = "Boya cardinal N", position = Position(43.3160, -2.9560), id = "demo-wp-2"),
        Waypoint(name = "Fondeadero", position = Position(43.2980, -2.9330), id = "demo-wp-3"),
    )

    val routes = listOf(
        Route(
            name = "Ruta costera",
            waypoints = listOf(
                RouteWaypoint("Marina", position = Position(43.3230, -2.9820)),
                RouteWaypoint("Boya N", position = Position(43.3160, -2.9560)),
                RouteWaypoint("Fondeadero", position = Position(43.2980, -2.9330)),
            ),
            id = "demo-route-1",
        ),
    )

    val aisNames = listOf("Nordic Star", "Sea Breeze", "Blue Horizon")
    val aisMmsi = listOf("211234567", "211234568", "211234569")
}
