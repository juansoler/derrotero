package com.derrotero.util

/**
 * Parses the coordinate formats people actually type on a chartplotter:
 *
 *  - decimal: `43.3000, -2.9500`
 *  - decimal with hemispheres: `43.3 N 2.95 W`
 *  - degrees/minutes: `43 18.02 N 2 56.97 W` or `43°18.02'N 2°56.97'W`
 *  - degrees/minutes/seconds: `43 18 1.2 N 2 56 58.2 W`
 *  - hemisphere before the numbers: `N 43 18.02 W 2 56.97`
 */
object CoordinateParser {

    fun parse(text: String): LatLon? {
        val cleaned = text
            .uppercase()
            .replace('°', ' ')
            .replace('\'', ' ')
            .replace('"', ' ')
            .replace(',', ' ')
            .replace(Regex("([NSEW])"), " $1 ")
        val tokens = cleaned.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return null

        val pair = if (tokens.first() in HEMISPHERES) {
            parseHemisphereFirst(tokens)
        } else {
            parseNumbersFirst(tokens)
        }
        if (pair != null) {
            val (lat, lon) = pair
            return if (lat.isFinite() && lon.isFinite() &&
                lat in -90.0..90.0 && lon in -180.0..180.0
            ) {
                LatLon(lat, lon)
            } else {
                null
            }
        }

        // Bare decimal pair: "43.3 -2.95"
        val numbers = tokens.mapNotNull { it.toDoubleOrNull() }
        if (numbers.size == 2) {
            val (lat, lon) = numbers
            if (lat in -90.0..90.0 && lon in -180.0..180.0) return LatLon(lat, lon)
        }
        return null
    }

    private fun parseNumbersFirst(tokens: List<String>): Pair<Double, Double>? {
        val numbers = ArrayList<Double>(3)
        var lat: Double? = null
        var lon: Double? = null
        for (token in tokens) {
            val value = token.toDoubleOrNull()
            if (value != null) {
                numbers.add(value)
                continue
            }
            if (token !in HEMISPHERES) return null
            if (numbers.isEmpty()) return null
            val coordinate = coordinateValue(numbers) * if (token == "S" || token == "W") -1 else 1
            when (token) {
                "N", "S" -> lat = coordinate
                "E", "W" -> lon = coordinate
            }
            numbers.clear()
        }
        return if (lat != null && lon != null) lat to lon else null
    }

    private fun parseHemisphereFirst(tokens: List<String>): Pair<Double, Double>? {
        var lat: Double? = null
        var lon: Double? = null
        var index = 0
        while (index < tokens.size) {
            val hemisphere = tokens[index]
            if (hemisphere !in HEMISPHERES) {
                index++
                continue
            }
            val numbers = ArrayList<Double>(3)
            var j = index + 1
            while (j < tokens.size && numbers.size < 3) {
                val value = tokens[j].toDoubleOrNull() ?: break
                numbers.add(value)
                j++
            }
            if (numbers.isEmpty()) return null
            val coordinate = coordinateValue(numbers) * if (hemisphere == "S" || hemisphere == "W") -1 else 1
            when (hemisphere) {
                "N", "S" -> lat = coordinate
                "E", "W" -> lon = coordinate
            }
            index = j
        }
        return if (lat != null && lon != null) lat to lon else null
    }

    private fun coordinateValue(parts: List<Double>): Double {
        if (parts.isEmpty() || parts.size > 3) return Double.NaN
        val degrees = kotlin.math.abs(parts[0])
        val minutes = parts.getOrNull(1) ?: 0.0
        val seconds = parts.getOrNull(2) ?: 0.0
        if (minutes >= 60.0 || seconds >= 60.0) return Double.NaN
        return degrees + minutes / 60.0 + seconds / 3600.0
    }

    private val HEMISPHERES = setOf("N", "S", "E", "W")
}
