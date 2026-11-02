package com.derrotero.util

import com.derrotero.data.polar.PolarTable
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Sail-plan recommendation for the course the boat is steering (or should steer).
 *
 * The texts are deliberately practical and generic: they assume a cruising/racing
 * monohull and must be adapted to the boat, its sail inventory and the real sea state.
 */
data class SailPlan(
    /** Point of sail band, e.g. "Ceñida cerrada" or "Popa". */
    val pointOfSail: String,
    /** Which sails to hoist, e.g. "Mayor con 1 rizo + génova 110 %". */
    val sails: String,
    /** Trim / manoeuvre hints for that configuration. */
    val trim: String,
)

/**
 * Tacking advice when the destination lies inside the no-go zone.
 *
 * [suggestedHeadingDeg] is the close-hauled heading that makes progress towards the
 * destination and [tackNow] becomes true when the destination crosses the wind axis
 * (the layline), i.e. the moment to put the boat on the other tack.
 */
data class TackAdvice(
    val active: Boolean = false,
    val upwindAngleDeg: Double = 45.0,
    val suggestedHeadingDeg: Double? = null,
    val tackName: String? = null,
    val tackNow: Boolean = false,
    val targetTwaDeg: Double? = null,
    val message: String? = null,
)

data class SailingAdvice(
    val plan: SailPlan? = null,
    val tack: TackAdvice? = null,
)

object SailAdvisor {

    private enum class Strength { UNKNOWN, LIGHT, MEDIUM, FRESH, STRONG, STORM }

    /** Shortest signed difference a-b in degrees, in (-180, 180]. */
    fun angleDiffDeg(a: Double, b: Double): Double {
        var d = (a - b) % 360.0
        if (d > 180.0) d -= 360.0
        if (d <= -180.0) d += 360.0
        return d
    }

    fun normalizeDeg(deg: Double): Double = ((deg % 360.0) + 360.0) % 360.0

    /**
     * Close-hauled angle for the current wind speed. Uses the polar table when it has one
     * (the TWA of best upwind VMG), clamped to a sane 30–55° range.
     */
    fun upwindAngleDeg(polar: PolarTable?, twsKn: Double?): Double {
        val speed = (twsKn ?: 10.0).coerceIn(4.0, 30.0)
        return (polar?.bestUpwind(speed)?.twaDeg ?: 45.0).coerceIn(30.0, 55.0)
    }

    /**
     * Recommends a close-hauled heading and warns when the destination has crossed the
     * wind axis. Returns null until both the destination bearing and the wind direction
     * are known.
     */
    fun tackAdvice(
        bearingToTargetDeg: Double?,
        trueWindDirectionDeg: Double?,
        headingDeg: Double?,
        upwindAngleDeg: Double,
    ): TackAdvice? {
        if (bearingToTargetDeg == null || trueWindDirectionDeg == null) return null

        val half = upwindAngleDeg.coerceIn(30.0, 55.0)
        val btw = normalizeDeg(bearingToTargetDeg)
        val twd = normalizeDeg(trueWindDirectionDeg)
        val delta = angleDiffDeg(btw, twd)

        // Outside the no-go cone the destination can be reached directly.
        if (abs(delta) > half) {
            return TackAdvice(active = false, upwindAngleDeg = half, targetTwaDeg = abs(delta))
        }

        val heading = headingDeg?.let(::normalizeDeg)
        val windFrom = heading?.let { angleDiffDeg(twd, it) }
        val currentTack = windFrom?.let {
            if (it >= 0.0) "amuras a estribor" else "amuras a babor"
        }
        val closeHauled = windFrom != null && abs(abs(windFrom) - half) <= 22.0

        // Sail on the side of the wind where the destination lies. Close to dead upwind
        // both tacks are equivalent; keep the one the boat is already sailing.
        var side = if (delta >= 0.0) 1 else -1
        if (abs(delta) < 8.0 && closeHauled && currentTack != null) {
            side = if (currentTack == "amuras a babor") 1 else -1
        }
        val suggested = normalizeDeg(twd + side * half)
        val tackName = if (side > 0) "amuras a babor" else "amuras a estribor"
        val onFavouredTack = closeHauled && currentTack == tackName
        // The destination is on the layline when it is roughly one close-hauled angle
        // away from the wind axis on the windward side.
        val nearLayline = abs(abs(delta) - half) <= 6.0
        val tackNow = closeHauled && currentTack != tackName && nearLayline

        val message = when {
            tackNow ->
                "¡Bordo! El destino está en la layline: virada a $tackName, " +
                    "rumbo ${suggested.roundToInt()}°"
            onFavouredTack && nearLayline ->
                "Destino en tu layline: mantén rumbo ${suggested.roundToInt()}° con $tackName"
            onFavouredTack ->
                "Ceñida a $tackName, rumbo ${suggested.roundToInt()}°; virada cuando " +
                    "el destino llegue a la otra layline"
            closeHauled ->
                "Mantén la ceñida actual; virada a $tackName, rumbo " +
                    "${suggested.roundToInt()}°, al llegar a la layline"
            else ->
                "Proa al viento: pon rumbo ${suggested.roundToInt()}° con $tackName; " +
                    "virada cuando el destino llegue a la layline"
        }

        return TackAdvice(
            active = true,
            upwindAngleDeg = half,
            suggestedHeadingDeg = suggested,
            tackName = tackName,
            tackNow = tackNow,
            targetTwaDeg = abs(delta),
            message = message,
        )
    }

    /**
     * Joins the two pieces the UI needs: the sail configuration for the course to the
     * destination (or for the current point of sail when there is no destination) and the
     * tacking advice.
     */
    fun recommend(
        bearingToTargetDeg: Double?,
        trueWindDirectionDeg: Double?,
        headingDeg: Double?,
        currentTwaDeg: Double?,
        twsKn: Double?,
        polar: PolarTable?,
        upwindOverrideDeg: Double? = null,
    ): SailingAdvice {
        val upwind = upwindOverrideDeg
            ?.takeIf { it.isFinite() }
            ?.coerceIn(30.0, 55.0)
            ?: upwindAngleDeg(polar, twsKn)
        val tack = tackAdvice(
            bearingToTargetDeg = bearingToTargetDeg,
            trueWindDirectionDeg = trueWindDirectionDeg,
            headingDeg = headingDeg,
            upwindAngleDeg = upwind,
        )
        val courseTwa = when {
            tack?.active == true -> upwind
            bearingToTargetDeg != null && trueWindDirectionDeg != null ->
                abs(angleDiffDeg(bearingToTargetDeg, trueWindDirectionDeg))
            else -> currentTwaDeg?.let { abs(it).coerceIn(0.0, 180.0) }
        }
        return SailingAdvice(plan = courseTwa?.let { plan(it, twsKn) }, tack = tack)
    }

    /** Sail configuration for a true wind angle (degrees, absolute) and wind speed (kn). */
    fun plan(twaDeg: Double?, twsKn: Double?): SailPlan? {
        val twa = twaDeg?.takeIf { it.isFinite() }?.let { abs(it).coerceIn(0.0, 180.0) }
            ?: return null
        val wind = twsKn?.takeIf { it.isFinite() && it >= 0.0 }
        val strength = when {
            wind == null -> Strength.UNKNOWN
            wind < 8.0 -> Strength.LIGHT
            wind < 16.0 -> Strength.MEDIUM
            wind < 22.0 -> Strength.FRESH
            wind < 28.0 -> Strength.STRONG
            else -> Strength.STORM
        }
        val band = WindMath.pointOfSail(twa)

        if (band == "Proa al viento") {
            return SailPlan(
                pointOfSail = band,
                sails = "Mayor cazada a tope y génova a punto para ceñir",
                trim = "Estás en la zona muerta: no hay rumbo directo. Vira para salir de " +
                    "ella y busca los rumbos de ceñida; catavientos volando.",
            )
        }

        if (strength == Strength.STORM) {
            return SailPlan(
                pointOfSail = band,
                sails = "Mayor con 3 rizos o solo trinquete + foque de tormenta",
                trim = "Superficie mínima, escotas cortas; valora capear o buscar puerto/abrigo.",
            )
        }

        return when (band) {
            "Ceñida cerrada", "Ceñida" -> upwindPlan(band, strength)
            "Ceñida abierta" -> reachPlan(band, strength)
            "Través" -> beamReachPlan(band, strength)
            "Largo" -> broadReachPlan(band, strength)
            "Aleta" -> runPlan(band, strength)
            else -> deadRunPlan(band, strength)
        }
    }

    private fun upwindPlan(band: String, strength: Strength): SailPlan = when (strength) {
        Strength.LIGHT -> SailPlan(
            band,
            "Mayor full + génova 150–140 %",
            "Escotas algo holgadas, outhaul suave y cunningham flojo; catavientos volando.",
        )
        Strength.MEDIUM -> SailPlan(
            band,
            "Mayor full + génova 140–130 %",
            "Outhaul a tope, cunningham justo y backstay para aplanar; traveller 2–5 cm " +
                "a barlovento.",
        )
        Strength.FRESH -> SailPlan(
            band,
            "Mayor con 1 rizo + génova 120–110 %",
            "Aplana la mayor con cunningham, backstay y outhaul; baja la escota de génova.",
        )
        Strength.STRONG -> SailPlan(
            band,
            "Mayor con 2 rizos + génova 100–90 %",
            "Velas planas y escotas cortas; traveller bajo y considera capear o buscar abrigo.",
        )
        Strength.UNKNOWN, Strength.STORM -> SailPlan(
            band,
            "Mayor full + génova de ceñida",
            "Sin TWS: ajusta rizos y aplanado a la fuerza real; catavientos a punto.",
        )
    }

    private fun reachPlan(band: String, strength: Strength): SailPlan = when (strength) {
        Strength.LIGHT -> SailPlan(
            band,
            "Mayor full + génova 150 %",
            "Escotas 5–10 cm holgadas, outhaul suave y vang justo.",
        )
        Strength.MEDIUM -> SailPlan(
            band,
            "Mayor full + génova 140 %",
            "Outhaul medio, vang firme y carro de escota un poco a popa.",
        )
        Strength.FRESH -> SailPlan(
            band,
            "Mayor con 1 rizo + génova 120 %",
            "Aplana la mayor, carro de escota a popa y vang firme.",
        )
        Strength.STRONG -> SailPlan(
            band,
            "Mayor con 2 rizos + génova 100 %",
            "Velas planas y escotas cortas; vigila las escoras.",
        )
        Strength.UNKNOWN, Strength.STORM -> SailPlan(
            band,
            "Mayor full + génova de través",
            "Sin TWS: ajusta rizos y aplanado a la fuerza real.",
        )
    }

    private fun beamReachPlan(band: String, strength: Strength): SailPlan = when (strength) {
        Strength.LIGHT -> SailPlan(
            band,
            "Mayor full + génova 150 %",
            "Outhaul suelto, vang ligero y carro de escota centrado.",
        )
        Strength.MEDIUM -> SailPlan(
            band,
            "Mayor full + génova 140–150 %",
            "Vang firme, carro de escota ligeramente a popa y outhaul medio.",
        )
        Strength.FRESH -> SailPlan(
            band,
            "Mayor con 1 rizo + génova 130 %",
            "Vang firme para torsionar, carro a popa y escotas algo cazadas.",
        )
        Strength.STRONG -> SailPlan(
            band,
            "Mayor con 2 rizos + génova 100 %",
            "Reduce superficie; vang y escotas firmes.",
        )
        Strength.UNKNOWN, Strength.STORM -> SailPlan(
            band,
            "Mayor full + génova 140 %",
            "Sin TWS: ajusta rizos y aplanado a la fuerza real.",
        )
    }

    private fun broadReachPlan(band: String, strength: Strength): SailPlan = when (strength) {
        Strength.LIGHT -> SailPlan(
            band,
            "Mayor full + génova 150 % o gennaker",
            "Escotas holgadas, vang firme y outhaul suelto; valora el asimétrico.",
        )
        Strength.MEDIUM -> SailPlan(
            band,
            "Mayor full + génova 140 % o gennaker",
            "Vang firme, carro de escota a popa y base de mayor arriba.",
        )
        Strength.FRESH -> SailPlan(
            band,
            "Mayor con 1 rizo + génova 120 %",
            "Vang firme y escotas algo cazadas; gennaker solo si el viento baja.",
        )
        Strength.STRONG -> SailPlan(
            band,
            "Mayor con 2 rizos + génova 100 %",
            "Sin asimétrico; vang y escotas firmes, vigila las escoras.",
        )
        Strength.UNKNOWN, Strength.STORM -> SailPlan(
            band,
            "Mayor full + génova 140 %",
            "Sin TWS: ajusta rizos y aplanado a la fuerza real.",
        )
    }

    private fun runPlan(band: String, strength: Strength): SailPlan = when (strength) {
        Strength.LIGHT -> SailPlan(
            band,
            "Mayor full + espinaker simétrico o génova",
            "Preventer, vang firme y puño de escota arriba; trasluchadas controladas.",
        )
        Strength.MEDIUM -> SailPlan(
            band,
            "Mayor full + espinaker simétrico o génova 150 %",
            "Preventer, vang firme y base de mayor arriba.",
        )
        Strength.FRESH -> SailPlan(
            band,
            "Mayor con 1 rizo + génova 130 %",
            "Sin espinaker; preventer y vang firmes.",
        )
        Strength.STRONG -> SailPlan(
            band,
            "Mayor con 2 rizos + génova 100 %",
            "Sin espinaker; preventer obligatorio y escotas firmes.",
        )
        Strength.UNKNOWN, Strength.STORM -> SailPlan(
            band,
            "Mayor full + génova 150 %",
            "Sin TWS: ajusta rizos a la fuerza real y mantén el preventer.",
        )
    }

    private fun deadRunPlan(band: String, strength: Strength): SailPlan = when (strength) {
        Strength.LIGHT -> SailPlan(
            band,
            "Mayor full + espinaker simétrico",
            "Preventer, vang firme y puño de escota arriba; ojo a las trasluchadas.",
        )
        Strength.MEDIUM -> SailPlan(
            band,
            "Mayor full + espinaker simétrico o génova 150 %",
            "Preventer, vang firme y base de mayor arriba; trasluchadas controladas.",
        )
        Strength.FRESH -> SailPlan(
            band,
            "Mayor con 1 rizo + génova 130 %",
            "Sin espinaker; preventer y vang firmes.",
        )
        Strength.STRONG -> SailPlan(
            band,
            "Mayor con 2 rizos + génova 100 %",
            "Sin espinaker; preventer obligatorio y valora capear.",
        )
        Strength.UNKNOWN, Strength.STORM -> SailPlan(
            band,
            "Mayor full + génova 150 %",
            "Sin TWS: ajusta rizos a la fuerza real y mantén el preventer.",
        )
    }
}
