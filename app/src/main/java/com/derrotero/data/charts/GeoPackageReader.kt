package com.derrotero.data.charts

import android.database.sqlite.SQLiteDatabase
import java.io.File
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.sinh

/**
 * Serves a **GeoPackage** (`.gpkg`) raster chart as `{z}/{x}/{y}` tiles.
 *
 * GeoPackage tile pyramids store `tile_row` in XYZ order (origin at the north-west
 * corner), unlike MBTiles, so the request can be used as-is. The MIME type is detected
 * from the stored blobs (PNG or JPEG).
 *
 * Vector GeoPackages (feature tables instead of `tiles`) are not renderable yet and are
 * reported with a clear error instead of silently showing nothing.
 */
class GeoPackageReader(private val file: File) : TileProvider {

    private val db: SQLiteDatabase =
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)

    private val table: String

    init {
        val tables = LinkedHashSet<String>()
        runCatching {
            db.rawQuery(
                "SELECT name FROM sqlite_master WHERE type IN ('table','view')",
                null,
            ).use { cursor ->
                while (cursor.moveToNext()) cursor.getString(0)?.let { tables.add(it) }
            }
        }
        if ("gpkg_contents" !in tables) {
            db.close()
            error("No es un GeoPackage válido (falta gpkg_contents)")
        }

        val fromContents = runCatching {
            db.rawQuery(
                "SELECT table_name FROM gpkg_contents WHERE data_type = 'tiles' LIMIT 1",
                null,
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull()

        table = fromContents?.takeIf { it.matches(Regex("[A-Za-z0-9_]+")) }
            ?: "tiles".takeIf { it in tables }
            ?: run {
                db.close()
                error("GeoPackage sin tabla de teselas (¿carta vectorial? aún no soportada)")
            }
    }

    val bounds: GeoBounds? = readBounds()

    /** Tile edge in pixels, from `gpkg_tile_matrix` (usually 256). */
    val tileSize: Int = readTileSize()

    val minZoom: Int
    val maxZoom: Int

    init {
        var min = 0
        var max = 14
        runCatching {
            db.rawQuery(
                "SELECT MIN(zoom_level), MAX(zoom_level) FROM \"$table\"",
                null,
            ).use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) {
                    min = cursor.getInt(0)
                    max = cursor.getInt(1)
                }
            }
        }
        minZoom = min
        maxZoom = max
    }

    override val tileMimeType: String by lazy {
        val head = firstTile() ?: return@lazy "image/png"
        when {
            head.size > 3 && head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() -> "image/jpeg"
            else -> "image/png"
        }
    }

    override fun tile(z: Int, x: Int, y: Int): ByteArray? {
        if (z < 0 || x < 0 || y < 0) return null
        return runCatching {
            db.rawQuery(
                "SELECT tile_data FROM \"$table\" " +
                    "WHERE zoom_level = ? AND tile_column = ? AND tile_row = ?",
                arrayOf(z.toString(), x.toString(), y.toString()),
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getBlob(0) else null }
        }.getOrNull()
    }

    private fun firstTile(): ByteArray? = runCatching {
        db.rawQuery("SELECT tile_data FROM \"$table\" LIMIT 1", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getBlob(0) else null
        }
    }.getOrNull()

    private fun readTileSize(): Int = runCatching {
        db.rawQuery(
            "SELECT tile_width, tile_height FROM gpkg_tile_matrix " +
                "WHERE table_name = ? LIMIT 1",
            arrayOf(table),
        ).use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) {
                val width = cursor.getInt(0)
                if (width in 64..2048) width else 256
            } else {
                256
            }
        }
    }.getOrDefault(256)

    private fun readBounds(): GeoBounds? = runCatching {        db.rawQuery(
            "SELECT min_x, min_y, max_x, max_y, srs_id FROM gpkg_contents " +
                "WHERE data_type = 'tiles' LIMIT 1",
            null,
        ).use { cursor ->
            if (!cursor.moveToFirst() || cursor.isNull(0)) return@use null
            val minX = cursor.getDouble(0)
            val minY = cursor.getDouble(1)
            val maxX = cursor.getDouble(2)
            val maxY = cursor.getDouble(3)
            val srs = cursor.getInt(4)
            when (srs) {
                // WGS84 lon/lat.
                4326 -> GeoBounds(north = maxY, south = minY, east = maxX, west = minX)
                // Web Mercator metres.
                3857, 900913, 102100 -> GeoBounds(
                    north = mercToLat(maxY),
                    south = mercToLat(minY),
                    east = mercToLon(maxX),
                    west = mercToLon(minX),
                )
                else -> null
            }?.takeIf { it.isValid }
        }
    }.getOrNull()

    override fun close() {
        runCatching { db.close() }
    }

    private fun mercToLat(y: Double): Double =
        atan(sinh(y / EARTH_RADIUS)) * 180.0 / PI

    private fun mercToLon(x: Double): Double =
        Math.toDegrees(x / EARTH_RADIUS)

    private companion object {
        const val EARTH_RADIUS = 6378137.0
    }
}
