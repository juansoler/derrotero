package com.derrotero.data.charts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

class KapFormatTest {

    @Test
    fun `parses header palette and georeferencing`() {
        val image = KapFormat.parse(sampleChart(), "test.kap")
        val header = image.header

        assertEquals(32, header.width)
        assertEquals(8, header.height)
        assertEquals("TEST KAP", header.name)
        assertEquals(40000, header.scale)
        assertEquals("Mercator", header.projection)
        assertEquals("WGS84", header.datum)
        assertEquals(3, header.paletteEntries)
        assertEquals(4, header.refs.size)
        assertEquals(4, header.polygon.size)
        assertEquals(2, header.colorSize)

        // 0 = transparent, 1 = white, 3 = red.
        assertEquals(0, header.palette[0])
        assertEquals(0xFFFFFFFF.toInt(), header.palette[1])
        assertEquals(0xFFFF0000.toInt(), header.palette[3])
    }

    @Test
    fun `decodes run length scanlines`() {
        val image = KapFormat.parse(sampleChart())

        val row = image.row(0)
        assertNotNull(row)
        row!!
        assertEquals(32, row.size)
        assertEquals(0xFFFFFFFF.toInt(), row[0])
        assertEquals(0xFFFFFFFF.toInt(), row[11])
        assertEquals(0xFFFF0000.toInt(), row[12])
        assertEquals(0xFFFF0000.toInt(), row[19])
        assertEquals(0xFFFFFFFF.toInt(), row[20])
        assertEquals(0xFFFFFFFF.toInt(), row[31])

        // Rows not stored in the chart decode to opaque white (nothing missing here).
        assertEquals(0xFFFF0000.toInt(), image.row(7)!![12])
    }

    @Test
    fun `maps pixels to lat lon`() {
        val image = KapFormat.parse(sampleChart())

        assertEquals(16.0, image.pixelAtLon(-3.0), 1e-6)
        assertEquals(0.0, image.pixelAtLon(-3.10), 1e-6)
        assertEquals(32.0, image.pixelAtLon(-2.90), 1e-6)
        // Mercator: the mid-latitude is not exactly the mid-row, but very close.
        assertEquals(4.0, image.rowAtLat(43.05), 0.01)
        assertEquals(0.0, image.rowAtLat(43.10), 1e-3)
        assertEquals(8.0, image.rowAtLat(43.00), 1e-3)
    }

    @Test
    fun `computes bounds and zoom range`() {
        val image = KapFormat.parse(sampleChart())
        val bounds = image.bounds!!

        assertEquals(43.10, bounds.north, 1e-6)
        assertEquals(43.00, bounds.south, 1e-6)
        assertEquals(-2.90, bounds.east, 1e-6)
        assertEquals(-3.10, bounds.west, 1e-6)

        assertEquals(8, image.nativeZoom)
        assertEquals(4, image.minZoom)
        assertEquals(10, image.maxZoom)
    }

    @Test
    fun `falls back to scanning when the index table is missing`() {
        val image = KapFormat.parse(sampleChart(withIndex = false))
        val row = image.row(3)!!
        assertEquals(0xFFFF0000.toInt(), row[12])
        assertEquals(0xFFFFFFFF.toInt(), row[31])
    }

    @Test
    fun `rejects files without reference points`() {
        assertThrows(IllegalStateException::class.java) {
            KapFormat.parse(sampleChart(withRefs = false), "sin-ref.kap")
        }
    }

    @Test
    fun `rejects files without header`() {
        assertThrows(IllegalStateException::class.java) {
            KapFormat.parse(ByteArray(200) { 0x41 }, "no.kap")
        }
    }

    @Test
    fun `nos charts take control points from the geo sidecar`() {
        val bytes = sampleChart(withRefs = false, withPolygon = false).copyOf()
        val text = String(bytes, StandardCharsets.ISO_8859_1)
        val index = text.indexOf("BSB/")
        bytes[index] = 'N'.code.toByte()
        bytes[index + 1] = 'O'.code.toByte()
        bytes[index + 2] = 'S'.code.toByte()

        val refs = listOf(
            KapRef(0.0, 0.0, 43.10, -3.10),
            KapRef(32.0, 0.0, 43.10, -2.90),
            KapRef(32.0, 8.0, 43.00, -2.90),
            KapRef(0.0, 8.0, 43.00, -3.10),
        )
        val image = KapFormat.parse(bytes, "test.nos", refs)

        assertEquals(43.10, image.bounds!!.north, 1e-6)
        assertEquals(-2.90, image.bounds!!.east, 1e-6)
        assertEquals(0xFFFF0000.toInt(), image.row(0)!![12])
    }

    @Test
    fun `parses geo sidecar control points`() {
        val refs = GeoRefParser.parse(
            """
            ; NOS/GEO georeference
            Point1=-3.100000 43.300000 0 0
            Point2=-2.900000 43.300000 0 50
            Point3=-2.900000 43.200000 50 50
            """.trimIndent(),
        )
        assertEquals(3, refs.size)
        assertEquals(-3.10, refs[0].lon, 1e-9)
        assertEquals(43.30, refs[0].lat, 1e-9)
        assertEquals(50.0, refs[2].line, 1e-9)
        assertEquals(50.0, refs[2].pixel, 1e-9)
    }

    // ------------------------------------------------------------------ fixtures

    private fun sampleChart(
        withIndex: Boolean = true,
        withRefs: Boolean = true,
        withPolygon: Boolean = true,
    ): ByteArray {
        val rows = mutableListOf(
            "!Copyright Derrotero test chart",
            "VER/3.0",
            "BSB/NA=TEST KAP,NU=999501,RA=32,8,DU=254",
            "KNP/SC=40000,GD=WGS84,PR=Mercator",
            "    PP=43.0,PI=0.03333,SP=,SK=0.0,TA=90.0",
            "     UN=Metres,SD=HHWLT,DX=2.5,DY=2.5",
            "RGB/1,255,255,255",
            "RGB/2,0,0,0",
            "RGB/3,255,0,0",
        )
        if (withRefs) {
            rows += listOf(
                "REF/1,0,0,43.10,-3.10",
                "REF/2,32,0,43.10,-2.90",
                "REF/3,32,8,43.00,-2.90",
                "REF/4,0,8,43.00,-3.10",
            )
        }
        rows += listOf(
            "PLY/1,43.10,-3.10",
            "PLY/2,43.10,-2.90",
            "PLY/3,43.00,-2.90",
            "PLY/4,43.00,-3.10",
        ).takeIf { withPolygon } ?: emptyList()
        val header = rows.joinToString("\r\n") + "\r\n"

        val out = ByteArrayOutputStream()
        out.write(header.toByteArray(StandardCharsets.ISO_8859_1))
        out.write(0x1A)
        out.write(0x00)
        out.write(2) // 2 bits per palette index

        val offsets = IntArray(8)
        for (row in 0 until 8) {
            offsets[row] = out.size()
            out.write(scanline(row + 1, listOf(1 to 12, 3 to 8, 1 to 12)))
        }

        if (withIndex) {
            val tableOffset = out.size()
            offsets.forEach { out.write(intBe(it)) }
            out.write(intBe(tableOffset))
        }
        return out.toByteArray()
    }

    /** BSB RLE with 2-bit values: bits 7..5 = palette value, bits 4..0 = run-1. */
    private fun scanline(marker: Int, runs: List<Pair<Int, Int>>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(marker)
        for ((value, count) in runs) {
            out.write((value shl 5) or (count - 1))
        }
        out.write(0)
        return out.toByteArray()
    }

    private fun intBe(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte(),
    )

    @Test
    fun `latitudes stay within the chart`() {
        val image = KapFormat.parse(sampleChart())
        val top = image.geo.latAt(0.0)
        val bottom = image.geo.latAt(8.0)
        assertTrue(top > bottom)
        assertEquals(43.10, top, 1e-6)
        assertEquals(43.00, bottom, 1e-6)
    }

    @Test
    fun `decodes 7 bit charts with long runs and multi byte markers`() {
        val image = KapFormat.parse(wideChart())
        val header = image.header

        assertEquals(300, header.width)
        assertEquals(3, header.height)
        assertEquals(7, header.colorSize)
        assertEquals(100, header.paletteEntries)

        val row = image.row(1)!!
        assertEquals(header.palette[1], row[0])
        assertEquals(header.palette[1], row[39])
        assertEquals(header.palette[50], row[40])
        assertEquals(header.palette[50], row[159])
        assertEquals(header.palette[100], row[160])
        assertEquals(header.palette[100], row[299])
    }

    private fun wideChart(): ByteArray {
        val lines = mutableListOf(
            "!Copyright Derrotero test chart",
            "VER/2.0",
            "BSB/NA=WIDE KAP,NU=999501,RA=300,3,DU=254",
            "KNP/SC=25000,GD=WGS84,PR=Mercator",
        )
        for (i in 1..100) {
            lines += "RGB/$i,${i * 2 % 255},${i * 3 % 255},${i * 5 % 255}"
        }
        lines += listOf(
            "REF/1,0,0,43.20,-3.20",
            "REF/2,300,0,43.20,-2.80",
            "REF/3,300,3,43.16,-2.80",
            "REF/4,0,3,43.16,-3.20",
        )
        val header = lines.joinToString("\r\n") + "\r\n"

        val out = ByteArrayOutputStream()
        out.write(header.toByteArray(StandardCharsets.ISO_8859_1))
        out.write(0x1A)
        out.write(0x00)
        out.write(7) // 7 bits per palette index: runs always use continuation bytes

        val offsets = IntArray(3)
        val runs = listOf(1 to 40, 50 to 120, 100 to 140)
        val markers = listOf(1, 129, 200)
        for (row in 0 until 3) {
            offsets[row] = out.size()
            out.write(marker(markers[row]))
            for ((value, count) in runs) {
                out.write(value or 0x80)
                out.write(base128(count - 1))
            }
            out.write(0)
        }

        val tableOffset = out.size()
        offsets.forEach { out.write(intBe(it)) }
        out.write(intBe(tableOffset))
        return out.toByteArray()
    }

    /** Base-128 line marker: high bit set means "another digit follows". */
    private fun marker(value: Int): ByteArray {
        if (value < 128) return byteArrayOf(value.toByte())
        val out = ByteArrayOutputStream()
        if (value >= 128 * 128) out.write(0x80 or ((value shr 14) and 0x7F))
        out.write(0x80 or ((value shr 7) and 0x7F))
        out.write(value and 0x7F)
        return out.toByteArray()
    }

    /** Base-128 run count: high bit on a byte means another digit follows. */
    private fun base128(value: Int): ByteArray {
        val out = ByteArrayOutputStream()
        if (value >= 128) out.write(0x80 or ((value shr 7) and 0x7F))
        out.write(value and 0x7F)
        return out.toByteArray()
    }
}
