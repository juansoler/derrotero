package com.derrotero.data.ais

import com.derrotero.data.charts.GeoBounds
import com.derrotero.data.settings.AppSettings
import com.derrotero.data.settings.InternetAisProvider
import com.derrotero.data.vessel.AisTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

/**
 * Internet AIS for the visible map area.
 *
 * Providers:
 *  - **aisstream.io** (WebSocket, free API key). NOTE: the service has had long outages where
 *    the socket connects and no messages are delivered (see aisstream/aisstream#15).
 *  - **AISHub** (HTTP/JSON, free username, requires contributing an AIS feed; 1 req/min).
 *
 * Diagnostics ([messageCount], [lastMessageAt], [lastError]) make it obvious whether the
 * provider is silent or the app is failing to parse.
 */
class InternetAisRepository(
    private val http: OkHttpClient,
    private val json: Json,
) {

    private class Builder {
        var lat: Double? = null
        var lon: Double? = null
        var name: String? = null
        var type: Int? = null
        var sogKn: Double? = null
        var cogDeg: Double? = null
        var hdgDeg: Double? = null
        var last: Long = 0L
    }

    private val builders = ConcurrentHashMap<String, Builder>()

    private val _targets = MutableStateFlow<Map<String, AisTarget>>(emptyMap())
    val targets: StateFlow<Map<String, AisTarget>> = _targets.asStateFlow()

    private val _status = MutableStateFlow("Desactivado")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _messageCount = MutableStateFlow(0)
    val messageCount: StateFlow<Int> = _messageCount.asStateFlow()

    private val _lastMessageAt = MutableStateFlow(0L)
    val lastMessageAt: StateFlow<Long> = _lastMessageAt.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    @OptIn(FlowPreview::class)
    suspend fun run(
        bounds: StateFlow<GeoBounds?>,
        settings: StateFlow<AppSettings>,
        scope: CoroutineScope,
    ) {
        scope.launch {
            while (true) {
                delay(30_000)
                val now = System.currentTimeMillis()
                if (builders.entries.removeIf { now - it.value.last > TTL_MS }) publish()
            }
        }

        settings.map { it.internetAis }.distinctUntilChanged().collectLatest { cfg ->
            builders.clear()
            _targets.value = emptyMap()
            _messageCount.value = 0
            _lastMessageAt.value = 0
            _lastError.value = null

            if (!cfg.enabled) {
                _status.value = "Desactivado"
                return@collectLatest
            }

            when (cfg.provider) {
                InternetAisProvider.AISSTREAM -> runAisStream(cfg.apiKey, bounds)
                InternetAisProvider.AISHUB -> runAisHub(cfg.username, bounds)
            }
        }
    }

    // ------------------------------------------------------------- aisstream.io

    private suspend fun runAisStream(apiKey: String, bounds: StateFlow<GeoBounds?>) {
        if (apiKey.isBlank()) {
            _status.value = "Falta la API key de aisstream.io"
            return
        }
        bounds.filterNotNull().debounce(1200).collectLatest { b ->
            _status.value = "Conectando…"
            while (coroutineContext.isActive) {
                runCatching { stream(apiKey, b).collect { handleStream(it) } }
                _status.value = "Reconectando…"
                delay(5_000)
            }
        }
    }

    private fun stream(apiKey: String, b: GeoBounds): Flow<String> = callbackFlow {
        val request = Request.Builder().url(AISSTREAM_URL).build()
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val sub = buildJsonObject {
                    put("APIKey", apiKey)
                    put("BoundingBoxes", buildJsonArray {
                        add(buildJsonArray {
                            add(buildJsonArray { add(JsonPrimitive(b.south)); add(JsonPrimitive(b.west)) })
                            add(buildJsonArray { add(JsonPrimitive(b.north)); add(JsonPrimitive(b.east)) })
                        })
                    })
                    put("FilterMessageTypes", buildJsonArray {
                        add(JsonPrimitive("PositionReport"))
                        add(JsonPrimitive("ShipStaticData"))
                    })
                }
                webSocket.send(sub.toString())
                _status.value = "Conectado (aisstream.io)"
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                trySend(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                _lastError.value = t.message
                close()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                close()
            }
        }
        val socket = http.newWebSocket(request, listener)
        awaitClose { socket.cancel() }
    }

    private fun handleStream(text: String) {
        _messageCount.value += 1
        _lastMessageAt.value = System.currentTimeMillis()

        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
        if (root == null) {
            _lastError.value = "Mensaje no JSON"
            return
        }
        root["error"]?.jsonPrimitive?.contentOrNull?.let {
            _lastError.value = it
            _status.value = "Error: $it"
            return
        }

        val type = root["MessageType"]?.jsonPrimitive?.contentOrNull ?: return
        val meta = root["MetaData"]?.jsonObject ?: return
        val mmsi = meta["MMSI"]?.jsonPrimitive?.contentOrNull ?: return

        val builder = builders.getOrPut(mmsi) { Builder() }
        meta["latitude"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()?.let { builder.lat = it }
        meta["longitude"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()?.let { builder.lon = it }
        meta["ShipName"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
            ?.let { builder.name = it }

        when (type) {
            "PositionReport" -> {
                val p = root["Message"]?.jsonObject?.get("PositionReport")?.jsonObject
                builder.sogKn = p?.get("Sog")?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
                builder.cogDeg = p?.get("Cog")?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
                builder.hdgDeg = p?.get("TrueHeading")?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
            }
            "ShipStaticData" -> {
                val s = root["Message"]?.jsonObject?.get("ShipStaticData")?.jsonObject
                s?.get("Type")?.jsonPrimitive?.contentOrNull?.toIntOrNull()?.let { builder.type = it }
                s?.get("Name")?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
                    ?.let { builder.name = it }
            }
        }
        builder.last = System.currentTimeMillis()
        publish()
    }

    // ------------------------------------------------------------------ AISHub

    private suspend fun runAisHub(username: String, bounds: StateFlow<GeoBounds?>) {
        if (username.isBlank()) {
            _status.value = "Falta el usuario de AISHub"
            return
        }
        while (coroutineContext.isActive) {
            val b = bounds.value
            if (b != null) {
                _status.value = "Consultando AISHub…"
                runCatching { fetchAisHub(username, b) }
                    .onSuccess { count ->
                        _messageCount.value = count
                        _lastMessageAt.value = System.currentTimeMillis()
                        _status.value = "AISHub: $count buques"
                    }
                    .onFailure {
                        _lastError.value = it.message
                        _status.value = "Error AISHub: ${it.message}"
                    }
            }
            delay(60_000)
        }
    }

    private suspend fun fetchAisHub(username: String, b: GeoBounds): Int = withContext(Dispatchers.IO) {
        val url = buildString {
            append("https://data.aishub.net/ws.php?username=").append(encode(username))
            append("&format=1&output=json&compress=0")
            append("&latmin=").append(b.south).append("&latmax=").append(b.north)
            append("&lonmin=").append(b.west).append("&lonmax=").append(b.east)
        }
        val text = httpGet(url) ?: error("sin respuesta")
        val root = json.parseToJsonElement(text)
        val block = (root as? JsonArray)?.firstOrNull()?.jsonObject
            ?: (root as? kotlinx.serialization.json.JsonObject)
            ?: error("respuesta inesperada")

        if (block["ERROR"]?.jsonPrimitive?.booleanOrNull == true) {
            error(block["ERROR_MESSAGE"]?.jsonPrimitive?.contentOrNull ?: "error de AISHub")
        }

        val messages = block["MESSAGES"] as? JsonArray ?: JsonArray(emptyList())
        val fresh = HashMap<String, Builder>()
        for (entry in messages) {
            val f = entry as? JsonArray ?: continue
            fun field(i: Int): String? =
                runCatching { f.getOrNull(i)?.jsonPrimitive?.contentOrNull }.getOrNull()

            val mmsi = field(0) ?: continue
            val lat = field(1)?.toDoubleOrNull() ?: continue
            val lon = field(2)?.toDoubleOrNull() ?: continue

            fresh[mmsi] = Builder().apply {
                this.lat = lat
                this.lon = lon
                sogKn = field(3)?.toDoubleOrNull()
                cogDeg = field(4)?.toDoubleOrNull()
                hdgDeg = field(5)?.toDoubleOrNull()
                name = field(9)?.trim()?.takeIf { it.isNotBlank() }
                type = field(11)?.toIntOrNull()
                last = System.currentTimeMillis()
            }
        }
        builders.clear()
        builders.putAll(fresh)
        publish()
        fresh.size
    }

    private fun httpGet(url: String): String? = runCatching {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Derrotero/0.1")
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            response.body?.string()
        }
    }.getOrNull()

    // ------------------------------------------------------------------ publish

    private fun publish() {
        val out = HashMap<String, AisTarget>(builders.size)
        for ((mmsi, b) in builders) {
            val lat = b.lat ?: continue
            val lon = b.lon ?: continue
            val (category, code) = ShipType.classify(b.type)
            out["net-$mmsi"] = AisTarget(
                id = "net-$mmsi",
                lat = lat,
                lon = lon,
                mmsi = mmsi,
                name = b.name,
                sogMps = b.sogKn?.times(0.514444),
                cogRad = b.cogDeg?.let { Math.toRadians(it) },
                headingRad = b.hdgDeg?.let { Math.toRadians(it) },
                category = category,
                code = code,
                source = "internet",
                lastSeenMs = b.last,
            )
        }
        _targets.value = out
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    companion object {
        private const val AISSTREAM_URL = "wss://stream.aisstream.io/v0/stream"
        private const val TTL_MS = 5 * 60_000L
    }
}
