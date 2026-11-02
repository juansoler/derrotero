package com.derrotero.data.charts

import java.nio.charset.StandardCharsets
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.roundToInt
import kotlin.math.tan

/** One BSB reference point: chart pixel (column, row) → WGS84 degrees. */
data class KapRef(
    val pixel: Double,
    val line: Double,
    val lat: Double,
    val lon: Double,
)

data class KapHeader(
    val version: String,
    val name: String,
    val width: Int,
    val height: Int,
    /** Nominal scale, e.g. 40000 for 1:40000. */
    val scale: Int,
    /** `KNP/PR`, e.g. `Mercator`, `Transverse Mercator`, `Polyconic`… */
    val projection: String,
    val datum: String,
    val depthUnit: String,
    val refs: List<KapRef>,
    /** Cutline (border polygon) as lat/lon pairs; empty when the chart has none. */
    val polygon: List<Pair<Double, Double>>,
    /** ARGB colours indexed by BSB palette value; 0 is transparent. */
    val palette: IntArray,
    val paletteEntries: Int,
    /** Bits per palette index (1..7). */
    val colorSize: Int,
    /** Offset of the color-size byte, i.e. right after the 0x1A 0x00 marker. */
    val colorSizeOffset: Int,
)

/**
 * Reader/decoder for BSB/Maptech **KAP** raster charts (`.kap`, `.bsb`), the classic
 * NOAA chart format that OpenCPN ships with.
 *
 * The header is a sequence of `KEY/...` lines terminated by `0x1A 0x00`; each scanline is
 * run-length encoded with a base-128 line marker. The file usually ends with an index
 * table holding the offset of every scanline (last 4 bytes point at the table).
 *
 * Only the parts needed to draw the chart are implemented: palette, georeferencing from
 * `REF/` points and the `PLY/` cutline. `KNP/SC` and the depth metadata are exposed for
 * information.
 */
object KapFormat {

    fun parse(
        bytes: ByteArray,
        fileName: String = "",
        externalRefs: List<KapRef> = emptyList(),
    ): KapImage {
        if (bytes.size < 64) error("El fichero KAP está vacío o truncado")

        val marker = findDataMarker(bytes)
            ?: error("No es un fichero BSB/KAP (falta la cabecera BSB/)")

        val headerEnd = marker
        val headerText = String(bytes, 0, headerEnd, StandardCharsets.ISO_8859_1)
        if (!headerText.contains("BSB/") && !headerText.contains("NOS/")) {
            error("No es un fichero BSB/KAP (falta la cabecera BSB/)")
        }

        val colorSizeOffset = marker + 2
        if (colorSizeOffset >= bytes.size) error("KAP truncado antes de la tabla de color")
        var colorSize = bytes[colorSizeOffset].toInt() and 0xFF
        // Some USGS charts store the value as ASCII.
        if (colorSize in 0x31..0x38) colorSize -= 0x30
        if (colorSize !in 1..7) error("Tamaño de color BSB no válido ($colorSize)")

        val header = parseHeader(headerText, colorSize, colorSizeOffset, fileName, externalRefs)
        return KapImage(bytes, header)
    }

    private fun findDataMarker(bytes: ByteArray): Int? {
        for (i in 0 until bytes.size - 1) {
            if (bytes[i].toInt() == 0x1A && bytes[i + 1].toInt() == 0x00) return i
        }
        return null
    }

    private fun parseHeader(
        text: String,
        colorSize: Int,
        colorSizeOffset: Int,
        fileName: String,
        externalRefs: List<KapRef>,
    ): KapHeader {
        val lines = logicalLines(text)

        var width = 0
        var height = 0
        var name = ""
        var scale = 0
        var projection = ""
        var datum = ""
        var depthUnit = ""
        var version = ""
        val refs = ArrayList<KapRef>()
        val polygon = ArrayList<Pair<Double, Double>>()
        val palettes = LinkedHashMap<String, MutableMap<Int, Int>>()

        for (line in lines) {
            if (line.length < 5 || line[3] != '/') continue
            val key = line.substring(0, 3).uppercase()
            val body = line.substring(4)
            when (key) {
                "VER" -> version = body.trim()
                "BSB", "NOS" -> {
                    val tokens = body.split(',', '=')
                    var i = 0
                    while (i < tokens.size) {
                        when (tokens[i].trim().uppercase()) {
                            "RA" -> {
                                val numbers = ArrayList<Int>(2)
                                var j = i + 1
                                while (j < tokens.size && numbers.size < 2) {
                                    tokens[j].trim().toIntOrNull()?.let { numbers.add(it) }
                                    j++
                                }
                                if (numbers.size >= 2) {
                                    width = numbers[0]
                                    height = numbers[1]
                                }
                            }
                            "NA" -> if (i + 1 < tokens.size) name = tokens[i + 1].trim()
                            "DU" -> if (i + 1 < tokens.size) depthUnit = tokens[i + 1].trim()
                        }
                        i++
                    }
                }
                "KNP", "KNQ" -> {
                    for (pair in body.split(',')) {
                        val eq = pair.indexOf('=')
                        if (eq <= 0) continue
                        val k = pair.substring(0, eq).trim().uppercase()
                        val v = pair.substring(eq + 1).trim()
                        when (k) {
                            "SC" -> scale = v.toIntOrNull() ?: scale
                            "PR" -> projection = v
                            "GD" -> datum = v
                        }
                    }
                }
                "REF" -> {
                    val t = body.split(',')
                    if (t.size >= 5) {
                        val pixel = t[1].trim().toDoubleOrNull()
                        val lineY = t[2].trim().toDoubleOrNull()
                        val lat = t[3].trim().toDoubleOrNull()
                        val lon = t[4].trim().toDoubleOrNull()
                        if (pixel != null && lineY != null && lat != null && lon != null) {
                            refs.add(KapRef(pixel, lineY, lat, lon))
                        }
                    }
                }
                "PLY" -> {
                    val t = body.split(',')
                    if (t.size >= 3) {
                        val lat = t[1].trim().toDoubleOrNull()
                        val lon = t[2].trim().toDoubleOrNull()
                        if (lat != null && lon != null) polygon.add(lat to lon)
                    }
                }
                "RGB", "DAY", "DSK", "NGT", "NGR", "GRY", "PRC", "PRG" -> {
                    val t = body.split(',')
                    if (t.size >= 4) {
                        val index = t[0].trim().toIntOrNull() ?: continue
                        if (index !in 1..255) continue
                        val r = t[1].trim().toIntOrNull()?.coerceIn(0, 255) ?: continue
                        val g = t[2].trim().toIntOrNull()?.coerceIn(0, 255) ?: continue
                        val b = t[3].trim().toIntOrNull()?.coerceIn(0, 255) ?: continue
                        palettes.getOrPut(key) { LinkedHashMap() }[index] =
                            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                    }
                }
            }
        }

        if (width <= 0 || height <= 0) {
            error("Cabecera KAP sin dimensiones (BSB/RA)")
        }
        if (refs.size < 2 && externalRefs.size >= 2) {
            refs.addAll(externalRefs)
        }
        if (refs.size < 2) {
            error("La carta no tiene puntos de referencia (REF/ o .geo) suficientes")
        }

        val palette = IntArray(256)
        val chosen = palettes["RGB"] ?: palettes.values.firstOrNull()
        if (chosen == null) {
            // No embedded palette: approximate the typical chart look in grayscale so the
            // chart is still usable instead of failing to open.
            for (v in 1..127) {
                val g = (255 - v * 2).coerceIn(0, 255)
                palette[v] = (0xFF shl 24) or (g shl 16) or (g shl 8) or g
            }
        } else {
            for ((index, color) in chosen) palette[index] = color
        }

        return KapHeader(
            version = version.ifBlank { "?" },
            name = name.ifBlank { fileName.substringBeforeLast('.').ifBlank { "Carta BSB" } },
            width = width,
            height = height,
            scale = scale,
            projection = projection,
            datum = datum,
            depthUnit = depthUnit,
            refs = refs,
            polygon = polygon,
            palette = palette,
            paletteEntries = chosen?.size ?: 0,
            colorSize = colorSize,
            colorSizeOffset = colorSizeOffset,
        )
    }

    /**
     * Splits the header in logical lines. KAP writers break long records by starting the
     * next physical line with a space, which means "continue the previous record" (and an
     * implicit comma, exactly as GDAL's BSB reader does).
     */
    private fun logicalLines(text: String): List<String> {
        val out = ArrayList<String>()
        for (raw in text.split('\n')) {
            val line = raw.trimEnd('\r')
            if (line.isEmpty()) continue
            if (line.startsWith(" ")) {
                if (out.isNotEmpty()) out[out.size - 1] = out.last() + "," + line.trimStart()
            } else {
                out.add(line)
            }
        }
        return out
    }
}

/**
 * A decoded KAP chart: metadata, georeferencing and lazy scanline decoding.
 */
class KapImage internal constructor(
    private val bytes: ByteArray,
    val header: KapHeader,
) {
    private val valueShift = 7 - header.colorSize
    private val valueMask = (((1 shl header.colorSize) - 1) shl valueShift) and 0xFF
    private val countMask = (1 shl (7 - header.colorSize)) - 1

    val width: Int get() = header.width
    val height: Int get() = header.height

    val geo: KapGeo = KapGeo.from(header.refs, header.projection)
        ?: error("No se pudo georreferenciar la carta KAP")

    private val rowOffsets: IntArray? = buildRowOffsets()

    val bounds: GeoBounds? = computeBounds()

    /** Zoom at which one chart pixel is about one screen pixel. */
    val nativeZoom: Int = run {
        val lonPerPixel = abs(geo.lonPerPixel)
        if (lonPerPixel <= 0.0 || !lonPerPixel.isFinite()) {
            12
        } else {
            log2(360.0 / (256.0 * lonPerPixel)).roundToInt().coerceIn(1, 17)
        }
    }

    val minZoom: Int get() = (nativeZoom - 4).coerceAtLeast(0)
    val maxZoom: Int get() = (nativeZoom + 2).coerceAtMost(18)

    /** Palette-index bitmap of one scanline expanded to ARGB. */
    fun row(index: Int): IntArray? {
        val offsets = rowOffsets ?: return null
        if (index !in offsets.indices) return null
        val out = IntArray(width)

        var p = offsets[index]
        if (p < 0 || p >= bytes.size) return null
        // Base-128 line marker (0-based in old charts, 1-based since BSB 2.0).
        var guard = 0
        while (true) {
            if (p >= bytes.size || guard++ > 8) return null
            val b = bytes[p++].toInt() and 0xFF
            if ((b and 0x80) == 0) break
        }

        var pixel = 0
        while (pixel < width) {
            if (p >= bytes.size) break
            val ctl = bytes[p++].toInt() and 0xFF
            if (ctl == 0) break
            val value = (ctl and valueMask) ushr valueShift
            var count = ctl and countMask
            var last = ctl
            var guard2 = 0
            while ((last and 0x80) != 0) {
                if (p >= bytes.size || guard2++ > 8) return null
                last = bytes[p++].toInt() and 0xFF
                count = count * 128 + (last and 0x7f)
                if (count > width + 1) return null
            }
            val color = if (value in 0..255) header.palette[value] else 0
            val run = (count + 1).coerceAtMost(width - pixel)
            for (i in 0 until run) out[pixel++] = color
        }
        return out
    }

    /** Pixel column at [lon] (may be outside `0..width`). */
    fun pixelAtLon(lon: Double): Double = geo.pixelAtLon(lon)

    /** Raster row at [lat] (may be outside `0..height`). */
    fun rowAtLat(lat: Double): Double = geo.rowAtLat(lat)

    // ------------------------------------------------------------------ offsets

    private fun buildRowOffsets(): IntArray? {
        indexOffsets()?.let { return it }
        return scanOffsets()
    }

    /**
     * Reads the index table referenced by the last 4 bytes of the file (big-endian). Some
     * charts advertise one row more than the table contains; those rows stay unreadable.
     */
    private fun indexOffsets(): IntArray? {
        if (bytes.size < 8) return null
        val tableOffset = intBe(bytes, bytes.size - 4)
        if (tableOffset <= header.colorSizeOffset + 1 || tableOffset > bytes.size - 4) return null
        val available = (bytes.size - 4 - tableOffset) / 4
        if (available <= 0) return null
        val count = minOf(header.height, available)
        val offsets = IntArray(header.height) { -1 }
        var previous = header.colorSizeOffset + 1
        for (i in 0 until count) {
            val value = intBe(bytes, tableOffset + i * 4)
            if (value < previous || value >= tableOffset) return null
            offsets[i] = value
            previous = value
        }
        return offsets
    }

    /** Walks the compressed stream once when the chart has no usable index. */
    private fun scanOffsets(): IntArray? {
        val offsets = IntArray(header.height) { -1 }
        var position = header.colorSizeOffset + 1
        for (y in 0 until header.height) {
            offsets[y] = position
            position = skipRow(position) ?: return null
        }
        return offsets
    }

    private fun skipRow(start: Int): Int? {
        var p = start
        var guard = 0
        while (true) {
            if (p >= bytes.size || guard++ > 8) return null
            val b = bytes[p++].toInt() and 0xFF
            if ((b and 0x80) == 0) break
        }
        var pixels = 0
        while (pixels < width) {
            if (p >= bytes.size) return null
            val ctl = bytes[p++].toInt() and 0xFF
            if (ctl == 0) return p
            var count = ctl and countMask
            var last = ctl
            var guard2 = 0
            while ((last and 0x80) != 0) {
                if (p >= bytes.size || guard2++ > 8) return null
                last = bytes[p++].toInt() and 0xFF
                count = count * 128 + (last and 0x7f)
                if (count > width + 1) return null
            }
            pixels += count + 1
        }
        // Rows normally end with a 0x00 control byte.
        if (p < bytes.size && bytes[p].toInt() == 0) p++
        return p
    }

    // ------------------------------------------------------------------- bounds

    private fun computeBounds(): GeoBounds? {
        if (header.polygon.size >= 3) {
            val lats = header.polygon.map { it.first }
            val lons = header.polygon.map { it.second }
            return makeBounds(lats.min(), lats.max(), lons.min(), lons.max())
        }
        val corners = listOf(
            0.0 to 0.0,
            width.toDouble() to 0.0,
            width.toDouble() to height.toDouble(),
            0.0 to height.toDouble(),
        )
        val lats = corners.map { geo.latAt(it.second) }
        val lons = corners.map { geo.lonAt(it.first) }
        return makeBounds(lats.min(), lats.max(), lons.min(), lons.max())
    }

    private fun makeBounds(south: Double, north: Double, west: Double, east: Double): GeoBounds? {
        if (!south.isFinite() || !north.isFinite() || !west.isFinite() || !east.isFinite()) return null
        if (east - west > 180.0) return null
        return GeoBounds(
            north = north.coerceIn(-90.0, 90.0),
            south = south.coerceIn(-90.0, 90.0),
            east = east.coerceIn(-180.0, 180.0),
            west = west.coerceIn(-180.0, 180.0),
        ).takeIf { it.isValid }
    }

    private fun intBe(b: ByteArray, offset: Int): Int =
        ((b[offset].toInt() and 0xFF) shl 24) or
            ((b[offset + 1].toInt() and 0xFF) shl 16) or
            ((b[offset + 2].toInt() and 0xFF) shl 8) or
            (b[offset + 3].toInt() and 0xFF)
}

/**
 * Pixel ↔ WGS84 georeferencing fitted to the `REF/` control points.
 *
 * Mercator charts (the vast majority) are fitted in Mercator northing, so latitude is
 * exact across the whole chart; other projections fall back to a linear latitude fit,
 * which is the same approximation GDAL derives from the GCPs.
 */
class KapGeo private constructor(
    private val lonAtPixel: Double,
    val lonPerPixel: Double,
    private val mercAtRow: Double,
    private val mercPerRow: Double,
    private val latAtRow: Double,
    private val latPerRow: Double,
    private val mercator: Boolean,
) {
    fun lonAt(pixel: Double): Double = lonAtPixel + lonPerPixel * pixel

    fun pixelAtLon(lon: Double): Double =
        if (lonPerPixel == 0.0) 0.0 else (lon - lonAtPixel) / lonPerPixel

    fun latAt(row: Double): Double =
        if (mercator) {
            val m = mercAtRow + mercPerRow * row
            2.0 * atan(exp(m)) * 180.0 / PI - 90.0
        } else {
            latAtRow + latPerRow * row
        }

    fun rowAtLat(lat: Double): Double =
        if (mercator) {
            val m = ln(tan(PI / 4.0 + lat * PI / 360.0))
            if (mercPerRow == 0.0) 0.0 else (m - mercAtRow) / mercPerRow
        } else {
            if (latPerRow == 0.0) 0.0 else (lat - latAtRow) / latPerRow
        }

    companion object {
        fun from(refs: List<KapRef>, projection: String): KapGeo? {
            if (refs.size < 2) return null
            val pixels = refs.map { it.pixel }.toDoubleArray()
            val lines = refs.map { it.line }.toDoubleArray()
            val lons = refs.map { it.lon }.toDoubleArray()
            val lats = refs.map { it.lat }.toDoubleArray()

            val (lonA, lonB) = fit(pixels, lons)
            if (lonB == 0.0 || !lonB.isFinite()) return null

            val upper = projection.uppercase()
            val isMercator = upper.contains("MERCATOR") &&
                !upper.contains("TRANSVERSE") &&
                !upper.contains("UTM")

            if (isMercator) {
                val merc = lats.map { ln(tan(PI / 4.0 + it * PI / 360.0)) }.toDoubleArray()
                val (m0, m1) = fit(lines, merc)
                if (m1 == 0.0 || !m1.isFinite()) return null
                return KapGeo(lonA, lonB, m0, m1, 0.0, 0.0, true)
            }

            val (lat0, lat1) = fit(lines, lats)
            return KapGeo(lonA, lonB, 0.0, 0.0, lat0, lat1, false)
        }

        /** Least-squares line `y = a + b·x`. */
        private fun fit(xs: DoubleArray, ys: DoubleArray): Pair<Double, Double> {
            val n = xs.size
            var sumX = 0.0
            var sumY = 0.0
            for (i in 0 until n) {
                sumX += xs[i]
                sumY += ys[i]
            }
            val mx = sumX / n
            val my = sumY / n
            var numerator = 0.0
            var denominator = 0.0
            for (i in 0 until n) {
                val dx = xs[i] - mx
                numerator += dx * (ys[i] - my)
                denominator += dx * dx
            }
            val b = if (denominator == 0.0) 0.0 else numerator / denominator
            return (my - b * mx) to b
        }
    }
}
