package com.derrotero.data.resources

import com.derrotero.data.settings.SignalKConfig
import com.derrotero.data.signalk.Paths
import com.derrotero.data.signalk.SignalKClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.UUID

/**
 * Waypoints and routes through the SignalK **resources API**. Anything created here is
 * visible to the rest of the SignalK ecosystem (apps, and with the `signalk-to-nmea2000`
 * plugin, forwarded to the chartplotter over NMEA 2000 as PGN 130074).
 *
 * Two real-world details are handled here that the first version missed:
 *  - a route's `waypoints` array normally contains **href references** to waypoint resources,
 *    not embedded positions, so the referenced waypoints are fetched and resolved;
 *  - some servers create a resource without returning a `Location`, in which case the caller
 *    must PUT the locally generated identifier (otherwise later edits hit a 404).
 */
class ResourceRepository(
    private val client: SignalKClient,
    private val json: Json,
) {

    suspend fun waypoints(cfg: SignalKConfig): List<Waypoint> =
        client.getCollection(cfg, Paths.RES_WAYPOINTS).mapNotNull { (id, obj) ->
            parseWaypoint(id, obj.toString())
        }

    suspend fun routes(cfg: SignalKConfig): List<Route> {
        // Fetch the waypoint collection once: real routes reference their waypoints by href,
        // so resolving them one by one would mean dozens of HTTP requests.
        val known = client.getCollection(cfg, Paths.RES_WAYPOINTS)
        return client.getCollection(cfg, Paths.RES_ROUTES).mapNotNull { (id, obj) ->
            parseRoute(cfg, id, obj, known)
        }
    }

    /** Create or update a waypoint. Returns the identifier used. */
    suspend fun saveWaypoint(cfg: SignalKConfig, waypoint: Waypoint): String {
        val id = waypoint.id ?: UUID.randomUUID().toString()
        val body = encodeWaypoint(waypoint)
        if (waypoint.id != null) {
            return client.putResource(cfg, Paths.RES_WAYPOINTS, id, body)
        }
        val created = runCatching { client.postResource(cfg, Paths.RES_WAYPOINTS, body) }
            .getOrElse { client.putResource(cfg, Paths.RES_WAYPOINTS, id, body) }
        // No Location/body id: persist under our own id so PUT/DELETE can find it later.
        if (created.isBlank()) {
            client.putResource(cfg, Paths.RES_WAYPOINTS, id, body)
            return id
        }
        return created
    }

    suspend fun saveRoute(cfg: SignalKConfig, route: Route): String {
        val id = route.id ?: UUID.randomUUID().toString()
        val body = encodeRoute(route)
        if (route.id != null) {
            return client.putResource(cfg, Paths.RES_ROUTES, id, body)
        }
        val created = runCatching { client.postResource(cfg, Paths.RES_ROUTES, body) }
            .getOrElse { client.putResource(cfg, Paths.RES_ROUTES, id, body) }
        if (created.isBlank()) {
            client.putResource(cfg, Paths.RES_ROUTES, id, body)
            return id
        }
        return created
    }

    suspend fun deleteWaypoint(cfg: SignalKConfig, id: String) =
        client.deleteResource(cfg, Paths.RES_WAYPOINTS, id)

    suspend fun deleteRoute(cfg: SignalKConfig, id: String) =
        client.deleteResource(cfg, Paths.RES_ROUTES, id)

    /**
     * Ask the vessel to navigate a route (sets `navigation.course.activeRoute`).
     * Experimental: server support varies.
     */
    suspend fun activateRoute(cfg: SignalKConfig, routeId: String) {
        val body = buildJsonObject {
            put("href", "/resources/routes/$routeId")
        }
        client.putPath(cfg, Paths.ACTIVE_ROUTE, body)
    }

    /** Stop the active route / destination on the server (best effort). */
    suspend fun cancelActiveRoute(cfg: SignalKConfig) {
        runCatching { client.putPath(cfg, Paths.ACTIVE_ROUTE, JsonNull) }
    }

    /** Identifier of the active route, if the server exposes one. */
    suspend fun activeRouteId(cfg: SignalKConfig): String? {
        val active = client.getPath(cfg, Paths.ACTIVE_ROUTE) ?: return null
        return extractHref(active)
    }

    /**
     * "Go to here": creates a one-waypoint route, makes it the active route and asks the
     * autopilot to follow it (best effort; requires a SignalK autopilot plugin).
     */
    suspend fun goTo(cfg: SignalKConfig, name: String, lat: Double, lon: Double): String {
        val id = UUID.randomUUID().toString()
        val route = Route(
            name = "GoTo ${name.ifBlank { "punto" }}",
            waypoints = listOf(
                RouteWaypoint(name = name.ifBlank { "GoTo" }, position = Position(lat, lon)),
            ),
            id = id,
        )
        client.putResource(cfg, Paths.RES_ROUTES, id, encodeRoute(route))
        client.putPath(cfg, Paths.ACTIVE_ROUTE, buildJsonObject {
            put("href", "/resources/routes/$id")
        })
        runCatching {
            client.putPath(cfg, "steering.autopilot.state", buildJsonObject { put("value", "route") })
        }
        return id
    }

    /**
     * Inserts a point as the next stop of the active route: right after the active waypoint
     * when the server reports it, otherwise appended at the end.
     */
    suspend fun addToActiveRoute(
        cfg: SignalKConfig,
        name: String,
        lat: Double,
        lon: Double,
    ): AddToRouteResult {
        val id = activeRouteId(cfg) ?: return AddToRouteResult(false, false)
        val existing = client.getResource(cfg, Paths.RES_ROUTES, id)
            ?: return AddToRouteResult(false, false)
        val route = parseRoute(cfg, id, existing) ?: return AddToRouteResult(false, false)

        val activeIndex = activeWaypointIndex(cfg, route)
        val insertAt = if (activeIndex != null && activeIndex in route.waypoints.indices) {
            activeIndex + 1
        } else {
            route.waypoints.size
        }

        val waypoints = route.waypoints.toMutableList().apply {
            add(
                insertAt,
                RouteWaypoint(name = name.ifBlank { "WP" }, position = Position(lat, lon)),
            )
        }
        client.putResource(
            cfg,
            Paths.RES_ROUTES,
            id,
            encodeRoute(route.copy(waypoints = waypoints)),
        )
        return AddToRouteResult(
            added = true,
            afterActiveWaypoint = activeIndex != null,
            index = insertAt,
        )
    }

    /** Index of the active waypoint within the route, when the server exposes it. */
    private suspend fun activeWaypointIndex(cfg: SignalKConfig, route: Route): Int? {
        val element = client.getPath(cfg, Paths.ACTIVE_WP) ?: return null
        val obj = runCatching { element.jsonObject }.getOrNull() ?: return null
        val value = runCatching { obj["value"]?.jsonObject }.getOrNull() ?: obj

        runCatching { value["index"]?.jsonPrimitive?.intOrNull }.getOrNull()?.let { return it }

        val position = runCatching { value["position"]?.jsonObject }.getOrNull()
        if (position != null) {
            val lat = runCatching { position["latitude"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() }.getOrNull()
            val lon = runCatching { position["longitude"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() }.getOrNull()
            if (lat != null && lon != null) {
                val idx = route.waypoints.indexOfFirst {
                    kotlin.math.abs(it.position.latitude - lat) < 1e-6 &&
                        kotlin.math.abs(it.position.longitude - lon) < 1e-6
                }
                if (idx >= 0) return idx
            }
        }
        return null
    }

    private fun extractHref(element: JsonElement): String? {
        val obj = runCatching { element.jsonObject }.getOrNull() ?: return null
        val direct = runCatching { obj["href"]?.jsonPrimitive?.contentOrNull }.getOrNull()
        if (direct != null) return direct.substringAfterLast('/')
        val nested = runCatching { obj["value"]?.jsonObject }.getOrNull() ?: return null
        return runCatching { nested["href"]?.jsonPrimitive?.contentOrNull }.getOrNull()
            ?.substringAfterLast('/')
    }

    // ------------------------------------------------------------------ mapping

    private fun encodeWaypoint(w: Waypoint) = buildJsonObject {
        w.name?.let { put("name", it) }
        w.description?.let { put("description", it) }
        put("position", buildJsonObject {
            put("latitude", w.position.latitude)
            put("longitude", w.position.longitude)
        })
    }

    private fun encodeRoute(r: Route) = buildJsonObject {
        r.name?.let { put("name", it) }
        r.description?.let { put("description", it) }
        put("waypoints", buildJsonArray {
            r.waypoints.forEach { wp ->
                add(buildJsonObject {
                    wp.name?.let { put("name", it) }
                    put("position", buildJsonObject {
                        put("latitude", wp.position.latitude)
                        put("longitude", wp.position.longitude)
                    })
                })
            }
        })
    }

    /** Reads a waypoint resource, tolerating string numbers and GeoJSON features. */
    private fun parseWaypoint(id: String, raw: String): Waypoint? = runCatching {
        val obj = json.parseToJsonElement(raw).jsonObject
        val position = parsePosition(obj)
        if (position == null) return null
        Waypoint(
            name = obj.string("name") ?: obj.string("commonName"),
            description = obj.string("description"),
            position = position,
            id = id,
        )
    }.getOrNull()

    private suspend fun parseRoute(
        cfg: SignalKConfig,
        id: String,
        obj: JsonObject,
        knownWaypoints: Map<String, JsonObject>? = null,
    ): Route? {
        val wps = obj["waypoints"]?.let { resolveRoutePoints(cfg, it, knownWaypoints) }.orEmpty()
        return Route(
            name = obj.string("name") ?: obj.string("commonName"),
            description = obj.string("description"),
            waypoints = wps,
            id = id,
        )
    }

    /**
     * SignalK routes contain waypoint **references** (`{"href": "/resources/waypoints/<id>"}`)
     * rather than coordinates. Embedded coordinates are still accepted for compatibility.
     */
    private suspend fun resolveRoutePoints(
        cfg: SignalKConfig,
        element: JsonElement,
        knownWaypoints: Map<String, JsonObject>?,
    ): List<RouteWaypoint> {
        val items: List<JsonElement> = when (element) {
            is JsonArray -> element.toList()
            is JsonObject -> {
                if (element.containsKey("href") || element.containsKey("position")) {
                    listOf(element)
                } else {
                    element.values.toList()
                }
            }
            else -> return emptyList()
        }
        return items.mapNotNull { item ->
            val obj = item as? JsonObject
            if (obj != null) {
                val position = parsePosition(obj)
                val name = obj.string("name")
                if (position != null) {
                    return@mapNotNull RouteWaypoint(name = name, position = position)
                }
                val href = obj.string("href")
                if (href != null) {
                    return@mapNotNull resolveReference(cfg, href, name, knownWaypoints)
                }
            }
            val href = (item as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            resolveReference(cfg, href, null, knownWaypoints)
        }
    }

    private suspend fun resolveReference(
        cfg: SignalKConfig,
        href: String,
        fallbackName: String?,
        knownWaypoints: Map<String, JsonObject>?,
    ): RouteWaypoint? {
        val wpId = href.substringAfterLast('/').takeIf { it.isNotBlank() } ?: return null
        val cached = knownWaypoints?.get(wpId)
        if (cached != null) {
            val parsed = parseWaypoint(wpId, cached.toString()) ?: return null
            return RouteWaypoint(name = parsed.name ?: fallbackName, position = parsed.position)
        }
        val wp = client.getResource(cfg, Paths.RES_WAYPOINTS, wpId) ?: return null
        val parsed = parseWaypoint(wpId, wp.toString()) ?: return null
        return RouteWaypoint(name = parsed.name ?: fallbackName, position = parsed.position)
    }

    private fun parsePosition(obj: JsonObject): Position? {
        val pos = obj["position"] as? JsonObject
        if (pos != null) {
            val lat = number(pos["latitude"])
            val lon = number(pos["longitude"])
            if (lat != null && lon != null) return Position(lat, lon)
        }
        // GeoJSON feature fallback: {"geometry":{"coordinates":[lon,lat]}}
        val geometry = (obj["feature"] as? JsonObject)?.get("geometry") as? JsonObject
            ?: obj["geometry"] as? JsonObject
        val coords = geometry?.get("coordinates") as? JsonArray
        if (coords != null && coords.size >= 2) {
            val lon = number(coords[0])
            val lat = number(coords[1])
            if (lat != null && lon != null) return Position(lat, lon)
        }
        return null
    }

    private fun number(element: JsonElement?): Double? {
        val text = (element as? JsonPrimitive)?.contentOrNull ?: return null
        return text.replace(',', '.').toDoubleOrNull()
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: ((this[key] as? JsonObject)?.get("value") as? JsonPrimitive)?.contentOrNull
}
