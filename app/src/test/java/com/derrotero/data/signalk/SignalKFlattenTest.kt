package com.derrotero.data.signalk

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalKFlattenTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val model = json.parseToJsonElement(
        """
        {
          "name": {"value": "Test Boat", "timestamp": "2026-01-01T00:00:00Z"},
          "mmsi": {"value": "211234560"},
          "navigation": {
            "position": {"value": {"latitude": 43.3, "longitude": -2.95}},
            "speedOverGround": {"value": 5.1},
            "courseOverGroundTrue": {"value": 3.5},
            "headingTrue": {"value": 350.0},
            "gnss": {"satellites": {"value": 11}}
          },
          "environment": {
            "depth": {"belowTransducer": {"value": 12.5}},
            "water": {"temperature": {"value": 16.5}},
            "outside": {"pressure": {"value": 1013.0}},
            "wind": {"angleApparent": {"value": "-1,2"}}
          }
        }
        """.trimIndent(),
    ).jsonObject

    @Test
    fun `flattens values and keeps object leaves`() {
        val flat = SignalKFlatten.flatten(model)
        assertEquals("Test Boat", flat.text("name"))
        assertEquals(5.1, flat.number("navigation.speedOverGround")!!, 1e-9)
        assertEquals(11.0, flat.number("navigation.gnss.satellites")!!, 1e-9)
        assertEquals(43.3, flat.latitude()!!, 1e-9)
        assertEquals(-2.95, flat.longitude()!!, 1e-9)
    }

    @Test
    fun `accepts decimal commas from sloppy plugins`() {
        val flat = SignalKFlatten.flatten(model)
        assertEquals(-1.2, flat.number("environment.wind.angleApparent")!!, 1e-9)
    }

    @Test
    fun `radians are kept and degrees are converted`() {
        val flat = SignalKFlatten.flatten(model)
        assertEquals(3.5, flat.angle("navigation.courseOverGroundTrue")!!, 1e-9)
        val degrees = flat.angle("navigation.headingTrue")!!
        assertEquals(Math.toRadians(350.0), degrees, 1e-9)
        assertEquals(350.0, Math.toDegrees(degrees), 1e-6)
    }

    @Test
    fun `celsius and hPa are converted to SI`() {
        val flat = SignalKFlatten.flatten(model)
        assertEquals(289.65, flat.kelvin("environment.water.temperature")!!, 1e-9)
        assertEquals(101_300.0, flat.pascal("environment.outside.pressure")!!, 1e-9)
    }

    @Test
    fun `depth falls back to belowKeel and belowSurface`() {
        val keel = mapOf("environment.depth.belowKeel" to JsonPrimitive(9.5))
        assertEquals(9.5, keel.depthBest()!!, 1e-9)

        val surface = mapOf("environment.depth.belowSurface" to JsonPrimitive(14.0))
        assertEquals(14.0, surface.depthBest()!!, 1e-9)
    }

    @Test
    fun `meta-only delta values do not break deserialization`() {
        val delta = json.decodeFromString(
            DeltaEnvelope.serializer(),
            """
            {"context":"vessels.urn:mrn:imo:mmsi:211234560","updates":[
              {"values":[{"path":"name","meta":{"units":"string"}}]},
              {"values":[{"path":"navigation.position","value":{"latitude":1.5,"longitude":2.5}}]}
            ]}
            """.trimIndent(),
        )
        assertEquals("vessels.urn:mrn:imo:mmsi:211234560", delta.context)
        assertEquals(JsonNull, delta.updates[0].values[0].value)
        assertEquals(2, delta.updates.size)
    }

    @Test
    fun `self URN can be matched by mmsi suffix`() {
        val urn = "vessels.urn:mrn:imo:mmsi:211234560"
        assertTrue(urn.endsWith("211234560"))
    }
}
