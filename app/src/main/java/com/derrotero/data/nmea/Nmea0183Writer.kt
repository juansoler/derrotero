package com.derrotero.data.nmea

import com.derrotero.data.signalk.Paths
import com.derrotero.data.signalk.angle
import com.derrotero.data.signalk.latitude
import com.derrotero.data.signalk.longitude
import com.derrotero.data.signalk.number
import com.derrotero.data.vessel.NavGuidance
import com.derrotero.data.vessel.NavigationTarget
import com.derrotero.util.Geo
import com.derrotero.util.LatLon
import kotlinx.serialization.json.JsonElement
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.round

/**
 * Outbound NMEA 0183 route guidance, the same sentences OpenCPN sends to an autopilot:
 * `ECAPB`, `ECRMB`, `ECRMC` and `ECXTE`.
 *
 * Raw NMEA 0183 has no authentication. This path is deliberately independent from Signal K
 * (and therefore from its bearer token): the autopilot only has to be in Track/Nav mode.
 * Mode changes, ±1/±10 and tacks are not part of NMEA 0183 and still need Signal K/SeaTalk.
 *
 * Distances and bearings are computed locally so output keeps flowing even when Signal K is
 * unavailable; [guidance] is only used to keep the arrival flags in sync with the app's
 * configured arrival radius.
 */
object Nmea0183Writer {

    private const val TALKER = "EC"
    private const val KNOTS_PER_MPS = 1.943844
    private const val METERS_PER_NM = 1852.0

    private val timeFormat: DateTimeFormatter =
        DateTimeFormatter.ofPattern("HHmmss.SS", Locale.US).withZone(ZoneOffset.UTC)
    private val dateFormat: DateTimeFormatter =
        DateTimeFormatter.ofPattern("ddMMyy", Locale.US).withZone(ZoneOffset.UTC)

    /**
     * Builds the sentence set for [target]. Returns an empty list until the own position is
     * known. Each string is a complete sentence (starting with `$`) without the line ending;
     * the transport writes them terminated in CRLF.
     */
    fun sentences(
        own: Map<String, JsonElement>,
        target: NavigationTarget,
        guidance: NavGuidance = NavGuidance(),
        now: Instant = Instant.now(),
    ): List<String> {
        val lat = own.latitude() ?: return emptyList()
        val lon = own.longitude() ?: return emptyList()
        if (!Geo.isValid(lat, lon) || !Geo.isValid(target.lat, target.lon)) return emptyList()

        val ownPos = LatLon(lat, lon)
        val targetPos = LatLon(target.lat, target.lon)
        val current = guidance.takeIf { it.isFor(target) }

        val distanceM = current?.distanceM ?: Geo.distanceMeters(ownPos, targetPos)
        val bearing = current?.bearingDeg ?: Geo.bearingDegrees(ownPos, targetPos)
        val xteM = current?.xteM ?: target.let {
            val fromLat = it.fromLat
            val fromLon = it.fromLon
            if (fromLat != null && fromLon != null) {
                Geo.crossTrackMeters(LatLon(fromLat, fromLon), targetPos, ownPos)
            } else {
                null
            }
        }

        val xteNm = (xteM ?: 0.0) / METERS_PER_NM
        val side = if ((xteM ?: 0.0) >= 0.0) "R" else "L"
        val bearingText = degrees(bearing)

        val sogMps = own.number(Paths.SOG) ?: 0.0
        val cogRad = own.angle(Paths.COG) ?: own.angle(Paths.COG_MAG)
        val cogDeg = normalizeDegrees(
            cogRad?.let { Math.toDegrees(it) } ?: bearing,
        )
        val vmgMps = current?.vmgMps
            ?: cogRad?.let { sogMps * cos(it - Math.toRadians(bearing)) }
            ?: sogMps
        val arrival = if (current?.arrived == true) "A" else "V"

        val variationDeg = own.angle(Paths.MAGNETIC_VARIATION)?.let { Math.toDegrees(it) }
        val variation = variationDeg?.let { format(abs(it), 1) } ?: ""
        val variationSide = variationDeg?.let { if (it >= 0.0) "E" else "W" } ?: ""

        val waypoint = waypointName(target.name)

        // Same order and field layout OpenCPN uses for its autopilot output. RMB and RMC
        // carry the FAA mode indicator added in NMEA 2.3; APB/XTE do not.
        return listOf(
            sentence(
                "RMB",
                "A",
                format(abs(xteNm), 2), side,
                "ORIG", waypoint,
                coordinate(target.lat, 2), if (target.lat >= 0.0) "N" else "S",
                coordinate(target.lon, 3), if (target.lon >= 0.0) "E" else "W",
                format(distanceM / METERS_PER_NM, 2),
                bearingText,
                format(vmgMps * KNOTS_PER_MPS, 1),
                arrival,
                "A",
            ),
            sentence(
                "RMC",
                timeFormat.format(now), "A",
                coordinate(lat, 2), if (lat >= 0.0) "N" else "S",
                coordinate(lon, 3), if (lon >= 0.0) "E" else "W",
                format(sogMps * KNOTS_PER_MPS, 1),
                degrees(cogDeg),
                dateFormat.format(now),
                variation, variationSide,
                "A",
            ),
            sentence(
                "APB",
                "A", "A",
                format(abs(xteNm), 2), side, "N",
                arrival, arrival,
                bearingText, "T", waypoint,
                bearingText, "T",
                bearingText, "T",
            ),
            sentence(
                "XTE",
                "A", "A",
                format(abs(xteNm), 2), side, "N",
            ),
        )
    }

    /** Builds one sentence and appends the NMEA 0183 checksum. */
    fun sentence(type: String, vararg fields: String): String {
        val data = fields.joinToString(",")
        val body = TALKER + type + if (data.isEmpty()) "" else ",$data"
        val checksum = body.fold(0) { acc, c -> acc xor c.code }
        return "\$$body*${"%02X".format(Locale.US, checksum)}"
    }

    private fun NavGuidance.isFor(target: NavigationTarget): Boolean {
        val targetLat = lat ?: return false
        val targetLon = lon ?: return false
        return active &&
            abs(targetLat - target.lat) < 1e-7 &&
            abs(targetLon - target.lon) < 1e-7
    }

    /** `ddmm.mmmm` ([width] 2) or `dddmm.mmmm` ([width] 3), always positive. */
    private fun coordinate(value: Double, width: Int): String {
        val absolute = abs(value)
        var degrees = floor(absolute).toInt()
        var minutes = round((absolute - degrees) * 60.0 * 10_000.0) / 10_000.0
        if (minutes >= 60.0) {
            degrees += 1
            minutes = 0.0
        }
        return "%0${width}d%07.4f".format(Locale.US, degrees, minutes)
    }

    private fun degrees(value: Double): String {
        var rounded = round(normalizeDegrees(value) * 10.0) / 10.0
        if (rounded >= 360.0) rounded = 0.0
        return format(rounded, 1)
    }

    private fun normalizeDegrees(value: Double): Double {
        var degrees = value % 360.0
        if (degrees < 0.0) degrees += 360.0
        return degrees
    }

    private fun waypointName(value: String): String {
        val clean = value
            .uppercase(Locale.US)
            .replace(Regex("[^A-Z0-9._-]"), "_")
            .take(20)
        return clean.ifBlank { "DEST" }
    }

    private fun format(value: Double, decimals: Int): String =
        "%.${decimals}f".format(Locale.US, value)
}
