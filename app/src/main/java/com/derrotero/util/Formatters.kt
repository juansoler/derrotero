package com.derrotero.util

import java.util.Locale
import kotlin.math.roundToInt

object Formatters {

    /** Degrees -> degrees/minutes (marine convention), e.g. 37°48.512'N. */
    fun latLon(value: Double, isLat: Boolean): String {
        val hemi = when {
            isLat && value >= 0 -> "N"
            isLat -> "S"
            value >= 0 -> "E"
            else -> "W"
        }
        val abs = kotlin.math.abs(value)
        val deg = abs.toInt()
        val minutes = (abs - deg) * 60.0
        val fmt = String.format(Locale.US, "%d°%06.3f'%s", deg, minutes, hemi)
        return fmt
    }

    fun position(lat: Double?, lon: Double?): String =
        if (lat == null || lon == null) "—"
        else "${latLon(lat, true)}  ${latLon(lon, false)}"

    fun knots(mps: Double?): String =
        if (mps == null) "—" else String.format(Locale.US, "%.1f kn", mps * 1.943844)

    fun degrees(rad: Double?): String =
        if (rad == null) "—" else String.format(
            Locale.US, "%03.0f°", (Math.toDegrees(rad) + 360.0) % 360.0,
        )

    fun degreesDirect(deg: Double?): String =
        if (deg == null) "—" else String.format(Locale.US, "%03.0f°", (deg + 360.0) % 360.0)

    /** Relative wind angle with side, e.g. "42° Br" / "35° Er" (0 = bow). */
    fun windAngle(rad: Double?): String {
        if (rad == null) return "—"
        val deg = Math.toDegrees(rad)
        val side = if (deg >= 0) "Br" else "Er"
        return String.format(Locale.US, "%.0f° %s", kotlin.math.abs(deg), side)
    }

    fun angleAbs(rad: Double?): String =
        if (rad == null) "—" else String.format(Locale.US, "%.0f°", kotlin.math.abs(Math.toDegrees(rad)))

    fun meters(m: Double?): String =
        if (m == null) "—" else String.format(Locale.US, "%.1f m", m)

    fun feet(m: Double?): String =
        if (m == null) "—" else String.format(Locale.US, "%.0f ft", m * 3.28084)

    fun celsius(k: Double?): String =
        if (k == null) "—" else String.format(Locale.US, "%.1f °C", k - 273.15)

    fun pressure(pa: Double?): String =
        if (pa == null) "—" else String.format(Locale.US, "%.0f hPa", pa / 100.0)

    fun distance(m: Double?): String = when {
        m == null -> "—"
        m < 1000 -> String.format(Locale.US, "%.0f m", m)
        else -> String.format(Locale.US, "%.2f NM", m / 1852.0)
    }

    fun int(value: Double?): String = if (value == null) "—" else value.roundToInt().toString()
}
