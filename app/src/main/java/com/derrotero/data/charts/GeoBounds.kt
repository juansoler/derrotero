package com.derrotero.data.charts

/** Simple WGS84 bounding box (degrees). */
data class GeoBounds(
    val north: Double,
    val south: Double,
    val east: Double,
    val west: Double,
) {
    val isValid: Boolean
        get() = north > south && east > west &&
            north in -90.0..90.0 && south in -90.0..90.0 &&
            west in -180.0..180.0 && east in -180.0..180.0

    fun summary(): String =
        "N %.4f · S %.4f · W %.4f · E %.4f".format(north, south, west, east)
}
