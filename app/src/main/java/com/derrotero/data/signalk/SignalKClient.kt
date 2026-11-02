package com.derrotero.data.signalk

import com.derrotero.data.settings.SignalKConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** HTTP/stream error with the status code so the UI can explain what went wrong. */
class SignalKException(val code: Int, message: String) : IOException(message)

/**
 * Thin SignalK client: REST (full model + resources) and the WebSocket delta stream.
 *
 * References:
 *  - REST:      GET  {base}/signalk/v1/api/vessels/self
 *  - Self:      GET  {base}/signalk/v1/api/self
 *  - Stream:    WS   {base}/signalk/v1/stream?subscribe=self|all
 *  - Resources: GET/POST/PUT/DELETE {base}/signalk/v1/api/resources/{type}[/{id}]
 *
 * Notable fixes over the first version:
 *  - the real self context is resolved (`vessels.urn:mrn:imo:mmsi:…`), so own-vessel deltas
 *    are no longer discarded;
 *  - the host field accepts full URLs, the access token is sent as a bearer token;
 *  - the socket is re-armed if the server stops sending (half-open TCP after a Wi-Fi drop);
 *  - resource creation recovers the identifier from `Location` or from the response body.
 */
class SignalKClient(
    private val http: OkHttpClient,
    private val json: Json,
) {

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()
    private val wsHttp: OkHttpClient = http.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    data class SelfIdentity(val context: String?, val mmsi: String?)

    // ------------------------------------------------------------------ plumbing

    private fun endpoint(cfg: SignalKConfig, path: String): String =
        "${cfg.httpBase}/${path.trimStart('/')}"

    private fun Request.Builder.signalK(cfg: SignalKConfig): Request.Builder {
        header("Accept", "application/json")
        if (cfg.token.isNotBlank()) header("Authorization", "Bearer ${cfg.token.trim()}")
        return this
    }

    private fun friendly(code: Int, path: String, body: String): String = when (code) {
        401, 403 -> "acceso denegado (HTTP $code). Configura el token de Signal K"
        404 -> "no encontrado (HTTP 404): /$path"
        429 -> "demasiadas peticiones (HTTP 429)"
        else -> {
            val detail = body.trim().take(160)
            "HTTP $code en /$path" + if (detail.isEmpty()) "" else ": $detail"
        }
    }

    private suspend fun getJson(cfg: SignalKConfig, path: String): JsonElement? =
        withContext(Dispatchers.IO) {
            val req = Request.Builder().url(endpoint(cfg, path)).signalK(cfg).build()
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw SignalKException(resp.code, friendly(resp.code, path, text))
                if (text.isBlank()) return@withContext JsonNull
                runCatching { json.parseToJsonElement(text) }.getOrElse {
                    throw SignalKException(resp.code, "respuesta no JSON en /$path")
                }
            }
        }

    /** Like `runCatching` but never swallows coroutine cancellation. */
    private suspend fun <T> attempt(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    // --------------------------------------------------------------------- REST

    suspend fun serverInfo(cfg: SignalKConfig): ServerInfo {
        val element = getJson(cfg, "signalk/v1/api/") ?: return ServerInfo()
        return runCatching {
            json.decodeFromJsonElement(ServerInfo.serializer(), element)
        }.getOrElse { ServerInfo() }
    }

    /**
     * Resolve the identifier the server uses for the own vessel in delta messages.
     * Standard servers answer `GET /signalk/v1/api/self` with `"vessels.urn:mrn:imo:mmsi:…"`.
     */
    suspend fun selfContext(cfg: SignalKConfig): String? {
        val direct = attempt { getJson(cfg, "signalk/v1/api/self") }
        val fromDirect = when (direct) {
            is JsonPrimitive -> direct.contentOrNull
            is JsonObject -> (direct["self"] ?: direct["value"]).asStringOrNull()
            else -> null
        }
        if (fromDirect != null && fromDirect.startsWith("vessels.")) return fromDirect

        val root = attempt { getJson(cfg, "signalk/v1/api/") } as? JsonObject ?: return null
        return (root["self"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.startsWith("vessels.") }
    }

    /** Full "vessels.self" model (nested JSON with {value, timestamp} leaves). */
    suspend fun fullModel(cfg: SignalKConfig): JsonObject {
        val ctxPath = cfg.context.ifBlank { "vessels.self" }.replace('.', '/')
        val candidates = linkedSetOf(
            "signalk/v1/api/$ctxPath",
            "signalk/v1/api/vessels/self",
        )
        var failure: Throwable? = null
        for (path in candidates) {
            val element = try {
                getJson(cfg, path)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failure = e
                null
            }
            val obj = element as? JsonObject ?: continue
            // Some servers answer /vessels/self with {"vessels": {...}, "self": "..."}.
            val nested = obj["vessels"] as? JsonObject
            if (nested != null && obj["self"] != null) {
                val selfKey = (obj["self"] as? JsonPrimitive)?.contentOrNull
                val vessel = selfKey?.let { nested[it.removePrefix("vessels.")] as? JsonObject }
                if (vessel != null) return vessel
                if (nested.size == 1) return nested.values.first() as? JsonObject ?: obj
            }
            return obj
        }
        throw failure ?: IllegalStateException("modelo del barco no disponible")
    }

    // ------------------------------------------------------------------ stream

    /**
     * Live delta stream.
     *
     * [subscribe] is `self` (own vessel) or `all` (own vessel + AIS/other vessels).
     * A watchdog closes the socket when the server stops sending for [STREAM_TIMEOUT_MS],
     * which is what happens when the tablet roams off the boat Wi-Fi: TCP stays "open".
     */
    fun stream(cfg: SignalKConfig, subscribe: String = "all"): Flow<DeltaEnvelope> = callbackFlow {
        val query = URLEncoder.encode(subscribe, "UTF-8")
        val url = "${cfg.wsBase}/signalk/v1/stream?subscribe=$query&sendMeta=none"
        val request = Request.Builder().url(url).signalK(cfg).build()

        val lastActivity = AtomicLong(System.currentTimeMillis())
        lateinit var socket: WebSocket

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                lastActivity.set(System.currentTimeMillis())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                lastActivity.set(System.currentTimeMillis())
                parseDeltas(text).forEach { trySend(it) }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                lastActivity.set(System.currentTimeMillis())
                parseDeltas(bytes.utf8()).forEach { trySend(it) }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                runCatching { webSocket.close(1000, null) }
                close()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                close()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val detail = response?.let { " (HTTP ${it.code})" }.orEmpty()
                close(IOException("${t.message ?: t.javaClass.simpleName}$detail", t))
            }
        }

        socket = wsHttp.newWebSocket(request, listener)

        val watchdog = launch {
            while (isActive) {
                delay(5_000)
                val idle = System.currentTimeMillis() - lastActivity.get()
                if (idle > STREAM_TIMEOUT_MS) {
                    runCatching { socket.cancel() }
                    close(IOException("sin datos del servidor durante ${idle / 1000} s"))
                    break
                }
            }
        }

        awaitClose {
            watchdog.cancel()
            runCatching { socket.cancel() }
        }
    }

    /**
     * SignalK sends one JSON document per frame, but proxies have been seen concatenating
     * newline-delimited documents or wrapping them in an array.
     */
    private fun parseDeltas(text: String): List<DeltaEnvelope> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()

        fun parseOne(raw: String): DeltaEnvelope? = try {
            json.decodeFromString(DeltaEnvelope.serializer(), raw)
        } catch (e: Exception) {
            null
        }

        parseOne(trimmed)?.let { return listOf(it) }

        val whole = runCatching { json.parseToJsonElement(trimmed) }.getOrNull()
        if (whole is JsonArray) {
            return whole.mapNotNull { element ->
                runCatching {
                    json.decodeFromJsonElement(DeltaEnvelope.serializer(), element)
                }.getOrNull()
            }
        }
        return trimmed.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("{") }
            .mapNotNull(::parseOne)
            .toList()
    }

    // ---------------------------------------------------------------- resources

    /** GET a resource collection (map of id -> resource), filtering the "version" key. */
    suspend fun getCollection(cfg: SignalKConfig, resource: String): Map<String, JsonObject> {
        val element = try {
            getJson(cfg, "signalk/v1/api/resources/$resource")
        } catch (e: SignalKException) {
            // A server without the resources provider is not an error: just no resources.
            if (e.code == 404) return emptyMap() else throw e
        } ?: return emptyMap()
        val obj = element as? JsonObject ?: return emptyMap()
        // Some versions nest the collection: {"waypoints": {...}, "version": "1.7.0"}.
        val source = (obj[resource] as? JsonObject) ?: obj
        return source.entries
            .filter { (key, value) ->
                key != "version" && key != "self" && key != "id" &&
                    !key.startsWith("$") && value is JsonObject
            }
            .associate { it.key to (it.value as JsonObject) }
    }

    suspend fun getResource(cfg: SignalKConfig, resource: String, id: String): JsonObject? =
        attempt { getJson(cfg, "signalk/v1/api/resources/$resource/$id") } as? JsonObject

    /**
     * Create (POST) a resource. Returns the identifier from `Location` (preferred), from the
     * response body, or an empty string when the server did not say (the caller then PUTs).
     */
    suspend fun postResource(cfg: SignalKConfig, resource: String, body: JsonElement): String =
        withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url(endpoint(cfg, "signalk/v1/api/resources/$resource"))
                .signalK(cfg)
                .post(body.toString().toRequestBody(jsonMedia))
                .build()
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw SignalKException(resp.code, friendly(resp.code, "resources/$resource", text))
                }
                val fromLocation = resp.header("Location")
                    ?.trim()
                    ?.trimEnd('/')
                    ?.substringAfterLast('/')
                    ?.takeIf { it.isNotBlank() }
                if (fromLocation != null) return@withContext fromLocation

                val element = runCatching { json.parseToJsonElement(text) }.getOrNull()
                val fromBody = (element as? JsonObject)?.let { obj ->
                    (obj["id"] ?: obj["identifier"] ?: obj["uuid"]).asStringOrNull()
                }
                fromBody.orEmpty()
            }
        }

    /** Upsert (PUT) a resource by id. */
    suspend fun putResource(
        cfg: SignalKConfig,
        resource: String,
        id: String,
        body: JsonElement,
    ): String = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(endpoint(cfg, "signalk/v1/api/resources/$resource/$id"))
            .signalK(cfg)
            .put(body.toString().toRequestBody(jsonMedia))
            .build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw SignalKException(resp.code, friendly(resp.code, "resources/$resource/$id", text))
            }
            id
        }
    }

    suspend fun deleteResource(cfg: SignalKConfig, resource: String, id: String) {
        withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url(endpoint(cfg, "signalk/v1/api/resources/$resource/$id"))
                .signalK(cfg)
                .delete()
                .build()
            http.newCall(req).execute().use { resp ->
                // Deleting something that is already gone is not an error for the UI.
                if (!resp.isSuccessful && resp.code != 404) {
                    throw SignalKException(resp.code, friendly(resp.code, "resources/$resource/$id", ""))
                }
            }
        }
    }

    // ------------------------------------------------------------ vessel paths

    suspend fun getPath(cfg: SignalKConfig, path: String): JsonElement? {
        val p = "${cfg.context.replace('.', '/')}.$path"
        return attempt { getJson(cfg, "signalk/v1/api/$p") }
    }

    /**
     * PUT a vessel path. SignalK expects `{"value": ...}`; object values that already carry a
     * `value` key are sent untouched.
     */
    suspend fun putPath(cfg: SignalKConfig, path: String, value: JsonElement) {
        val body = if (value is JsonObject && value.containsKey("value")) {
            value
        } else {
            buildJsonObject { put("value", value) }
        }
        withContext(Dispatchers.IO) {
            val p = "${cfg.context.replace('.', '/')}.$path"
            val req = Request.Builder()
                .url(endpoint(cfg, "signalk/v1/api/$p"))
                .signalK(cfg)
                .put(body.toString().toRequestBody(jsonMedia))
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw SignalKException(resp.code, friendly(resp.code, p, ""))
                }
            }
        }
    }

    /** Best-effort API path probe used by the "copy diagnostics" action. */
    suspend fun probe(cfg: SignalKConfig, path: String): Result<String> = try {
        val element = getJson(cfg, path.trimStart('/'))
        Result.success(element?.toString()?.take(400) ?: "vacío")
    } catch (e: Exception) {
        Result.failure(e)
    }

    companion object {
        private const val STREAM_TIMEOUT_MS = 90_000L
    }
}

private fun JsonElement?.asStringOrNull(): String? = when (this) {
    is JsonPrimitive -> contentOrNull
    is JsonObject -> (this["value"] as? JsonPrimitive)?.contentOrNull
    else -> null
}
