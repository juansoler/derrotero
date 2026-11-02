package com.derrotero.data.nmea

import com.derrotero.data.signalk.DeltaEnvelope
import com.derrotero.data.signalk.Paths
import com.derrotero.data.signalk.SignalKPathValue
import com.derrotero.data.signalk.SignalKUpdate
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import java.util.concurrent.ConcurrentHashMap

data class NmeaParseResult(
    val type: String? = null,
    val checksumOk: Boolean? = null,
    val values: Map<String, JsonElement> = emptyMap(),
    val ais: List<DeltaEnvelope> = emptyList(),
    val error: String? = null,
)

/**
 * NMEA 0183 reader for the sentences a boat actually produces, plus an AIS decoder for
 * `!AIVDM`/`!AIVDO` (types 1/2/3, 5, 18, 19 and 24).
 *
 * All values are converted to Signal K units (metres, m/s, radians, kelvin, pascal) so the
 * rest of the app does not need to know whether the data came from NMEA or from the Signal K
 * delta stream.
 */
class Nmea0183Parser {

    private data class Fragment(
        val total: Int,
        val parts: MutableMap<Int, String> = ConcurrentHashMap(),
        var updatedAt: Long = System.currentTimeMillis(),
    )

    private val fragments = ConcurrentHashMap<String, Fragment>()

    fun reset() {
        fragments.clear()
    }

    fun parse(line: String): NmeaParseResult {
        val start = line.indexOfFirst { it == '$' || it == '!' }
        if (start < 0) return NmeaParseResult(error = "no es una frase NMEA")
        val raw = line.substring(start).trim()
        if (raw.length < 6) return NmeaParseResult(error = "frase demasiado corta")

        val star = raw.lastIndexOf('*')
        val checksumOk: Boolean?
        if (star > 0) {
            val expected = raw.substring(star + 1).take(2).toIntOrNull(16)
            checksumOk = when {
                expected == null -> null
                else -> checksum(raw.substring(1, star)) == expected
            }
            if (checksumOk == false) {
                return NmeaParseResult(checksumOk = false, error = "checksum incorrecto")
            }
        } else {
            checksumOk = null
        }

        val body = if (star > 0) raw.substring(0, star) else raw
        val fields = body.split(',')
        val head = fields.firstOrNull().orEmpty()
        if (!head.startsWith("$") && !head.startsWith("!")) {
            return NmeaParseResult(error = "cabecera no válida")
        }

        if (head.startsWith("!AI")) {
            val ais = parseAis(fields)
            if (head.startsWith("!AIVDO")) {
                // Own-ship AIS report: feed it as own-vessel navigation data, not as a target.
                val own = ais.flatMap { delta -> delta.updates.flatMap { it.values } }
                    .filter { it.path.startsWith("navigation.") }
                    .associate { it.path to it.value }
                return NmeaParseResult(type = "AIVDO", checksumOk = checksumOk, values = own)
            }
            return NmeaParseResult(
                type = head.removePrefix("!"),
                checksumOk = checksumOk,
                ais = ais,
            )
        }

        val type = head.takeLast(3)
        val values = when (type) {
            "GGA" -> gga(fields)
            "RMC" -> rmc(fields)
            "GLL" -> gll(fields)
            "VTG" -> vtg(fields)
            "HDT" -> heading(fields, magnetic = false)
            "HDM" -> heading(fields, magnetic = true)
            "VHW" -> vhw(fields)
            "DPT" -> dpt(fields)
            "DBT" -> dbt(fields)
            "MTW" -> waterTemperature(fields)
            "MWV" -> mwv(fields)
            "VWR" -> vwr(fields)
            "MDA" -> mda(fields)
            "GSA" -> gsa(fields)
            "RMB" -> rmb(fields)
            "BWC", "BWR" -> bwc(fields)
            "APB" -> apb(fields)
            "XTE" -> xte(fields)
            else -> emptyMap()
        }
        return NmeaParseResult(type = type, checksumOk = checksumOk, values = values)
    }

    // ------------------------------------------------------------- own vessel

    private fun gga(f: List<String>): Map<String, JsonElement> {
        val out = LinkedHashMap<String, JsonElement>()
        val lat = coordinate(f.getOrNull(2), f.getOrNull(3))
        val lon = coordinate(f.getOrNull(4), f.getOrNull(5))
        val quality = f.getOrNull(6)?.toIntOrNull() ?: 0
        if (quality != 0 && valid(lat, lon)) {
            out[Paths.POSITION] = position(lat!!, lon!!)
        }
        f.getOrNull(7)?.toIntOrNull()?.let { out[Paths.GNSS_SATELLITES] = JsonPrimitive(it) }
        f.getOrNull(8)?.toDoubleOrNull()?.let { out[Paths.GNSS_HDOP] = JsonPrimitive(it) }
        return out
    }

    private fun rmc(f: List<String>): Map<String, JsonElement> {
        if (!f.getOrNull(2).equals("A", true)) return emptyMap()
        val out = LinkedHashMap<String, JsonElement>()
        val lat = coordinate(f.getOrNull(3), f.getOrNull(4))
        val lon = coordinate(f.getOrNull(5), f.getOrNull(6))
        if (valid(lat, lon)) out[Paths.POSITION] = position(lat!!, lon!!)
        f.getOrNull(7)?.toDoubleOrNull()?.let { out[Paths.SOG] = JsonPrimitive(it * KNOT_TO_MPS) }
        f.getOrNull(8)?.toDoubleOrNull()?.let { out[Paths.COG] = JsonPrimitive(Math.toRadians(it)) }
        return out
    }

    private fun gll(f: List<String>): Map<String, JsonElement> {
        if (!f.getOrNull(6).equals("A", true)) return emptyMap()
        val lat = coordinate(f.getOrNull(1), f.getOrNull(2))
        val lon = coordinate(f.getOrNull(3), f.getOrNull(4))
        if (!valid(lat, lon)) return emptyMap()
        return mapOf(Paths.POSITION to position(lat!!, lon!!))
    }

    private fun vtg(f: List<String>): Map<String, JsonElement> {
        val out = LinkedHashMap<String, JsonElement>()
        f.getOrNull(1)?.toDoubleOrNull()?.let { out[Paths.COG] = JsonPrimitive(Math.toRadians(it)) }
        f.getOrNull(5)?.toDoubleOrNull()?.let { out[Paths.SOG] = JsonPrimitive(it * KNOT_TO_MPS) }
        return out
    }

    private fun heading(f: List<String>, magnetic: Boolean): Map<String, JsonElement> {
        val value = f.getOrNull(1)?.toDoubleOrNull() ?: return emptyMap()
        val path = if (magnetic) Paths.HEADING_MAG else Paths.HEADING_TRUE
        return mapOf(path to JsonPrimitive(Math.toRadians(value)))
    }

    private fun vhw(f: List<String>): Map<String, JsonElement> {
        val out = LinkedHashMap<String, JsonElement>()
        f.getOrNull(1)?.toDoubleOrNull()?.let { out[Paths.HEADING_TRUE] = JsonPrimitive(Math.toRadians(it)) }
        f.getOrNull(3)?.toDoubleOrNull()?.let { out[Paths.HEADING_MAG] = JsonPrimitive(Math.toRadians(it)) }
        f.getOrNull(5)?.toDoubleOrNull()?.let { out[Paths.STW] = JsonPrimitive(it * KNOT_TO_MPS) }
        return out
    }

    private fun dpt(f: List<String>): Map<String, JsonElement> {
        val depth = f.getOrNull(1)?.toDoubleOrNull() ?: return emptyMap()
        return mapOf(Paths.DEPTH to JsonPrimitive(depth))
    }

    private fun dbt(f: List<String>): Map<String, JsonElement> {
        // fields: feet, f, meters, M, fathoms, F
        val meters = f.getOrNull(3)?.toDoubleOrNull() ?: return emptyMap()
        return mapOf(Paths.DEPTH to JsonPrimitive(meters))
    }

    private fun waterTemperature(f: List<String>): Map<String, JsonElement> {
        val celsius = f.getOrNull(1)?.toDoubleOrNull() ?: return emptyMap()
        return mapOf(Paths.WATER_TEMP to JsonPrimitive(celsius + 273.15))
    }

    private fun mwv(f: List<String>): Map<String, JsonElement> {
        val angle = f.getOrNull(1)?.toDoubleOrNull() ?: return emptyMap()
        val reference = f.getOrNull(2)?.uppercase().orEmpty()
        val speed = speedToMps(f.getOrNull(3), f.getOrNull(4))
        val out = LinkedHashMap<String, JsonElement>()
        if (reference == "T") {
            // NMEA MWV "T" is the true wind direction (0..360 from north).
            out[Paths.WIND_DIRECTION_TRUE] = JsonPrimitive(wrap2Pi(Math.toRadians(angle)))
            speed?.let { out[Paths.WIND_SPEED_TRUE] = JsonPrimitive(it) }
        } else {
            out[Paths.WIND_ANGLE_APPARENT] = JsonPrimitive(signedAngle(Math.toRadians(angle)))
            speed?.let { out[Paths.WIND_SPEED_APPARENT] = JsonPrimitive(it) }
        }
        return out
    }

    private fun vwr(f: List<String>): Map<String, JsonElement> {
        val angle = f.getOrNull(1)?.toDoubleOrNull() ?: return emptyMap()
        val side = f.getOrNull(2)?.uppercase().orEmpty()
        val metersPerSecond = f.getOrNull(5)?.toDoubleOrNull()
            ?: f.getOrNull(3)?.toDoubleOrNull()?.times(KNOT_TO_MPS)
            ?: f.getOrNull(7)?.toDoubleOrNull()?.div(3.6)
            ?: return emptyMap()
        var radians = Math.toRadians(angle)
        if (side == "L") radians = -radians
        return mapOf(
            Paths.WIND_ANGLE_APPARENT to JsonPrimitive(signedAngle(radians)),
            Paths.WIND_SPEED_APPARENT to JsonPrimitive(metersPerSecond),
        )
    }

    private fun mda(f: List<String>): Map<String, JsonElement> {
        val out = LinkedHashMap<String, JsonElement>()
        val bar = f.getOrNull(3)?.toDoubleOrNull()
        if (bar != null) out[Paths.PRESSURE] = JsonPrimitive(bar * 100_000.0)
        f.getOrNull(5)?.toDoubleOrNull()?.let { out[Paths.AIR_TEMP] = JsonPrimitive(it + 273.15) }
        f.getOrNull(7)?.toDoubleOrNull()?.let { out[Paths.WATER_TEMP] = JsonPrimitive(it + 273.15) }
        f.getOrNull(13)?.toDoubleOrNull()?.let {
            out[Paths.WIND_DIRECTION_TRUE] = JsonPrimitive(wrap2Pi(Math.toRadians(it)))
        }
        val windMps = f.getOrNull(19)?.toDoubleOrNull()
            ?: f.getOrNull(17)?.toDoubleOrNull()?.times(KNOT_TO_MPS)
        if (windMps != null) out[Paths.WIND_SPEED_TRUE] = JsonPrimitive(windMps)
        return out
    }

    private fun gsa(f: List<String>): Map<String, JsonElement> {
        val out = LinkedHashMap<String, JsonElement>()
        f.getOrNull(16)?.toDoubleOrNull()?.let { out[Paths.GNSS_HDOP] = JsonPrimitive(it) }
        return out
    }

    private fun rmb(f: List<String>): Map<String, JsonElement> {
        val out = LinkedHashMap<String, JsonElement>()
        xte(f.getOrNull(2), f.getOrNull(3))?.let { out[Paths.XTE] = JsonPrimitive(it) }
        f.getOrNull(10)?.toDoubleOrNull()?.let { out[Paths.DTW] = JsonPrimitive(it * 1852.0) }
        f.getOrNull(11)?.toDoubleOrNull()?.let { out[Paths.BTW] = JsonPrimitive(Math.toRadians(it)) }
        f.getOrNull(12)?.toDoubleOrNull()?.let { out[Paths.VMG] = JsonPrimitive(it * KNOT_TO_MPS) }
        return out
    }

    private fun bwc(f: List<String>): Map<String, JsonElement> {
        val out = LinkedHashMap<String, JsonElement>()
        f.getOrNull(6)?.toDoubleOrNull()?.let { out[Paths.BTW] = JsonPrimitive(Math.toRadians(it)) }
        f.getOrNull(10)?.toDoubleOrNull()?.let { out[Paths.DTW] = JsonPrimitive(it * 1852.0) }
        return out
    }

    private fun apb(f: List<String>): Map<String, JsonElement> {
        val out = LinkedHashMap<String, JsonElement>()
        // $--APB,A,A,x.x,L,N,A,A,brg,M,wp,brg2,M,hdg,M
        xte(f.getOrNull(3), f.getOrNull(4))?.let { out[Paths.XTE] = JsonPrimitive(it) }
        // Prefer the bearing from the present position; fall back to the leg bearing.
        (f.getOrNull(11) ?: f.getOrNull(8))
            ?.toDoubleOrNull()
            ?.let { out[Paths.BTW] = JsonPrimitive(Math.toRadians(it)) }
        return out
    }

    private fun xte(f: List<String>): Map<String, JsonElement> {
        // Two common layouts: "$--XTE,A,x.x,L,N" and "$--XTE,A,A,x.x,L,N".
        val value = if (f.getOrNull(2)?.toDoubleOrNull() != null) {
            xte(f.getOrNull(2), f.getOrNull(3))
        } else {
            xte(f.getOrNull(3), f.getOrNull(4))
        } ?: return emptyMap()
        return mapOf(Paths.XTE to JsonPrimitive(value))
    }

    private fun xte(distanceNm: String?, side: String?): Double? {
        val value = distanceNm?.toDoubleOrNull() ?: return null
        val signed = if (side.equals("L", true)) -value else value
        return signed * 1852.0
    }

    // ------------------------------------------------------------------- AIS

    private fun parseAis(fields: List<String>): List<DeltaEnvelope> {
        if (fields.size < 6) return emptyList()
        val total = fields.getOrNull(1)?.toIntOrNull() ?: return emptyList()
        val number = fields.getOrNull(2)?.toIntOrNull() ?: return emptyList()
        val sequence = fields.getOrNull(3).orEmpty()
        val channel = fields.getOrNull(4).orEmpty()
        val payload = fields.getOrNull(5).orEmpty()
        val fill = fields.getOrNull(6)?.toIntOrNull() ?: 0
        if (payload.isEmpty() || total < 1 || number !in 1..total) return emptyList()

        expireFragments()
        if (total == 1) {
            return listOfNotNull(decodeAis(payload, fill))
        }

        val key = "${sequence.ifEmpty { channel }}|$total"
        val fragment = fragments.getOrPut(key) { Fragment(total) }
        fragment.parts[number] = payload
        fragment.updatedAt = System.currentTimeMillis()
        if (fragment.parts.size < total) return emptyList()

        fragments.remove(key)
        val whole = buildString {
            for (i in 1..total) append(fragment.parts[i] ?: return emptyList())
        }
        return listOfNotNull(decodeAis(whole, fill))
    }

    private fun expireFragments() {
        val now = System.currentTimeMillis()
        fragments.entries.removeIf { now - it.value.updatedAt > 20_000L }
    }

    private fun decodeAis(payload: String, fill: Int): DeltaEnvelope? {
        val bits = toBits(payload)
        val length = bits.size - fill.coerceIn(0, 5)
        if (length < 38) return null
        val reader = Bits(bits, length)
        val type = reader.uint(0, 6)
        val mmsi = reader.uint(8, 30)
        if (mmsi == 0) return null

        val values = LinkedHashMap<String, JsonElement>()
        values["mmsi"] = JsonPrimitive(mmsi.toString())

        when (type) {
            1, 2, 3 -> decodePositionA(reader, values)
            5 -> decodeStaticA(reader, values)
            18 -> decodePositionB(reader, values, hasName = false)
            19 -> decodePositionB(reader, values, hasName = true)
            24 -> decodeStaticB(reader, values)
            else -> return null
        }
        if (values.size <= 1) return null

        return DeltaEnvelope(
            context = "vessels.urn:mrn:imo:mmsi:$mmsi",
            updates = listOf(
                SignalKUpdate(
                    values = values.map { SignalKPathValue(it.key, it.value) },
                ),
            ),
        )
    }

    private fun decodePositionA(reader: Bits, values: MutableMap<String, JsonElement>) {
        if (reader.size < 168) return
        values[Paths.AIS_CLASS] = JsonPrimitive("A")
        values[Paths.NAV_STATE] = JsonPrimitive(navStatus(reader.uint(38, 4)))
        val rot = reader.sint(42, 8)
        if (rot != -128) {
            val degreesPerMinute = (rot / 4.733) * (rot / 4.733) * if (rot < 0) -1 else 1
            values[Paths.RATE_OF_TURN] = JsonPrimitive(Math.toRadians(degreesPerMinute) / 60.0)
        }
        positionSpeedCourse(reader, values, sogStart = 50, lonStart = 61, latStart = 89, cogStart = 116, hdgStart = 128)
    }

    private fun decodePositionB(reader: Bits, values: MutableMap<String, JsonElement>, hasName: Boolean) {
        if (reader.size < 168) return
        values[Paths.AIS_CLASS] = JsonPrimitive("B")
        positionSpeedCourse(reader, values, sogStart = 46, lonStart = 57, latStart = 85, cogStart = 112, hdgStart = 124)
        if (hasName && reader.size >= 312) {
            text(reader, 143, 120)?.let { values["name"] = JsonPrimitive(it) }
            val shipType = reader.uint(263, 8)
            if (shipType in 1..99) values[Paths.AIS_SHIP_TYPE] = JsonPrimitive(shipType)
        }
    }

    private fun positionSpeedCourse(
        reader: Bits,
        values: MutableMap<String, JsonElement>,
        sogStart: Int,
        lonStart: Int,
        latStart: Int,
        cogStart: Int,
        hdgStart: Int,
    ) {
        val lonRaw = reader.sint(lonStart, 28)
        val latRaw = reader.sint(latStart, 27)
        val lon = lonRaw / 600_000.0
        val lat = latRaw / 600_000.0
        if (abs(lat) <= 90.0 && abs(lon) <= 180.0) {
            values[Paths.POSITION] = position(lat, lon)
        }

        val sogRaw = reader.uint(sogStart, 10)
        if (sogRaw < 1023) {
            values[Paths.SOG] = JsonPrimitive(sogRaw * 0.1 * KNOT_TO_MPS)
        }
        val cogRaw = reader.uint(cogStart, 12)
        if (cogRaw < 3600) {
            values[Paths.COG] = JsonPrimitive(Math.toRadians(cogRaw * 0.1))
        }
        val headingRaw = reader.uint(hdgStart, 9)
        if (headingRaw < 511) {
            values[Paths.HEADING_TRUE] = JsonPrimitive(Math.toRadians(headingRaw.toDouble()))
        }
    }

    private fun decodeStaticA(reader: Bits, values: MutableMap<String, JsonElement>) {
        if (reader.size < 424) return
        values[Paths.AIS_CLASS] = JsonPrimitive("A")
        text(reader, 70, 42)?.let { values["callsign"] = JsonPrimitive(it) }
        text(reader, 112, 120)?.let { values["name"] = JsonPrimitive(it) }
        val shipType = reader.uint(232, 8)
        if (shipType in 1..99) values[Paths.AIS_SHIP_TYPE] = JsonPrimitive(shipType)
        dimensions(reader, values, 240, 249, 258, 264)
        val draught = reader.uint(294, 8)
        if (draught > 0) values[Paths.DESIGN_DRAFT] = JsonPrimitive(draught * 0.1)
        text(reader, 302, 120)?.let { values[Paths.DESTINATION] = JsonPrimitive(it) }
    }

    private fun decodeStaticB(reader: Bits, values: MutableMap<String, JsonElement>) {
        if (reader.size < 160) return
        values[Paths.AIS_CLASS] = JsonPrimitive("B")
        val part = reader.uint(38, 2)
        if (part == 0) {
            text(reader, 40, 120)?.let { values["name"] = JsonPrimitive(it) }
        } else if (part == 1 && reader.size >= 168) {
            val shipType = reader.uint(40, 8)
            if (shipType in 1..99) values[Paths.AIS_SHIP_TYPE] = JsonPrimitive(shipType)
            text(reader, 90, 42)?.let { values["callsign"] = JsonPrimitive(it) }
            dimensions(reader, values, 132, 141, 150, 156)
        }
    }

    private fun dimensions(
        reader: Bits,
        values: MutableMap<String, JsonElement>,
        bowStart: Int,
        sternStart: Int,
        portStart: Int,
        starboardStart: Int,
    ) {
        val bow = reader.uint(bowStart, 9)
        val stern = reader.uint(sternStart, 9)
        val port = reader.uint(portStart, 6)
        val starboard = reader.uint(starboardStart, 6)
        if (bow + stern > 0) values[Paths.DESIGN_LENGTH] = JsonPrimitive((bow + stern).toDouble())
        if (port + starboard > 0) values[Paths.DESIGN_BEAM] = JsonPrimitive((port + starboard).toDouble())
    }

    private fun text(reader: Bits, start: Int, length: Int): String? {
        if (reader.size < start + length) return null
        val builder = StringBuilder()
        var i = start
        while (i + 6 <= start + length) {
            builder.append(SIX_BIT[reader.uint(i, 6)])
            i += 6
        }
        val value = builder.toString().trim().trim('@').trim()
        return value.takeIf { it.isNotBlank() }
    }

    private fun toBits(payload: String): IntArray {
        val bits = IntArray(payload.length * 6)
        var index = 0
        for (char in payload) {
            var value = char.code - 48
            if (value > 40) value -= 8
            if (value !in 0..63) value = 0
            for (shift in 5 downTo 0) {
                bits[index++] = (value shr shift) and 1
            }
        }
        return bits
    }

    private class Bits(private val bits: IntArray, val size: Int) {
        fun uint(start: Int, length: Int): Int {
            var value = 0
            for (i in start until start + length) {
                value = (value shl 1) or (if (i < size) bits[i] else 0)
            }
            return value
        }

        fun sint(start: Int, length: Int): Int {
            val value = uint(start, length)
            val sign = 1 shl (length - 1)
            return if (value and sign != 0) value - (1 shl length) else value
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun position(lat: Double, lon: Double): JsonElement = buildJsonObject {
        put("latitude", lat)
        put("longitude", lon)
    }

    private fun valid(lat: Double?, lon: Double?): Boolean =
        lat != null && lon != null && abs(lat) <= 90.0 && abs(lon) <= 180.0

    private fun coordinate(raw: String?, hemisphere: String?): Double? {
        if (raw.isNullOrBlank() || hemisphere.isNullOrBlank()) return null
        val value = raw.toDoubleOrNull() ?: return null
        val degrees = floor(value / 100.0)
        val minutes = value - degrees * 100.0
        var result = degrees + minutes / 60.0
        if (hemisphere.equals("S", true) || hemisphere.equals("W", true)) result = -result
        return result.takeIf { it.isFinite() }
    }

    private fun speedToMps(value: String?, unit: String?): Double? {
        val speed = value?.toDoubleOrNull() ?: return null
        return when (unit?.uppercase()) {
            "N" -> speed * KNOT_TO_MPS
            "K" -> speed / 3.6
            else -> speed
        }
    }

    private fun signedAngle(radians: Double): Double {
        var value = radians
        while (value > PI) value -= 2 * PI
        while (value < -PI) value += 2 * PI
        return value
    }

    private fun wrap2Pi(radians: Double): Double {
        var value = radians % (2 * PI)
        if (value < 0) value += 2 * PI
        return value
    }

    private fun checksum(body: String): Int {
        var value = 0
        for (char in body) value = value xor char.code
        return value
    }

    private fun navStatus(status: Int): String = when (status) {
        0 -> "En navegación"
        1 -> "Fondeado"
        2 -> "Sin gobierno"
        3 -> "Restringido"
        4 -> "Limitado por calado"
        5 -> "Atracado"
        6 -> "Embarrancado"
        7 -> "Pescando"
        8 -> "A vela"
        11 -> "Remolcando"
        12 -> "Empujando"
        14 -> "AIS-SART"
        else -> "No definido"
    }

    companion object {
        private const val KNOT_TO_MPS = 0.514444
        private const val SIX_BIT =
            "@ABCDEFGHIJKLMNOPQRSTUVWXYZ[\\]^_ !\"#\$%&'()*+,-./0123456789:;<=>?"
    }
}
