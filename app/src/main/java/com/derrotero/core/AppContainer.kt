package com.derrotero.core

import android.app.Application
import androidx.compose.runtime.staticCompositionLocalOf
import com.derrotero.data.charts.ChartRepository
import com.derrotero.data.charts.GeoBounds
import com.derrotero.data.charts.OfflineChartDownloader
import com.derrotero.data.ais.InternetAisRepository
import com.derrotero.data.nmea.Nmea0183Writer
import com.derrotero.data.nmea.NmeaOutputRepository
import com.derrotero.data.nmea.NmeaRepository
import com.derrotero.data.polar.PolarRepository
import com.derrotero.data.polar.PolarTable
import com.derrotero.data.resources.ResourceRepository
import com.derrotero.data.sd.GpxParser
import com.derrotero.data.sd.SdCardScanner
import com.derrotero.data.settings.AppSettings
import com.derrotero.data.settings.SettingsStore
import com.derrotero.data.signalk.SignalKClient
import com.derrotero.data.signalk.latitude
import com.derrotero.data.signalk.longitude
import com.derrotero.data.vessel.AisAlarmMonitor
import com.derrotero.data.vessel.AisRepository
import com.derrotero.data.vessel.AnchorMonitor
import com.derrotero.data.vessel.NavMonitor
import com.derrotero.data.vessel.NavigationRepository
import com.derrotero.data.vessel.NavigationTarget
import com.derrotero.data.vessel.TrackRepository
import com.derrotero.data.vessel.VesselRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Hand-rolled dependency container (kept small on purpose; swap for Hilt if the app grows).
 */
class AppContainer(val app: Application) {

    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS) // REST; the WebSocket client disables it
        .writeTimeout(10, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settings = SettingsStore(app)
    val signalK = SignalKClient(http, json)
    val vessel = VesselRepository(signalK, json)
    val ais = AisRepository()
    val nmea = NmeaRepository(
        onOwnValues = { values -> vessel.ingestNmea(values) },
        onAisDelta = { delta -> ais.onDelta(delta) { context -> vessel.isSelfContext(context) } },
    )
    val nmeaOutput = NmeaOutputRepository()
    val aisAlarms = AisAlarmMonitor(app)
    val anchor = AnchorMonitor(app)
    val track = TrackRepository(app)
    val navigation = NavigationRepository()
    val navMonitor = NavMonitor(app)
    val resources = ResourceRepository(signalK, json)
    val charts = ChartRepository(app, http)
    val offlineCharts = OfflineChartDownloader(http)
    val polar = PolarRepository(settings, scope)
    val mapBounds = MutableStateFlow<GeoBounds?>(null)
    val internetAis = InternetAisRepository(http, json)
    val sdScanner = SdCardScanner(app)
    val gpx = GpxParser()

    val appSettings = settings.settings.stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = AppSettings(),
    )

    private var liveJob: Job? = null

    /**
     * Restores persisted charts and starts the live connection. Safe to call repeatedly:
     * the UI calls [startLive] again when the app comes back to the foreground after the
     * user stopped it from the notification.
     */
    fun start() {
        scope.launch { charts.restore() }
        startLive()
    }

    /** (Re)connect all live feeds whenever the endpoint changes. */
    @Synchronized
    fun startLive() {
        if (liveJob?.isActive == true) return
        liveJob = scope.launch {
            launch {
                // Only connection-relevant settings should restart the stream. Previously any
                // preference change (alarms, polar CSV, night mode…) reconnected SignalK.
                settings.settings
                    .map { it.demoMode to it.signalK }
                    .distinctUntilChanged()
                    .collectLatest { (demo, cfg) ->
                        ais.clear()
                        when {
                            demo -> vessel.runDemo()
                            cfg.isValid() -> vessel.runConnection(cfg)
                        }
                    }
            }
            // NMEA 0183 over Wi-Fi (TCP client or UDP listener), independent of Signal K.
            launch {
                settings.settings
                    .map { it.nmea }
                    .distinctUntilChanged()
                    .collectLatest { nmeaCfg ->
                        if (nmeaCfg.enabled && nmeaCfg.isValid()) {
                            nmea.run(nmeaCfg)
                        } else {
                            nmea.reset()
                        }
                    }
            }
            // OpenCPN-style autopilot guidance over its own raw UDP (Signal K, which forwards
            // to the serial pilot) or TCP (direct gateway) connection. It does not depend on
            // the NMEA input or on Signal K authentication.
            launch {
                settings.settings
                    .map { it.nmea }
                    .distinctUntilChanged()
                    .collectLatest { nmeaCfg ->
                        if (nmeaCfg.sendsToAutopilot) {
                            nmeaOutput.run(nmeaCfg)
                        } else {
                            nmeaOutput.reset()
                        }
                    }
            }
            launch {
                while (isActive) {
                    delay(NMEA_OUTPUT_PERIOD_MS)
                    val cfg = appSettings.value.nmea
                    if (!cfg.sendsToAutopilot) continue
                    val target = navigation.target.value ?: continue
                    nmeaOutput.send(
                        Nmea0183Writer.sentences(
                            own = vessel.state.value,
                            target = target,
                            guidance = navMonitor.state.value,
                        ),
                    )
                }
            }
            // Feed non-self deltas into the AIS tracker. The self context is a URN on real
            // servers, so the repository supplies the predicate instead of comparing strings.
            launch {
                vessel.deltas.collect { delta ->
                    ais.onDelta(delta) { context -> vessel.isSelfContext(context) }
                }
            }
            // Forget contacts that stopped transmitting.
            launch {
                while (isActive) {
                    delay(20_000)
                    ais.expireOlderThan(AIS_TTL_MS)
                }
            }
            // Evaluate CPA/TCPA thresholds against the configured alarm settings.
            launch {
                combine(vessel.state, ais.targets, appSettings) { own, targets, cfg ->
                    Triple(own, targets, cfg)
                }.collect { (own, targets, cfg) ->
                    aisAlarms.evaluate(own, targets, cfg)
                }
            }
            // Route guidance: DTW/BTW/XTE/VMG/ETA, waypoint arrival / off-track alarms and
            // the tack recommendation for destinations inside the no-go cone.
            launch {
                combine(
                    vessel.state,
                    navigation.target,
                    appSettings,
                    polar.table,
                ) { own, target, cfg, polarTable ->
                    NavEvaluation(own, target, cfg, polarTable)
                }.collect { input ->
                    navMonitor.evaluate(input.own, input.target, input.cfg, input.polar)
                }
            }
            // Anchor watch.
            launch {
                combine(vessel.state, appSettings) { own, cfg -> own to cfg }
                    .collect { (own, cfg) -> anchor.evaluate(own, cfg) }
            }
            // Persistent track ("estela").
            launch {
                vessel.state.collect { own ->
                    val lat = own.latitude()
                    val lon = own.longitude()
                    if (lat != null && lon != null) track.add(lat, lon)
                }
            }
            // Internet AIS for the visible map area.
            launch {
                internetAis.run(mapBounds, appSettings, this@AppContainer.scope)
            }
        }
    }

    /**
     * Stops every network/background feed. Called by the notification's "Parar" button so the
     * app really goes quiet instead of keeping the SignalK socket, NMEA and AIS alive.
     */
    @Synchronized
    fun stopLive() {
        liveJob?.cancel()
        liveJob = null
        nmea.reset()
        nmeaOutput.reset()
        ais.clear()
        vessel.markDisconnected()
    }

    /** Stops the feeds and remembers that background operation is no longer wanted. */
    fun stopBackground() {
        stopLive()
        scope.launch { runCatching { settings.setKeepAlive(false) } }
    }

    companion object {
        private const val AIS_TTL_MS = 5 * 60_000L
        private const val NMEA_OUTPUT_PERIOD_MS = 1_000L
    }
}

/** Snapshot of the four flows the navigation monitor needs, combined for every evaluation. */
private data class NavEvaluation(
    val own: Map<String, JsonElement>,
    val target: NavigationTarget?,
    val cfg: AppSettings,
    val polar: PolarTable,
)

val LocalContainer = staticCompositionLocalOf<AppContainer> {
    error("AppContainer not provided")
}
