package com.derrotero.data.vessel

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Destination (or active route waypoint) the vessel is steering to.
 *
 * [fromLat]/[fromLon] are the leg origin used to compute the cross-track error: the previous
 * waypoint of the route, or the position where "Go to" was pressed.
 */
data class NavigationTarget(
    val lat: Double,
    val lon: Double,
    val name: String,
    val fromLat: Double? = null,
    val fromLon: Double? = null,
    val routeId: String? = null,
    val waypointIndex: Int? = null,
)

/** Shared navigation state (survives navigation between screens and feeds the map/monitor). */
class NavigationRepository {

    private val _target = MutableStateFlow<NavigationTarget?>(null)
    val target: StateFlow<NavigationTarget?> = _target.asStateFlow()

    private val _activeRouteId = MutableStateFlow<String?>(null)
    val activeRouteId: StateFlow<String?> = _activeRouteId.asStateFlow()

    fun setTarget(target: NavigationTarget?) {
        _target.value = target
        if (target?.routeId != null) _activeRouteId.value = target.routeId
    }

    fun setActiveRoute(id: String?) {
        _activeRouteId.value = id
        if (id == null && _target.value?.routeId != null) _target.value = null
    }

    fun clear() {
        _target.value = null
        _activeRouteId.value = null
    }
}
