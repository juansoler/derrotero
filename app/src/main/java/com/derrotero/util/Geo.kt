package com.derrotero.util

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

data class LatLon(val lat: Double, val lon: Double)

object Geo {

    private const val EARTH_R = 6_371_000.0

    /** Great-circle distance in metres. */
    fun distanceMeters(a: LatLon, b: LatLon): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val lat1 = Math.toRadians(a.lat)
        val lat2 = Math.toRadians(b.lat)
        val h = sin(dLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dLon / 2).pow(2)
        return 2 * EARTH_R * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    /** Initial bearing a -> b, degrees true (0..360). */
    fun bearingDegrees(a: LatLon, b: LatLon): Double {
        val lat1 = Math.toRadians(a.lat)
        val lat2 = Math.toRadians(b.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    /** Dead-reckoned position: move [meters] from [origin] along [bearingDegrees]. */
    fun offset(origin: LatLon, bearingDegrees: Double, meters: Double): LatLon {
        val d = meters / EARTH_R
        val br = Math.toRadians(bearingDegrees)
        val lat1 = Math.toRadians(origin.lat)
        val lon1 = Math.toRadians(origin.lon)
        val lat2 = asin(sin(lat1) * cos(d) + cos(lat1) * sin(d) * cos(br))
        val lon2 = lon1 + atan2(sin(br) * sin(d) * cos(lat1), cos(d) - sin(lat1) * sin(lat2))
        return LatLon(Math.toDegrees(lat2), normalizeLon(Math.toDegrees(lon2)))
    }

    private fun normalizeLon(lon: Double): Double {
        var l = lon
        while (l > 180) l -= 360
        while (l < -180) l += 360
        return l
    }

    fun isValid(lat: Double?, lon: Double?): Boolean =
        lat != null && lon != null && abs(lat) <= 90 && abs(lon) <= 180 && (lat != 0.0 || lon != 0.0)

    /**
     * Signed distance in metres from the great-circle leg [origin]→[target] to [own].
     * Positive means the vessel is to the right (starboard) of the track.
     */
    fun crossTrackMeters(origin: LatLon, target: LatLon, own: LatLon): Double {
        val d13 = distanceMeters(origin, own) / EARTH_R
        val b13 = Math.toRadians(bearingDegrees(origin, own))
        val b12 = Math.toRadians(bearingDegrees(origin, target))
        return asin((sin(d13) * sin(b13 - b12)).coerceIn(-1.0, 1.0)) * EARTH_R
    }

    /** Closed ring approximating a circle of [radiusMeters] around [center]. */
    fun circlePolygon(center: LatLon, radiusMeters: Double, segments: Int = 64): List<LatLon> {
        val step = 360.0 / segments
        val ring = ArrayList<LatLon>(segments + 1)
        for (i in 0 until segments) ring.add(offset(center, i * step, radiusMeters))
        ring.add(ring.first())
        return ring
    }
}
