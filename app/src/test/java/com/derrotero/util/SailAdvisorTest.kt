package com.derrotero.util

import com.derrotero.data.polar.PolarTable
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SailAdvisorTest {

    @Test
    fun `no tack advice without bearing or wind direction`() {
        assertNull(SailAdvisor.tackAdvice(null, 0.0, 0.0, 45.0))
        assertNull(SailAdvisor.tackAdvice(20.0, null, 0.0, 45.0))
    }

    @Test
    fun `destination outside the no-go cone needs no tacking`() {
        val advice = SailAdvisor.tackAdvice(
            bearingToTargetDeg = 90.0,
            trueWindDirectionDeg = 0.0,
            headingDeg = 80.0,
            upwindAngleDeg = 45.0,
        )
        assertNotNull(advice)
        assertFalse(advice!!.active)
        assertFalse(advice.tackNow)
        assertNull(advice.suggestedHeadingDeg)
        assertEquals(90.0, advice.targetTwaDeg!!, 1e-9)
    }

    @Test
    fun `destination inside the cone suggests the close-hauled heading on its side`() {
        val advice = SailAdvisor.tackAdvice(
            bearingToTargetDeg = 20.0,
            trueWindDirectionDeg = 0.0,
            headingDeg = 10.0,
            upwindAngleDeg = 45.0,
        )!!
        assertTrue(advice.active)
        assertEquals(45.0, advice.suggestedHeadingDeg!!, 1e-9)
        assertEquals("amuras a babor", advice.tackName)
        assertFalse(advice.tackNow)
        assertTrue(advice.message!!.contains("45"))
    }

    @Test
    fun `destination on the port side of the wind recommends starboard tack`() {
        val advice = SailAdvisor.tackAdvice(
            bearingToTargetDeg = 340.0,
            trueWindDirectionDeg = 0.0,
            headingDeg = 350.0,
            upwindAngleDeg = 45.0,
        )!!
        assertTrue(advice.active)
        assertEquals(315.0, advice.suggestedHeadingDeg!!, 1e-9)
        assertEquals("amuras a estribor", advice.tackName)
    }

    @Test
    fun `warns to tack when the destination reaches the layline`() {
        // Destination at 320° (wind axis minus 40°, near the starboard layline) while the
        // boat is still close-hauled on the port tack (heading 45°).
        val advice = SailAdvisor.tackAdvice(
            bearingToTargetDeg = 320.0,
            trueWindDirectionDeg = 0.0,
            headingDeg = 45.0,
            upwindAngleDeg = 45.0,
        )!!
        assertTrue(advice.tackNow)
        assertTrue(advice.message!!.contains("Bordo"))
        assertTrue(advice.message!!.contains("estribor"))
    }

    @Test
    fun `does not tack just because the destination crosses the wind axis`() {
        // Only 10° west of the wind: the boat should keep beating on the current tack
        // until the destination reaches the layline.
        val advice = SailAdvisor.tackAdvice(
            bearingToTargetDeg = 350.0,
            trueWindDirectionDeg = 0.0,
            headingDeg = 45.0,
            upwindAngleDeg = 45.0,
        )!!
        assertFalse(advice.tackNow)
        assertTrue(advice.message!!.contains("Mantén"))
    }

    @Test
    fun `on the layline of the current tack it holds the heading`() {
        val advice = SailAdvisor.tackAdvice(
            bearingToTargetDeg = 40.0,
            trueWindDirectionDeg = 0.0,
            headingDeg = 45.0,
            upwindAngleDeg = 45.0,
        )!!
        assertFalse(advice.tackNow)
        assertTrue(advice.message!!.contains("mantén"))
    }

    @Test
    fun `does not warn while sailing close-hauled on the favoured tack`() {
        val advice = SailAdvisor.tackAdvice(
            bearingToTargetDeg = 20.0,
            trueWindDirectionDeg = 0.0,
            headingDeg = 45.0,
            upwindAngleDeg = 45.0,
        )!!
        assertFalse(advice.tackNow)
        assertTrue(advice.message!!.contains("virada cuando"))
    }

    @Test
    fun `bearing and wind wrapping around north`() {
        val advice = SailAdvisor.tackAdvice(
            bearingToTargetDeg = 10.0,
            trueWindDirectionDeg = 350.0,
            headingDeg = 350.0,
            upwindAngleDeg = 45.0,
        )!!
        assertTrue(advice.active)
        assertEquals(35.0, advice.suggestedHeadingDeg!!, 1e-9)
        assertEquals(20.0, advice.targetTwaDeg!!, 1e-9)
    }

    @Test
    fun `sail plan for close hauled in fresh wind reefs the main`() {
        val plan = SailAdvisor.plan(45.0, 18.0)!!
        assertEquals("Ceñida cerrada", plan.pointOfSail)
        assertTrue(plan.sails.contains("1 rizo"))
    }

    @Test
    fun `sail plan for downwind in fresh wind sets a preventer and no spinnaker`() {
        val plan = SailAdvisor.plan(170.0, 20.0)!!
        assertEquals("Popa", plan.pointOfSail)
        assertTrue(plan.sails.contains("1 rizo"))
        assertTrue(plan.trim.contains("preventer", ignoreCase = true))
        assertTrue(plan.trim.contains("Sin espinaker", ignoreCase = true))
    }

    @Test
    fun `light wind plan does not reef`() {
        val plan = SailAdvisor.plan(45.0, 12.0)!!
        assertFalse(plan.sails.contains("rizo"))
        assertTrue(plan.sails.contains("génova"))
    }

    @Test
    fun `storm plan uses storm sails`() {
        val plan = SailAdvisor.plan(110.0, 30.0)!!
        assertTrue(plan.sails.contains("3 rizos"))
        assertTrue(plan.trim.contains("capear"))
    }

    @Test
    fun `no-go cone is reported as head to wind`() {
        val plan = SailAdvisor.plan(20.0, 10.0)!!
        assertEquals("Proa al viento", plan.pointOfSail)
        assertTrue(plan.trim.contains("Vira"))
    }

    @Test
    fun `unknown wind speed still produces a plan`() {
        val plan = SailAdvisor.plan(70.0, null)!!
        assertEquals("Ceñida abierta", plan.pointOfSail)
        assertTrue(plan.trim.contains("Sin TWS"))
    }

    @Test
    fun `plan handles missing angle and speed`() {
        assertNull(SailAdvisor.plan(null, 10.0))
        assertNotNull(SailAdvisor.plan(45.0, null))
    }

    @Test
    fun `upwind angle is derived from the polar table within sane limits`() {
        val angle = SailAdvisor.upwindAngleDeg(PolarTable.default(), 12.0)
        assertTrue(angle in 30.0..55.0)
        assertEquals(45.0, SailAdvisor.upwindAngleDeg(null, 12.0), 1e-9)
    }

    @Test
    fun `recommendation uses the upwind plan when the destination is head to wind`() {
        val advice = SailAdvisor.recommend(
            bearingToTargetDeg = 0.0,
            trueWindDirectionDeg = 0.0,
            headingDeg = 0.0,
            currentTwaDeg = 0.0,
            twsKn = 12.0,
            polar = null,
        )
        assertTrue(advice.tack!!.active)
        assertEquals("Ceñida cerrada", advice.plan!!.pointOfSail)
        assertEquals(45.0, advice.tack.suggestedHeadingDeg!!, 1e-9)
    }

    @Test
    fun `recommendation without a destination uses the current wind angle`() {
        val advice = SailAdvisor.recommend(
            bearingToTargetDeg = null,
            trueWindDirectionDeg = null,
            headingDeg = null,
            currentTwaDeg = 100.0,
            twsKn = 14.0,
            polar = null,
        )
        assertNull(advice.tack)
        assertEquals("Través", advice.plan!!.pointOfSail)
    }

    @Test
    fun `angle difference wraps correctly`() {
        assertEquals(20.0, SailAdvisor.angleDiffDeg(10.0, 350.0), 1e-9)
        assertEquals(-20.0, SailAdvisor.angleDiffDeg(350.0, 10.0), 1e-9)
        assertEquals(180.0, abs(SailAdvisor.angleDiffDeg(0.0, 180.0)), 1e-9)
    }
}
