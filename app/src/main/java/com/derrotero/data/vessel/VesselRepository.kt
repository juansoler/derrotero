package com.derrotero.data.vessel

import com.derrotero.data.demo.DemoData
import com.derrotero.data.settings.SignalKConfig
import com.derrotero.data.signalk.DeltaEnvelope
import com.derrotero.data.signalk.SignalKClient
import com.derrotero.data.signalk.SignalKFlatten
import com.derrotero.data.signalk.SignalKPathValue
import com.derrotero.data.signalk.SignalKUpdate
import com.derrotero.data.signalk.text
import com.derrotero.util.Geo
import com.derrotero.util.LatLon
import com.derrotero.util.WindMath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.sin

/** Live diagnostics surfaced in Ajustes → SignalK (helps when a server "connects" but is silent). */
data class SignalKDiagnostics(
    val selfContext: String = "vessels.self",
    val selfMmsi: String? = null,
    val selfResolved: Boolean = false,
    val streamState: String = "Desconectado",
    val lastMessageAt: Long = 0L,
    val messages: Long = 0L,
    val values: Int = 0,
    val fullModelAt: Long = 0L,
    val lastError: String? = null,
)

/**
 * Keeps a live map of `SignalK path -> value` for the local vessel.
 *
 * Two streams are used, which is the robust way to handle real SignalK servers:
 *  - `subscribe=self` carries the own vessel. Servers identify it as
 *    `vessels.urn:mrn:imo:mmsi:…`, not as `vessels.self`, so every delta used to be rejected.
 *  - `subscribe=all` carries AIS/other vessels and doubles as a fallback for own data.
 *
 * Both streams reconnect with exponential backoff and a watchdog, and the full model is
 * re-fetched after every reconnect to fill in anything the delta stream does not publish.
 */
class VesselRepository(
    private val client: SignalKClient,
    private val json: Json,
) {

    private val _state = MutableStateFlow<Map<String, JsonElement>>(emptyMap())
    val state: StateFlow<Map<String, JsonElement>> = _state.asStateFlow()

    private val _status = MutableStateFlow<SignalKStatus>(SignalKStatus.Disconnected)
    val status: StateFlow<SignalKStatus> = _status.asStateFlow()

    private val _lastUpdate = MutableStateFlow(0L)
    val lastUpdate: StateFlow<Long> = _lastUpdate.asStateFlow()

    /** Reflects an explicit user stop in the UI (the streams have already been cancelled). */
    fun markDisconnected() {
        _status.value = SignalKStatus.Disconnected
        _diagnostics.update { it.copy(streamState = "Detenido") }
    }

    /** Every raw delta of other vessels, for consumers such as the AIS tracker. */
    private val _deltas = MutableSharedFlow<DeltaEnvelope>(
        extraBufferCapacity = 512,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val deltas: SharedFlow<DeltaEnvelope> = _deltas.asSharedFlow()

    private val _selfContext = MutableStateFlow("vessels.self")
    val selfContext: StateFlow<String> = _selfContext.asStateFlow()

    private val _selfMmsi = MutableStateFlow<String?>(null)
    val selfMmsi: StateFlow<String?> = _selfMmsi.asStateFlow()

    private val _diagnostics = MutableStateFlow(SignalKDiagnostics())
    val diagnostics: StateFlow<SignalKDiagnostics> = _diagnostics.asStateFlow()

    @Volatile private var resolvedSelf: String? = null
    @Volatile private var selfMmsiValue: String? = null
    @Volatile private var configuredContext: String = "vessels.self"

    /** Last time each path was received from the live Signal K stream (NMEA takes over gaps). */
    private val lastSignalKPathAt = ConcurrentHashMap<String, Long>()

    // ------------------------------------------------------------- connection

    suspend fun runConnection(cfg: SignalKConfig) {
        configuredContext = cfg.context.ifBlank { "vessels.self" }
        resolvedSelf = null
        selfMmsiValue = null
        _selfContext.value = configuredContext
        _selfMmsi.value = null
        lastSignalKPathAt.clear()
        _state.value = emptyMap()
        _lastUpdate.value = 0L
        _status.value = SignalKStatus.Connecting
        _diagnostics.value = SignalKDiagnostics(
            selfContext = configuredContext,
            streamState = "Conectando",
        )

        resolveIdentity(cfg)
        loadFullModel(cfg)

        coroutineScope {
            launch { streamLoop(cfg, subscribe = "self") { ingest(it, selfHint = true, emit = false) } }
            launch { streamLoop(cfg, subscribe = "all") { ingest(it, selfHint = false, emit = true) } }
            // Periodically refresh the REST model to fill paths the stream does not publish.
            launch {
                while (currentCoroutineContext().isActive) {
                    delay(MODEL_REFRESH_MS)
                    loadFullModel(cfg, quiet = true)
                }
            }
        }
    }

    private suspend fun resolveIdentity(cfg: SignalKConfig) {
        val context = try {
            client.selfContext(cfg)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _diagnostics.update { it.copy(lastError = "self: ${e.message}") }
            null
        }
        if (context != null) setSelfContext(context)
    }

    private fun setSelfContext(context: String) {
        resolvedSelf = context
        _selfContext.value = context
        _diagnostics.update {
            it.copy(
                selfContext = context,
                selfResolved = true,
            )
        }
    }

    /**
     * True when [context] belongs to the own vessel. Accepts `vessels.self`, the resolved
     * URN, the configured context and any context that ends in the vessel MMSI.
     */
    fun isSelfContext(context: String?): Boolean {
        if (context == null) return true
        if (context == "vessels.self") return true
        if (context == configuredContext) return true
        if (resolvedSelf != null && context == resolvedSelf) return true
        val mmsi = selfMmsiValue
        return mmsi != null && context.endsWith(mmsi)
    }

    private suspend fun loadFullModel(cfg: SignalKConfig, quiet: Boolean = false): Boolean {
        repeat(2) { attemptIndex ->
            val model = try {
                client.fullModel(cfg)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!quiet) {
                    _diagnostics.update { it.copy(lastError = "modelo: ${e.message}") }
                    if (_status.value !is SignalKStatus.Streaming) {
                        _status.value = SignalKStatus.Error(e.message ?: "sin modelo")
                    }
                }
                null
            }
            if (model != null) {
                val flat = SignalKFlatten.flatten(model)
                val streaming = _status.value is SignalKStatus.Streaming
                _state.update { current ->
                    if (streaming && current.isNotEmpty()) flat + current else flat
                }
                val mmsi = flat.text("mmsi")
                    ?: flat.text("design.mmsi")
                    ?: flat.text("sensors.ais.mmsi")
                if (mmsi != null && mmsi.isNotBlank()) {
                    selfMmsiValue = mmsi
                    _selfMmsi.value = mmsi
                    if (resolvedSelf == null) setSelfContext("vessels.urn:mrn:imo:mmsi:$mmsi")
                }
                if (_status.value !is SignalKStatus.Streaming &&
                    _status.value !is SignalKStatus.Nmea
                ) {
                    _status.value = SignalKStatus.Connected
                }
                _diagnostics.update {
                    it.copy(
                        selfContext = resolvedSelf ?: configuredContext,
                        selfMmsi = mmsi ?: it.selfMmsi,
                        selfResolved = resolvedSelf != null,
                        values = _state.value.size,
                        fullModelAt = System.currentTimeMillis(),
                        streamState = when {
                            _status.value is SignalKStatus.Streaming -> "En vivo"
                            _status.value is SignalKStatus.Nmea -> "NMEA 0183"
                            else -> "Conectado"
                        },
                        lastError = null,
                    )
                }
                return true
            }
            if (attemptIndex == 0) delay(1_500)
        }
        return false
    }

    private suspend fun streamLoop(
        cfg: SignalKConfig,
        subscribe: String,
        onDelta: (DeltaEnvelope) -> Unit,
    ) {
        var failures = 0
        while (currentCoroutineContext().isActive) {
            try {
                client.stream(cfg, subscribe).collect { delta ->
                    failures = 0
                    _diagnostics.update {
                        if (it.streamState == "En vivo") it else it.copy(streamState = "En vivo")
                    }
                    onDelta(delta)
                }
                // The flow completed without an exception (server closed the socket).
                _diagnostics.update { it.copy(streamState = "Reconectando") }
                if (subscribe == "self") _status.value = SignalKStatus.Reconnecting
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _diagnostics.update {
                    it.copy(streamState = "Reconectando", lastError = e.message)
                }
                if (subscribe == "self") _status.value = SignalKStatus.Reconnecting
            }

            delay(backoffMs(failures))
            failures++
            if (subscribe == "self") {
                // The socket may have been down for a while: re-sync before re-subscribing.
                loadFullModel(cfg, quiet = true)
            }
        }
    }

    private fun ingest(delta: DeltaEnvelope, selfHint: Boolean, emit: Boolean) {
        if (emit) _deltas.tryEmit(delta)
        if (delta.updates.isEmpty()) return

        // `subscribe=self` guarantees the context is ours even when the server uses a URN.
        // If /api/self was unavailable, learn the real context from the self stream so the
        // AIS tracker never mistakes our own vessel for a contact.
        if (selfHint) {
            val ctx = delta.context
            if (ctx != null && ctx != "vessels.self" && resolvedSelf == null) {
                setSelfContext(ctx)
            }
        }
        if (!selfHint && !isSelfContext(delta.context)) return

        val incoming = HashMap<String, JsonElement>()
        for (update in delta.updates) {
            for (v in update.values) {
                if (v.path.isBlank()) continue
                incoming[v.path] = v.value
            }
        }
        if (incoming.isEmpty()) return

        val now = System.currentTimeMillis()
        for (path in incoming.keys) lastSignalKPathAt[path] = now
        _state.update { it + incoming }
        _lastUpdate.value = now
        if (_status.value !is SignalKStatus.Streaming) _status.value = SignalKStatus.Streaming
        _diagnostics.update {
            it.copy(
                selfContext = resolvedSelf ?: configuredContext,
                selfMmsi = selfMmsiValue,
                selfResolved = resolvedSelf != null,
                streamState = "En vivo",
                lastMessageAt = now,
                messages = it.messages + 1,
                values = _state.value.size,
                lastError = null,
            )
        }
    }

    // ------------------------------------------------------------------ NMEA

    /**
     * Merges own-vessel values decoded from NMEA 0183.
     *
     * Signal K is the primary source while its stream is live: NMEA only fills paths that
     * Signal K is not publishing. If the Signal K stream stops, NMEA takes over immediately;
     * when Signal K comes back it regains priority. This avoids both sources fighting over
     * the same values while still letting a boat without Signal K use NMEA alone.
     */
    fun ingestNmea(values: Map<String, JsonElement>) {
        if (values.isEmpty()) return
        val now = System.currentTimeMillis()
        val streaming = _status.value is SignalKStatus.Streaming
        val toApply = if (streaming) {
            values.filterKeys { path ->
                now - (lastSignalKPathAt[path] ?: 0L) > NMEA_TAKEOVER_MS
            }
        } else {
            values
        }
        if (toApply.isEmpty()) return

        _state.update { it + toApply }
        _lastUpdate.value = now
        if (_status.value !is SignalKStatus.Streaming) {
            _status.value = SignalKStatus.Nmea
        }
        _diagnostics.update {
            it.copy(
                selfContext = resolvedSelf ?: configuredContext,
                selfMmsi = selfMmsiValue,
                selfResolved = resolvedSelf != null,
                streamState = if (_status.value is SignalKStatus.Streaming) "En vivo (Signal K)" else "NMEA 0183",
                lastMessageAt = now,
                messages = it.messages + 1,
                values = _state.value.size,
                lastError = null,
            )
        }
    }

    // ------------------------------------------------------------------ demo

    /**
     * Synthetic 1 Hz data loop (demo mode): a moving own vessel plus three AIS targets.
     * It feeds the same [ingest] path, so everything (deltas, AIS, alarms, wind page)
     * behaves exactly as with a real server.
     */
    suspend fun runDemo() {
        _state.value = emptyMap()
        resolvedSelf = "vessels.self"
        selfMmsiValue = null
        _selfContext.value = "vessels.self"
        _selfMmsi.value = null
        _status.value = SignalKStatus.Streaming
        _diagnostics.value = SignalKDiagnostics(
            selfContext = "vessels.self",
            selfResolved = true,
            streamState = "Demo",
        )

        var t = 0.0
        var lat = 43.3450
        var lon = -3.0450
        var heading = Math.toRadians(300.0)

        val names = DemoData.aisNames
        val mmsi = DemoData.aisMmsi
        val aisLat = doubleArrayOf(43.3550, 43.3370, 43.3520)
        val aisLon = doubleArrayOf(-3.0350, -3.0300, -3.0620)
        val aisHdg = doubleArrayOf(210.0, 30.0, 120.0)
        val aisSogKn = doubleArrayOf(6.5, 4.2, 5.8)

        while (true) {
            t += 1.0
            heading = WindMath.wrap2Pi(heading + Math.toRadians(0.35 * sin(t / 20.0)))
            val sogKn = 5.4 + 0.5 * sin(t / 9.0)
            val sogMps = sogKn * 0.514444
            val moved = Geo.offset(LatLon(lat, lon), Math.toDegrees(heading), sogMps)
            lat = moved.lat
            lon = moved.lon

            val aws = 8.2 + 1.4 * sin(t / 7.0)
            val awa = Math.toRadians(38.0 + 9.0 * sin(t / 15.0))
            val stw = sogMps * 0.95
            val trueWind = WindMath.trueFromApparent(aws, awa, stw)

            val selfValues = listOf(
                pv("name", JsonPrimitive("Demo Yacht")),
                pv("mmsi", JsonPrimitive("211234560")),
                pv("navigation.position", latLon(lat, lon)),
                pv("navigation.speedOverGround", JsonPrimitive(sogMps)),
                pv("navigation.courseOverGroundTrue", JsonPrimitive(heading)),
                pv("navigation.headingTrue", JsonPrimitive(heading)),
                pv("navigation.speedThroughWater", JsonPrimitive(stw)),
                pv("environment.wind.speedApparent", JsonPrimitive(aws)),
                pv("environment.wind.angleApparent", JsonPrimitive(awa)),
                pv("environment.wind.speedTrue", JsonPrimitive(trueWind.trueSpeed)),
                pv("environment.wind.angleTrueWater", JsonPrimitive(trueWind.trueAngle)),
                pv(
                    "environment.wind.directionTrue",
                    JsonPrimitive(WindMath.trueWindDirection(heading, trueWind.trueAngle)),
                ),
                pv("environment.depth.belowTransducer", JsonPrimitive(12.5 + 3.0 * sin(t / 18.0))),
                pv("environment.water.temperature", JsonPrimitive(289.5)),
                pv("environment.outside.temperature", JsonPrimitive(295.0)),
                pv("environment.outside.pressure", JsonPrimitive(101300.0)),
                pv("navigation.gnss.satellites", JsonPrimitive(11.0)),
            )
            ingest(
                DeltaEnvelope(context = "vessels.self", updates = listOf(SignalKUpdate(values = selfValues))),
                selfHint = true,
                emit = true,
            )

            for (i in names.indices) {
                val step = aisSogKn[i] * 0.514444
                val p = Geo.offset(LatLon(aisLat[i], aisLon[i]), aisHdg[i], step)
                aisLat[i] = p.lat
                aisLon[i] = p.lon
                val aisValues = listOf(
                    pv("name", JsonPrimitive(names[i])),
                    pv("mmsi", JsonPrimitive(mmsi[i])),
                    pv("sensors.ais.class", JsonPrimitive("A")),
                    pv("navigation.position", latLon(aisLat[i], aisLon[i])),
                    pv("navigation.speedOverGround", JsonPrimitive(step)),
                    pv("navigation.courseOverGroundTrue", JsonPrimitive(Math.toRadians(aisHdg[i]))),
                    pv("navigation.headingTrue", JsonPrimitive(Math.toRadians(aisHdg[i]))),
                )
                ingest(
                    DeltaEnvelope(
                        context = "vessels.demo-${i + 1}",
                        updates = listOf(SignalKUpdate(values = aisValues)),
                    ),
                    selfHint = false,
                    emit = true,
                )
            }
            delay(1000)
        }
    }

    private fun pv(path: String, value: JsonElement) = SignalKPathValue(path, value)

    private fun latLon(lat: Double, lon: Double): JsonElement = buildJsonObject {
        put("latitude", lat)
        put("longitude", lon)
    }

    companion object {
        private const val MODEL_REFRESH_MS = 180_000L
        private const val NMEA_TAKEOVER_MS = 30_000L

        private fun backoffMs(failures: Int): Long = when (failures) {
            0 -> 750L
            1 -> 2_000L
            2 -> 4_000L
            3 -> 8_000L
            else -> 15_000L
        }
    }
}
