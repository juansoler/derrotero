package com.derrotero.util

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Closest Point of Approach between own vessel and a target, in a local ENU
 * approximation around the own position (fine for the ranges used in AIS).
 *
 * COG values are SignalK radians (true, 0 = north).
 */
data class CpaResult(
    val distanceM: Double,
    val bearingDeg: Double,
    val dcpaM: Double?,
    val tcpaSec: Double?,
)

object Cpa {

    private const val EARTH_R = 6_371_000.0

    fun compute(
        ownLat: Double,
        ownLon: Double,
        ownSogMps: Double?,
        ownCogRad: Double?,
        tgtLat: Double,
        tgtLon: Double,
        tgtSogMps: Double?,
        tgtCogRad: Double?,
    ): CpaResult {
        val own = LatLon(ownLat, ownLon)
        val tgt = LatLon(tgtLat, tgtLon)
        val distance = Geo.distanceMeters(own, tgt)
        val bearing = Geo.bearingDegrees(own, tgt)

        if (ownSogMps == null || ownCogRad == null || tgtSogMps == null || tgtCogRad == null) {
            return CpaResult(distance, bearing, null, null)
        }

        val lat0 = Math.toRadians(ownLat)
        val x = Math.toRadians(tgtLon - ownLon) * EARTH_R * cos(lat0)
        val y = Math.toRadians(tgtLat - ownLat) * EARTH_R

        val vOwnX = ownSogMps * sin(ownCogRad)
        val vOwnY = ownSogMps * cos(ownCogRad)
        val vTgtX = tgtSogMps * sin(tgtCogRad)
        val vTgtY = tgtSogMps * cos(tgtCogRad)

        val rvx = vTgtX - vOwnX
        val rvy = vTgtY - vOwnY
        val v2 = rvx * rvx + rvy * rvy
        if (v2 < 1e-6) return CpaResult(distance, bearing, distance, 0.0)

        var t = -(x * rvx + y * rvy) / v2
        if (t < 0.0) t = 0.0
        val cx = x + rvx * t
        val cy = y + rvy * t
        return CpaResult(distance, bearing, sqrt(cx * cx + cy * cy), t)
    }
}
