package com.derrotero.data.charts

import android.database.sqlite.SQLiteDatabase
import com.derrotero.util.LatLon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.tan

/**
 * Downloads raster tiles into a self-contained MBTiles file for offline use.
 *
 * Two modes:
 *  - [download]: a rectangular area (the visible map).
 *  - [downloadRoute]: a corridor around a polyline (route legs), unioning per-leg tiles
 *    so a long route does not become one huge bounding box.
 *
 * Works with any XYZ template containing `{z}`, `{x}` and `{y}`.
 */
class OfflineChartDownloader(private val http: OkHttpClient) {

    data class Progress(val done: Int, val total: Int, val failed: Int)

    suspend fun download(
        template: String,
        bounds: GeoBounds,
        minZoom: Int,
        maxZoom: Int,
        target: File,
        attribution: String = "",
        pauseMs: Long = 40,
        onProgress: (Progress) -> Unit,
    ): File {
        require(bounds.isValid) { "área no válida" }
        val tiles = tileListForBounds(bounds, minZoom, maxZoom)
        return write(template, tiles, bounds, minZoom, maxZoom, target, attribution, pauseMs, onProgress)
    }

    suspend fun downloadRoute(
        template: String,
        points: List<LatLon>,
        corridorMeters: Double,
        minZoom: Int,
        maxZoom: Int,
        target: File,
        attribution: String = "",
        pauseMs: Long = 40,
        onProgress: (Progress) -> Unit,
    ): File {
        require(points.size >= 2) { "la ruta necesita al menos 2 puntos" }
        val tiles = tileListForRoute(points, corridorMeters, minZoom, maxZoom)
        return write(template, tiles, boundsOf(points), minZoom, maxZoom, target, attribution, pauseMs, onProgress)
    }

    private suspend fun write(
        template: String,
        tiles: List<Tile>,
        bounds: GeoBounds,
        minZoom: Int,
        maxZoom: Int,
        target: File,
        attribution: String,
        pauseMs: Long,
        onProgress: (Progress) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        require(minZoom in 0..19 && maxZoom in minZoom..19) { "zoom no válido" }
        require(tiles.isNotEmpty()) { "el área no contiene teselas" }
        require(tiles.size <= MAX_TILES) {
            "demasiadas teselas (${tiles.size}); reduce el área o el zoom (máx $MAX_TILES)"
        }

        target.parentFile?.mkdirs()
        if (target.exists()) target.delete()

        val db = SQLiteDatabase.openOrCreateDatabase(target, null)
        try {
            db.execSQL("CREATE TABLE IF NOT EXISTS metadata (name TEXT, value TEXT)")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS tiles (zoom_level INTEGER, tile_column INTEGER, " +
                    "tile_row INTEGER, tile_data BLOB)",
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS tile_index ON tiles " +
                    "(zoom_level, tile_column, tile_row)",
            )

            val name = target.nameWithoutExtension
            insertMeta(db, "name", name)
            insertMeta(db, "format", "png")
            insertMeta(db, "type", "basemap")
            insertMeta(db, "version", "1")
            insertMeta(db, "minzoom", minZoom.toString())
            insertMeta(db, "maxzoom", maxZoom.toString())
            insertMeta(db, "bounds", "${bounds.west},${bounds.south},${bounds.east},${bounds.north}")
            insertMeta(
                db,
                "attribution",
                attribution.ifBlank { "Descargado por Derrotero" },
            )

            var done = 0
            var failed = 0
            for (tile in tiles) {
                coroutineContext.ensureActive()
                val url = template
                    .replace("{z}", tile.z.toString())
                    .replace("{x}", tile.x.toString())
                    .replace("{y}", tile.y.toString())
                val bytes = runCatching { fetch(url) }.getOrNull()
                if (bytes == null) {
                    failed++
                } else {
                    val tmsRow = (1 shl tile.z) - 1 - tile.y
                    db.execSQL(
                        "INSERT OR REPLACE INTO tiles (zoom_level, tile_column, tile_row, tile_data) " +
                            "VALUES (?, ?, ?, ?)",
                        arrayOf<Any>(tile.z, tile.x, tmsRow, bytes),
                    )
                }
                done++
                if (done % 10 == 0 || done == tiles.size) {
                    onProgress(Progress(done, tiles.size, failed))
                }
                if (pauseMs > 0) delay(pauseMs)
            }
            onProgress(Progress(done, tiles.size, failed))
        } finally {
            runCatching { db.close() }
        }
        target
    }

    private fun fetch(url: String): ByteArray? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body ?: return null
            if (body.contentLength() == 0L) return null
            return body.bytes()
        }
    }

    private fun insertMeta(db: SQLiteDatabase, key: String, value: String) {
        db.execSQL("INSERT INTO metadata (name, value) VALUES (?, ?)", arrayOf<Any>(key, value))
    }

    companion object {
        const val MAX_TILES = 20_000
        private const val USER_AGENT = "Derrotero/0.9.4 (offline chart download)"

        /** Tile count for a rectangular download (for the UI estimate). */
        fun countTiles(bounds: GeoBounds, minZoom: Int, maxZoom: Int): Int {
            if (!bounds.isValid || minZoom > maxZoom) return 0
            return tileListForBounds(bounds, minZoom, maxZoom, cap = MAX_TILES).size
        }

        /** Tile count for a route corridor download (for the UI estimate). */
        fun countRouteTiles(
            points: List<LatLon>,
            corridorMeters: Double,
            minZoom: Int,
            maxZoom: Int,
        ): Int {
            if (points.size < 2 || minZoom > maxZoom) return 0
            return tileListForRoute(points, corridorMeters, minZoom, maxZoom, cap = MAX_TILES).size
        }
    }
}

// --------------------------------------------------------------------- tile math

private data class Tile(val z: Int, val x: Int, val y: Int)

private fun lonToTileX(lon: Double, z: Int): Double = (lon + 180.0) / 360.0 * (1 shl z)

private fun latToTileY(lat: Double, z: Int): Double {
    val clamped = lat.coerceIn(-85.05112878, 85.05112878)
    val r = Math.toRadians(clamped)
    return (1.0 - ln(tan(r) + 1.0 / cos(r)) / PI) / 2.0 * (1 shl z)
}

private fun tileListForBounds(
    bounds: GeoBounds,
    minZoom: Int,
    maxZoom: Int,
    cap: Int = 20_000,
): List<Tile> {
    val out = ArrayList<Tile>()
    for (z in minZoom..maxZoom) {
        addTilesForBounds(out, bounds, z, cap)
        if (out.size > cap) break
    }
    return out
}

private fun addTilesForBounds(out: MutableCollection<Tile>, bounds: GeoBounds, z: Int, cap: Int) {
    val n = 1 shl z
    val x0 = lonToTileX(bounds.west, z).toInt().coerceIn(0, n - 1)
    val x1 = lonToTileX(bounds.east, z).toInt().coerceIn(0, n - 1)
    val y0 = latToTileY(bounds.north, z).toInt().coerceIn(0, n - 1)
    val y1 = latToTileY(bounds.south, z).toInt().coerceIn(0, n - 1)
    for (x in minOf(x0, x1)..maxOf(x0, x1)) {
        for (y in minOf(y0, y1)..maxOf(y0, y1)) {
            out.add(Tile(z, x, y))
            if (out.size > cap) return
        }
    }
}

/** Union of per-leg corridor boxes, deduplicated. */
private fun tileListForRoute(
    points: List<LatLon>,
    corridorMeters: Double,
    minZoom: Int,
    maxZoom: Int,
    cap: Int = 20_000,
): List<Tile> {
    val set = LinkedHashSet<Tile>()
    for (i in 0 until points.size - 1) {
        val a = points[i]
        val b = points[i + 1]
        val box = corridorBox(a, b, corridorMeters)
        for (z in minZoom..maxZoom) {
            addTilesForBounds(set, box, z, cap)
            if (set.size > cap) return set.toList()
        }
    }
    return set.toList()
}

private fun corridorBox(a: LatLon, b: LatLon, corridorMeters: Double): GeoBounds {
    val dLat = corridorMeters / 111_320.0
    val meanLat = Math.toRadians((a.lat + b.lat) / 2.0)
    val dLon = corridorMeters / (111_320.0 * maxOf(0.05, cos(meanLat)))
    return GeoBounds(
        north = (maxOf(a.lat, b.lat) + dLat).coerceAtMost(90.0),
        south = (minOf(a.lat, b.lat) - dLat).coerceAtLeast(-90.0),
        east = maxOf(a.lon, b.lon) + dLon,
        west = minOf(a.lon, b.lon) - dLon,
    )
}

private fun boundsOf(points: List<LatLon>): GeoBounds = GeoBounds(
    north = points.maxOf { it.lat },
    south = points.minOf { it.lat },
    east = points.maxOf { it.lon },
    west = points.minOf { it.lon },
)
