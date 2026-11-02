package com.derrotero.util

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class WindSolution(val trueSpeed: Double, val trueAngle: Double)

/**
 * Wind vector maths used by the wind page.
 *
 * Angles follow SignalK: radians, 0 = ahead, positive to starboard; speeds in m/s.
 */
object WindMath {

    fun wrapPi(a: Double): Double {
        var x = a
        while (x <= -PI) x += 2 * PI
        while (x > PI) x -= 2 * PI
        return x
    }

    fun wrap2Pi(a: Double): Double {
        var x = a % (2 * PI)
        if (x < 0) x += 2 * PI
        return x
    }

    /**
     * True wind from apparent wind and boat speed through water.
     * Components are taken in the boat frame (x = starboard, y = forward) and the
     * boat's velocity is added to the apparent wind to obtain the wind over water.
     */
    fun trueFromApparent(apparentSpeed: Double, apparentAngle: Double, boatSpeed: Double): WindSolution {
        val vx = -apparentSpeed * sin(apparentAngle)
        val vy = -apparentSpeed * cos(apparentAngle) + boatSpeed
        return WindSolution(
            trueSpeed = sqrt(vx * vx + vy * vy),
            trueAngle = wrapPi(atan2(-vx, -vy)),
        )
    }

    fun trueWindDirection(headingRad: Double, trueAngleRad: Double): Double =
        wrap2Pi(headingRad + trueAngleRad)

    /** VMG along the wind axis: positive upwind, negative downwind (m/s). */
    fun vmg(boatSpeed: Double, trueAngleRad: Double): Double = boatSpeed * cos(trueAngleRad)

    /** Short label for the point of sail. Angles are absolute true wind angles. */
    fun pointOfSail(trueAngleDeg: Double): String = when {
        trueAngleDeg < 35 -> "Proa al viento"
        trueAngleDeg < 50 -> "Ceñida cerrada"
        trueAngleDeg < 65 -> "Ceñida"
        trueAngleDeg < 85 -> "Ceñida abierta"
        trueAngleDeg < 105 -> "Través"
        trueAngleDeg < 135 -> "Largo"
        trueAngleDeg < 160 -> "Aleta"
        else -> "Popa"
    }

    /** Tacking angle needed to clear laylines, as a pair relative to the wind. */
    fun laylines(trueAngleRad: Double, tackingAngleDeg: Double): Pair<Double, Double> {
        val half = Math.toRadians(tackingAngleDeg / 2.0)
        return (trueAngleRad - half) to (trueAngleRad + half)
    }

    fun absDeg(rad: Double?): Double? = rad?.let { abs(Math.toDegrees(it)) }
}
