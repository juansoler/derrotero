package com.derrotero.data.signalk

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlin.math.PI
import kotlin.math.abs

/**
 * SignalK servers expose the "full model" as deeply nested JSON where leaf values look like
 * `{"value": ..., "timestamp": ...}`. [SignalKFlatten.flatten] turns it into `path -> value`.
 *
 * The helpers below also normalise the few values that non-compliant servers publish in the
 * wrong unit (angles in degrees, temperatures in Celsius, pressure in hPa) so the rest of the
 * app can always assume SI.
 */
object SignalKFlatten {

    private val SKIP_KEYS = setOf("timestamp", "\$source", "source", "meta", "version")

    fun flatten(root: JsonObject): Map<String, JsonElement> {
        val out = LinkedHashMap<String, JsonElement>()
        for ((key, element) in root) {
            if (key in SKIP_KEYS) continue
            walk(key, element, out)
        }
        return out
    }

    private fun walk(path: String, element: JsonElement, out: MutableMap<String, JsonElement>) {
        when (element) {
            is JsonObject -> {
                val value = element["value"]
                if (value != null) {
                    // A leaf: `{"value": x, "timestamp": ...}`. Keep the value even when it is
                    // an object (navigation.position, design.length…).
                    if (value !is JsonNull) out[path] = value
                    return
                }
                for ((key, child) in element) {
                    if (key in SKIP_KEYS) continue
                    walk(if (path.isEmpty()) key else "$path.$key", child, out)
                }
            }
            is JsonNull -> Unit
            else -> out[path] = element
        }
    }
}

private val TWO_PI = 2.0 * PI

fun Map<String, JsonElement>.raw(path: String): JsonElement? = this[path]

/** Numeric value (accepts numbers and numeric strings, including decimal commas). */
fun Map<String, JsonElement>.number(path: String): Double? = this[path].asDoubleOrNull()

fun Map<String, JsonElement>.text(path: String): String? = this[path].asTextOrNull()

fun Map<String, JsonElement>.bool(path: String): Boolean? {
    val p = this[path] as? JsonPrimitive ?: return null
    p.booleanOrNull?.let { return it }
    return when (p.contentOrNull?.trim()?.lowercase()) {
        "1", "yes", "on", "true" -> true
        "0", "no", "off", "false" -> false
        else -> null
    }
}

/**
 * Angle in radians. SignalK mandates radians, but some plugins publish degrees.
 * Anything beyond one full turn is treated as degrees, which fixes headings shown as
 * "3471°" without breaking legitimate radian values (always < 2π).
 */
fun Map<String, JsonElement>.angle(path: String): Double? {
    val v = number(path) ?: return null
    return if (abs(v) > TWO_PI + 0.01) Math.toRadians(v) else v
}

/** Angle in degrees (radians from the server converted). */
fun Map<String, JsonElement>.angleDegrees(path: String): Double? =
    angle(path)?.let { Math.toDegrees(it) }

/** Temperature in kelvin. Non-standard Celsius values (< 200) are converted. */
fun Map<String, JsonElement>.kelvin(path: String): Double? {
    val v = number(path) ?: return null
    return if (v < 200.0) v + 273.15 else v
}

/** Pressure in pascal. Non-standard hPa values are converted. */
fun Map<String, JsonElement>.pascal(path: String): Double? {
    val v = number(path) ?: return null
    return if (v in 1.0..2000.0) v * 100.0 else v
}

/**
 * Depth published by the server, preferring the value closest to the keel.
 * Many installations publish only one of belowTransducer / belowKeel / belowSurface.
 */
fun Map<String, JsonElement>.depthBest(): Double? =
    number(Paths.DEPTH) ?: number(Paths.DEPTH_KEEL) ?: number(Paths.DEPTH_SURFACE)

fun Map<String, JsonElement>.latitude(): Double? =
    positionObject()?.get("latitude").asDoubleOrNull()

fun Map<String, JsonElement>.longitude(): Double? =
    positionObject()?.get("longitude").asDoubleOrNull()

/** True when at least one of the given paths has a usable value. */
fun Map<String, JsonElement>.hasAny(vararg paths: String): Boolean =
    paths.any { this[it] != null && this[it] !is JsonNull }

/**
 * Position is normally `{"latitude": .., "longitude": ..}` but the full model can wrap it
 * again as `{"value": {...}}` if the flattener did not run.
 */
private fun Map<String, JsonElement>.positionObject(): JsonObject? {
    val el = this[Paths.POSITION] as? JsonObject ?: return null
    return (el["value"] as? JsonObject) ?: el
}

private fun JsonElement?.asDoubleOrNull(): Double? {
    val p = this as? JsonPrimitive ?: return null
    if (p is JsonNull) return null
    val raw = p.contentOrNull?.trim() ?: return null
    if (raw.isEmpty()) return null
    val v = raw.replace(',', '.').toDoubleOrNull() ?: return null
    return v.takeIf { it.isFinite() }
}

private fun JsonElement?.asTextOrNull(): String? = when (this) {
    is JsonPrimitive -> if (this is JsonNull) null else contentOrNull
    is JsonObject -> (this["value"] as? JsonPrimitive)?.contentOrNull
        ?: (this["name"] as? JsonPrimitive)?.contentOrNull
    else -> null
}
