package com.derrotero.data.nmea

import com.derrotero.data.settings.NmeaSettings
import com.derrotero.data.settings.NmeaTransport
import com.derrotero.data.signalk.DeltaEnvelope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.JsonElement
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

data class NmeaStatus(
    val enabled: Boolean = false,
    val transport: String = "—",
    val state: String = "Desactivado",
    val sentences: Long = 0,
    val ownSentences: Long = 0,
    val aisMessages: Long = 0,
    val checksumErrors: Long = 0,
    val parseErrors: Long = 0,
    val lastAt: Long = 0,
    val lastError: String? = null,
    val lastSentence: String? = null,
)

/**
 * Reads NMEA 0183 from a Wi-Fi multiplexer (TCP client) or from UDP datagrams (the usual
 * broadcast of a boat network). Own-vessel values are merged into the same state used by the
 * Signal K stream; AIS sentences are converted to Signal K deltas and fed to [AisRepository].
 */
class NmeaRepository(
    private val onOwnValues: (Map<String, JsonElement>) -> Unit,
    private val onAisDelta: (DeltaEnvelope) -> Unit,
) {

    private val parser = Nmea0183Parser()

    private val _status = MutableStateFlow(NmeaStatus())
    val status: StateFlow<NmeaStatus> = _status.asStateFlow()

    fun reset() {
        parser.reset()
        _status.value = NmeaStatus()
    }

    suspend fun run(cfg: NmeaSettings) {
        parser.reset()
        _status.value = NmeaStatus(
            enabled = true,
            transport = cfg.transport.name,
            state = "Conectando",
        )
        try {
            when (cfg.transport) {
                NmeaTransport.TCP -> runTcp(cfg)
                NmeaTransport.UDP -> runUdp(cfg)
            }
        } finally {
            _status.update { it.copy(state = if (it.enabled) "Desconectado" else "Desactivado") }
        }
    }

    private suspend fun runTcp(cfg: NmeaSettings) {
        var failures = 0
        while (currentCoroutineContext().isActive) {
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(cfg.host.trim(), cfg.port), CONNECT_TIMEOUT_MS)
                    socket.soTimeout = 1_000
                    failures = 0
                    _status.update {
                        it.copy(state = "Conectado", lastError = null)
                    }

                    val reader = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                    while (currentCoroutineContext().isActive) {
                        val line: String? = try {
                            reader.readLine()
                        } catch (e: SocketTimeoutException) {
                            continue
                        }
                        if (line == null) break
                        if (line.isNotBlank()) handle(line)
                    }
                    _status.update { it.copy(state = "Reconectando") }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _status.update { it.copy(state = "Reconectando", lastError = e.message) }
            }

            delay(backoffMs(failures))
            failures++
        }
    }

    private suspend fun runUdp(cfg: NmeaSettings) {
        var failures = 0
        while (currentCoroutineContext().isActive) {
            try {
                DatagramSocket(null).use { socket ->
                    socket.reuseAddress = true
                    socket.bind(InetSocketAddress(cfg.port))
                    socket.soTimeout = 1_000
                    failures = 0
                    _status.update {
                        it.copy(state = "Escuchando UDP :${cfg.port}", lastError = null)
                    }

                    val buffer = ByteArray(8_192)
                    while (currentCoroutineContext().isActive) {
                        val packet = DatagramPacket(buffer, buffer.size)
                        try {
                            socket.receive(packet)
                        } catch (e: SocketTimeoutException) {
                            continue
                        }
                        val text = String(
                            packet.data,
                            packet.offset,
                            packet.length,
                            Charsets.US_ASCII,
                        )
                        text.lineSequence()
                            .filter { it.isNotBlank() }
                            .forEach { handle(it) }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _status.update { it.copy(state = "Error UDP", lastError = e.message) }
                delay(backoffMs(failures))
                failures++
            }
        }
    }

    private fun handle(line: String) {
        val result = parser.parse(line)
        val now = System.currentTimeMillis()

        if (result.checksumOk == false) {
            _status.update {
                it.copy(
                    sentences = it.sentences + 1,
                    checksumErrors = it.checksumErrors + 1,
                    lastAt = now,
                    lastError = result.error,
                    lastSentence = line.take(90),
                )
            }
            return
        }
        if (result.error != null && result.values.isEmpty() && result.ais.isEmpty()) {
            _status.update {
                it.copy(
                    sentences = it.sentences + 1,
                    parseErrors = it.parseErrors + 1,
                    lastAt = now,
                    lastError = result.error,
                    lastSentence = line.take(90),
                )
            }
            return
        }

        if (result.values.isNotEmpty()) onOwnValues(result.values)
        result.ais.forEach(onAisDelta)

        _status.update {
            it.copy(
                sentences = it.sentences + 1,
                ownSentences = it.ownSentences + if (result.values.isNotEmpty()) 1 else 0,
                aisMessages = it.aisMessages + result.ais.size,
                lastAt = now,
                lastError = null,
                lastSentence = line.take(90),
                state = if (result.values.isNotEmpty() || result.ais.isNotEmpty()) "En vivo" else it.state,
            )
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
    }
}
