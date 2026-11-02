package com.derrotero.data.charts

import fi.iki.elonen.NanoHTTPD
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.File
import java.net.URLDecoder

/**
 * Serves one local tile archive as `http://127.0.0.1:<port>/{z}/{x}/{y}.png` (or MVT), plus
 * cached/proxied style assets:
 *   `/glyphs/{fontstack}/{range}.pbf`
 *   `/sprite.json`, `/sprite.png`, `/sprite@2x.json`, `/sprite@2x.png`
 *
 * Asset requests hit the disk cache first; on a miss the original remote URL is fetched and
 * the result is cached, so a vector style keeps working offline for everything prefetched.
 */
class LocalTileServer(
    private val provider: TileProvider,
    port: Int = 0,
    private val cacheDir: File? = null,
    private val glyphsBase: String? = null,
    private val spriteBase: String? = null,
    private val http: OkHttpClient? = null,
) : NanoHTTPD("127.0.0.1", port), Closeable {

    val boundPort: Int get() = listeningPort

    fun urlTemplate(): String = "http://127.0.0.1:$listeningPort/{z}/{x}/{y}.png"

    fun tileTemplate(): String = "http://127.0.0.1:$listeningPort/{z}/{x}/{y}"

    fun glyphsTemplate(): String =
        "http://127.0.0.1:$listeningPort/glyphs/{fontstack}/{range}.pbf"

    fun spriteBase(): String = "http://127.0.0.1:$listeningPort/sprite"

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        return when {
            uri.startsWith("/glyphs/") -> serveGlyph(uri)
            uri.startsWith("/sprite") -> serveSprite(uri)
            else -> serveTile(uri)
        }
    }

    // --------------------------------------------------------------------- tiles

    private fun serveTile(uri: String): Response {
        val parts = uri.trim('/').split('/')
        if (parts.size != 3) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "expected /z/x/y")
        }
        val z = parts[0].toIntOrNull()
        val x = parts[1].toIntOrNull()
        val y = parts[2].substringBefore('.').toIntOrNull()
        if (z == null || x == null || y == null) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "bad tile coords")
        }
        val data = provider.tile(z, x, y)
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "no tile")
        return newFixedLengthResponse(
            Response.Status.OK,
            provider.tileMimeType,
            ByteArrayInputStream(data),
            data.size.toLong(),
        )
    }

    // -------------------------------------------------------------------- glyphs

    private fun serveGlyph(uri: String): Response {
        val parts = uri.trim('/').split('/')
        if (parts.size != 3) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "bad glyph path")
        }
        val font = decode(parts[1])
        val range = decode(parts[2]).removeSuffix(".pbf")
        if (!isSafe(font) || !isSafe(range)) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "bad name")
        }

        val cached = cacheDir?.let { File(it, "glyphs/${StyleAssets.safeName(font)}/$range.pbf") }
        if (cached != null && cached.exists()) {
            return pngLike(cached.readBytes(), "application/x-protobuf")
        }

        val base = glyphsBase ?: return notFound("no glyph source")
        val url = base.replace("{fontstack}", encode(font)).replace("{range}", range)
        val bytes = fetch(url) ?: return notFound("glyph fetch failed")
        cached?.parentFile?.mkdirs()
        runCatching { cached?.writeBytes(bytes) }
        return pngLike(bytes, "application/x-protobuf")
    }

    // -------------------------------------------------------------------- sprites

    private fun serveSprite(uri: String): Response {
        val name = uri.trimStart('/')
        if (!isSafe(name)) return notFound("bad sprite name")
        val cached = cacheDir?.let { File(it, name) }
        if (cached != null && cached.exists()) {
            return pngLike(cached.readBytes(), spriteMime(name))
        }

        val base = spriteBase ?: return notFound("no sprite source")
        val suffix = name.removePrefix("sprite")
        val url = base + suffix
        val bytes = fetch(url) ?: return notFound("sprite fetch failed")
        cached?.parentFile?.mkdirs()
        runCatching { cached?.writeBytes(bytes) }
        return pngLike(bytes, spriteMime(name))
    }

    // -------------------------------------------------------------------- helpers

    private fun pngLike(bytes: ByteArray, mime: String): Response =
        newFixedLengthResponse(Response.Status.OK, mime, ByteArrayInputStream(bytes), bytes.size.toLong())

    private fun spriteMime(name: String): String =
        if (name.endsWith(".json")) "application/json" else "image/png"

    private fun fetch(url: String): ByteArray? {
        val client = http ?: return null
        return runCatching {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Derrotero/0.1")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body ?: return null
                if (body.contentLength() == 0L) return null
                body.bytes()
            }
        }.getOrNull()
    }

    private fun notFound(message: String): Response =
        newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, message)

    private fun decode(value: String): String = runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun isSafe(value: String): Boolean =
        value.isNotBlank() && !value.contains("..") && !value.contains('/') && !value.contains('\\')

    override fun close() {
        stop()
    }
}
