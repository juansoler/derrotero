package com.derrotero.data.nmea

import com.derrotero.data.signalk.Paths
import com.derrotero.data.vessel.NavGuidance
import com.derrotero.data.vessel.NavigationTarget
import java.time.Instant
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Nmea0183WriterTest {

    private val parser = Nmea0183Parser()

    private val own = mapOf(
        Paths.POSITION to buildJsonObject {
            put("latitude", 0.0)
            put("longitude", 0.001)
        },
        Paths.SOG to JsonPrimitive(2.0),
        Paths.COG to JsonPrimitive(Math.toRadians(90.0)),
        Paths.MAGNETIC_VARIATION to JsonPrimitive(Math.toRadians(2.0)),
    )

    private val target = NavigationTarget(
        lat = 0.0,
        lon = 1.0,
        name = "WP 1",
        fromLat = 0.0,
        fromLon = 0.001,
    )

    private val guidance = NavGuidance(
        active = true,
        name = target.name,
        lat = target.lat,
        lon = target.lon,
        distanceM = 111_130.0,
        bearingDeg = 90.0,
        xteM = 100.0,
        vmgMps = 2.0,
    )

    private val now = Instant.parse("2026-11-02T12:34:56Z")

    private fun lines(): List<String> =
        Nmea0183Writer.sentences(own, target, guidance, now)

    @Test
    fun `emits APB RMB RMC and XTE with valid checksums`() {
        val lines = lines()
        assertEquals(4, lines.size)
        assertEquals(listOf("RMB", "RMC", "APB", "XTE"), lines.map { it.substring(3, 6) })

        lines.forEach { line ->
            val result = parser.parse(line)
            assertEquals("checksum of $line", true, result.checksumOk)
            assertTrue("recognised: $line", result.type in setOf("APB", "RMB", "RMC", "XTE"))
        }
    }

    @Test
    fun `APB carries the cross track error in nautical miles and true bearing`() {
        val apb = lines().first { it.startsWith("\$ECAPB") }
        val fields = apb.substringBefore('*').split(',')
        assertEquals("A", fields[1])
        assertEquals("0.05", fields[3]) // 100 m converted to NM, rounded to 2 decimals
        assertEquals("R", fields[4])
        assertEquals("N", fields[5])
        assertEquals("90.0", fields[8])
        assertEquals("T", fields[9])
        assertEquals("WP_1", fields[10])

        val parsed = parser.parse(apb)
        assertEquals(0.05 * 1852.0, parsed.values.getValue(Paths.XTE).toString().toDouble(), 1e-9)
        assertEquals(
            Math.toRadians(90.0),
            parsed.values.getValue(Paths.BTW).toString().toDouble(),
            1e-9,
        )
    }

    @Test
    fun `RMC uses UTC time, own position and magnetic variation`() {
        val rmc = lines().first { it.startsWith("\$ECRMC") }
        val fields = rmc.substringBefore('*').split(',')
        assertEquals("123456.00", fields[1])
        assertEquals("A", fields[2])
        assertEquals("0000.0000", fields[3])
        assertEquals("N", fields[4])
        assertEquals("00000.0600", fields[5])
        assertEquals("E", fields[6])
        assertEquals("3.9", fields[7]) // 2 m/s in knots
        assertEquals("90.0", fields[8])
        assertEquals("021126", fields[9])
        assertEquals("2.0", fields[10])
        assertEquals("E", fields[11])
        assertEquals("A", fields[12]) // FAA mode indicator
    }

    @Test
    fun `RMB includes origin waypoint and mode indicator like OpenCPN`() {
        val fields = lines().first { it.startsWith("\$ECRMB") }
            .substringBefore('*').split(',')
        assertEquals("ORIG", fields[4])
        assertEquals("WP_1", fields[5])
        assertEquals("A", fields[14]) // FAA mode indicator
    }

    @Test
    fun `RMB and XTE can be parsed back by the app`() {
        val rmb = parser.parse(lines().first { it.startsWith("\$ECRMB") })
        // RMB range is sent with 0.01 NM precision (about 18.5 m).
        assertEquals(111_130.0, rmb.values.getValue(Paths.DTW).toString().toDouble(), 20.0)
        assertEquals(
            Math.toRadians(90.0),
            rmb.values.getValue(Paths.BTW).toString().toDouble(),
            1e-9,
        )

        val xte = parser.parse(lines().first { it.startsWith("\$ECXTE") })
        assertEquals(0.05 * 1852.0, xte.values.getValue(Paths.XTE).toString().toDouble(), 1e-9)
    }

    @Test
    fun `no sentences are produced without a position`() {
        val empty = own - Paths.POSITION
        assertTrue(Nmea0183Writer.sentences(empty, target, guidance, now).isEmpty())
    }

    @Test
    fun `xte parser accepts both single and dual status layouts`() {
        val single = parser.parse(Nmea0183Writer.sentence("XTE", "A", "0.10", "L", "N"))
        val dual = parser.parse(Nmea0183Writer.sentence("XTE", "A", "A", "0.10", "L", "N"))
        assertEquals(-185.2, single.values.getValue(Paths.XTE).toString().toDouble(), 1e-9)
        assertEquals(-185.2, dual.values.getValue(Paths.XTE).toString().toDouble(), 1e-9)
    }
}
