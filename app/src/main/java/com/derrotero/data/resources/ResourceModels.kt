package com.derrotero.data.resources

import kotlinx.serialization.Serializable

@Serializable
data class Position(val latitude: Double, val longitude: Double)

/** A SignalK `resources.waypoints` entry. */
@Serializable
data class Waypoint(
    val name: String? = null,
    val description: String? = null,
    val position: Position,
    /** Set when read back from the server (the resource id). */
    val id: String? = null,
)

/** One leg of a route resource. */
@Serializable
data class RouteWaypoint(
    val name: String? = null,
    val description: String? = null,
    val position: Position,
)

/** A SignalK `resources.routes` entry. */
@Serializable
data class Route(
    val name: String? = null,
    val description: String? = null,
    val waypoints: List<RouteWaypoint> = emptyList(),
    val id: String? = null,
)

/** Result of inserting a point into the active route. */
data class AddToRouteResult(
    val added: Boolean,
    val afterActiveWaypoint: Boolean,
    val index: Int = -1,
)
