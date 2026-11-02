package com.derrotero.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore("derrotero-settings")

/**
 * SignalK server endpoint.
 *
 * [host] accepts a bare IP/hostname, a full URL (`http://192.168.1.10:3000`) or a pasted API
 * address (`http://host:3000/signalk/v1/api`). [httpBase]/[wsBase] normalise all of them, which
 * used to break when the user pasted a complete URL into the host field.
 */
data class SignalKConfig(
    val host: String = "192.168.1.10",
    val port: Int = 3000,
    val useTls: Boolean = false,
    val context: String = "vessels.self",
    /** Optional SignalK access token (long-lived bearer token). */
    val token: String = "",
) {
    val httpBase: String get() = origin(if (useTls) "https" else "http")
    val wsBase: String get() = origin(if (useTls) "wss" else "ws")

    private fun origin(scheme: String): String {
        var raw = host.trim()
        if (raw.isEmpty()) return "$scheme://127.0.0.1:$port"

        raw = raw.substringAfter("://", raw)
        raw = raw.substringBefore('?').trimEnd('/')
        raw = raw.substringBefore("/signalk").trimEnd('/')
        if (raw.isEmpty()) return "$scheme://127.0.0.1:$port"

        val embeddedPort = Regex(":(\\d{1,5})$").find(raw)?.groupValues?.get(1)?.toIntOrNull()
        val effectivePort = embeddedPort ?: port
        val hostOnly = if (embeddedPort != null) raw.substringBeforeLast(':') else raw
        val defaultPort = (scheme == "http" && effectivePort == 80) ||
            (scheme == "https" && effectivePort == 443) ||
            (scheme == "ws" && effectivePort == 80) ||
            (scheme == "wss" && effectivePort == 443)
        return if (defaultPort) "$scheme://$hostOnly" else "$scheme://$hostOnly:$effectivePort"
    }

    fun isValid(): Boolean {
        val trimmed = host.trim()
        return trimmed.isNotEmpty() && port in 1..65535 && !trimmed.contains(' ')
    }
}

/** AIS proximity alarm rules. */
data class AlarmSettings(
    val enabled: Boolean = false,
    val cpaMeters: Double = 500.0,
    val tcpaMinutes: Double = 10.0,
    /** Any contact inside this radius raises an alarm regardless of CPA. */
    val radiusMeters: Double = 250.0,
    /** Only consider class A transponders. */
    val onlyClassA: Boolean = false,
    /** Ignore targets slower than this (kn); 0 disables the filter. */
    val minSpeedKn: Double = 0.0,
)

/** Internet AIS providers. */
enum class InternetAisProvider { AISSTREAM, AISHUB }

/** NMEA 0183 connection over Wi-Fi. UDP listens on [port] for input and sends to host:port for output. */
enum class NmeaTransport { TCP, UDP }

data class NmeaSettings(
    val enabled: Boolean = false,
    val transport: NmeaTransport = NmeaTransport.TCP,
    val host: String = "192.168.1.10",
    val port: Int = 10110,
    /**
     * Emit OpenCPN-style APB/RMB/RMC/XTE route guidance to the autopilot. The output has its
     * own connection, so it works with Signal K as the data source and does not require
     * the NMEA input to be enabled.
     */
    val sendNavigation: Boolean = false,
    /**
     * Outbound transport. UDP is the Signal K path: the server receives the sentences and
     * forwards them to the autopilot serial port. TCP connects directly to a gateway.
     */
    val outputTransport: NmeaTransport = NmeaTransport.UDP,
    /** Signal K server or gateway destination. TCP output may inherit the input endpoint. */
    val outputHost: String = "",
    /** Signal K/gateway UDP/TCP port. TCP output may inherit the input port. */
    val outputPort: Int = 0,
) {
    fun isValid(): Boolean =
        port in 1..65535 &&
            (transport == NmeaTransport.UDP || (host.isNotBlank() && !host.contains(' ')))

    /** Endpoint used for autopilot output. UDP requires an explicit destination host. */
    val effectiveOutputHost: String
        get() = outputHost.trim().ifBlank {
            if (outputTransport == NmeaTransport.TCP) host.trim() else ""
        }

    val effectiveOutputPort: Int
        get() = if (outputPort in 1..65535) {
            outputPort
        } else if (outputTransport == NmeaTransport.TCP) {
            port
        } else {
            0
        }

    val outputValid: Boolean
        get() = effectiveOutputHost.isNotBlank() &&
            !effectiveOutputHost.contains(' ') &&
            effectiveOutputPort in 1..65535

    /** True when the configured UDP/TCP link can carry navigation guidance. */
    val sendsToAutopilot: Boolean
        get() = sendNavigation && outputValid
}

data class InternetAisSettings(
    val enabled: Boolean = false,
    val provider: InternetAisProvider = InternetAisProvider.AISSTREAM,
    /** aisstream.io API key. */
    val apiKey: String = "",
    /** AISHub username (free, requires contributing an AIS feed). */
    val username: String = "",
)

/**
 * OpenCPN-style route guidance: arrival radius, cross-track alarm and automatic advance to
 * the next waypoint of the active route.
 */
data class NavigationSettings(
    val alarmsEnabled: Boolean = true,
    val arrivalRadiusMeters: Double = 60.0,
    val xteAlarmMeters: Double = 120.0,
    val autoAdvance: Boolean = true,
)

/** Night mode dimmer (0 = off, 0.75 = very dark) and chart overlays. */
data class DisplaySettings(
    val nightDim: Float = 0f,
    val aisVectors: Boolean = true,
    val showAis: Boolean = true,
    val showWaypoints: Boolean = true,
    val showRoute: Boolean = true,
    val showTrack: Boolean = true,
    val showHeading: Boolean = true,
    val showCurrent: Boolean = true,
    val showArrivalRing: Boolean = true,
    val courseUp: Boolean = false,
    val followBoat: Boolean = false,
    val showScaleBar: Boolean = true,
    val showCompass: Boolean = true,
)

/** Man-overboard mark. */
data class MobMarker(
    val lat: Double,
    val lon: Double,
    val atMs: Long,
)

data class AppSettings(
    val signalK: SignalKConfig = SignalKConfig(),
    val activeChartId: String? = null,
    val sdcardTreeUri: String? = null,
    val keepAlive: Boolean = false,
    val alarms: AlarmSettings = AlarmSettings(),
    /** Generates synthetic data locally instead of connecting to a server. */
    val demoMode: Boolean = false,
    /** User-imported polar table as raw CSV (null = built-in default). */
    val polarCsv: String? = null,
    val anchor: AnchorSettings = AnchorSettings(),
    val internetAis: InternetAisSettings = InternetAisSettings(),
    val navigation: NavigationSettings = NavigationSettings(),
    val display: DisplaySettings = DisplaySettings(),
    val mob: MobMarker? = null,
    val nmea: NmeaSettings = NmeaSettings(),
    /** The user has acknowledged the safety and non-affiliation notice. */
    val disclaimerAccepted: Boolean = false,
)

/** Anchor watch: alarm when the vessel drifts outside [radiusMeters] of the anchor point. */
data class AnchorSettings(
    val enabled: Boolean = false,
    val lat: Double? = null,
    val lon: Double? = null,
    val radiusMeters: Double = 30.0,
)

class SettingsStore(private val context: Context) {

    private object Keys {
        val host = stringPreferencesKey("sk_host")
        val port = intPreferencesKey("sk_port")
        val tls = booleanPreferencesKey("sk_tls")
        val context = stringPreferencesKey("sk_context")
        val token = stringPreferencesKey("sk_token")
        val activeChart = stringPreferencesKey("active_chart")
        val sdcard = stringPreferencesKey("sdcard_uri")
        val keepAlive = booleanPreferencesKey("keep_alive")
        val alarmEnabled = booleanPreferencesKey("alarm_enabled")
        val alarmCpa = floatPreferencesKey("alarm_cpa_m")
        val alarmTcpa = floatPreferencesKey("alarm_tcpa_min")
        val alarmRadius = floatPreferencesKey("alarm_radius_m")
        val alarmOnlyA = booleanPreferencesKey("alarm_only_class_a")
        val alarmMinSpeed = floatPreferencesKey("alarm_min_speed_kn")
        val demoMode = booleanPreferencesKey("demo_mode")
        val polarCsv = stringPreferencesKey("polar_csv")
        val anchorEnabled = booleanPreferencesKey("anchor_enabled")
        val anchorLat = stringPreferencesKey("anchor_lat")
        val anchorLon = stringPreferencesKey("anchor_lon")
        val anchorRadius = floatPreferencesKey("anchor_radius_m")
        val internetAisEnabled = booleanPreferencesKey("internet_ais_enabled")
        val internetAisKey = stringPreferencesKey("internet_ais_key")
        val internetAisProvider = stringPreferencesKey("internet_ais_provider")
        val internetAisUser = stringPreferencesKey("internet_ais_user")
        val nmeaEnabled = booleanPreferencesKey("nmea_enabled")
        val nmeaTransport = stringPreferencesKey("nmea_transport")
        val nmeaHost = stringPreferencesKey("nmea_host")
        val nmeaPort = intPreferencesKey("nmea_port")
        val nmeaSendNavigation = booleanPreferencesKey("nmea_send_navigation")
        val nmeaOutputTransport = stringPreferencesKey("nmea_output_transport")
        val nmeaOutputHost = stringPreferencesKey("nmea_output_host")
        val nmeaOutputPort = intPreferencesKey("nmea_output_port")
        val navAlarms = booleanPreferencesKey("nav_alarms")
        val navArrival = floatPreferencesKey("nav_arrival_m")
        val navXte = floatPreferencesKey("nav_xte_m")
        val navAutoAdvance = booleanPreferencesKey("nav_auto_advance")
        val nightDim = floatPreferencesKey("night_dim")
        val aisVectors = booleanPreferencesKey("ais_vectors")
        val showAis = booleanPreferencesKey("show_ais")
        val showWaypoints = booleanPreferencesKey("show_waypoints")
        val showRoute = booleanPreferencesKey("show_route")
        val showTrack = booleanPreferencesKey("show_track")
        val showHeading = booleanPreferencesKey("show_heading")
        val showCurrent = booleanPreferencesKey("show_current")
        val showArrivalRing = booleanPreferencesKey("show_arrival_ring")
        val courseUp = booleanPreferencesKey("course_up")
        val followBoat = booleanPreferencesKey("follow_boat")
        val showScaleBar = booleanPreferencesKey("show_scale_bar")
        val showCompass = booleanPreferencesKey("show_compass")
        val mobLat = stringPreferencesKey("mob_lat")
        val mobLon = stringPreferencesKey("mob_lon")
        val mobAt = stringPreferencesKey("mob_at")
        val disclaimerAccepted = booleanPreferencesKey("disclaimer_accepted")
    }

    val settings: Flow<AppSettings> = context.settingsDataStore.data.map { p ->
        // 0.9.3 had no output transport: it always used TCP and allowed an empty output
        // endpoint to inherit the NMEA input. When upgrading, carry that endpoint over so
        // the new UDP default still has a destination to send to.
        val savedOutputTransport = p[Keys.nmeaOutputTransport]
        val outputTransport = runCatching {
            NmeaTransport.valueOf(savedOutputTransport ?: "UDP")
        }.getOrDefault(NmeaTransport.UDP)
        val inheritLegacyTcpOutput = savedOutputTransport == null &&
            runCatching {
                NmeaTransport.valueOf(p[Keys.nmeaTransport] ?: "TCP")
            }.getOrDefault(NmeaTransport.TCP) == NmeaTransport.TCP

        AppSettings(
            signalK = SignalKConfig(
                host = p[Keys.host] ?: SignalKConfig().host,
                port = p[Keys.port] ?: SignalKConfig().port,
                useTls = p[Keys.tls] ?: false,
                context = p[Keys.context] ?: "vessels.self",
                token = p[Keys.token] ?: "",
            ),
            activeChartId = p[Keys.activeChart],
            sdcardTreeUri = p[Keys.sdcard],
            keepAlive = p[Keys.keepAlive] ?: false,
            alarms = AlarmSettings(
                enabled = p[Keys.alarmEnabled] ?: false,
                cpaMeters = (p[Keys.alarmCpa] ?: 500f).toDouble(),
                tcpaMinutes = (p[Keys.alarmTcpa] ?: 10f).toDouble(),
                radiusMeters = (p[Keys.alarmRadius] ?: 250f).toDouble(),
                onlyClassA = p[Keys.alarmOnlyA] ?: false,
                minSpeedKn = (p[Keys.alarmMinSpeed] ?: 0f).toDouble(),
            ),
            demoMode = p[Keys.demoMode] ?: false,
            polarCsv = p[Keys.polarCsv],
            anchor = AnchorSettings(
                enabled = p[Keys.anchorEnabled] ?: false,
                lat = p[Keys.anchorLat]?.toDoubleOrNull(),
                lon = p[Keys.anchorLon]?.toDoubleOrNull(),
                radiusMeters = (p[Keys.anchorRadius] ?: 30f).toDouble(),
            ),
            internetAis = InternetAisSettings(
                enabled = p[Keys.internetAisEnabled] ?: false,
                provider = runCatching {
                    InternetAisProvider.valueOf(p[Keys.internetAisProvider] ?: "AISSTREAM")
                }.getOrDefault(InternetAisProvider.AISSTREAM),
                apiKey = p[Keys.internetAisKey] ?: "",
                username = p[Keys.internetAisUser] ?: "",
            ),
            navigation = NavigationSettings(
                alarmsEnabled = p[Keys.navAlarms] ?: true,
                arrivalRadiusMeters = (p[Keys.navArrival] ?: 60f).toDouble(),
                xteAlarmMeters = (p[Keys.navXte] ?: 120f).toDouble(),
                autoAdvance = p[Keys.navAutoAdvance] ?: true,
            ),
            display = DisplaySettings(
                nightDim = p[Keys.nightDim] ?: 0f,
                aisVectors = p[Keys.aisVectors] ?: true,
                showAis = p[Keys.showAis] ?: true,
                showWaypoints = p[Keys.showWaypoints] ?: true,
                showRoute = p[Keys.showRoute] ?: true,
                showTrack = p[Keys.showTrack] ?: true,
                showHeading = p[Keys.showHeading] ?: true,
                showCurrent = p[Keys.showCurrent] ?: true,
                showArrivalRing = p[Keys.showArrivalRing] ?: true,
                courseUp = p[Keys.courseUp] ?: false,
                followBoat = p[Keys.followBoat] ?: false,
                showScaleBar = p[Keys.showScaleBar] ?: true,
                showCompass = p[Keys.showCompass] ?: true,
            ),
            mob = runCatching {
                val lat = p[Keys.mobLat]?.toDoubleOrNull()
                val lon = p[Keys.mobLon]?.toDoubleOrNull()
                if (lat != null && lon != null) {
                    MobMarker(lat, lon, p[Keys.mobAt]?.toLongOrNull() ?: 0L)
                } else {
                    null
                }
            }.getOrNull(),
            nmea = NmeaSettings(
                enabled = p[Keys.nmeaEnabled] ?: false,
                transport = runCatching {
                    NmeaTransport.valueOf(p[Keys.nmeaTransport] ?: "TCP")
                }.getOrDefault(NmeaTransport.TCP),
                host = p[Keys.nmeaHost] ?: NmeaSettings().host,
                port = p[Keys.nmeaPort] ?: 10110,
                sendNavigation = p[Keys.nmeaSendNavigation] ?: false,
                outputTransport = outputTransport,
                outputHost = p[Keys.nmeaOutputHost]
                    ?: if (inheritLegacyTcpOutput) p[Keys.nmeaHost] ?: "" else "",
                outputPort = p[Keys.nmeaOutputPort]
                    ?: if (inheritLegacyTcpOutput) p[Keys.nmeaPort] ?: 0 else 0,
            ),
            disclaimerAccepted = p[Keys.disclaimerAccepted] ?: false,
        )
    }

    suspend fun setDisclaimerAccepted(accepted: Boolean) {
        context.settingsDataStore.edit { p -> p[Keys.disclaimerAccepted] = accepted }
    }

    suspend fun setServer(host: String, port: Int, useTls: Boolean, contextPath: String) {
        context.settingsDataStore.edit { p ->
            p[Keys.host] = host.trim()
            p[Keys.port] = port
            p[Keys.tls] = useTls
            p[Keys.context] = contextPath.trim().ifBlank { "vessels.self" }
        }
    }

    suspend fun setToken(token: String) {
        context.settingsDataStore.edit { p ->
            if (token.isBlank()) p.remove(Keys.token) else p[Keys.token] = token.trim()
        }
    }

    suspend fun setActiveChart(id: String?) {
        context.settingsDataStore.edit { p ->
            if (id == null) p.remove(Keys.activeChart) else p[Keys.activeChart] = id
        }
    }

    suspend fun setSdcardTreeUri(uri: String?) {
        context.settingsDataStore.edit { p ->
            if (uri == null) p.remove(Keys.sdcard) else p[Keys.sdcard] = uri
        }
    }

    suspend fun setKeepAlive(enabled: Boolean) {
        context.settingsDataStore.edit { p -> p[Keys.keepAlive] = enabled }
    }

    suspend fun setDemoMode(enabled: Boolean) {
        context.settingsDataStore.edit { p -> p[Keys.demoMode] = enabled }
    }

    suspend fun setPolarCsv(csv: String?) {
        context.settingsDataStore.edit { p ->
            if (csv == null) p.remove(Keys.polarCsv) else p[Keys.polarCsv] = csv
        }
    }

    suspend fun setAnchor(enabled: Boolean, lat: Double?, lon: Double?, radiusMeters: Double) {
        context.settingsDataStore.edit { p ->
            p[Keys.anchorEnabled] = enabled
            p[Keys.anchorRadius] = radiusMeters.toFloat()
            if (lat == null || lon == null) {
                p.remove(Keys.anchorLat)
                p.remove(Keys.anchorLon)
            } else {
                p[Keys.anchorLat] = lat.toString()
                p[Keys.anchorLon] = lon.toString()
            }
        }
    }

    suspend fun setInternetAis(
        enabled: Boolean,
        provider: InternetAisProvider,
        apiKey: String,
        username: String,
    ) {
        context.settingsDataStore.edit { p ->
            p[Keys.internetAisEnabled] = enabled
            p[Keys.internetAisProvider] = provider.name
            p[Keys.internetAisKey] = apiKey.trim()
            p[Keys.internetAisUser] = username.trim()
        }
    }

    suspend fun setAlarms(
        enabled: Boolean,
        cpaMeters: Double,
        tcpaMinutes: Double,
        radiusMeters: Double,
        onlyClassA: Boolean,
        minSpeedKn: Double,
    ) {
        context.settingsDataStore.edit { p ->
            p[Keys.alarmEnabled] = enabled
            p[Keys.alarmCpa] = cpaMeters.toFloat()
            p[Keys.alarmTcpa] = tcpaMinutes.toFloat()
            p[Keys.alarmRadius] = radiusMeters.toFloat()
            p[Keys.alarmOnlyA] = onlyClassA
            p[Keys.alarmMinSpeed] = minSpeedKn.toFloat()
        }
    }

    suspend fun setNavigation(
        alarmsEnabled: Boolean,
        arrivalRadiusMeters: Double,
        xteAlarmMeters: Double,
        autoAdvance: Boolean,
    ) {
        context.settingsDataStore.edit { p ->
            p[Keys.navAlarms] = alarmsEnabled
            p[Keys.navArrival] = arrivalRadiusMeters.toFloat()
            p[Keys.navXte] = xteAlarmMeters.toFloat()
            p[Keys.navAutoAdvance] = autoAdvance
        }
    }

    suspend fun setDisplay(display: DisplaySettings) {
        context.settingsDataStore.edit { p ->
            p[Keys.nightDim] = display.nightDim.coerceIn(0f, 0.8f)
            p[Keys.aisVectors] = display.aisVectors
            p[Keys.showAis] = display.showAis
            p[Keys.showWaypoints] = display.showWaypoints
            p[Keys.showRoute] = display.showRoute
            p[Keys.showTrack] = display.showTrack
            p[Keys.showHeading] = display.showHeading
            p[Keys.showCurrent] = display.showCurrent
            p[Keys.showArrivalRing] = display.showArrivalRing
            p[Keys.courseUp] = display.courseUp
            p[Keys.followBoat] = display.followBoat
            p[Keys.showScaleBar] = display.showScaleBar
            p[Keys.showCompass] = display.showCompass
        }
    }

    suspend fun setMob(lat: Double, lon: Double, atMs: Long = System.currentTimeMillis()) {
        context.settingsDataStore.edit { p ->
            p[Keys.mobLat] = lat.toString()
            p[Keys.mobLon] = lon.toString()
            p[Keys.mobAt] = atMs.toString()
        }
    }

    suspend fun clearMob() {
        context.settingsDataStore.edit { p ->
            p.remove(Keys.mobLat)
            p.remove(Keys.mobLon)
            p.remove(Keys.mobAt)
        }
    }

    suspend fun setNmea(
        enabled: Boolean,
        transport: NmeaTransport,
        host: String,
        port: Int,
        sendNavigation: Boolean = false,
        outputTransport: NmeaTransport = NmeaTransport.UDP,
        outputHost: String = "",
        outputPort: Int = 0,
    ) {
        context.settingsDataStore.edit { p ->
            p[Keys.nmeaEnabled] = enabled
            p[Keys.nmeaTransport] = transport.name
            p[Keys.nmeaHost] = host.trim()
            p[Keys.nmeaPort] = port
            p[Keys.nmeaSendNavigation] = sendNavigation
            p[Keys.nmeaOutputTransport] = outputTransport.name
            val outputHostTrimmed = outputHost.trim()
            if (outputHostTrimmed.isBlank()) {
                p.remove(Keys.nmeaOutputHost)
            } else {
                p[Keys.nmeaOutputHost] = outputHostTrimmed
            }
            if (outputPort in 1..65535) {
                p[Keys.nmeaOutputPort] = outputPort
            } else {
                p.remove(Keys.nmeaOutputPort)
            }
        }
    }
}
