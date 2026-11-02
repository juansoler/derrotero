package com.derrotero.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NmeaSettingsTest {

    @Test
    fun `tcp needs a host and a valid port`() {
        assertTrue(NmeaSettings(enabled = true, host = "192.168.1.10", port = 10110).isValid())
        assertFalse(NmeaSettings(enabled = true, host = "", port = 10110).isValid())
        assertFalse(NmeaSettings(enabled = true, host = "bad host", port = 10110).isValid())
        assertFalse(NmeaSettings(enabled = true, host = "host", port = 0).isValid())
    }

    @Test
    fun `udp only needs a port`() {
        val cfg = NmeaSettings(
            enabled = true,
            transport = NmeaTransport.UDP,
            host = "",
            port = 10110,
        )
        assertTrue(cfg.isValid())
        assertFalse(cfg.copy(port = 70000).isValid())
    }

    @Test
    fun `autopilot output does not require the nmea input`() {
        val cfg = NmeaSettings(
            enabled = false,
            transport = NmeaTransport.TCP,
            host = "192.168.1.10",
            port = 10110,
            sendNavigation = true,
            outputTransport = NmeaTransport.TCP,
        )
        assertTrue(cfg.sendsToAutopilot)
        assertEquals("192.168.1.10", cfg.effectiveOutputHost)
        assertEquals(10110, cfg.effectiveOutputPort)
        assertFalse(cfg.copy(sendNavigation = false).sendsToAutopilot)
        assertFalse(cfg.copy(host = "").sendsToAutopilot)
    }

    @Test
    fun `udp output goes to signal k without the nmea input`() {
        val cfg = NmeaSettings(
            enabled = false,
            transport = NmeaTransport.TCP,
            host = "192.0.2.1",
            port = 1,
            sendNavigation = true,
            outputTransport = NmeaTransport.UDP,
            outputHost = "192.168.1.103",
            outputPort = 10119,
        )
        assertTrue(cfg.sendsToAutopilot)
        assertEquals("192.168.1.103", cfg.effectiveOutputHost)
        assertEquals(10119, cfg.effectiveOutputPort)
        assertFalse(cfg.copy(outputHost = "").sendsToAutopilot)
        assertFalse(cfg.copy(outputPort = 0).sendsToAutopilot)
    }

    @Test
    fun `explicit output endpoint overrides the input`() {
        val cfg = NmeaSettings(
            enabled = false,
            transport = NmeaTransport.UDP,
            host = "",
            port = 10110,
            sendNavigation = true,
            outputTransport = NmeaTransport.UDP,
            outputHost = "192.168.1.103",
            outputPort = 10119,
        )
        assertTrue(cfg.sendsToAutopilot)
        assertEquals("192.168.1.103", cfg.effectiveOutputHost)
        assertEquals(10119, cfg.effectiveOutputPort)
    }

    @Test
    fun `udp input still needs an explicit output endpoint`() {
        val cfg = NmeaSettings(
            enabled = true,
            transport = NmeaTransport.UDP,
            host = "",
            port = 10110,
            sendNavigation = true,
        )
        assertFalse(cfg.sendsToAutopilot)
        assertFalse(cfg.outputValid)
        assertEquals(NmeaTransport.UDP, cfg.outputTransport)
    }
}
