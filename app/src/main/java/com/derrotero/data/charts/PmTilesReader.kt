package com.derrotero.data.charts

import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.zip.GZIPInputStream

/**
 * Minimal PMTiles v3 reader (https://github.com/protomaps/PMTiles, spec v3).
 *
 * Backed by a [FileChannel] so it can read directly from a SAF `ParcelFileDescriptor`
 * without copying a multi-GB archive. Supports internal/tile compression `none` and `gzip`.
 */
class PmTilesReader private constructor(
    private val channel: FileChannel,
    private val pfd: ParcelFileDescriptor?,
) : TileProvider {

    constructor(file: File) : this(FileInputStream(file).channel, null)

    constructor(pfd: ParcelFileDescriptor) : this(FileInputStream(pfd.fileDescriptor).channel, pfd)

    data class Header(
        val rootOffset: Long,
        val rootLength: Long,
        val metadataOffset: Long,
        val metadataLength: Long,
        val leafOffset: Long,
        val leafLength: Long,
        val dataOffset: Long,
        val dataLength: Long,
        val internalCompression: Int,
        val tileCompression: Int,
        val tileType: Int,
        val minZoom: Int,
        val maxZoom: Int,
        val minLon: Double,
        val minLat: Double,
        val maxLon: Double,
        val maxLat: Double,
    )

    private data class Entry(val tileId: Long, val offset: Long, val length: Int, val runLength: Int)

    val header: Header

    init {
        val buf = readAt(0, 127)
        require(buf.size == 127) { "archivo PMTiles demasiado corto" }
        require(String(buf, 0, 7, Charsets.US_ASCII) == "PMTiles") { "no es un archivo PMTiles" }
        require(buf[7].toInt() == 3) { "versión PMTiles no soportada (${buf[7]})" }

        val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
        fun u64(o: Int) = bb.getLong(o)
        fun i32(o: Int) = bb.getInt(o)

        header = Header(
            rootOffset = u64(8),
            rootLength = u64(16),
            metadataOffset = u64(24),
            metadataLength = u64(32),
            leafOffset = u64(40),
            leafLength = u64(48),
            dataOffset = u64(56),
            dataLength = u64(64),
            internalCompression = buf[97].toInt() and 0xFF,
            tileCompression = buf[98].toInt() and 0xFF,
            tileType = buf[99].toInt() and 0xFF,
            minZoom = buf[100].toInt() and 0xFF,
            maxZoom = buf[101].toInt() and 0xFF,
            minLon = i32(102) / 1e7,
            minLat = i32(106) / 1e7,
            maxLon = i32(110) / 1e7,
            maxLat = i32(114) / 1e7,
        )
    }

    val isVector: Boolean get() = header.tileType == 1 || header.tileType == 6

    override val tileMimeType: String
        get() = when (header.tileType) {
            1, 6 -> "application/x-protobuf"
            2 -> "image/png"
            3 -> "image/jpeg"
            4 -> "image/webp"
            5 -> "image/avif"
            else -> "application/octet-stream"
        }

    fun metadata(): String? = runCatching {
        if (header.metadataLength <= 0) return null
        val raw = readAt(header.metadataOffset, header.metadataLength.toInt())
        decompress(raw, header.internalCompression)?.toString(Charsets.UTF_8)
    }.getOrNull()

    override fun tile(z: Int, x: Int, y: Int): ByteArray? {
        if (z < header.minZoom || z > header.maxZoom) return null
        val id = zxyToTileId(z, x, y)

        var entry = findEntry(readDirectory(header.rootOffset, header.rootLength), id) ?: return null
        if (entry.runLength == 0) {
            if (header.leafLength <= 0) return null
            val leaf = readDirectory(header.leafOffset + entry.offset, entry.length.toLong()) ?: return null
            entry = findEntry(leaf, id) ?: return null
            if (entry.runLength == 0) return null
        }
        if (id >= entry.tileId + entry.runLength) return null

        val data = readAt(header.dataOffset + entry.offset, entry.length)
        if (data.size != entry.length) return null
        return decompress(data, header.tileCompression)
    }

    override fun close() {
        runCatching { channel.close() }
        runCatching { pfd?.close() }
    }

    // ------------------------------------------------------------------ internals

    private fun readAt(offset: Long, length: Int): ByteArray {
        if (length <= 0) return ByteArray(0)
        val buf = ByteBuffer.allocate(length)
        var pos = offset
        while (buf.hasRemaining()) {
            val n = channel.read(buf, pos)
            if (n <= 0) break
            pos += n
        }
        val size = buf.position()
        val out = ByteArray(size)
        buf.flip()
        buf.get(out)
        return out
    }

    private fun readDirectory(offset: Long, length: Long): List<Entry>? {
        if (length <= 0 || length > MAX_DIRECTORY_BYTES) return emptyList()
        return runCatching {
            val raw = readAt(offset, length.toInt())
            if (raw.size != length.toInt()) return emptyList()
            val buf = decompress(raw, header.internalCompression) ?: return emptyList()

            val pos = intArrayOf(0)
            val count = readVarint(buf, pos).toInt()
            if (count <= 0) return emptyList()

            val ids = LongArray(count)
            var last = 0L
            for (i in 0 until count) {
                last += readVarint(buf, pos)
                ids[i] = last
            }
            val runs = IntArray(count)
            for (i in 0 until count) runs[i] = readVarint(buf, pos).toInt()
            val lengths = IntArray(count)
            for (i in 0 until count) lengths[i] = readVarint(buf, pos).toInt()
            val offsets = LongArray(count)
            for (i in 0 until count) {
                val v = readVarint(buf, pos)
                offsets[i] = if (v == 0L && i > 0) offsets[i - 1] + lengths[i - 1] else v - 1
            }
            (0 until count).map { Entry(ids[it], offsets[it], lengths[it], runs[it]) }
        }.getOrNull()
    }

    private fun findEntry(entries: List<Entry>?, id: Long): Entry? {
        if (entries.isNullOrEmpty()) return null
        var lo = 0
        var hi = entries.size - 1
        var best: Entry? = null
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (entries[mid].tileId <= id) {
                best = entries[mid]
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return best
    }

    private fun readVarint(buf: ByteArray, pos: IntArray): Long {
        var result = 0L
        var shift = 0
        while (pos[0] < buf.size) {
            val b = buf[pos[0]++].toInt() and 0xFF
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) break
            shift += 7
            if (shift > 63) break
        }
        return result
    }

    private fun decompress(data: ByteArray, compression: Int): ByteArray? = when (compression) {
        1 -> data
        2 -> runCatching { GZIPInputStream(data.inputStream()).use { it.readBytes() } }.getOrNull()
        else -> null // brotli / zstd unsupported
    }

    companion object {
        private const val MAX_DIRECTORY_BYTES = 8L * 1024 * 1024

        /** Hilbert-curve tile id used by PMTiles; verified against the spec examples. */
        fun zxyToTileId(z: Int, x: Int, y: Int): Long {
            var acc = 0L
            for (tz in 0 until z) acc += 1L shl (tz * 2)

            val n = 1 shl z
            var tx = x
            var ty = y
            var d = 0L
            var s = n shr 1
            while (s > 0) {
                val rx = if (tx and s != 0) 1 else 0
                val ry = if (ty and s != 0) 1 else 0
                d += s.toLong() * s.toLong() * ((3 * rx) xor ry)
                if (ry == 0) {
                    if (rx == 1) {
                        tx = s - 1 - tx
                        ty = s - 1 - ty
                    }
                    val tmp = tx
                    tx = ty
                    ty = tmp
                }
                s = s shr 1
            }
            return acc + d
        }
    }
}
