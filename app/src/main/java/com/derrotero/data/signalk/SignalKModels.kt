package com.derrotero.data.signalk

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/** A SignalK delta message (stream payload). */
@Serializable
data class DeltaEnvelope(
    val context: String? = null,
    val updates: List<SignalKUpdate> = emptyList(),
)

@Serializable
data class SignalKUpdate(
    val source: JsonObject? = null,
    val timestamp: String? = null,
    val values: List<SignalKPathValue> = emptyList(),
)

/**
 * One path/value pair inside a delta.
 *
 * [value] has a default because a server may publish a meta-only update
 * (`{"path": "...", "meta": {...}}`). With a required field the whole delta used to
 * fail to deserialize and was silently dropped.
 */
@Serializable
data class SignalKPathValue(
    val path: String,
    val value: JsonElement = JsonNull,
)

/** Minimal model of the SignalK server root (`GET /signalk/v1/api/`). */
@Serializable
data class ServerInfo(
    val server: ServerVersion? = null,
    /** Some servers expose the self context at the root of the discovery document. */
    val self: String? = null,
)

@Serializable
data class ServerVersion(
    val id: String? = null,
    val version: String? = null,
)

/** Well known SignalK paths used by the app. */
object Paths {
    const val POSITION = "navigation.position"
    const val SOG = "navigation.speedOverGround"
    const val COG = "navigation.courseOverGroundTrue"
    const val COG_MAG = "navigation.courseOverGroundMagnetic"
    const val HEADING_TRUE = "navigation.headingTrue"
    const val HEADING_MAG = "navigation.headingMagnetic"
    const val HEADING_COMPASS = "navigation.headingCompass"
    const val MAGNETIC_VARIATION = "navigation.magneticVariation"
    const val STW = "navigation.speedThroughWater"
    const val RATE_OF_TURN = "navigation.rateOfTurn"
    const val NAV_STATE = "navigation.state"
    const val DEPTH = "environment.depth.belowTransducer"
    const val DEPTH_KEEL = "environment.depth.belowKeel"
    const val DEPTH_SURFACE = "environment.depth.belowSurface"
    const val WATER_TEMP = "environment.water.temperature"
    const val WIND_SPEED_APPARENT = "environment.wind.speedApparent"
    const val WIND_ANGLE_APPARENT = "environment.wind.angleApparent"
    const val WIND_SPEED_TRUE = "environment.wind.speedTrue"
    const val WIND_ANGLE_TRUE = "environment.wind.angleTrueWater"
    const val WIND_ANGLE_TRUE_GROUND = "environment.wind.angleTrueGround"
    const val WIND_DIRECTION_TRUE = "environment.wind.directionTrue"
    const val WIND_DIRECTION_MAG = "environment.wind.directionMagnetic"
    const val CURRENT_SET_TRUE = "environment.current.setTrue"
    const val CURRENT_DRIFT = "environment.current.drift"
    const val LEEWAY = "navigation.leewayAngle"
    const val AIR_TEMP = "environment.outside.temperature"
    const val PRESSURE = "environment.outside.pressure"
    const val GNSS_SATELLITES = "navigation.gnss.satellites"
    const val GNSS_HDOP = "navigation.gnss.horizontalDilution"
    const val XTE = "navigation.course.calcValues.crossTrackError"
    const val BTW = "navigation.course.calcValues.bearingTrue"
    const val DTW = "navigation.course.calcValues.distance"
    const val VMG = "navigation.course.calcValues.velocityMadeGood"
    const val ACTIVE_ROUTE = "navigation.course.activeRoute"
    const val ACTIVE_WP = "navigation.course.activeRoute.activeRouteWaypoint"
    /** Route resources referenced by the active route (array or object). */
    const val ACTIVE_ROUTE_WAYPOINTS = "navigation.course.activeRoute.waypoints"
    const val BATTERY_VOLTAGE = "electrical.batteries.house.voltage"
    const val TANK_FUEL = "tanks.fuel.0.currentLevel"
    const val TANK_WATER = "tanks.freshWater.0.currentLevel"

    // Autopilot (Signal K autopilot API)
    const val AP_STATE = "steering.autopilot.state"
    const val AP_TARGET_HEADING_MAG = "steering.autopilot.target.headingMagnetic"
    const val AP_TARGET_HEADING_TRUE = "steering.autopilot.target.headingTrue"
    const val AP_TARGET_WIND_ANGLE = "steering.autopilot.target.windAngleApparent"
    const val AP_ADJUST_HEADING = "steering.autopilot.actions.adjustHeading"
    const val AP_TACK = "steering.autopilot.actions.tack"

    // AIS / other vessels
    const val AIS_CLASS = "sensors.ais.class"
    const val AIS_SHIP_TYPE = "design.aisShipType"
    const val MMSI = "mmsi"
    const val DESIGN_MMSI = "design.mmsi"
    const val DESTINATION = "design.destination"
    const val DESIGN_LENGTH = "design.length"
    const val DESIGN_DRAFT = "design.draft"
    const val DESIGN_BEAM = "design.beam"

    // Resource collections (SignalK resources API)
    const val RES_WAYPOINTS = "waypoints"
    const val RES_ROUTES = "routes"
}
