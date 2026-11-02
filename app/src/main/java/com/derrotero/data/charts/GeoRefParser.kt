package com.derrotero.data.charts

/**
 * Parser for the `.geo` georeferencing file that accompanies **NOS/GEO** charts
 * (the pre-BSB Softchart format read by OpenCPN).
 *
 * Each control point looks like:
 *
 *     Point1=-22.000000 64.250000 197 744
 *
 * i.e. `lon lat line pixel`.
 */
object GeoRefParser {

    fun parse(text: String): List<KapRef> {
        val refs = ArrayList<KapRef>()
        for (raw in text.lineSequence()) {
            val line = raw.trim().removePrefix("\uFEFF")
            if (!line.startsWith("Point", ignoreCase = true)) continue
            val payload = line.substringAfter('=', "").trim()
            val parts = payload.split(Regex("[\\s,]+")).filter { it.isNotEmpty() }
            if (parts.size < 4) continue
            val lon = parts[0].toDoubleOrNull() ?: continue
            val lat = parts[1].toDoubleOrNull() ?: continue
            val lineY = parts[2].toDoubleOrNull() ?: continue
            val pixel = parts[3].toDoubleOrNull() ?: continue
            refs.add(KapRef(pixel, lineY, lat, lon))
        }
        return refs
    }
}
