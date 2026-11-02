package com.derrotero.data.charts

import java.nio.charset.StandardCharsets

/**
 * One ISO/IEC 8211 record: field tag → raw field data (field terminator stripped).
 *
 * The descriptor record (DDR) is flagged with [isDescriptor]; S-57 data records carry
 * `FRID`/`VRID`/`SG2D`/... fields.
 */
internal class Iso8211Record(
    val fields: Map<String, ByteArray>,
    val isDescriptor: Boolean,
)

/**
 * Minimal ISO/IEC 8211 reader, the container used by S-57 ENC charts.
 *
 * Layout (all numbers are ASCII, big-endian digits):
 *  - 24-byte leader: record length (0..4), field-area start (12..16), field length size (20),
 *    field position size (21), field tag size (23);
 *  - directory entries: tag + length + position, terminated by 0x1E;
 *  - fields live at `recordStart + fieldAreaStart + position`, terminated by 0x1E.
 *
 * Field internals are decoded by [S57Reader] using the S-57 field definitions.
 */
internal object Iso8211 {

    fun read(bytes: ByteArray): List<Iso8211Record> {
        val out = ArrayList<Iso8211Record>()
        var offset = 0
        while (offset + 24 <= bytes.size) {
            val length = intAt(bytes, offset, 5)
            if (length < 24 || offset + length > bytes.size) break

            val fieldAreaStart = intAt(bytes, offset + 12, 5)
            val sizeFieldLength = intAt(bytes, offset + 20, 1)
            val sizeFieldPosition = intAt(bytes, offset + 21, 1)
            val sizeFieldTag = intAt(bytes, offset + 23, 1)
            if (fieldAreaStart < 24 ||
                sizeFieldLength <= 0 || sizeFieldLength > 9 ||
                sizeFieldPosition <= 0 || sizeFieldPosition > 9 ||
                sizeFieldTag <= 0 || sizeFieldTag > 9
            ) {
                break
            }

            val directoryEnd = offset + fieldAreaStart
            val entrySize = sizeFieldTag + sizeFieldLength + sizeFieldPosition
            if (directoryEnd > offset + length) break

            val fields = LinkedHashMap<String, ByteArray>()
            var entry = offset + 24
            while (entry + entrySize <= directoryEnd && (bytes[entry].toInt() and 0xFF) != 0x1E) {
                val tag = String(bytes, entry, sizeFieldTag, StandardCharsets.ISO_8859_1).trim()
                val fieldLength = intAt(bytes, entry + sizeFieldTag, sizeFieldLength)
                val fieldPosition = intAt(
                    bytes,
                    entry + sizeFieldTag + sizeFieldLength,
                    sizeFieldPosition,
                )
                val start = offset + fieldAreaStart + fieldPosition
                if (tag.isNotEmpty() && fieldLength >= 0 && start >= offset && start < offset + length) {
                    var end = (start + fieldLength).coerceAtMost(offset + length)
                    if (end > start && (bytes[end - 1].toInt() and 0xFF) == 0x1E) end--
                    fields[tag] = bytes.copyOfRange(start, end)
                }
                entry += entrySize
            }

            out.add(Iso8211Record(fields, (bytes[offset + 6].toInt() and 0xFF) == 'L'.code))
            offset += length
        }
        return out
    }

    private fun intAt(bytes: ByteArray, offset: Int, length: Int): Int {
        if (offset < 0 || offset + length > bytes.size) return -1
        var value = 0
        for (i in 0 until length) {
            val c = bytes[offset + i].toInt() and 0xFF
            if (c < '0'.code || c > '9'.code) return -1
            value = value * 10 + (c - '0'.code)
        }
        return value
    }
}
