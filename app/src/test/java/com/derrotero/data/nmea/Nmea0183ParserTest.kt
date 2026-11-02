package com.derrotero.data.nmea

import com.derrotero.data.signalk.DeltaEnvelope
import com.derrotero.data.signalk.Paths
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Nmea0183ParserTest {

    private val parser = Nmea0183Parser()

    private fun Map<String, JsonElement>.num(path: String): Double =
        this.getValue(path).jsonPrimitive.double

    private fun Map<String, JsonElement>.position(): Pair<Double, Double> {
        val obj = getValue(Paths.POSITION) as JsonObject
        return obj.getValue("latitude").jsonPrimitive.double to
            obj.getValue("longitude").jsonPrimitive.double
    }

    private fun DeltaEnvelope.values(): Map<String, JsonElement> =
        updates.flatMap { it.values }.associate { it.path to it.value }

    @Test
    fun `parses RMC position speed and course`() {
        val result = parser.parse(
            "\$GPRMC,123519,A,4807.038,N,01131.000,E,022.4,084.4,230394,003.1,W*6A",
        )
        assertEquals(true, result.checksumOk)
        assertEquals("RMC", result.type)
        val (lat, lon) = result.values.position()
        assertEquals(48.1173, lat, 1e-4)
        assertEquals(11.5167, lon, 1e-4)
        assertEquals(22.4 * 0.514444, result.values.num(Paths.SOG), 1e-6)
        assertEquals(Math.toRadians(84.4), result.values.num(Paths.COG), 1e-9)
    }

    @Test
    fun `parses GGA fix satellites and HDOP`() {
        val result = parser.parse(
            "\$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*47",
        )
        val (lat, lon) = result.values.position()
        assertEquals(48.1173, lat, 1e-4)
        assertEquals(11.5167, lon, 1e-4)
        assertEquals(8.0, result.values.num(Paths.GNSS_SATELLITES), 1e-9)
        assertEquals(0.9, result.values.num(Paths.GNSS_HDOP), 1e-9)
    }

    @Test
    fun `parses VTG and HDT`() {
        val vtg = parser.parse("\$GPVTG,054.7,T,034.4,M,005.5,N,010.2,K")
        assertEquals(Math.toRadians(54.7), vtg.values.num(Paths.COG), 1e-9)
        assertEquals(5.5 * 0.514444, vtg.values.num(Paths.SOG), 1e-6)

        val hdt = parser.parse("\$GPHDT,123.4,T")
        assertEquals(Math.toRadians(123.4), hdt.values.num(Paths.HEADING_TRUE), 1e-9)
    }

    @Test
    fun `parses depth and water temperature`() {
        val dpt = parser.parse("\$SDDPT,12.5,0.5")
        assertEquals(12.5, dpt.values.num(Paths.DEPTH), 1e-9)

        val dbt = parser.parse("\$SDDBT,41.0,f,12.5,M,6.8,F")
        assertEquals(12.5, dbt.values.num(Paths.DEPTH), 1e-9)

        val mtw = parser.parse("\$IIMTW,16.5,C")
        assertEquals(289.65, mtw.values.num(Paths.WATER_TEMP), 1e-9)
    }

    @Test
    fun `parses apparent and true wind`() {
        val mwv = parser.parse("\$IIMWV,045,R,10.5,N,A")
        assertEquals(Math.toRadians(45.0), mwv.values.num(Paths.WIND_ANGLE_APPARENT), 1e-9)
        assertEquals(10.5 * 0.514444, mwv.values.num(Paths.WIND_SPEED_APPARENT), 1e-6)

        val trueWind = parser.parse("\$IIMWV,210,T,12.0,N,A")
        assertEquals(Math.toRadians(210.0), trueWind.values.num(Paths.WIND_DIRECTION_TRUE), 1e-9)
        assertEquals(12.0 * 0.514444, trueWind.values.num(Paths.WIND_SPEED_TRUE), 1e-6)

        val vwr = parser.parse("\$IIVWR,030,L,08.0,N,,,")
        assertEquals(-Math.toRadians(30.0), vwr.values.num(Paths.WIND_ANGLE_APPARENT), 1e-9)
        assertEquals(8.0 * 0.514444, vwr.values.num(Paths.WIND_SPEED_APPARENT), 1e-6)
    }

    @Test
    fun `parses MDA pressure temperatures and wind`() {
        val result = parser.parse(
            "\$IIMDA,29.85,I,1.013,B,21.5,C,18.2,C,60.0,40.0,12.0,C,210.0,T,205.0,M,12.0,N,6.2,M",
        )
        assertEquals(101_300.0, result.values.num(Paths.PRESSURE), 1e-6)
        assertEquals(294.65, result.values.num(Paths.AIR_TEMP), 1e-9)
        assertEquals(291.35, result.values.num(Paths.WATER_TEMP), 1e-9)
        assertEquals(Math.toRadians(210.0), result.values.num(Paths.WIND_DIRECTION_TRUE), 1e-9)
        assertEquals(6.2, result.values.num(Paths.WIND_SPEED_TRUE), 1e-6)
    }

    @Test
    fun `parses route XTE and waypoint data`() {
        val result = parser.parse(
            "\$GPRMB,A,0.10,R,WP1,WP2,4318.000,N,00257.000,W,2.50,090.0,5.0,A",
        )
        assertEquals(185.2, result.values.num(Paths.XTE), 1e-6)
        assertEquals(2.50 * 1852.0, result.values.num(Paths.DTW), 1e-6)
        assertEquals(Math.toRadians(90.0), result.values.num(Paths.BTW), 1e-9)
        assertEquals(5.0 * 0.514444, result.values.num(Paths.VMG), 1e-6)
    }

    @Test
    fun `parses standard APB bearing and cross track error`() {
        val sentence = Nmea0183Writer.sentence(
            "APB",
            "A", "A", "0.10", "R", "N", "V", "V",
            "090.0", "T", "WP1", "091.5", "T", "091.5", "T",
        )
        val result = parser.parse(sentence)
        assertEquals(true, result.checksumOk)
        assertEquals("APB", result.type)
        assertEquals(185.2, result.values.num(Paths.XTE), 1e-6)
        assertEquals(Math.toRadians(91.5), result.values.num(Paths.BTW), 1e-9)
    }

    @Test
    fun `rejects a bad checksum`() {
        val result = parser.parse(
            "\$GPRMC,123519,A,4807.038,N,01131.000,E,022.4,084.4,230394,003.1,W*00",
        )
        assertEquals(false, result.checksumOk)
        assertTrue(result.values.isEmpty())
        assertNotNull(result.error)
    }

    @Test
    fun `accepts sentences without checksum`() {
        val result = parser.parse("\$GPRMC,123519,A,4807.038,N,01131.000,E,022.4,084.4,230394,003.1,W")
        assertNull(result.checksumOk)
        assertTrue(result.values.isNotEmpty())
    }

    @Test
    fun `decodes AIS type 1 position`() {
        val result = parser.parse("!AIVDM,1,1,,A,139Lg1h011wjOgPHidH8=6T00000,0*60")
        assertEquals(1, result.ais.size)
        val delta = result.ais.first()
        assertEquals("vessels.urn:mrn:imo:mmsi:211234567", delta.context)
        val values = delta.values()
        assertEquals(211234567.0, values.num("mmsi"), 0.0)
        assertEquals("A", (values["sensors.ais.class"] as JsonPrimitive).content)
        val (lat, lon) = values.position()
        assertEquals(43.30, lat, 1e-4)
        assertEquals(-2.95, lon, 1e-4)
        assertEquals(6.5 * 0.514444, values.num(Paths.SOG), 1e-4)
        assertEquals(Math.toRadians(210.0), values.num(Paths.COG), 1e-6)
        assertEquals(Math.toRadians(210.0), values.num(Paths.HEADING_TRUE), 1e-6)
        assertEquals("En navegación", (values[Paths.NAV_STATE] as JsonPrimitive).content)
    }

    @Test
    fun `assembles multipart AIS type 5 static data`() {
        val first = parser.parse(
            "!AIVDM,2,1,1,A,539Lg1h00001@E=C7;<pu8@T>1=@580000000016<PD886PdN;@RC0PCh000,0*07",
        )
        assertTrue(first.ais.isEmpty())
        val second = parser.parse("!AIVDM,2,2,1,A,00000000000,2*25")
        assertEquals(1, second.ais.size)
        val values = second.ais.first().values()
        assertEquals("NORDIC STAR", (values["name"] as JsonPrimitive).content)
        assertEquals("TEST123", (values["callsign"] as JsonPrimitive).content)
        assertEquals("BILBAO", (values[Paths.DESTINATION] as JsonPrimitive).content)
        assertEquals(120.0, values.num(Paths.DESIGN_LENGTH), 0.0)
        assertEquals(16.0, values.num(Paths.DESIGN_BEAM), 0.0)
        assertEquals(4.5, values.num(Paths.DESIGN_DRAFT), 1e-9)
        assertEquals("A", (values[Paths.AIS_CLASS] as JsonPrimitive).content)
        assertEquals(70.0, values.num(Paths.AIS_SHIP_TYPE), 0.0)
    }

    @Test
    fun `decodes AIS type 18 and type 24`() {
        val type18 = parser.parse("!AIVDM,1,1,,A,B39Lg200:WtV6d6<BCPBh?000000,0*24")
        assertEquals(1, type18.ais.size)
        val values18 = type18.ais.first().values()
        assertEquals("B", (values18[Paths.AIS_CLASS] as JsonPrimitive).content)
        val (lat, lon) = values18.position()
        assertEquals(43.285, lat, 1e-4)
        assertEquals(-2.975, lon, 1e-4)

        val type24a = parser.parse("!AIVDM,1,1,,A,H39Lg2A<D6098DE`D0000000000,0*66")
        assertEquals("SEA BREEZE", (type24a.ais.first().values()["name"] as JsonPrimitive).content)

        val type24b = parser.parse("!AIVDM,1,1,,A,H39Lg2DT000000031<<qq0104220,0*6A")
        val values24b = type24b.ais.first().values()
        assertEquals("CALL99", (values24b["callsign"] as JsonPrimitive).content)
        assertEquals(12.0, values24b.num(Paths.DESIGN_LENGTH), 0.0)
        assertEquals(4.0, values24b.num(Paths.DESIGN_BEAM), 0.0)
        assertEquals(36.0, values24b.num(Paths.AIS_SHIP_TYPE), 0.0)
    }

    @Test
    fun `AIVDO own report is merged as own navigation data`() {
        val result = parser.parse("!AIVDO,1,1,,A,139Lg1h011wjOgPHidH8=6T00000,0*62")
        assertTrue(result.ais.isEmpty())
        assertEquals(true, result.checksumOk)
        val (lat, lon) = result.values.position()
        assertEquals(43.30, lat, 1e-4)
        assertEquals(-2.95, lon, 1e-4)
        assertTrue(result.values.containsKey(Paths.SOG))
        assertTrue(result.values.containsKey(Paths.COG))
    }

    @Test
    fun `ignores unsupported sentences`() {
        val result = parser.parse("\$GPZDA,123519,23,03,1994,00,00")
        assertTrue(result.values.isEmpty())
        assertTrue(result.ais.isEmpty())
        assertNull(result.error)
    }

    @Test
    fun `invalid checksum from AIS is reported`() {
        val result = parser.parse("!AIVDM,1,1,,A,139Lg1h011wjOgPHidH8=6T00000,0*00")
        assertEquals(false, result.checksumOk)
        assertTrue(result.ais.isEmpty())
    }
}
