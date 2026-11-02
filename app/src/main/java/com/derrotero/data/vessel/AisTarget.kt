package com.derrotero.data.vessel

/** One AIS/other-vessel contact derived from the SignalK delta stream. */
data class AisTarget(
    val id: String,
    val lat: Double,
    val lon: Double,
    val mmsi: String? = null,
    val name: String? = null,
    val sogMps: Double? = null,
    val cogRad: Double? = null,
    val headingRad: Double? = null,
    val vesselClass: String? = null,
    val lastSeenMs: Long = 0L,
    /** Ship category derived from the AIS ship-type code (cargo, tanker, passenger…). */
    val category: String? = null,
    /** Short letter used on the map for the category. */
    val code: String? = null,
    /** "signal" (local SignalK/AIS) or "internet" (AIS aggregator). */
    val source: String? = null,
    val destination: String? = null,
    val lengthM: Double? = null,
    val beamM: Double? = null,
    val draughtM: Double? = null,
    val navStatus: String? = null,
    /** Rate of turn in degrees per minute (positive = starboard). */
    val rotDegPerMin: Double? = null,
) {
    /** Best available short label. */
    val label: String
        get() = name?.takeIf { it.isNotBlank() }
            ?: mmsi?.takeIf { it.isNotBlank() }
            ?: id.substringAfterLast('.').takeIf { it.isNotBlank() }
            ?: "?"
}
