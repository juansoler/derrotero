package com.derrotero.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalKConfigTest {

    @Test
    fun `bare host gets scheme and port`() {
        val cfg = SignalKConfig(host = "192.168.1.10", port = 3000)
        assertEquals("http://192.168.1.10:3000", cfg.httpBase)
        assertEquals("ws://192.168.1.10:3000", cfg.wsBase)
    }

    @Test
    fun `pasted full API URL is normalised`() {
        val cfg = SignalKConfig(
            host = "http://192.168.1.10:3000/signalk/v1/api/",
            port = 3000,
        )
        assertEquals("http://192.168.1.10:3000", cfg.httpBase)
    }

    @Test
    fun `embedded port wins over the port field`() {
        val cfg = SignalKConfig(host = "http://10.0.0.2:8080", port = 3000)
        assertEquals("http://10.0.0.2:8080", cfg.httpBase)
    }

    @Test
    fun `tls uses https and omits the default port`() {
        val cfg = SignalKConfig(host = "boat.local", port = 443, useTls = true)
        assertEquals("https://boat.local", cfg.httpBase)
        assertEquals("wss://boat.local", cfg.wsBase)
    }

    @Test
    fun `default ports are omitted`() {
        assertEquals("http://boat.local", SignalKConfig(host = "boat.local", port = 80).httpBase)
    }

    @Test
    fun `hosts with spaces are invalid`() {
        assertFalse(SignalKConfig(host = "bad host", port = 3000).isValid())
        assertTrue(SignalKConfig(host = " 192.168.0.5 ", port = 3001).isValid())
        assertFalse(SignalKConfig(host = "host", port = 0).isValid())
    }
}
