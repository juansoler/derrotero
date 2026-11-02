package com.derrotero.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CoordinateParserTest {

    @Test
    fun `parses decimal pair`() {
        val p = CoordinateParser.parse("43.3000, -2.9500")!!
        assertEquals(43.3, p.lat, 1e-9)
        assertEquals(-2.95, p.lon, 1e-9)
    }

    @Test
    fun `parses degrees and decimal minutes`() {
        val p = CoordinateParser.parse("43 18.02 N 2 56.97 W")!!
        assertEquals(43.3003333, p.lat, 1e-6)
        assertEquals(-2.9495, p.lon, 1e-6)
    }

    @Test
    fun `parses degrees minutes seconds`() {
        val p = CoordinateParser.parse("43 18 1.2 N 2 56 58.2 W")!!
        assertEquals(43.3003333, p.lat, 1e-6)
        assertEquals(-2.9495, p.lon, 1e-6)
    }

    @Test
    fun `parses hemisphere before the numbers`() {
        val p = CoordinateParser.parse("N 43 18.02 W 2 56.97")!!
        assertEquals(43.3003333, p.lat, 1e-6)
        assertEquals(-2.9495, p.lon, 1e-6)
    }

    @Test
    fun `parses chart symbols`() {
        val p = CoordinateParser.parse("43°18.02'N 2°56.97'W")!!
        assertEquals(43.3003333, p.lat, 1e-6)
        assertEquals(-2.9495, p.lon, 1e-6)
    }

    @Test
    fun `rejects out of range and invalid minutes`() {
        assertNull(CoordinateParser.parse("200 N 300 W"))
        assertNull(CoordinateParser.parse("43 60.0 N 2 56.97 W"))
        assertNull(CoordinateParser.parse("hola"))
    }
}
