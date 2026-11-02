package com.derrotero.data.polar

import kotlin.math.cos
import kotlin.math.min

data class VmgTarget(
    val twaDeg: Double,
    val boatSpeedKn: Double,
    val vmgKn: Double,
)

/**
 * Polar table: target boat speed (kn) as a function of true wind speed (rows) and true wind
 * angle (columns). Built-in default is an illustrative ~40 ft cruiser/racer; users can import
 * their own CSV.
 *
 * CSV format (comma or semicolon separated):
 * ```
 * tws,30,40,50,60,75,90,110,120,135,150,165,180
 * 4,2.6,3.2,3.8,4.2,4.6,4.8,4.4,4.0,3.4,2.8,2.4,2.2
 * 6,3.6,4.4,5.1,5.5,5.9,6.0,5.6,5.1,4.4,3.7,3.2,3.0
 * ```
 */
class PolarTable(
    val twsRows: DoubleArray,
    val twaColumns: DoubleArray,
    private val speeds: Array<DoubleArray>,
) {

    /** Target boat speed (kn) for a given TWS/TWA with bilinear interpolation. */
    fun speedKn(twsKn: Double, twaDeg: Double): Double {
        if (twsRows.size < 2 || twaColumns.size < 2) return 0.0
        val tws = twsKn.coerceIn(twsRows.first(), twsRows.last())
        val twa = twaDeg.coerceIn(twaColumns.first(), twaColumns.last())

        var i = 0
        while (i < twsRows.size - 2 && tws > twsRows[i + 1]) i++
        var j = 0
        while (j < twaColumns.size - 2 && twa > twaColumns[j + 1]) j++

        val t = safeRatio(tws - twsRows[i], twsRows[i + 1] - twsRows[i])
        val u = safeRatio(twa - twaColumns[j], twaColumns[j + 1] - twaColumns[j])

        val a = lerp(speeds[i][j], speeds[i][j + 1], u)
        val b = lerp(speeds[i + 1][j], speeds[i + 1][j + 1], u)
        return lerp(a, b, t)
    }

    fun bestUpwind(twsKn: Double): VmgTarget = best(twsKn, upwind = true)

    fun bestDownwind(twsKn: Double): VmgTarget = best(twsKn, upwind = false)

    private fun best(twsKn: Double, upwind: Boolean): VmgTarget {
        val from = if (upwind) 25.0 else 90.0
        val to = if (upwind) 90.0 else 179.0
        var bestTwa = from
        var bestVmg = -Double.MAX_VALUE
        var bestSpeed = 0.0
        var twa = from
        while (twa <= to) {
            val speed = speedKn(twsKn, twa)
            val vmg = if (upwind) {
                speed * cos(Math.toRadians(twa))
            } else {
                speed * cos(Math.toRadians(180.0 - twa))
            }
            if (vmg > bestVmg) {
                bestVmg = vmg
                bestTwa = twa
                bestSpeed = speed
            }
            twa += 0.5
        }
        return VmgTarget(bestTwa, bestSpeed, bestVmg)
    }

    fun performancePercent(actualKn: Double, twsKn: Double, twaDeg: Double): Double? {
        val target = speedKn(twsKn, twaDeg)
        if (target <= 0.1) return null
        return min(actualKn / target * 100.0, 200.0)
    }

    companion object {
        private val DEFAULT_TWS = doubleArrayOf(4.0, 6.0, 8.0, 10.0, 12.0, 14.0, 16.0, 20.0)
        private val DEFAULT_TWA = doubleArrayOf(30.0, 40.0, 50.0, 60.0, 75.0, 90.0, 110.0, 120.0, 135.0, 150.0, 165.0, 180.0)
        private val DEFAULT_SPEEDS = arrayOf(
            doubleArrayOf(2.6, 3.2, 3.8, 4.2, 4.6, 4.8, 4.4, 4.0, 3.4, 2.8, 2.4, 2.2),
            doubleArrayOf(3.6, 4.4, 5.1, 5.5, 5.9, 6.0, 5.6, 5.1, 4.4, 3.7, 3.2, 3.0),
            doubleArrayOf(4.4, 5.3, 6.0, 6.4, 6.8, 6.9, 6.5, 6.0, 5.3, 4.6, 4.0, 3.7),
            doubleArrayOf(5.0, 6.0, 6.7, 7.1, 7.5, 7.6, 7.2, 6.7, 6.0, 5.3, 4.7, 4.3),
            doubleArrayOf(5.4, 6.4, 7.1, 7.5, 7.9, 8.0, 7.7, 7.2, 6.5, 5.8, 5.2, 4.8),
            doubleArrayOf(5.7, 6.7, 7.4, 7.8, 8.2, 8.3, 8.0, 7.6, 6.9, 6.2, 5.6, 5.1),
            doubleArrayOf(5.9, 6.9, 7.6, 8.0, 8.4, 8.5, 8.2, 7.8, 7.2, 6.5, 5.9, 5.4),
            doubleArrayOf(6.0, 7.0, 7.7, 8.1, 8.5, 8.6, 8.3, 7.9, 7.3, 6.6, 6.0, 5.5),
        )

        fun default(): PolarTable = PolarTable(DEFAULT_TWS.copyOf(), DEFAULT_TWA.copyOf(), DEFAULT_SPEEDS.map { it.copyOf() }.toTypedArray())

        fun parse(csv: String): PolarTable? {
            val lines = csv.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
            if (lines.size < 3) return null
            val sep = if (lines[0].contains(';')) ';' else ','

            val header = lines[0].split(sep).map { it.trim() }
            val twaRaw = header.drop(1).mapNotNull { it.replace(',', '.').toDoubleOrNull() }
            if (twaRaw.size < 2) return null

            val twsList = ArrayList<Double>()
            val rows = ArrayList<DoubleArray>()
            for (line in lines.drop(1)) {
                val cells = line.split(sep).map { it.trim() }
                val tws = cells.firstOrNull()?.replace(',', '.')?.toDoubleOrNull() ?: continue
                val values = cells.drop(1).map { it.replace(',', '.').toDoubleOrNull() ?: 0.0 }
                if (values.size < twaRaw.size) continue
                twsList.add(tws)
                rows.add(values.take(twaRaw.size).toDoubleArray())
            }
            if (twsList.size < 2) return null

            // Sort columns (TWA) and rows (TWS) ascending.
            val twaOrder = twaRaw.indices.sortedBy { twaRaw[it] }
            val twa = twaOrder.map { twaRaw[it] }.toDoubleArray()
            val reordered = rows.map { row -> twaOrder.map { row[it] }.toDoubleArray() }

            val twsOrder = twsList.indices.sortedBy { twsList[it] }
            val tws = twsOrder.map { twsList[it] }.toDoubleArray()
            val speeds = twsOrder.map { reordered[it] }.toTypedArray()

            if (tws.first() == tws.last() || twa.first() == twa.last()) return null
            return PolarTable(tws, twa, speeds)
        }

        private fun lerp(a: Double, b: Double, t: Double): Double = a + (b - a) * t

        private fun safeRatio(num: Double, den: Double): Double =
            if (kotlin.math.abs(den) < 1e-9) 0.0 else num / den
    }
}
