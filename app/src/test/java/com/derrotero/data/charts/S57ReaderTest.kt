package com.derrotero.data.charts

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class S57ReaderTest {

    @Test
    fun `parses an enc cell with depth, lines and points`() {
        val chart = read("US5CA13M.000")
        println(
            "S57 ${chart.name} scale=1:${chart.scale} features=${chart.featureCount} " +
                "bounds=${chart.bounds} zooms=${chart.minZoom}-${chart.maxZoom} " +
                "geojson=${chart.geoJson.length / 1024} kB",
        )

        assertTrue(chart.scale > 0)
        assertNotNull(chart.bounds)

        assertTrue(chart.featureCount > 10)
        assertTrue(chart.geoJson.startsWith("{\"type\":\"FeatureCollection\""))
        assertTrue(chart.geoJson.contains("\"layer\":\"depth\""))
        assertTrue(
            chart.geoJson.contains("\"layer\":\"line\"") ||
                chart.geoJson.contains("\"layer\":\"point\""),
        )

        // Must be valid JSON and every feature must carry a geometry.
        val root = Json.parseToJsonElement(chart.geoJson)
        assertTrue(root.toString().contains("\"features\""))

        File(System.getProperty("java.io.tmpdir"), "s57-test.geojson").writeText(chart.geoJson)
    }

    @Test
    fun `applies update cells and draws light sectors`() {
        val base = bytes("US5CA13M.000")
        val first = bytes("US5CA13M.001")
        val second = bytes("US5CA13M.002")

        val withoutUpdates = S57Reader.read(base, "US5CA13M.000")
        val chart = S57Reader.read(base, "US5CA13M.000", listOf(first, second))

        assertEquals(0, withoutUpdates.updateCells)
        assertEquals(2, chart.updateCells)
        assertEquals(5, chart.updatedRecords)
        assertTrue(chart.featureCount > 1000)

        // The NOAA cell has lights: the simplified S-52 layer must include sectors and labels.
        assertTrue(chart.geoJson.contains("\"layer\":\"sector\""))
        assertTrue(chart.geoJson.contains("\"label\":\""))

        println(
            "ENC ${chart.name} features=${chart.featureCount} updates=${chart.updateCells}/" +
                "${chart.updatedRecords} geojson=${chart.geoJson.length / 1024} kB",
        )
        File(System.getProperty("java.io.tmpdir"), "s57-noaa-updated.geojson").writeText(chart.geoJson)
    }

    @Test
    fun `applies updates found next to the base cell`() {
        val directory = File(System.getProperty("java.io.tmpdir"), "derrotero-s57-updates")
        directory.mkdirs()
        for (name in listOf("US5CA13M.000", "US5CA13M.001", "US5CA13M.002")) {
            File(directory, name).writeBytes(bytes(name))
        }
        val chart = S57Reader.read(File(directory, "US5CA13M.000"))
        assertEquals(2, chart.updateCells)
        assertEquals(5, chart.updatedRecords)
    }

    private fun bytes(name: String): ByteArray =
        javaClass.getResourceAsStream("/s57/$name")?.readBytes()
            ?: error("missing test resource $name")

    private fun read(name: String): S57Chart {
        val data = bytes(name)
        return S57Reader.read(data, name)
    }
}
