package com.derrotero.data.nmea

import com.derrotero.data.settings.NmeaSettings
import com.derrotero.data.settings.NmeaTransport
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NmeaOutputRepositoryTest {

    @Test
    fun `sends sentences over its own tcp connection without nmea input`() = runBlocking {
        ServerSocket(0).use { server ->
            val repository = NmeaOutputRepository()
            val cfg = NmeaSettings(
                enabled = false,
                transport = NmeaTransport.TCP,
                host = "192.0.2.1",
                port = 1,
                sendNavigation = true,
                outputTransport = NmeaTransport.TCP,
                outputHost = "127.0.0.1",
                outputPort = server.localPort,
            )
            val job = launch(Dispatchers.IO) { repository.run(cfg) }
            try {
                server.soTimeout = 5_000
                server.accept().use { client ->
                    client.soTimeout = 5_000
                    withTimeout(5_000) {
                        while (repository.status.value.state != "Conectado") delay(20)
                    }

                    val sentence = Nmea0183Writer.sentence("XTE", "A", "A", "0.10", "R", "N")
                    assertTrue(repository.send(listOf(sentence)))

                    val reader = client.getInputStream().bufferedReader(Charsets.US_ASCII)
                    assertEquals(sentence, reader.readLine())
                    assertEquals(1L, repository.status.value.sentencesOut)
                }
            } finally {
                job.cancel()
            }
        }
    }

    @Test
    fun `sends each sentence as a udp datagram to signal k`() = runBlocking {
        DatagramSocket(0, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 5_000
            val repository = NmeaOutputRepository()
            val cfg = NmeaSettings(
                enabled = false,
                transport = NmeaTransport.TCP,
                host = "192.0.2.1",
                port = 1,
                sendNavigation = true,
                outputTransport = NmeaTransport.UDP,
                outputHost = "127.0.0.1",
                outputPort = server.localPort,
            )
            val job = launch(Dispatchers.IO) { repository.run(cfg) }
            try {
                val sentence = Nmea0183Writer.sentence("XTE", "A", "A", "0.10", "R", "N")
                // "UDP" is published before the socket exists; retry until it is ready.
                withTimeout(5_000) {
                    while (!repository.send(listOf(sentence))) delay(20)
                }

                val packet = DatagramPacket(ByteArray(512), 512)
                server.receive(packet)
                val received = String(
                    packet.data,
                    packet.offset,
                    packet.length,
                    Charsets.US_ASCII,
                ).trimEnd('\r', '\n')
                assertEquals(sentence, received)
                assertEquals(1L, repository.status.value.sentencesOut)
            } finally {
                job.cancel()
            }
        }
    }
}
