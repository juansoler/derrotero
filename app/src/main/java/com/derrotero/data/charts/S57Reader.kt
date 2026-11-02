package com.derrotero.data.charts

import com.derrotero.util.LatLon
import java.io.File
import java.nio.charset.StandardCharsets
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log2
import kotlin.math.roundToInt
import kotlin.math.sin

/** Decoded S-57 cell ready to be drawn as a MapLibre GeoJSON layer. */
data class S57Chart(
    val name: String,
    val scale: Int,
    val bounds: GeoBounds?,
    val geoJson: String,
    val featureCount: Int,
    val minZoom: Int,
    val maxZoom: Int,
    /** Number of update cells (`.001`, `.002`…) applied. */
    val updateCells: Int = 0,
    /** Records inserted, modified or deleted by the updates. */
    val updatedRecords: Int = 0,
)

/**
 * Reader for **S-57 ENC** charts (`.000`), the vector format used by OpenCPN and the
 * hydrographic offices.
 *
 * Base cells are merged with their update cells (`.001`, `.002`…) using the S-57
 * `RUIN` record-update indicators (1 insert, 2 delete, 3 modify). S-57 objects are turned
 * into a GeoJSON FeatureCollection with presentation hints (`layer`, `fill`, `stroke`,
 * `width`, `radius`, `opacity`, `label`) so the map can paint them with a simplified S-52
 * palette, including light sectors.
 */
object S57Reader {

    private val CLASSES = mapOf(
        5 to "BCNCAR", 6 to "BCNISD", 7 to "BCNLAT", 8 to "BCNSAW", 9 to "BCNSPP",
        14 to "BOYCAR", 16 to "BOYISD", 17 to "BOYLAT", 18 to "BOYSAW", 19 to "BOYSPP",
        27 to "CTNARE", 30 to "COALNE", 42 to "DEPARE", 43 to "DEPCNT", 46 to "DRGARE",
        51 to "FAIRWY", 69 to "LAKARE", 71 to "LNDARE", 75 to "LIGHTS", 76 to "LITFLT",
        77 to "LITVES", 84 to "MORFAC", 85 to "NAVLNE", 86 to "OBSTRN", 90 to "PILPNT",
        103 to "RTPBCN", 109 to "RECTRC", 112 to "RESARE", 122 to "SLCONS", 129 to "SOUNDG",
        153 to "UWTROC", 154 to "UNSARE", 159 to "WRECKS",
    )

    private val LIGHT_CHARACTERISTICS = mapOf(
        "1" to "F", "2" to "Fl", "3" to "LFl", "4" to "Q", "5" to "VQ", "6" to "UQ",
        "7" to "Iso", "8" to "Oc", "9" to "Mo", "10" to "IQ", "11" to "IVQ", "12" to "IUQ",
        "13" to "Mo(U)", "14" to "Fl(2)", "15" to "Fl(3)", "16" to "Fl(4)", "17" to "Q+LFl",
    )

    private val COLOUR_NAMES = mapOf(
        "1" to "W", "2" to "R", "3" to "G", "4" to "Bu", "5" to "Y", "6" to "Bk",
        "7" to "Gy", "8" to "Br", "9" to "Am", "10" to "Vi", "11" to "Or", "12" to "Mg",
    )

    private val COLOUR_HEX = mapOf(
        "1" to "#FFFFFF", "2" to "#D32F2F", "3" to "#2E7D32", "4" to "#1565C0",
        "5" to "#F9A825", "6" to "#212121", "9" to "#FFB300",
    )

    fun read(file: File): S57Chart {
        val updates = findUpdateFiles(file).map { it.readBytes() }
        return read(file.readBytes(), file.name, updates)
    }

    /** Reads a base cell and optionally applies update cells in order. */
    fun read(
        bytes: ByteArray,
        fileName: String = "ENC.000",
        updates: List<ByteArray> = emptyList(),
    ): S57Chart {
        val baseRecords = Iso8211.read(bytes).filterNot { it.isDescriptor }
        if (baseRecords.isEmpty()) error("El fichero no contiene registros ISO 8211 (¿S-57?)")

        var scale = 0
        var dataSetName = ""
        var comf = 0
        val features = LinkedHashMap<Int, MutableMap<String, ByteArray>>()
        val vectors = LinkedHashMap<Long, MutableMap<String, ByteArray>>()

        fun addBase(fields: Map<String, ByteArray>) {
            val frid = fields["FRID"]?.let(::parseFrid)
            if (frid != null) {
                features[frid.rcid] = fields.toMutableMap()
                return
            }
            val vrid = fields["VRID"]?.let(::parseVrid)
            if (vrid != null) {
                vectors[key(vrid.rcnm, vrid.rcid)] = fields.toMutableMap()
            }
        }

        /** Returns true when the record changed the data set. */
        fun applyUpdate(fields: Map<String, ByteArray>): Boolean {
            val frid = fields["FRID"]?.let(::parseFrid)
            if (frid != null) {
                when (frid.ruin) {
                    2 -> {
                        features.remove(frid.rcid)
                    }
                    3 -> {
                        val existing = features[frid.rcid]
                        if (existing != null) existing.putAll(fields)
                        else features[frid.rcid] = fields.toMutableMap()
                    }
                    else -> features[frid.rcid] = fields.toMutableMap()
                }
                return true
            }
            val vrid = fields["VRID"]?.let(::parseVrid)
            if (vrid != null) {
                val vectorKey = key(vrid.rcnm, vrid.rcid)
                when (vrid.ruin) {
                    2 -> vectors.remove(vectorKey)
                    3 -> {
                        val existing = vectors[vectorKey]
                        if (existing != null) existing.putAll(fields)
                        else vectors[vectorKey] = fields.toMutableMap()
                    }
                    else -> vectors[vectorKey] = fields.toMutableMap()
                }
                return true
            }
            return false
        }

        for (record in baseRecords) {
            record.fields["DSID"]?.let { field ->
                if (dataSetName.isEmpty()) dataSetName = parseDataSetName(field)
            }
            record.fields["DSPM"]?.let { field ->
                if (scale == 0) scale = i32(field, 8)
                if (comf == 0) comf = i32(field, 16)
            }
            addBase(record.fields)
        }

        var updatedRecords = 0
        for (update in updates) {
            for (record in Iso8211.read(update).filterNot { it.isDescriptor }) {
                if (applyUpdate(record.fields)) updatedRecords++
            }
        }

        val coordinateFactor = if (comf > 0) comf.toDouble() else 500_000.0
        val vectorMap = HashMap<Long, VectorRecord>()
        for ((vectorKey, fields) in vectors) {
            val points = ArrayList<LatLon>()
            val depths = ArrayList<Double>()
            fields["SG2D"]?.let { parseSg2d(it, points) }
            fields["SG3D"]?.let { parseSg3d(it, points, depths) }
            val pointers = fields["VRPT"]?.let(::parseVrpt) ?: emptyList()
            vectorMap[vectorKey] = VectorRecord(points, depths, pointers)
        }

        val builder = GeometryBuilder(vectorMap)
        val out = StringBuilder(1 shl 20)
        out.append("{\"type\":\"FeatureCollection\",\"features\":[")

        var first = true
        var count = 0
        var minLat = Double.MAX_VALUE
        var maxLat = -Double.MAX_VALUE
        var minLon = Double.MAX_VALUE
        var maxLon = -Double.MAX_VALUE
        var covMinLat = Double.MAX_VALUE
        var covMaxLat = -Double.MAX_VALUE
        var covMinLon = Double.MAX_VALUE
        var covMaxLon = -Double.MAX_VALUE
        var hasCoverage = false

        fun addBounds(lat: Double, lon: Double, coverage: Boolean) {
            if (lat < minLat) minLat = lat
            if (lat > maxLat) maxLat = lat
            if (lon < minLon) minLon = lon
            if (lon > maxLon) maxLon = lon
            if (coverage) {
                hasCoverage = true
                if (lat < covMinLat) covMinLat = lat
                if (lat > covMaxLat) covMaxLat = lat
                if (lon < covMinLon) covMinLon = lon
                if (lon > covMaxLon) covMaxLon = lon
            }
        }

        fun emit(feature: FeatureOut) {
            if (!first) out.append(',')
            first = false
            count++
            appendFeature(out, feature)
        }

        for (fields in features.values) {
            val frid = fields["FRID"]?.let(::parseFrid) ?: continue
            val pointers = fields["FSPT"]?.let(::parseFspt) ?: emptyList()

            if (frid.objl == 302) {
                for (polygon in builder.polygons(pointers, coordinateFactor)) {
                    for (ring in polygon) {
                        for (point in ring) addBounds(point.lat, point.lon, true)
                    }
                }
                continue
            }

            val attributes = fields["ATTF"]?.let(::parseAttributes) ?: emptyMap()
            val paint = styleFor(frid.objl, attributes) ?: continue
            val name = attributes[116]?.takeIf { it.isNotBlank() }
                ?: attributes[301]?.takeIf { it.isNotBlank() }
            val k = CLASSES[frid.objl] ?: "OTHR"

            if (frid.prim == 1 && (paint.layer == "depth" || paint.layer == "land" || paint.layer == "area")) {
                continue
            }

            when (frid.prim) {
                1 -> {
                    val points = builder.points(pointers, coordinateFactor)
                    if (points.isEmpty()) continue
                    for (point in points) {
                        addBounds(point.first.lat, point.first.lon, false)

                        if (frid.objl == 75) {
                            val sector = lightSector(point.first, attributes)
                            if (sector != null) {
                                val colour = lightColour(attributes)
                                emit(
                                    FeatureOut(
                                        paint = Paint("sector", colour, colour, 0.13, 0.6, 0.0),
                                        k = "LIGHTS",
                                        name = null,
                                        label = null,
                                        icon = null,
                                        depth = null,
                                        geometry = Geometry.Area(sector),
                                    ),
                                )
                            }
                        }

                        val label = when {
                            frid.objl == 75 -> lightLabel(name, attributes)
                            frid.objl == 129 -> null
                            else -> name
                        }
                        emit(
                            FeatureOut(
                                paint = paint,
                                k = k,
                                name = if (frid.objl == 129) formatDepth(point.second) else name,
                                label = label,
                                icon = pointIcon(frid.objl, attributes),
                                depth = if (frid.objl == 129) point.second else null,
                                geometry = Geometry.Point(point.first),
                            ),
                        )
                    }
                }

                2 -> {
                    val segments = builder.lines(pointers, coordinateFactor).filter { it.size >= 2 }
                    if (segments.isEmpty()) continue
                    for (segment in segments) {
                        for (point in segment) addBounds(point.lat, point.lon, false)
                    }
                    emit(FeatureOut(paint, k, name, null, null, null, Geometry.Lines(segments)))
                }

                3 -> {
                    val areas = builder.polygons(pointers, coordinateFactor)
                        .map { polygon -> polygon.map(::closeRing).filter { it.size >= 4 } }
                        .filter { it.isNotEmpty() }
                    if (areas.isEmpty()) continue
                    for (polygon in areas) {
                        for (ring in polygon) {
                            for (point in ring) addBounds(point.lat, point.lon, false)
                        }
                    }
                    emit(FeatureOut(paint, k, name, null, null, null, Geometry.Areas(areas)))
                }
            }
        }

        out.append("]}")
        if (count == 0) error("La carta S-57 no contiene objetos representables")

        val bounds = if (hasCoverage) {
            makeBounds(covMinLat, covMaxLat, covMinLon, covMaxLon)
        } else {
            makeBounds(minLat, maxLat, minLon, maxLon)
        }

        val latitude = bounds?.let { (it.north + it.south) / 2.0 } ?: 0.0
        val effectiveScale = if (scale > 0) scale else 50_000
        // One chart pixel is ~0.2646 mm at 96 dpi; pick the zoom where that matches.
        val metersPerPixel = effectiveScale * 0.0002645833
        val nativeZoom = log2(156543.03392 * cos(latitude * PI / 180.0) / metersPerPixel)
            .roundToInt()
            .coerceIn(3, 17)

        return S57Chart(
            name = dataSetName.substringBeforeLast('.').ifBlank {
                fileName.substringBeforeLast('.').ifBlank { "ENC" }
            },
            scale = effectiveScale,
            bounds = bounds,
            geoJson = out.toString(),
            featureCount = count,
            minZoom = (nativeZoom - 5).coerceAtLeast(3),
            maxZoom = (nativeZoom + 5).coerceAtMost(18),
            updateCells = updates.size,
            updatedRecords = updatedRecords,
        )
    }

    /** Sequential update cells next to [file]: `US5CA13M.001`, `.002`… */
    private fun findUpdateFiles(file: File): List<File> {
        val directory = file.parentFile ?: return emptyList()
        val stem = file.nameWithoutExtension
        val first = file.extension.toIntOrNull() ?: return emptyList()
        val out = ArrayList<File>()
        var index = first + 1
        var misses = 0
        while (index <= first + 99 && misses < 3) {
            val candidate = File(directory, "%s.%03d".format(stem, index))
            if (candidate.exists() && candidate.length() > 0) {
                out.add(candidate)
                misses = 0
            } else {
                misses++
            }
            index++
        }
        return out
    }

    // ------------------------------------------------------------------ records

    private data class VectorRecord(
        val points: List<LatLon>,
        val depths: List<Double>,
        val pointers: List<Ptr>,
    )

    private data class Ptr(
        val rcnm: Int,
        val rcid: Int,
        val ornt: Int,
        val usag: Int,
        val mask: Int,
    )

    private data class Vrid(val rcnm: Int, val rcid: Int, val ruin: Int)

    private data class Frid(
        val rcnm: Int,
        val rcid: Int,
        val prim: Int,
        val grup: Int,
        val objl: Int,
        val ruin: Int,
    )

    private class GeometryBuilder(private val vectors: Map<Long, VectorRecord>) {

        fun points(ptrs: List<Ptr>, comf: Double): List<Pair<LatLon, Double?>> {
            val out = ArrayList<Pair<LatLon, Double?>>()
            for (ptr in ptrs) {
                val vector = vectors[key(ptr.rcnm, ptr.rcid)] ?: continue
                for (i in vector.points.indices) {
                    out.add(georef(vector.points[i], comf) to vector.depths.getOrNull(i))
                }
            }
            return out
        }

        fun lines(ptrs: List<Ptr>, comf: Double): List<List<LatLon>> {
            val out = ArrayList<List<LatLon>>()
            for (ptr in ptrs) {
                val segment = line(ptr, comf, 0)
                if (segment.size >= 2) out.add(segment)
            }
            return out
        }

        fun polygons(ptrs: List<Ptr>, comf: Double): List<List<List<LatLon>>> {
            val out = ArrayList<List<List<LatLon>>>()
            for (ptr in ptrs) out.addAll(polygonsFor(ptr, comf, 0))
            return out
        }

        private fun polygonsFor(ptr: Ptr, comf: Double, depth: Int): List<List<List<LatLon>>> {
            if (depth > 3) return emptyList()
            val vector = vectors[key(ptr.rcnm, ptr.rcid)] ?: return emptyList()
            if (vector.points.isNotEmpty()) {
                return listOf(listOf(vector.points.map { georef(it, comf) }))
            }
            if (vector.pointers.isEmpty()) return emptyList()

            // S-57 faces separate exterior (USAG=1) and interior (USAG=2) boundaries.
            val groups = LinkedHashMap<Int, MutableList<LatLon>>()
            for (child in vector.pointers) {
                val usage = if (child.usag == 2) 2 else 1
                append(groups.getOrPut(usage) { ArrayList() }, line(child, comf, depth + 1))
            }
            val exterior = groups[1] ?: groups.values.firstOrNull() ?: return emptyList()
            val polygon = ArrayList<List<LatLon>>()
            polygon.add(exterior)
            if (groups.containsKey(1)) {
                for ((usage, holes) in groups) {
                    if (usage == 2 && holes.size >= 3) polygon.add(holes)
                }
            }
            return listOf(polygon)
        }

        private fun line(ptr: Ptr, comf: Double, depth: Int): List<LatLon> {
            if (depth > 3) return emptyList()
            val vector = vectors[key(ptr.rcnm, ptr.rcid)] ?: return emptyList()
            val result = ArrayList<LatLon>()
            if (vector.points.isNotEmpty()) {
                for (point in vector.points) result.add(georef(point, comf))
            } else {
                for (child in vector.pointers) {
                    append(result, line(child, comf, depth + 1))
                }
            }
            if (ptr.ornt == 2) result.reverse()
            return result
        }

        private fun append(destination: MutableList<LatLon>, segment: List<LatLon>) {
            if (segment.isEmpty()) return
            if (destination.isEmpty()) {
                destination.addAll(segment)
                return
            }
            val start = if (destination.last() == segment.first()) 1 else 0
            for (i in start until segment.size) destination.add(segment[i])
        }

        private fun georef(point: LatLon, comf: Double): LatLon =
            LatLon(point.lat / comf, normalizeLon(point.lon / comf))
    }

    private sealed class Geometry {
        class Point(val point: LatLon) : Geometry()
        class Lines(val lines: List<List<LatLon>>) : Geometry()
        class Area(val ring: List<LatLon>) : Geometry()
        /** Each polygon is `[exterior, hole, hole…]`. */
        class Areas(val polygons: List<List<List<LatLon>>>) : Geometry()
    }

    private class FeatureOut(
        val paint: Paint,
        val k: String,
        val name: String?,
        val label: String?,
        val icon: String?,
        val depth: Double?,
        val geometry: Geometry,
    )

    private data class Paint(
        val layer: String,
        val fill: String,
        val stroke: String,
        val opacity: Double,
        val width: Double,
        val radius: Double,
    )

    // -------------------------------------------------------------- S-57 fields

    private fun parseFrid(data: ByteArray): Frid? {
        if (data.size < 12) return null
        return Frid(
            rcnm = u8(data, 0),
            rcid = i32(data, 1),
            prim = u8(data, 5),
            grup = u8(data, 6),
            objl = u16(data, 7),
            ruin = u8(data, 11),
        )
    }

    private fun parseVrid(data: ByteArray): Vrid? {
        if (data.size < 8) return null
        return Vrid(u8(data, 0), i32(data, 1), u8(data, 7))
    }

    private fun parseFspt(data: ByteArray): List<Ptr> {
        val out = ArrayList<Ptr>()
        var offset = 0
        while (offset + 8 <= data.size) {
            val rcnm = u8(data, offset)
            val rcid = i32(data, offset + 1)
            val ornt = u8(data, offset + 5)
            val usag = u8(data, offset + 6)
            val mask = u8(data, offset + 7)
            if (rcnm == 0) break
            out.add(Ptr(rcnm, rcid, ornt, usag, mask))
            offset += 8
        }
        return out
    }

    private fun parseVrpt(data: ByteArray): List<Ptr> {
        val out = ArrayList<Ptr>()
        var offset = 0
        while (offset + 9 <= data.size) {
            val rcnm = u8(data, offset)
            val rcid = i32(data, offset + 1)
            val ornt = u8(data, offset + 5)
            val usag = u8(data, offset + 6)
            val topi = u8(data, offset + 7)
            val mask = u8(data, offset + 8)
            if (rcnm == 0) break
            out.add(Ptr(rcnm, rcid, ornt, topi, mask))
            offset += 9
        }
        return out
    }

    private fun parseSg2d(data: ByteArray, out: MutableList<LatLon>) {
        var offset = 0
        while (offset + 8 <= data.size) {
            out.add(LatLon(i32(data, offset).toDouble(), i32(data, offset + 4).toDouble()))
            offset += 8
        }
    }

    private fun parseSg3d(data: ByteArray, out: MutableList<LatLon>, depths: MutableList<Double>) {
        var offset = 0
        while (offset + 12 <= data.size) {
            out.add(LatLon(i32(data, offset).toDouble(), i32(data, offset + 4).toDouble()))
            depths.add(i32(data, offset + 8).toDouble())
            offset += 12
        }
    }

    private fun parseAttributes(data: ByteArray): Map<Int, String> {
        val out = HashMap<Int, String>()
        var offset = 0
        while (offset + 2 <= data.size) {
            val label = u16(data, offset)
            offset += 2
            if (label == 0) break
            val start = offset
            while (offset < data.size) {
                val byte = data[offset].toInt() and 0xFF
                if (byte == 0x1F || byte == 0x1E) break
                offset++
            }
            val value = String(data, start, offset - start, StandardCharsets.ISO_8859_1).trim()
            if (value.isNotEmpty()) out[label] = value
            while (offset < data.size) {
                val byte = data[offset].toInt() and 0xFF
                if (byte != 0x1F && byte != 0x0A && byte != 0x0D) break
                offset++
            }
        }
        return out
    }

    private fun parseDataSetName(data: ByteArray): String {
        if (data.size < 8) return ""
        var offset = 7
        val start = offset
        while (offset < data.size) {
            val byte = data[offset].toInt() and 0xFF
            if (byte == 0x1F || byte == 0x1E) break
            offset++
        }
        return String(data, start, offset - start, StandardCharsets.ISO_8859_1).trim()
    }

    // -------------------------------------------------------------------- paint

    private fun styleFor(objl: Int, attributes: Map<Int, String>): Paint? = when (objl) {
        42 -> Paint("depth", depthFill(attributes[87]?.toDoubleOrNull()), "#8FAFBF", 0.85, 0.8, 0.0)
        46 -> Paint("depth", "#CDE4F2", "#7FA3B5", 0.8, 1.0, 0.0)
        154 -> Paint("depth", "#E7E7E7", "#9E9E9E", 0.9, 1.0, 0.0)
        69 -> Paint("depth", "#BFE3F2", "#7FA3B5", 0.65, 1.0, 0.0)
        71 -> Paint("land", "#F7E7B5", "#B7A97A", 1.0, 1.0, 0.0)
        112 -> Paint("area", "#C2185B", "#C2185B", 0.07, 1.4, 0.0)
        51 -> Paint("area", "#C2185B", "#C2185B", 0.05, 1.2, 0.0)
        27 -> Paint("area", "#FFA000", "#FFA000", 0.07, 1.2, 0.0)
        30 -> Paint("line", "", "#1A1A1A", 0.0, 1.7, 0.0)
        43 -> Paint("line", "", "#7FA3B5", 0.0, 1.0, 0.0)
        85, 109 -> Paint("line", "", "#C2185B", 0.0, 1.0, 0.0)
        122 -> Paint("line", "", "#555555", 0.0, 1.2, 0.0)
        5, 9, 14, 19 -> Paint("point", "#F9A825", "#111111", 0.0, 0.0, 5.0)
        7, 17 -> Paint("point", lateralColor(attributes[36]), "#111111", 0.0, 0.0, 5.0)
        6, 8, 16, 18 -> Paint("point", "#D32F2F", "#111111", 0.0, 0.0, 5.0)
        75, 76, 77, 103 -> Paint("point", "#C2185B", "#FFFFFF", 0.0, 0.0, 4.5)
        84 -> Paint("point", "#757575", "#FFFFFF", 0.0, 0.0, 3.5)
        86 -> Paint("point", "#616161", "#FFFFFF", 0.0, 0.0, 3.5)
        90 -> Paint("point", "#8D6E63", "#FFFFFF", 0.0, 0.0, 3.0)
        129 -> Paint("sound", "#37474F", "", 0.0, 0.0, 1.1)
        153 -> Paint("point", "#616161", "#FFFFFF", 0.0, 0.0, 3.5)
        159 -> Paint("point", "#6D4C41", "#FFFFFF", 0.0, 0.0, 4.0)
        else -> null
    }

    /** Icon name for a point object, used by the MapLibre symbol layer. */
    private fun pointIcon(objl: Int, attributes: Map<Int, String>): String? = when (objl) {
        7, 17 -> when (attributes[36]?.trim()) {
            "1", "3" -> "s57-buoy-port"
            "2", "4" -> "s57-buoy-starboard"
            else -> "s57-buoy-special"
        }
        6, 16 -> "s57-buoy-danger"
        5, 14 -> "s57-cardinal"
        8, 18 -> "s57-buoy-safe"
        9, 19 -> "s57-buoy-special"
        75, 76, 77, 103 -> "s57-light"
        84 -> "s57-mooring"
        86 -> "s57-obstruction"
        153 -> "s57-rock"
        159 -> "s57-wreck"
        else -> null
    }

    private fun lateralColor(value: String?): String = when (value?.trim()) {
        "1" -> "#D32F2F" // port (red)
        "2" -> "#2E7D32" // starboard (green)
        "3" -> "#D32F2F"
        "4" -> "#2E7D32"
        else -> "#F9A825"
    }

    private fun depthFill(drval1: Double?): String = when {
        drval1 == null -> "#EAF6FB"
        drval1 < 2.0 -> "#C9E9F7"
        drval1 < 5.0 -> "#B5DFF0"
        drval1 < 10.0 -> "#A2D4E9"
        drval1 < 20.0 -> "#8FC9E2"
        drval1 < 30.0 -> "#A9D6EA"
        else -> "#EAF6FB"
    }

    /** A wedge polygon for a light sector (`SECTR1`/`SECTR2`). */
    private fun lightSector(center: LatLon, attributes: Map<Int, String>): List<LatLon>? {
        val start = attributes[136]?.toDoubleOrNull() ?: return null
        val end = attributes[137]?.toDoubleOrNull() ?: return null
        if (start == end) return null
        var sweep = (end - start) % 360.0
        if (sweep <= 0.0) sweep += 360.0
        if (sweep < 1.0) return null

        val rangeNm = attributes[178]?.toDoubleOrNull()?.coerceIn(0.1, 6.0) ?: 0.8
        val radius = rangeNm * 1852.0
        val steps = maxOf(8, (sweep / 8.0).roundToInt())
        val cosLat = cos(Math.toRadians(center.lat)).coerceAtLeast(0.01)
        val ring = ArrayList<LatLon>(steps + 3)
        ring.add(center)
        for (i in 0..steps) {
            val bearing = Math.toRadians(start + sweep * i / steps)
            val dLat = radius * cos(bearing) / 111_320.0
            val dLon = radius * sin(bearing) / (111_320.0 * cosLat)
            ring.add(LatLon(center.lat + dLat, normalizeLon(center.lon + dLon)))
        }
        ring.add(center)
        return ring
    }

    private fun lightColour(attributes: Map<Int, String>): String =
        COLOUR_HEX[attributes[75]?.split(',')?.firstOrNull()?.trim()] ?: "#FFFFFF"

    /** Simplified S-52 light label: `Fl(2) R 6s 12m 8M`. */
    private fun lightLabel(name: String?, attributes: Map<Int, String>): String? {
        val characteristic = attributes[107]?.trim()?.let {
            LIGHT_CHARACTERISTICS[it] ?: it
        }
        val group = attributes[141]?.trim()?.takeIf { it.isNotEmpty() }?.let {
            if (it.startsWith("(")) it else "($it)"
        }
        val colour = COLOUR_NAMES[attributes[75]?.split(',')?.firstOrNull()?.trim()]
        val period = attributes[142]?.toDoubleOrNull()?.let { "${trimNumber(it)}s" }
        val height = attributes[95]?.toDoubleOrNull()?.let { "${trimNumber(it)}m" }
        val range = attributes[178]?.toDoubleOrNull()?.let { "${trimNumber(it)}M" }

        val parts = ArrayList<String>(4)
        val head = buildString {
            if (characteristic != null) append(characteristic)
            if (group != null) append(group)
        }
        if (head.isNotEmpty()) parts.add(head)
        colour?.let(parts::add)
        period?.let(parts::add)
        height?.let(parts::add)
        range?.let(parts::add)

        val detail = parts.joinToString(" ")
        return when {
            name != null && detail.isNotEmpty() -> "$name $detail"
            name != null -> name
            detail.isNotEmpty() -> detail
            else -> null
        }
    }

    private fun trimNumber(value: Double): String {
        val rounded = kotlin.math.round(value * 10.0) / 10.0
        return if (rounded == rounded.toLong().toDouble()) rounded.toLong().toString()
        else rounded.toString()
    }

    // ------------------------------------------------------------------ GeoJSON

    private fun appendFeature(out: StringBuilder, feature: FeatureOut) {
        val paint = feature.paint
        out.append("{\"type\":\"Feature\",\"properties\":{")
        prop(out, "layer", paint.layer)
        out.append(',')
        prop(out, "k", feature.k)
        feature.name?.takeIf { it.isNotBlank() }?.let {
            out.append(',')
            prop(out, "name", it)
        }
        feature.label?.takeIf { it.isNotBlank() }?.let {
            out.append(',')
            prop(out, "label", it)
        }
        feature.icon?.takeIf { it.isNotBlank() }?.let {
            out.append(',')
            prop(out, "icon", it)
        }
        if (paint.fill.isNotEmpty()) {
            out.append(',')
            prop(out, "fill", paint.fill)
        }
        if (paint.stroke.isNotEmpty()) {
            out.append(',')
            prop(out, "stroke", paint.stroke)
        }
        out.append(",\"opacity\":").append(paint.opacity)
        out.append(",\"width\":").append(paint.width)
        out.append(",\"radius\":").append(paint.radius)
        feature.depth?.let { out.append(",\"depth\":").append(it) }
        out.append("},\"geometry\":")
        appendGeometry(out, feature.geometry)
        out.append('}')
    }

    private fun appendGeometry(out: StringBuilder, geometry: Geometry) {
        when (geometry) {
            is Geometry.Point -> {
                out.append("{\"type\":\"Point\",\"coordinates\":")
                appendPoint(out, geometry.point)
                out.append('}')
            }

            is Geometry.Lines -> {
                if (geometry.lines.size == 1) {
                    out.append("{\"type\":\"LineString\",\"coordinates\":")
                    appendLine(out, geometry.lines[0])
                    out.append('}')
                } else {
                    out.append("{\"type\":\"MultiLineString\",\"coordinates\":[")
                    geometry.lines.forEachIndexed { index, line ->
                        if (index > 0) out.append(',')
                        appendLine(out, line)
                    }
                    out.append("]}")
                }
            }

            is Geometry.Area -> {
                out.append("{\"type\":\"Polygon\",\"coordinates\":[")
                appendLine(out, geometry.ring)
                out.append("]}")
            }

            is Geometry.Areas -> {
                if (geometry.polygons.size == 1) {
                    out.append("{\"type\":\"Polygon\",\"coordinates\":[")
                    appendPolygon(out, geometry.polygons[0])
                    out.append("]}")
                } else {
                    out.append("{\"type\":\"MultiPolygon\",\"coordinates\":[")
                    geometry.polygons.forEachIndexed { index, polygon ->
                        if (index > 0) out.append(',')
                        out.append('[')
                        appendPolygon(out, polygon)
                        out.append(']')
                    }
                    out.append("]}")
                }
            }
        }
    }

    private fun appendPolygon(out: StringBuilder, rings: List<List<LatLon>>) {
        rings.forEachIndexed { index, ring ->
            if (index > 0) out.append(',')
            appendLine(out, ring)
        }
    }

    private fun appendLine(out: StringBuilder, line: List<LatLon>) {
        out.append('[')
        line.forEachIndexed { index, point ->
            if (index > 0) out.append(',')
            appendPoint(out, point)
        }
        out.append(']')
    }

    private fun appendPoint(out: StringBuilder, point: LatLon) {
        out.append('[').append(number(point.lon)).append(',').append(number(point.lat)).append(']')
    }

    private fun number(value: Double): String {
        val rounded = kotlin.math.round(value * 100_000.0) / 100_000.0
        return if (rounded == rounded.toLong().toDouble()) {
            rounded.toLong().toString()
        } else {
            rounded.toString()
        }
    }

    private fun prop(out: StringBuilder, key: String, value: String) {
        out.append('"').append(key).append("\":\"")
        for (char in value) {
            when (char) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (char.code >= 0x20) out.append(char)
            }
        }
        out.append('"')
    }

    // ------------------------------------------------------------------ helpers

    private fun key(rcnm: Int, rcid: Int): Long =
        (rcnm.toLong() shl 32) or (rcid.toLong() and 0xFFFF_FFFFL)

    private fun closeRing(ring: List<LatLon>): List<LatLon> {
        if (ring.size < 3) return ring
        if (ring.first() == ring.last()) return ring
        return ring + ring.first()
    }

    private fun formatDepth(depth: Double?): String? {
        if (depth == null) return null
        val rounded = kotlin.math.round(depth * 10.0) / 10.0
        return if (rounded == rounded.toLong().toDouble()) rounded.toLong().toString()
        else rounded.toString()
    }

    private fun normalizeLon(lon: Double): Double {
        var value = lon
        while (value > 180.0) value -= 360.0
        while (value < -180.0) value += 360.0
        return value
    }

    private fun makeBounds(south: Double, north: Double, west: Double, east: Double): GeoBounds? {
        if (!south.isFinite() || !north.isFinite() || !west.isFinite() || !east.isFinite()) {
            return null
        }
        if (north < south || east < west) return null
        return GeoBounds(
            north = north.coerceIn(-90.0, 90.0),
            south = south.coerceIn(-90.0, 90.0),
            east = east.coerceIn(-180.0, 180.0),
            west = west.coerceIn(-180.0, 180.0),
        ).takeIf { it.isValid }
    }

    private fun u8(data: ByteArray, offset: Int): Int =
        data[offset].toInt() and 0xFF

    private fun u16(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)

    private fun i32(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or
            ((data[offset + 1].toInt() and 0xFF) shl 8) or
            ((data[offset + 2].toInt() and 0xFF) shl 16) or
            ((data[offset + 3].toInt() and 0xFF) shl 24)
}
