package com.derrotero.data.vessel

import com.derrotero.data.ais.ShipType
import com.derrotero.data.signalk.DeltaEnvelope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * Tracks other vessels (`vessels.<id>` / `vessels.urn:…` contexts) from the SignalK delta stream.
 *
 * Deltas are partial: a target is materialised once it reports a position, and the remaining
 * fields (name, MMSI, SOG, COG…) are filled in as they arrive. Targets that stop transmitting
 * are dropped by [expireOlderThan].
 */
class AisRepository {

    private class Builder {
        var mmsi: String? = null
        var name: String? = null
        var lat: Double? = null
        var lon: Double? = null
        var sog: Double? = null
        var cog: Double? = null
        var hdg: Double? = null
        var cls: String? = null
        var destination: String? = null
        var length: Double? = null
        var beam: Double? = null
        var draught: Double? = null
        var navStatus: String? = null
        var rot: Double? = null
        var shipType: Int? = null
        var last: Long = 0L
    }

    private val builders = ConcurrentHashMap<String, Builder>()

    private val _targets = MutableStateFlow<Map<String, AisTarget>>(emptyMap())
    val targets: StateFlow<Map<String, AisTarget>> = _targets.asStateFlow()

    /**
     * @param isSelf predicate supplied by the vessel repository; deltas from the own vessel
     *   (whose context is a URN in real servers) must never become an AIS target.
     */
    fun onDelta(
        delta: DeltaEnvelope,
        isSelf: (String?) -> Boolean = { it == null || it == "vessels.self" },
    ) {
        val ctx = delta.context ?: return
        if (isSelf(ctx)) return
        if (!ctx.startsWith("vessels.")) return
        if (ctx == "vessels.self") return

        val builder = builders.getOrPut(ctx) { Builder() }
        var touched = false
        val now = System.currentTimeMillis()

        for (update in delta.updates) {
            for (v in update.values) {
                when (v.path) {
                    "navigation.position" -> v.value.asLatLon()?.let { (lat, lon) ->
                        builder.lat = lat
                        builder.lon = lon
                        touched = true
                    }
                    "navigation.speedOverGround" ->
                        v.value.asDouble()?.let { builder.sog = it; touched = true }
                    "navigation.courseOverGroundTrue",
                    "navigation.courseOverGroundMagnetic",
                    -> v.value.asDouble()?.let { builder.cog = normalizeRadians(it); touched = true }
                    "navigation.headingTrue", "navigation.headingMagnetic" ->
                        v.value.asDouble()?.let { builder.hdg = normalizeRadians(it); touched = true }
                    "navigation.rateOfTurn" ->
                        v.value.asDouble()?.let { builder.rot = Math.toDegrees(it) * 60.0; touched = true }
                    "name", "design.name" ->
                        v.value.asString()?.takeIf { it.isNotBlank() }?.let { builder.name = it; touched = true }
                    "mmsi", "design.mmsi", "sensors.ais.mmsi" ->
                        v.value.asString()?.takeIf { it.isNotBlank() }?.let { builder.mmsi = it; touched = true }
                    "sensors.ais.class" ->
                        v.value.asString()?.let { builder.cls = normalizeClass(it); touched = true }
                    "design.aisShipType", "sensors.ais.shipType" ->
                        v.value.asInt()?.let { builder.shipType = it; touched = true }
                    "design.destination", "navigation.destination.commonName" ->
                        v.value.asString()?.takeIf { it.isNotBlank() }?.let {
                            builder.destination = it
                            touched = true
                        }
                    "navigation.state" ->
                        v.value.asString()?.takeIf { it.isNotBlank() }?.let {
                            builder.navStatus = it
                            touched = true
                        }
                    "design.length" -> v.value.asDimension()?.let { builder.length = it; touched = true }
                    "design.beam" -> v.value.asDimension()?.let { builder.beam = it; touched = true }
                    "design.draft", "design.draught" ->
                        v.value.asDimension()?.let { builder.draught = it; touched = true }
                }
            }
        }
        if (!touched) return

        // Recover the MMSI from the context URN when the target only sends dynamic data.
        if (builder.mmsi == null) builder.mmsi = mmsiFromContext(ctx)
        builder.last = now
        publish()
    }

    /** Drop targets not heard from within [ttlMs]. */
    fun expireOlderThan(ttlMs: Long, now: Long = System.currentTimeMillis()) {
        val removed = builders.entries.removeIf { it.value.last < now - ttlMs }
        if (removed) publish()
    }

    fun clear() {
        builders.clear()
        _targets.value = emptyMap()
    }

    private fun publish() {
        val out = HashMap<String, AisTarget>(builders.size)
        for ((id, b) in builders) {
            val lat = b.lat ?: continue
            val lon = b.lon ?: continue
            val (category, code) = ShipType.classify(b.shipType)
            out[id] = AisTarget(
                id = id,
                lat = lat,
                lon = lon,
                mmsi = b.mmsi,
                name = b.name,
                sogMps = b.sog,
                cogRad = b.cog,
                headingRad = b.hdg,
                vesselClass = b.cls,
                lastSeenMs = b.last,
                source = "signal",
                category = category,
                code = code,
                destination = b.destination,
                lengthM = b.length,
                beamM = b.beam,
                draughtM = b.draught,
                navStatus = b.navStatus,
                rotDegPerMin = b.rot,
            )
        }
        _targets.value = out
    }

    private fun mmsiFromContext(ctx: String): String? =
        ctx.substringAfterLast(':')
            .takeIf { it.length in 7..9 && it.all(Char::isDigit) }

    private fun normalizeClass(raw: String): String? {
        val v = raw.trim().uppercase()
        return when {
            v.isEmpty() -> null
            v == "A" || v.contains("CLASS A") || v.contains("CLASE A") -> "A"
            v == "B" || v.contains("CLASS B") || v.contains("CLASE B") -> "B"
            else -> raw.trim()
        }
    }

    /** Angles are radians per spec; a few gateways publish degrees. */
    private fun normalizeRadians(v: Double): Double =
        if (abs(v) > 2 * Math.PI + 0.01) Math.toRadians(v) else v

    private fun JsonElement?.asDouble(): Double? =
        runCatching { this?.jsonPrimitive?.content?.replace(',', '.')?.toDoubleOrNull() }.getOrNull()

    private fun JsonElement?.asInt(): Int? {
        asDouble()?.let { return it.toInt() }
        val obj = runCatching { this?.jsonObject }.getOrNull() ?: return null
        return (obj["id"] ?: obj["value"])
            ?.jsonPrimitive
            ?.content
            ?.toIntOrNull()
    }

    private fun JsonElement?.asString(): String? =
        runCatching { this?.jsonPrimitive?.content }.getOrNull()

    private fun JsonElement?.asLatLon(): Pair<Double, Double>? {
        val o = runCatching { this?.jsonObject }.getOrNull() ?: return null
        val inner = runCatching { (o["value"] as? JsonObject) }.getOrNull() ?: o
        val lat = inner["latitude"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: return null
        val lon = inner["longitude"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: return null
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        return lat to lon
    }

    /**
     * `design.length` may be a number or an object (`{"overall": 12.4}`). Accept both.
     */
    private fun JsonElement?.asDimension(): Double? {
        val direct = asDouble()
        if (direct != null) return direct
        val obj = runCatching { this?.jsonObject }.getOrNull() ?: return null
        return listOf("overall", "maximum", "current", "value")
            .firstNotNullOfOrNull { key ->
                obj[key]?.jsonPrimitive?.content?.toDoubleOrNull()
            }
    }
}
