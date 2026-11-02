package com.derrotero.data.charts

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.URLEncoder

/**
 * Downloads the assets a MapLibre vector style needs besides tiles: the sprite sheet and
 * the glyph ranges for the fonts it uses. Everything is cached on disk and then served by
 * [LocalTileServer], so a vector chart keeps working offline.
 */
class StyleAssets(private val http: OkHttpClient) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    data class Result(val downloaded: Int, val failed: Int, val fonts: List<String>)

    suspend fun prefetch(styleText: String, cacheDir: File): Result = withContext(Dispatchers.IO) {
        val root = runCatching { json.parseToJsonElement(styleText).jsonObject }.getOrNull()
            ?: return@withContext Result(0, 0, emptyList())

        val glyphsUrl = root["glyphs"]?.jsonPrimitive?.contentOrNull
        val spriteUrl = root["sprite"]?.jsonPrimitive?.contentOrNull
        val fonts = collectFontstacks(root)

        var downloaded = 0
        var failed = 0

        if (spriteUrl != null) {
            for (suffix in listOf(".json", ".png", "@2x.json", "@2x.png")) {
                val target = File(cacheDir, "sprite$suffix")
                if (target.exists() && target.length() > 0) {
                    downloaded++
                    continue
                }
                val bytes = fetch(spriteUrl + suffix)
                if (bytes != null) {
                    target.parentFile?.mkdirs()
                    runCatching { target.writeBytes(bytes) }
                    downloaded++
                } else {
                    failed++
                }
            }
        }

        if (glyphsUrl != null && fonts.isNotEmpty()) {
            for (font in fonts) {
                for (start in GLYPH_RANGES) {
                    val range = "$start-${start + 255}"
                    val target = File(cacheDir, "glyphs/${safeName(font)}/$range.pbf")
                    if (target.exists() && target.length() > 0) {
                        downloaded++
                        continue
                    }
                    val url = glyphsUrl
                        .replace("{fontstack}", encode(font))
                        .replace("{range}", range)
                    val bytes = fetch(url)
                    if (bytes != null) {
                        target.parentFile?.mkdirs()
                        runCatching { target.writeBytes(bytes) }
                        downloaded++
                    } else {
                        failed++
                    }
                }
            }
        }
        Result(downloaded, failed, fonts)
    }

    /** Fonts referenced by the style (public for the "download all" action). */
    fun fontstacks(styleText: String): List<String> {
        val root = runCatching { json.parseToJsonElement(styleText).jsonObject }.getOrNull()
            ?: return emptyList()
        return collectFontstacks(root)
    }

    /**
     * Downloads every glyph range (0..65535) for [fonts] plus the sprite sheet.
     * Used by the explicit "download assets" action for vector charts.
     */
    suspend fun downloadAll(
        glyphsUrl: String?,
        spriteUrl: String?,
        fonts: List<String>,
        cacheDir: File,
        onProgress: (Int, Int) -> Unit,
    ): Pair<Int, Int> = withContext(Dispatchers.IO) {
        val ranges = (0..65280 step 256).map { "$it-${it + 255}" }
        val spriteSuffixes = listOf(".json", ".png", "@2x.json", "@2x.png")
        val total = (if (spriteUrl != null) spriteSuffixes.size else 0) +
            (if (glyphsUrl != null) fonts.size * ranges.size else 0)
        var done = 0
        var ok = 0

        if (spriteUrl != null) {
            for (suffix in spriteSuffixes) {
                val target = File(cacheDir, "sprite$suffix")
                if (target.exists() && target.length() > 0) {
                    ok++
                } else {
                    val bytes = fetch(spriteUrl + suffix)
                    if (bytes != null) {
                        target.parentFile?.mkdirs()
                        runCatching { target.writeBytes(bytes) }
                        ok++
                    }
                }
                done++
                onProgress(done, total)
            }
        }

        if (glyphsUrl != null) {
            for (font in fonts) {
                for (range in ranges) {
                    val target = File(cacheDir, "glyphs/${safeName(font)}/$range.pbf")
                    if (target.exists() && target.length() > 0) {
                        ok++
                    } else {
                        val url = glyphsUrl
                            .replace("{fontstack}", encode(font))
                            .replace("{range}", range)
                        val bytes = fetch(url)
                        if (bytes != null) {
                            target.parentFile?.mkdirs()
                            runCatching { target.writeBytes(bytes) }
                            ok++
                        }
                    }
                    done++
                    if (done % 16 == 0 || done == total) onProgress(done, total)
                }
            }
        }
        onProgress(done, total)
        ok to total
    }

    private fun collectFontstacks(root: JsonObject): List<String> {
        val layers = root["layers"] as? kotlinx.serialization.json.JsonArray ?: return emptyList()
        val fonts = LinkedHashSet<String>()
        for (layer in layers) {
            val layout = runCatching { layer.jsonObject["layout"]?.jsonObject }.getOrNull() ?: continue
            val textFont = layout["text-font"] as? kotlinx.serialization.json.JsonArray ?: continue
            for (item in textFont) {
                runCatching { item.jsonPrimitive.contentOrNull }.getOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?.let { fonts.add(it) }
            }
        }
        return fonts.toList()
    }

    private fun fetch(url: String): ByteArray? = runCatching {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Derrotero/0.1")
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body ?: return null
            if (body.contentLength() == 0L) return null
            body.bytes()
        }
    }.getOrNull()

    companion object {
        /** Latin + European ranges; other ranges are proxied and cached on demand. */
        private val GLYPH_RANGES = listOf(0, 256, 512, 768)

        fun safeName(font: String): String =
            font.replace(Regex("[^A-Za-z0-9._ -]"), "_").replace("/", "_").trim()

        private fun encode(value: String): String =
            URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    }
}
