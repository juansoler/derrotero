package com.derrotero.data.charts

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.floor
import kotlin.math.sinh

/**
 * Serves a **BSB/KAP** chart (NOAA, Maptech, CHS…) as `{z}/{x}/{y}` PNG tiles.
 *
 * The chart is decoded in memory from the compressed KAP stream: scanlines are expanded on
 * demand and cached, and tiles are rendered in Web Mercator by sampling the chart through
 * its `REF/` georeferencing. Nothing is converted on disk, so a 20 MB chart opens instantly
 * and the original file is never modified.
 *
 * When the view is zoomed out (one screen pixel covers several chart pixels) the renderer
 * averages a small box of samples so coastlines and soundings do not disappear.
 */
class BsbKapReader(
    val file: File,
    private val externalRefs: List<KapRef> = emptyList(),
) : TileProvider {

    private val image = KapFormat.parse(file.readBytes(), file.name, externalRefs)

    val header: KapHeader get() = image.header
    val bounds: GeoBounds? get() = image.bounds
    val minZoom: Int get() = image.minZoom
    val maxZoom: Int get() = image.maxZoom

    override val tileMimeType: String = "image/png"

    /** Keep the decoded scanline cache under ~8 MB whatever the chart width is. */
    private val maxCachedRows = (8_000_000 / (image.width * 4)).coerceIn(64, 384)

    private val rows = object : LinkedHashMap<Int, IntArray>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, IntArray>?): Boolean =
            size > maxCachedRows
    }

    private val tileCache = object : LinkedHashMap<Long, ByteArray>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?): Boolean =
            size > 48
    }

    override fun tile(z: Int, x: Int, y: Int): ByteArray? {
        if (z !in 0..22) return null
        val n = 1 shl z
        if (x !in 0 until n || y !in 0 until n) return null

        val key = (z.toLong() shl 58) or (x.toLong() shl 29) or y.toLong()
        synchronized(tileCache) { tileCache[key] }?.let { return it }

        val data = render(z, x, y) ?: return null
        synchronized(tileCache) { tileCache[key] = data }
        return data
    }

    private fun render(z: Int, x: Int, y: Int): ByteArray? {
        val n = 1 shl z
        val lonLeft = x.toDouble() / n * 360.0 - 180.0
        val lonRight = (x + 1).toDouble() / n * 360.0 - 180.0
        val mercTop = PI * (1.0 - 2.0 * y / n)
        val mercBottom = PI * (1.0 - 2.0 * (y + 1) / n)

        val latTop = mercToLat(mercTop)
        val latBottom = mercToLat(mercBottom)
        bounds?.let { b ->
            if (latBottom > b.north || latTop < b.south) return null
            if (lonRight < b.west || lonLeft > b.east) return null
        }

        val firstColumn = image.pixelAtLon(lonLeft)
        val lastColumn = image.pixelAtLon(lonRight)
        val firstRow = image.rowAtLat(latTop)
        val lastRow = image.rowAtLat(latBottom)
        val chartPerPixel = maxOf(
            abs(lastColumn - firstColumn) / TILE,
            abs(lastRow - firstRow) / TILE,
        )
        val samples = when {
            chartPerPixel >= 6.0 -> 3
            chartPerPixel >= 2.0 -> 2
            else -> 1
        }

        // Chart columns used by each output column.
        val columns = Array(TILE) { i ->
            val lonA = lonLeft + (lonRight - lonLeft) * i / TILE
            val lonB = lonLeft + (lonRight - lonLeft) * (i + 1) / TILE
            sampleIndexes(image.pixelAtLon(lonA), image.pixelAtLon(lonB), samples)
        }
        if (columns.none { column -> column.any { it in 0 until image.width } }) return null

        val pixels = IntArray(TILE * TILE)
        var anyPixel = false
        for (j in 0 until TILE) {
            val latA = mercToLat(mercTop + (mercBottom - mercTop) * j / TILE.toDouble())
            val latB = mercToLat(mercTop + (mercBottom - mercTop) * (j + 1) / TILE.toDouble())
            val rowIndexes = sampleIndexes(image.rowAtLat(latA), image.rowAtLat(latB), samples)
            val lines = Array(rowIndexes.size) { k ->
                val index = rowIndexes[k]
                if (index in 0 until image.height) rowFor(index) else null
            }
            if (lines.all { it == null }) continue

            val offset = j * TILE
            for (i in 0 until TILE) {
                var red = 0
                var green = 0
                var blue = 0
                var count = 0
                for (line in lines) {
                    if (line == null) continue
                    for (column in columns[i]) {
                        if (column !in 0 until image.width) continue
                        val color = line[column]
                        if ((color ushr 24) == 0) continue
                        red += (color shr 16) and 0xFF
                        green += (color shr 8) and 0xFF
                        blue += color and 0xFF
                        count++
                    }
                }
                if (count > 0) {
                    pixels[offset + i] = (0xFF shl 24) or
                        ((red / count) shl 16) or
                        ((green / count) shl 8) or
                        (blue / count)
                    anyPixel = true
                }
            }
        }
        if (!anyPixel) return null

        val bitmap = Bitmap.createBitmap(TILE, TILE, Bitmap.Config.ARGB_8888)
        return try {
            bitmap.setPixels(pixels, 0, TILE, 0, 0, TILE, TILE)
            val out = ByteArrayOutputStream(32 * 1024)
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    /** Evenly spaced sample positions inside the inclusive chart range [a]..[b]. */
    private fun sampleIndexes(a: Double, b: Double, samples: Int): IntArray {
        val low = floor(minOf(a, b)).toInt()
        val high = floor(maxOf(a, b)).toInt()
        if (samples <= 1) return intArrayOf(low)
        val span = (high - low + 1).coerceAtLeast(1)
        return IntArray(samples) { low + ((it + 0.5) * span / samples).toInt() }
    }

    private fun rowFor(index: Int): IntArray? {
        synchronized(rows) { rows[index] }?.let { return it }
        val decoded = image.row(index) ?: return null
        synchronized(rows) { rows[index] = decoded }
        return decoded
    }

    private fun mercToLat(mercator: Double): Double =
        atan(sinh(mercator)) * 180.0 / PI

    override fun close() {
        synchronized(rows) { rows.clear() }
        synchronized(tileCache) { tileCache.clear() }
    }

    private companion object {
        const val TILE = 256
    }
}
