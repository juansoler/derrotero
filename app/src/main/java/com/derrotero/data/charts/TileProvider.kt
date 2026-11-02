package com.derrotero.data.charts

import java.io.Closeable

/** A local tile archive served over loopback by [LocalTileServer]. */
interface TileProvider : Closeable {
    fun tile(z: Int, x: Int, y: Int): ByteArray?
    val tileMimeType: String
}
