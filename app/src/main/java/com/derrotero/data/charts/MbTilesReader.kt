package com.derrotero.data.charts

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import java.io.Closeable
import java.io.File

/**
 * Minimal read-only view over an MBTiles (SQLite) chart package.
 *
 * MBTiles stores raster tiles in the `tiles` table with **TMS** row numbering
 * (`tile_row` grows northward); XYZ requests are converted here.
 */
class MbTilesReader(private val file: File) : TileProvider {

    private val db: SQLiteDatabase =
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)

    val metadata: Map<String, String> by lazy {
        val out = LinkedHashMap<String, String>()
        runCatching {
            db.rawQuery("SELECT name, value FROM metadata", null).use { c ->
                while (c.moveToNext()) out[c.getString(0)] = c.getString(1)
            }
        }
        out
    }

    override val tileMimeType: String
        get() = when (metadata["format"]?.lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            else -> "image/png"
        }

    fun bounds(): DoubleArray? = runCatching {
        val parts = metadata["bounds"]?.split(',')?.map { it.trim().toDouble() } ?: return null
        if (parts.size == 4) parts.toDoubleArray() else null
    }.getOrNull()

    fun centerZoom(): Pair<DoubleArray, Int>? = runCatching {
        val b = bounds() ?: return null
        val center = doubleArrayOf((b[0] + b[2]) / 2.0, (b[1] + b[3]) / 2.0)
        center to (metadata["maxzoom"]?.toIntOrNull() ?: 12)
    }.getOrNull()

    override fun tile(z: Int, x: Int, yXyz: Int): ByteArray? {
        val yTms = (1 shl z) - 1 - yXyz
        var cursor: Cursor? = null
        return try {
            cursor = db.rawQuery(
                "SELECT tile_data FROM tiles WHERE zoom_level = ? AND tile_column = ? AND tile_row = ?",
                arrayOf(z.toString(), x.toString(), yTms.toString()),
            )
            if (cursor.moveToFirst()) cursor.getBlob(0) else null
        } catch (_: Exception) {
            null
        } finally {
            cursor?.close()
        }
    }

    override fun close() {
        runCatching { db.close() }
    }
}
