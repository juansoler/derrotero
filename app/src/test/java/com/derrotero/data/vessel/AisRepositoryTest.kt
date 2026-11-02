package com.derrotero.data.vessel

import com.derrotero.data.signalk.DeltaEnvelope
import com.derrotero.data.signalk.SignalKPathValue
import com.derrotero.data.signalk.SignalKUpdate
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AisRepositoryTest {

    @Test
    fun `AIS ship type is mapped to OpenCPN-style category`() {
        val repository = AisRepository()
        repository.onDelta(
            DeltaEnvelope(
                context = "vessels.urn:mrn:imo:mmsi:211234567",
                updates = listOf(
                    SignalKUpdate(
                        values = listOf(
                            SignalKPathValue(
                                "navigation.position",
                                buildJsonObject {
                                    put("latitude", 43.3)
                                    put("longitude", -2.95)
                                },
                            ),
                            SignalKPathValue("design.aisShipType", JsonPrimitive(70)),
                            SignalKPathValue("name", JsonPrimitive("NORDIC STAR")),
                            SignalKPathValue("navigation.speedOverGround", JsonPrimitive(3.3)),
                            SignalKPathValue("navigation.courseOverGroundTrue", JsonPrimitive(1.5)),
                        ),
                    ),
                ),
            ),
        )

        val target = repository.targets.value.values.single()
        assertEquals("NORDIC STAR", target.name)
        assertEquals("cargo", target.category)
        assertEquals("C", target.code)
        assertTrue(target.lat > 43.0 && target.lon < -2.0)
    }

    @Test
    fun `own vessel is excluded by the predicate`() {
        val repository = AisRepository()
        repository.onDelta(
            DeltaEnvelope(
                context = "vessels.urn:mrn:imo:mmsi:211234560",
                updates = listOf(
                    SignalKUpdate(
                        values = listOf(
                            SignalKPathValue(
                                "navigation.position",
                                buildJsonObject {
                                    put("latitude", 43.3)
                                    put("longitude", -2.95)
                                },
                            ),
                        ),
                    ),
                ),
            ),
            isSelf = { it == "vessels.urn:mrn:imo:mmsi:211234560" },
        )
        assertTrue(repository.targets.value.isEmpty())
    }
}
