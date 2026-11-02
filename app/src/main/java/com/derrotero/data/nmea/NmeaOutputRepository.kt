package com.derrotero.data.nmea

import com.derrotero.data.settings.NmeaSettings
import com.derrotero.data.settings.NmeaTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket

data class NmeaOutputStatus(
    val enabled: Boolean = false,
    val state: String = "Desactivado",
    val host: String = "",
    val port: Int = 0,
    /** Sentences actually written to the autopilot/gateway. */
    val sentencesOut: Long = 0,
    val lastAt: Long = 0,
    val lastError: String? = null,
)

/**
 * Dedicated UDP/TCP sender for outbound autopilot guidance.
 *
 * It is deliberately independent from [NmeaRepository]: the app can receive its navigation
 * data from Signal K while OpenCPN-style APB/RMB/RMC/XTE sentences go to Signal K (UDP,
 * which forwards them to the serial autopilot port) or directly to a gateway (TCP). The
 * link is write-only; a server that never sends anything back is fine.
 */
class NmeaOutputRepository {

    private val _status = MutableStateFlow(NmeaOutputStatus())
    val status: StateFlow<NmeaOutputStatus> = _status.asStateFlow()

    private val outputLock = Any()
    private var socket: Socket? = null
    private var stream: OutputStream? = null
    private var datagram: DatagramSocket? = null
    private var datagramTarget: InetSocketAddress? = null

    fun reset() {
        synchronized(outputLock) {
            runCatching { socket?.close() }
            runCatching { datagram?.close() }
            socket = null
            stream = null
            datagram = null
            datagramTarget = null
        }
        _status.value = NmeaOutputStatus()
    }

    /**
     * Opens/reopens the selected UDP/TCP sender and keeps it alive. [send] may be called
     * from another coroutine; when a write fails the socket is closed and this loop retries.
     */
    suspend fun run(cfg: NmeaSettings) {
        if (!cfg.sendsToAutopilot) {
            reset()
            return
        }
        val host = cfg.effectiveOutputHost
        val port = cfg.effectiveOutputPort
        val transport = cfg.outputTransport
        _status.value = NmeaOutputStatus(
            enabled = true,
            state = if (transport == NmeaTransport.UDP) "UDP" else "Conectando",
            host = host,
            port = port,
        )
        when (transport) {
            NmeaTransport.TCP -> runTcp(host, port)
            NmeaTransport.UDP -> runUdp(host, port)
        }
    }

    private suspend fun runTcp(host: String, port: Int) {
        var failures = 0
        while (currentCoroutineContext().isActive) {
            var currentSocket: Socket? = null
            try {
                val s = Socket()
                currentSocket = s
                s.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                s.tcpNoDelay = true
                s.keepAlive = true
                synchronized(outputLock) {
                    socket = s
                    stream = s.getOutputStream()
                }
                failures = 0
                _status.update {
                    it.copy(state = "Conectado", lastError = null)
                }

                // There is no input on this link. Wake up regularly to notice a socket
                // closed by a failed write or by the peer.
                while (currentCoroutineContext().isActive && !s.isClosed) {
                    delay(SOCKET_CHECK_MS)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _status.update {
                    it.copy(
                        state = "Reconectando",
                        lastError = "Salida NMEA: ${e.message ?: "no se pudo conectar"}",
                    )
                }
            } finally {
                synchronized(outputLock) {
                    if (socket === currentSocket) {
                        runCatching { socket?.close() }
                        socket = null
                        stream = null
                    }
                }
            }
            if (!currentCoroutineContext().isActive) break
            delay(backoffMs(failures))
            failures++
        }
    }

    /**
     * UDP is connectionless: the socket is only a sender. Signal K receives the datagrams
     * and, with an «autopilot» input/output event, forwards them to the serial port.
     */
    private suspend fun runUdp(host: String, port: Int) {
        var failures = 0
        while (currentCoroutineContext().isActive) {
            var currentSocket: DatagramSocket? = null
            try {
                val s = DatagramSocket()
                currentSocket = s
                runCatching { s.broadcast = true }
                val target = InetSocketAddress(host, port)
                synchronized(outputLock) {
                    datagram = s
                    datagramTarget = target
                }
                failures = 0
                _status.update { it.copy(state = "UDP", lastError = null) }

                // No handshake or input. Keep the sender open; a failed [send] closes it
                // and this loop creates a fresh socket.
                while (currentCoroutineContext().isActive && !s.isClosed) {
                    delay(SOCKET_CHECK_MS)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _status.update {
                    it.copy(
                        state = "Reconectando",
                        lastError = "Salida UDP: ${e.message ?: "no se pudo abrir el socket"}",
                    )
                }
            } finally {
                synchronized(outputLock) {
                    if (datagram === currentSocket) {
                        runCatching { datagram?.close() }
                        datagram = null
                        datagramTarget = null
                    }
                }
            }
            if (!currentCoroutineContext().isActive) break
            delay(backoffMs(failures))
            failures++
        }
    }

    /**
     * Writes complete NMEA sentences (without line endings) and counts them. TCP sends them
     * as lines on the stream; UDP sends one sentence per datagram. Returns false when the
     * sender is not ready or the write failed.
     */
    fun send(sentences: List<String>): Boolean {
        if (sentences.isEmpty()) return false

        synchronized(outputLock) {
            val udp = datagram
            if (udp != null) {
                val target = datagramTarget ?: return false
                var sent = 0
                return try {
                    // One NMEA sentence per datagram: the format Signal K and most
                    // NMEA-over-UDP multiplexers expect.
                    sentences.forEach { line ->
                        val bytes = (line + "\r\n").toByteArray(Charsets.US_ASCII)
                        udp.send(DatagramPacket(bytes, bytes.size, target))
                        sent++
                    }
                    _status.update {
                        it.copy(
                            sentencesOut = it.sentencesOut + sent,
                            lastAt = System.currentTimeMillis(),
                            lastError = null,
                        )
                    }
                    true
                } catch (e: Exception) {
                    runCatching { udp.close() }
                    datagram = null
                    datagramTarget = null
                    _status.update {
                        it.copy(
                            state = "Reconectando",
                            lastError = "Salida UDP: ${e.message ?: "no se pudo enviar"}",
                        )
                    }
                    false
                }
            }

            val payload = buildString {
                sentences.forEach { append(it).append("\r\n") }
            }.toByteArray(Charsets.US_ASCII)

            val output = stream ?: return false
            return try {
                output.write(payload)
                output.flush()
                _status.update {
                    it.copy(
                        sentencesOut = it.sentencesOut + sentences.size,
                        lastAt = System.currentTimeMillis(),
                        lastError = null,
                    )
                }
                true
            } catch (e: Exception) {
                val dead = socket
                socket = null
                stream = null
                runCatching { dead?.close() }
                _status.update {
                    it.copy(
                        state = "Reconectando",
                        lastError = "Salida NMEA: ${e.message ?: "conexión perdida"}",
                    )
                }
                false
            }
        }
    }

    private fun backoffMs(failures: Int): Long = when (failures) {
        0 -> 1_000L
        1 -> 2_000L
        2 -> 4_000L
        else -> 10_000L
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val SOCKET_CHECK_MS = 1_000L
    }
}
