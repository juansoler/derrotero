package com.derrotero.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.derrotero.core.AppContainer
import com.derrotero.data.charts.ChartSource
import com.derrotero.data.charts.ChartKind
import com.derrotero.data.charts.DefaultCharts
import com.derrotero.data.charts.GeoBounds
import com.derrotero.data.charts.OfflineChartDownloader
import com.derrotero.data.demo.DemoData
import com.derrotero.data.resources.Position
import com.derrotero.data.resources.Route
import com.derrotero.data.resources.Waypoint
import com.derrotero.data.sd.GpxParser
import com.derrotero.data.sd.GpxWriter
import com.derrotero.data.sd.SdScanResult
import com.derrotero.data.settings.NmeaTransport
import com.derrotero.data.signalk.Paths
import com.derrotero.data.signalk.angle
import com.derrotero.data.signalk.latitude
import com.derrotero.data.signalk.longitude
import com.derrotero.data.vessel.NavigationTarget
import com.derrotero.data.vessel.SignalKStatus
import com.derrotero.service.ServiceController
import com.derrotero.util.Geo
import com.derrotero.util.LatLon
import com.derrotero.util.WindMath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

class NavViewModel(private val container: AppContainer) : ViewModel() {

    val settings = container.appSettings
    val vesselState = container.vessel.state
    val status = container.vessel.status
    val chartSources = container.charts.sources
    val serviceRunning = ServiceController.running
    val aisTargets = container.ais.targets
    val aisAlarms = container.aisAlarms.alarms
    val polarTable = container.polar.table
    val anchorState = container.anchor.state
    val mapBounds = container.mapBounds
    val internetAisTargets = container.internetAis.targets
    val internetAisStatus = container.internetAis.status
    val internetAisMessages = container.internetAis.messageCount
    val internetAisLastMessage = container.internetAis.lastMessageAt
    val internetAisError = container.internetAis.lastError
    val nmeaStatus = container.nmea.status
    val nmeaOutputStatus = container.nmeaOutput.status

    /** Resolved own-vessel context (a URN on real servers) and live data diagnostics. */
    val selfContext = container.vessel.selfContext
    val selfMmsi = container.vessel.selfMmsi
    val lastUpdate = container.vessel.lastUpdate
    val diagnostics = container.vessel.diagnostics

    /** Destination / active route waypoint, shared with the guidance monitor and map. */
    val navTarget = container.navigation.target
    val activeRouteId = container.navigation.activeRouteId
    val navGuidance = container.navMonitor.state

    private val _mapFocus = MutableStateFlow<MapFocus?>(null)
    val mapFocus = _mapFocus.asStateFlow()

    fun focusAis(lat: Double, lon: Double) {
        _mapFocus.value = MapFocus(lat, lon, 14.0)
    }

    fun focusMap(lat: Double, lon: Double, zoom: Double = 13.0) {
        _mapFocus.value = MapFocus(lat, lon, zoom)
    }

    fun clearMapFocus() {
        _mapFocus.value = null
    }

    /** Man-overboard mark (null when not set). */
    val mob = container.appSettings.map { it.mob }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = container.appSettings.value.mob,
    )

    fun clearNavTarget() = viewModelScope.launch {
        container.navigation.clear()
        if (!settings.value.demoMode) {
            runCatching { withContext(Dispatchers.IO) {
                container.resources.cancelActiveRoute(settings.value.signalK)
            } }
        }
    }

    private fun maybeAdvance(snapshot: AdvanceSnapshot) {
        val target = snapshot.target ?: return
        val cfg = snapshot.cfg
        if (!cfg.navigation.autoAdvance) return
        val lat = snapshot.own.latitude() ?: return
        val lon = snapshot.own.longitude() ?: return
        val distance = Geo.distanceMeters(LatLon(lat, lon), LatLon(target.lat, target.lon))
        if (distance > cfg.navigation.arrivalRadiusMeters) return

        val routeId = target.routeId ?: return
        val route = snapshot.routes.firstOrNull { it.id == routeId } ?: return
        val index = target.waypointIndex ?: return
        val nextIndex = index + 1
        if (nextIndex in route.waypoints.indices) {
            val next = route.waypoints[nextIndex]
            container.navigation.setTarget(
                NavigationTarget(
                    lat = next.position.latitude,
                    lon = next.position.longitude,
                    name = next.name ?: "WP ${nextIndex + 1}",
                    fromLat = target.lat,
                    fromLon = target.lon,
                    routeId = routeId,
                    waypointIndex = nextIndex,
                ),
            )
            showMessage("Waypoint ${index + 1} alcanzado · siguiente: ${next.name ?: nextIndex + 1}")
        } else {
            container.navigation.setTarget(null)
            showMessage("Ruta completada: ${route.name ?: ""}")
        }
    }

    val trackPoints = container.track.points
    val trackEnabled = container.track.enabled

    fun setTrackEnabled(enabled: Boolean) = viewModelScope.launch {
        container.track.setEnabled(enabled)
        if (enabled) {
            // Turning recording on also makes the trail visible and drops a first point
            // immediately, so the button gives feedback even before the boat moves.
            container.settings.setDisplay(settings.value.display.copy(showTrack = true))
            val own = container.vessel.state.value
            val lat = own.latitude()
            val lon = own.longitude()
            if (lat != null && lon != null) {
                container.track.add(lat, lon, force = true)
                showMessage("Estela: grabando desde tu posición")
            } else {
                showMessage("Estela: grabando; esperando posición GPS")
            }
        } else {
            showMessage("Estela: grabación pausada (la traza sigue visible)")
        }
    }

    fun clearTrack() {
        container.track.clear()
        showMessage("Estela borrada")
    }

    private val _downloadAreaVisible = MutableStateFlow(false)
    val downloadAreaVisible = _downloadAreaVisible.asStateFlow()

    fun setDownloadAreaVisible(visible: Boolean) { _downloadAreaVisible.value = visible }

    fun setInternetAis(
        enabled: Boolean,
        provider: com.derrotero.data.settings.InternetAisProvider,
        apiKey: String,
        username: String,
    ) = viewModelScope.launch {
        container.settings.setInternetAis(enabled, provider, apiKey, username)
        showMessage(if (enabled) "AIS por Internet activado" else "AIS por Internet desactivado")
    }

    private val _waypoints = MutableStateFlow<List<Waypoint>>(emptyList())
    val waypoints = _waypoints.asStateFlow()

    private val _routes = MutableStateFlow<List<Route>>(emptyList())
    val routes = _routes.asStateFlow()

    private val _scan = MutableStateFlow<SdScanResult?>(null)
    val scan = _scan.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()

    data class DownloadState(
        val running: Boolean = false,
        val done: Int = 0,
        val total: Int = 0,
        val failed: Int = 0,
        val message: String? = null,
    )

    private val _download = MutableStateFlow(DownloadState())
    val download = _download.asStateFlow()
    private var downloadJob: Job? = null

    private val _serverInfo = MutableStateFlow<String?>(null)
    val serverInfo = _serverInfo.asStateFlow()

    init {
        // All coroutines start here, after every property above has been initialised:
        // StateFlow.collect emits synchronously on the main dispatcher, and starting it
        // earlier made refreshResources() touch not-yet-created flows (NPE).
        viewModelScope.launch {
            // OpenCPN-style route following: when the active waypoint is reached, move the
            // guidance to the next leg (and notify) instead of leaving a stale destination.
            combine(vesselState, container.navigation.target, settings, _routes) { own, target, cfg, routes ->
                AdvanceSnapshot(own, target, cfg, routes)
            }
                .distinctUntilChanged()
                .collect { maybeAdvance(it) }
        }
        viewModelScope.launch {
            // Load waypoints/routes as soon as the stream is alive (including after the user
            // configures the server, when no manual "Recargar" was pressed).
            container.vessel.status.collect { status ->
                if (status is SignalKStatus.Streaming) refreshResources()
            }
        }
    }

    fun testConnection() = viewModelScope.launch {
        val cfg = settings.value.signalK
        _serverInfo.value = "Probando ${cfg.httpBase}…"
        runCatching {
            withContext(Dispatchers.IO) {
                val info = container.signalK.serverInfo(cfg)
                val self = runCatching { container.signalK.selfContext(cfg) }.getOrNull()
                val model = container.signalK.probe(cfg, "signalk/v1/api/vessels/self")
                Triple(info, self, model)
            }
        }.onSuccess { (info, self, model) ->
            _serverInfo.value = buildString {
                append("SignalK ${info.server?.version ?: "?"} · id ${info.server?.id ?: "?"}")
                append("\nBase: ${cfg.httpBase}")
                append("\nSelf: ${self ?: "no resuelto (se usará el contexto configurado)"}")
                append("\nModelo: ")
                append(model.fold({ "OK (${it.length} caracteres)" }, { "ERROR: ${it.message}" }))
            }
        }.onFailure {
            _serverInfo.value = "Error: ${it.message}"
        }
    }

    fun showMessage(text: String) { _message.value = text }
    fun clearMessage() { _message.value = null }

    // ------------------------------------------------------------------ settings

    fun saveServer(
        host: String,
        port: Int,
        tls: Boolean,
        context: String,
        token: String? = null,
    ) = viewModelScope.launch {
        container.settings.setServer(host, port, tls, context)
        token?.let { container.settings.setToken(it) }
        showMessage("Servidor guardado: $host:$port")
    }

    fun setAuthToken(token: String) = viewModelScope.launch {
        container.settings.setToken(token)
        showMessage(if (token.isBlank()) "Token borrado" else "Token guardado")
    }

    fun setNmea(
        enabled: Boolean,
        transport: NmeaTransport,
        host: String,
        port: Int,
        sendNavigation: Boolean = false,
        outputTransport: NmeaTransport = NmeaTransport.UDP,
        outputHost: String = "",
        outputPort: Int = 0,
    ) = viewModelScope.launch {
        container.settings.setNmea(
            enabled,
            transport,
            host,
            port,
            sendNavigation,
            outputTransport,
            outputHost,
            outputPort,
        )
        val input = if (transport == NmeaTransport.UDP) "UDP :$port" else "$host:$port"
        val outHost = outputHost.trim().ifBlank {
            if (outputTransport == NmeaTransport.TCP) host.trim() else ""
        }
        val outPort = if (outputPort in 1..65535) {
            outputPort
        } else if (outputTransport == NmeaTransport.TCP) {
            port
        } else {
            0
        }
        val output = if (sendNavigation) {
            if (outHost.isBlank() || outPort !in 1..65535) {
                " · piloto $outputTransport sin destino"
            } else {
                " · piloto $outputTransport $outHost:$outPort"
            }
        } else {
            ""
        }
        showMessage(
            when {
                enabled && sendNavigation -> "NMEA $input$output"
                enabled -> "NMEA $input"
                sendNavigation && outHost.isNotBlank() && outPort in 1..65535 ->
                    "Entrada NMEA desactivada; salida al piloto $outputTransport $outHost:$outPort"
                sendNavigation -> "Salida al piloto activada; configura host y puerto"
                else -> "NMEA desactivado"
            },
        )
    }

    // --------------------------------------------------------------- autopilot

    fun setAutopilotState(state: String) = putAutopilot(Paths.AP_STATE, JsonPrimitive(state))

    fun autopilotTack(side: String) = putAutopilot(Paths.AP_TACK, JsonPrimitive(side))

    fun adjustAutopilotHeading(deltaDeg: Double) {
        viewModelScope.launch {
            if (settings.value.demoMode) {
                showMessage("Piloto (demo): ${deltaDeg}°")
                return@launch
            }
            val cfg = settings.value.signalK

            // Standard Signal K autopilot action: the server adjusts its own target.
            val action = runCatching {
                withContext(Dispatchers.IO) {
                    container.signalK.putPath(
                        cfg,
                        Paths.AP_ADJUST_HEADING,
                        JsonPrimitive(deltaDeg),
                    )
                }
            }
            if (action.isSuccess) return@launch

            // Fallback for providers that only accept an absolute target heading (radians).
            val own = vesselState.value
            val current = own.angle(Paths.AP_TARGET_HEADING_MAG)
                ?: own.angle(Paths.AP_TARGET_HEADING_TRUE)
                ?: own.angle(Paths.HEADING_MAG)
                ?: own.angle(Paths.HEADING_TRUE)
            if (current == null) {
                showMessage("Piloto: sin rumbo disponible")
                return@launch
            }
            val path = if (own[Paths.AP_TARGET_HEADING_MAG] != null ||
                own.angle(Paths.HEADING_MAG) != null
            ) {
                Paths.AP_TARGET_HEADING_MAG
            } else {
                Paths.AP_TARGET_HEADING_TRUE
            }
            val target = WindMath.wrap2Pi(current + Math.toRadians(deltaDeg))
            runCatching {
                withContext(Dispatchers.IO) {
                    container.signalK.putPath(cfg, path, JsonPrimitive(target))
                }
            }.onFailure { showMessage(autopilotFailureMessage(it)) }
        }
    }

    private fun putAutopilot(path: String, value: JsonElement) = viewModelScope.launch {
        if (settings.value.demoMode) {
            showMessage("Piloto (demo): $path")
            return@launch
        }
        runCatching {
            withContext(Dispatchers.IO) {
                container.signalK.putPath(settings.value.signalK, path, value)
            }
        }.onSuccess {
            showMessage("Piloto: comando enviado")
        }.onFailure {
            showMessage(autopilotFailureMessage(it))
        }
    }

    /**
     * Signal K answers 401/403 when security is enabled and no token is configured. That
     * only affects the Signal K autopilot API: NMEA 0183 is a raw, unauthenticated stream,
     * so route guidance keeps working in Track mode even if these buttons cannot.
     */
    private fun autopilotFailureMessage(error: Throwable): String {
        val text = error.message ?: "error"
        val nmeaPilot = settings.value.nmea.sendsToAutopilot
        val denied = text.contains("token", ignoreCase = true) ||
            text.contains("401") || text.contains("403")
        return when {
            nmeaPilot && denied ->
                "Auto/Standby y ±1° necesitan Signal K (token). " +
                    "Por NMEA 0183 el piloto sigue la ruta en modo Track"
            nmeaPilot -> "Piloto: $text · la guía de ruta sigue saliendo por NMEA 0183"
            denied ->
                "Piloto: Auto/Standby necesita Signal K (token). " +
                    "Sin token, activa la salida NMEA 0183 y usa el modo Track"
            else -> "Piloto: $text"
        }
    }

    fun setNavigationSettings(
        alarmsEnabled: Boolean,
        arrivalRadiusMeters: Double,
        xteAlarmMeters: Double,
        autoAdvance: Boolean,
    ) = viewModelScope.launch {
        container.settings.setNavigation(alarmsEnabled, arrivalRadiusMeters, xteAlarmMeters, autoAdvance)
        if (!alarmsEnabled) container.navMonitor.clear()
        showMessage("Navegación: llegada ${arrivalRadiusMeters.toInt()} m · XTE ${xteAlarmMeters.toInt()} m")
    }

    fun setDisplaySettings(display: com.derrotero.data.settings.DisplaySettings) =
        viewModelScope.launch {
            container.settings.setDisplay(display)
        }

    fun setNightDim(dim: Float) = viewModelScope.launch {
        container.settings.setDisplay(settings.value.display.copy(nightDim = dim.coerceIn(0f, 0.8f)))
    }

    fun setAisVectors(enabled: Boolean) = viewModelScope.launch {
        container.settings.setDisplay(settings.value.display.copy(aisVectors = enabled))
    }

    fun setActiveChart(id: String?) = viewModelScope.launch { container.settings.setActiveChart(id) }

    fun setSdTree(uri: Uri?) = viewModelScope.launch {
        container.settings.setSdcardTreeUri(uri?.toString())
    }

    fun acceptDisclaimer() = viewModelScope.launch {
        container.settings.setDisclaimerAccepted(true)
    }

    fun setKeepAlive(enabled: Boolean) = viewModelScope.launch {
        container.settings.setKeepAlive(enabled)
        if (enabled) {
            container.startLive()
            ServiceController.start(container.app)
        } else {
            ServiceController.stop(container.app)
        }
    }

    /**
     * Full stop: cancels SignalK/NMEA/AIS feeds, removes the foreground service and its
     * notification, and clears the keep-alive preference so it does not come back by itself.
     */
    fun stopBackground() {
        container.stopBackground()
        ServiceController.stop(container.app)
        _message.value = "Conexión y servicio en segundo plano detenidos"
    }

    fun setAlarmSettings(
        enabled: Boolean,
        cpaMeters: Double,
        tcpaMinutes: Double,
        radiusMeters: Double,
        onlyClassA: Boolean,
        minSpeedKn: Double,
    ) = viewModelScope.launch {
        container.settings.setAlarms(enabled, cpaMeters, tcpaMinutes, radiusMeters, onlyClassA, minSpeedKn)
        if (!enabled) container.aisAlarms.clear()
    }

    fun setDemoMode(enabled: Boolean) = viewModelScope.launch {
        container.navigation.clear()
        container.settings.setDemoMode(enabled)
        if (enabled) {
            _waypoints.value = DemoData.waypoints
            _routes.value = DemoData.routes
            showMessage("Modo demo activado (sin servidor)")
        } else {
            showMessage("Modo demo desactivado")
        }
    }

    // ----------------------------------------------------------------- resources

    fun refreshResources() = viewModelScope.launch {
        if (settings.value.demoMode) {
            _waypoints.value = DemoData.waypoints
            _routes.value = DemoData.routes
            return@launch
        }
        val cfg = settings.value.signalK
        _busy.value = true
        runCatching {
            withContext(Dispatchers.IO) {
                _waypoints.value = container.resources.waypoints(cfg)
                _routes.value = container.resources.routes(cfg)
                // Only trust the server when it actually reports an active route;
                // otherwise keep the locally activated one (many servers omit this path).
                container.resources.activeRouteId(cfg)?.let { container.navigation.setActiveRoute(it) }
            }
        }.onFailure {
            // Do not nag at startup when no server has been configured yet; the
            // diagnostics panel and "Probar" explain the problem.
            val status = container.vessel.status.value
            if (status is SignalKStatus.Connected || status is SignalKStatus.Streaming) {
                showMessage("SignalK: ${it.message}")
            }
        }
        _busy.value = false
    }

    fun createWaypoint(name: String, lat: Double, lon: Double) = viewModelScope.launch {
        if (settings.value.demoMode) {
            _waypoints.value = _waypoints.value + Waypoint(
                name = name,
                position = Position(lat, lon),
                id = "demo-${System.currentTimeMillis()}",
            )
            showMessage("Waypoint creado (demo)")
            return@launch
        }
        val cfg = settings.value.signalK
        _busy.value = true
        runCatching {
            withContext(Dispatchers.IO) {
                container.resources.saveWaypoint(cfg, Waypoint(name = name, position = com.derrotero.data.resources.Position(lat, lon)))
            }
        }.onSuccess { showMessage("Waypoint enviado a SignalK") }
            .onFailure { showMessage("No se pudo enviar: ${it.message}") }
        refreshResources()
        _busy.value = false
    }

    /** Save the vessel's current position as a waypoint. */
    fun createWaypointAtVessel(name: String) = viewModelScope.launch {
        val state = vesselState.value
        val lat = state.latitude()
        val lon = state.longitude()
        if (lat == null || lon == null) {
            showMessage("Sin posición GPS todavía")
            return@launch
        }
        createWaypoint(name, lat, lon)
    }

    fun deleteWaypoint(id: String) = viewModelScope.launch {
        if (settings.value.demoMode) {
            _waypoints.value = _waypoints.value.filterNot { it.id == id }
            return@launch
        }
        val cfg = settings.value.signalK
        runCatching { withContext(Dispatchers.IO) { container.resources.deleteWaypoint(cfg, id) } }
            .onFailure { showMessage("Borrado: ${it.message}") }
        refreshResources()
    }

    /** Move an existing waypoint to a new position (keeps its id and name). */
    fun moveWaypoint(id: String, name: String, lat: Double, lon: Double) = viewModelScope.launch {
        // Keep the guidance line attached if this waypoint is the current destination.
        container.navigation.target.value?.let { target ->
            if (target.name == name) {
                container.navigation.setTarget(target.copy(lat = lat, lon = lon))
            }
        }
        if (settings.value.demoMode) {
            _waypoints.value = _waypoints.value.map {
                if (it.id == id) it.copy(position = Position(lat, lon)) else it
            }
            showMessage("Waypoint movido (demo)")
            return@launch
        }
        val cfg = settings.value.signalK
        _busy.value = true
        runCatching {
            withContext(Dispatchers.IO) {
                container.resources.saveWaypoint(
                    cfg,
                    Waypoint(id = id, name = name, position = Position(lat, lon)),
                )
            }
        }.onSuccess {
            showMessage("Waypoint movido")
            refreshResources()
        }.onFailure { showMessage("Mover waypoint: ${it.message}") }
        _busy.value = false
    }

    /** Rename a waypoint, keeping its position and the active guidance in sync. */
    fun renameWaypoint(id: String, oldName: String, newName: String, lat: Double, lon: Double) {
        container.navigation.target.value?.let { target ->
            if (target.name == oldName) {
                container.navigation.setTarget(target.copy(name = newName))
            }
        }
        moveWaypoint(id, newName.ifBlank { oldName }, lat, lon)
    }

    fun renameRoute(route: Route, newName: String) = viewModelScope.launch {
        if (newName.isBlank()) {
            showMessage("El nombre no puede estar vacío")
            return@launch
        }
        if (settings.value.demoMode) {
            _routes.value = _routes.value.map {
                if (it.id == route.id) it.copy(name = newName) else it
            }
            showMessage("Ruta renombrada (demo)")
            return@launch
        }
        _busy.value = true
        runCatching {
            withContext(Dispatchers.IO) {
                container.resources.saveRoute(settings.value.signalK, route.copy(name = newName))
            }
        }.onSuccess { showMessage("Ruta renombrada") }
            .onFailure { showMessage("Renombrar ruta: ${it.message}") }
        refreshResources()
        _busy.value = false
    }

    fun deleteRoutePoint(route: Route, index: Int) = viewModelScope.launch {
        if (route.waypoints.size <= 1) {
            showMessage("La ruta necesita al menos un punto")
            return@launch
        }
        if (index !in route.waypoints.indices) return@launch
        val updated = route.copy(
            waypoints = route.waypoints.filterIndexed { i, _ -> i != index },
        )
        if (container.navigation.activeRouteId.value == route.id) {
            container.navigation.clear()
        }
        if (settings.value.demoMode) {
            _routes.value = _routes.value.map { if (it.id == route.id) updated else it }
            showMessage("Punto borrado (demo)")
            return@launch
        }
        runCatching {
            withContext(Dispatchers.IO) {
                container.resources.saveRoute(settings.value.signalK, updated)
            }
        }.onSuccess {
            showMessage("Punto borrado de la ruta")
            refreshResources()
        }.onFailure { showMessage("Borrar punto: ${it.message}") }
    }

    fun deleteRoute(id: String) = viewModelScope.launch {
        if (container.navigation.activeRouteId.value == id) container.navigation.clear()
        if (settings.value.demoMode) {
            _routes.value = _routes.value.filterNot { it.id == id }
            return@launch
        }
        val cfg = settings.value.signalK
        runCatching { withContext(Dispatchers.IO) { container.resources.deleteRoute(cfg, id) } }
            .onFailure { showMessage("Borrado: ${it.message}") }
        refreshResources()
    }

    fun activateRoute(id: String) = viewModelScope.launch {
        val route = _routes.value.firstOrNull { it.id == id }
        val first = route?.waypoints?.firstOrNull()

        // Local guidance first: many SignalK servers do not implement PUT
        // navigation.course.activeRoute. OpenCPN-style guidance must still work.
        container.navigation.setActiveRoute(id)
        if (first != null) {
            val own = vesselState.value
            container.navigation.setTarget(
                NavigationTarget(
                    lat = first.position.latitude,
                    lon = first.position.longitude,
                    name = first.name ?: "WP 1",
                    fromLat = own.latitude(),
                    fromLon = own.longitude(),
                    routeId = id,
                    waypointIndex = 0,
                ),
            )
        }

        if (settings.value.demoMode) {
            showMessage("Ruta activada (demo)")
            return@launch
        }
        val cfg = settings.value.signalK
        runCatching { withContext(Dispatchers.IO) { container.resources.activateRoute(cfg, id) } }
            .onSuccess { showMessage("Ruta activada") }
            .onFailure {
                showMessage("Ruta activada en local (servidor: ${it.message})")
            }
    }

    /** Duplicates a route in reverse order (OpenCPN "reverse route"). */
    fun reverseRoute(route: Route) = viewModelScope.launch {
        if (route.waypoints.size < 2) {
            showMessage("La ruta necesita al menos 2 puntos")
            return@launch
        }
        val reversed = route.copy(
            id = null,
            name = "${route.name ?: "Ruta"} (inversa)",
            waypoints = route.waypoints.reversed(),
        )
        if (settings.value.demoMode) {
            _routes.value = _routes.value + reversed.copy(id = "demo-rev-${System.currentTimeMillis()}")
            showMessage("Ruta inversa creada (demo)")
            return@launch
        }
        _busy.value = true
        runCatching {
            withContext(Dispatchers.IO) {
                container.resources.saveRoute(settings.value.signalK, reversed)
            }
        }.onSuccess { showMessage("Ruta inversa creada") }
            .onFailure { showMessage("Ruta inversa: ${it.message}") }
        refreshResources()
        _busy.value = false
    }

    // ---------------------------------------------------------------- SD / charts

    fun scanSd(uri: Uri) = viewModelScope.launch {
        _busy.value = true
        runCatching { _scan.value = container.sdScanner.scan(uri) }
            .onFailure { showMessage("Escaneo: ${it.message}") }
        setSdTree(uri)
        _busy.value = false
    }

    fun importGpx(uri: Uri, pushToSignalK: Boolean) = viewModelScope.launch {
        _busy.value = true
        runCatching {
            val parsed = withContext(Dispatchers.IO) {
                container.app.contentResolver.openInputStream(uri)?.use { container.gpx.parse(it) }
                    ?: error("no se pudo abrir el GPX")
            }
            if (pushToSignalK) {
                val cfg = settings.value.signalK
                withContext(Dispatchers.IO) {
                    parsed.waypoints.forEach { container.resources.saveWaypoint(cfg, it) }
                    parsed.routes.forEach { container.resources.saveRoute(cfg, it) }
                }
                refreshResources()
            }
            showMessage("GPX: ${parsed.waypoints.size} waypoints, ${parsed.routes.size} rutas")
        }.onFailure { showMessage("GPX: ${it.message}") }
        _busy.value = false
    }

    fun addMbTiles(uri: Uri, name: String?) = viewModelScope.launch {        _busy.value = true
        runCatching {
            val src: ChartSource = container.charts.addMbTiles(uri, name)
            setActiveChart(src.id)
            showMessage("Carta añadida: ${src.name}")
        }.onFailure { showMessage("MBTiles: ${it.message}") }
        _busy.value = false
    }

    fun addMbTilesFile(file: File) = viewModelScope.launch {
        val uri = Uri.fromFile(file)
        addMbTiles(uri, file.name)
    }

    fun addPmTiles(uri: Uri, name: String?) = viewModelScope.launch {
        _busy.value = true
        runCatching {
            val src = container.charts.addPmTiles(uri, name)
            setActiveChart(src.id)
            showMessage("PMTiles añadido: ${src.name}")
        }.onFailure { showMessage("PMTiles: ${it.message}") }
        _busy.value = false
    }

    /** Opens a BSB/Maptech `.kap`/`.bsb` chart (native OpenCPN raster format). */
    fun addBsbKap(uri: Uri, name: String?) = viewModelScope.launch {
        _busy.value = true
        runCatching {
            val src = container.charts.addBsbKap(uri, name)
            setActiveChart(src.id)
            showMessage("Carta BSB/KAP: ${src.name} · z${src.minZoom}-${src.maxZoom}")
        }.onFailure { showMessage("BSB/KAP: ${it.message}") }
        _busy.value = false
    }

    /** Opens a GeoPackage (`.gpkg`) raster chart. */
    fun addGeoPackage(uri: Uri, name: String?) = viewModelScope.launch {
        _busy.value = true
        runCatching {
            val src = container.charts.addGeoPackage(uri, name)
            setActiveChart(src.id)
            showMessage("GeoPackage: ${src.name} · z${src.minZoom}-${src.maxZoom}")
        }.onFailure { showMessage("GeoPackage: ${it.message}") }
        _busy.value = false
    }

    /** Opens an S-57 ENC cell; if several files are picked, the `.001/.002` updates apply. */
    fun addS57(uris: List<Uri>) = viewModelScope.launch {
        _busy.value = true
        runCatching {
            val base = uris.firstOrNull {
                it.lastPathSegment?.lowercase()?.endsWith(".000") == true
            } ?: uris.firstOrNull() ?: error("Selecciona un fichero .000")
            val lower = base.lastPathSegment?.lowercase().orEmpty()
            if (lower.endsWith(".os63") || lower.endsWith(".s63")) {
                error("S-63: carta cifrada con User Permit; no soportada")
            }
            if (lower.endsWith(".cap")) {
                error("BSB4 (.cap): formato propietario, solo con plugin de OpenCPN")
            }
            val updates = uris.filter { it != base }
            val src = container.charts.addS57(
                base,
                updates,
                base.lastPathSegment?.substringAfterLast('/'),
            )
            val extra = if (updates.isNotEmpty()) " · ${updates.size} act." else ""
            showMessage("S-57: ${src.name} · z${src.minZoom}-${src.maxZoom}$extra")
        }.onFailure { showMessage("S-57: ${it.message}") }
        _busy.value = false
    }

    /** Opens a NOS/GEO chart; both the `.nos` and the `.geo` must be selected. */
    fun addNos(uris: List<Uri>) = viewModelScope.launch {
        _busy.value = true
        runCatching {
            val nos = uris.firstOrNull {
                it.lastPathSegment?.endsWith(".nos", ignoreCase = true) == true
            } ?: error("Selecciona también el fichero .nos")
            val geo = uris.firstOrNull {
                it.lastPathSegment?.endsWith(".geo", ignoreCase = true) == true
            } ?: error("Selecciona también el fichero .geo")
            val src = container.charts.addNos(
                nos,
                geo,
                nos.lastPathSegment?.substringAfterLast('/'),
            )
            setActiveChart(src.id)
            showMessage("NOS/GEO: ${src.name} · z${src.minZoom}-${src.maxZoom}")
        }.onFailure { showMessage("NOS/GEO: ${it.message}") }
        _busy.value = false
    }

    fun importPolar(uri: Uri) = viewModelScope.launch {
        runCatching {
            val csv = withContext(Dispatchers.IO) {
                container.app.contentResolver.openInputStream(uri)
                    ?.bufferedReader()
                    ?.use { it.readText() }
                    ?: error("no se pudo leer el CSV")
            }
            if (container.polar.importCsv(csv)) {
                showMessage("Polar importada")
            } else {
                showMessage("CSV de polares no válido")
            }
        }.onFailure { showMessage("Polar: ${it.message}") }
    }

    fun resetPolar() = viewModelScope.launch {
        container.polar.reset()
        showMessage("Polar restablecida")
    }

    // --------------------------------------------------------------- anchor watch

    fun setAnchorHere() = viewModelScope.launch {
        val state = vesselState.value
        val lat = state.latitude()
        val lon = state.longitude()
        if (lat == null || lon == null) {
            showMessage("Sin posición GPS")
            return@launch
        }
        val radius = settings.value.anchor.radiusMeters
        container.settings.setAnchor(true, lat, lon, radius)
        showMessage("Ancla fijada (radio ${radius.toInt()} m)")
    }

    fun clearAnchor() = viewModelScope.launch {
        container.settings.setAnchor(false, null, null, settings.value.anchor.radiusMeters)
        container.anchor.clear()
        showMessage("Alarma de fondeo desactivada")
    }

    fun setAnchorAlarm(enabled: Boolean, radiusMeters: Double) = viewModelScope.launch {
        val current = settings.value.anchor
        container.settings.setAnchor(enabled, current.lat, current.lon, radiusMeters)
        if (!enabled) container.anchor.clear()
    }

    // ------------------------------------------------------------- navigation

    fun goTo(lat: Double, lon: Double, name: String) = viewModelScope.launch {
        val own = vesselState.value
        container.navigation.setTarget(
            NavigationTarget(
                lat = lat,
                lon = lon,
                name = name.ifBlank { "Destino" },
                fromLat = own.latitude(),
                fromLon = own.longitude(),
            ),
        )
        if (settings.value.demoMode) {
            showMessage("Ir aquí: enviado al piloto (demo)")
            return@launch
        }

        val cfg = settings.value.signalK
        val nmeaPilot = settings.value.nmea.sendsToAutopilot
        if (nmeaPilot) {
            // Route guidance already flows over raw NMEA 0183 (APB/RMB/XTE), which is
            // unauthenticated. Do not surface a Signal K token error in this mode.
            showMessage("Rumbo enviado al piloto por NMEA 0183 · ponlo en modo Track")
        }
        if (!cfg.isValid()) {
            if (!nmeaPilot) {
                showMessage("Destino activado. Conecta Signal K o activa la salida NMEA al piloto")
            }
            return@launch
        }

        runCatching {
            withContext(Dispatchers.IO) {
                container.resources.goTo(cfg, name, lat, lon)
            }
        }.onSuccess { routeId ->
            container.navigation.target.value?.let { target ->
                container.navigation.setTarget(
                    target.copy(routeId = routeId, waypointIndex = 0),
                )
            }
            if (!nmeaPilot) showMessage("Rumbo enviado al piloto")
            refreshResources()
        }.onFailure {
            if (!nmeaPilot) showMessage("Ir aquí: ${it.message}")
        }
    }

    // ------------------------------------------------------------------- MOB

    /** Man overboard: drops a persistent mark at the current position. */
    fun setMob() = viewModelScope.launch {
        val state = vesselState.value
        val lat = state.latitude()
        val lon = state.longitude()
        if (lat == null || lon == null) {
            showMessage("Sin posición GPS: no se puede marcar MOB")
            return@launch
        }
        container.settings.setMob(lat, lon)
        container.navMonitor.notifyMob(lat, lon)
        showMessage("MOB marcado en ${"%.4f, %.4f".format(lat, lon)}")
    }

    fun clearMob() = viewModelScope.launch {
        container.settings.clearMob()
        container.navMonitor.clearMobNotification()
        showMessage("Marca MOB borrada")
    }

    // ---------------------------------------------------------------- GPX export

    /** Writes the current track, waypoints and routes to a GPX file chosen by the user. */
    fun exportGpx(uri: Uri) = viewModelScope.launch {
        _busy.value = true
        runCatching {
            withContext(Dispatchers.IO) {
                container.app.contentResolver.openOutputStream(uri)?.use { out ->
                    GpxWriter.write(
                        out = out,
                        waypoints = _waypoints.value,
                        routes = _routes.value,
                        track = container.track.points.value,
                    )
                } ?: error("no se pudo abrir el destino")
            }
            showMessage(
                "GPX exportado: ${_waypoints.value.size} waypoints, " +
                    "${_routes.value.size} rutas, ${container.track.points.value.size} puntos de estela",
            )
        }.onFailure { showMessage("GPX: ${it.message}") }
        _busy.value = false
    }

    fun addToActiveRoute(lat: Double, lon: Double, name: String) = viewModelScope.launch {
        if (settings.value.demoMode) {
            showMessage("Añadido a la ruta activa (demo)")
            return@launch
        }
        runCatching {
            withContext(Dispatchers.IO) {
                container.resources.addToActiveRoute(settings.value.signalK, name, lat, lon)
            }
        }.onSuccess { result ->
            when {
                !result.added -> showMessage("No hay ruta activa")
                result.afterActiveWaypoint ->
                    showMessage("Añadido tras el waypoint activo (posición ${result.index + 1})")
                else -> showMessage("Añadido al final de la ruta")
            }
            if (result.added) refreshResources()
        }.onFailure { showMessage("Ruta: ${it.message}") }
    }

    // ------------------------------------------------------------ chart assets

    fun downloadChartAssets() {
        val source = chartSources.value.firstOrNull { it.id == settings.value.activeChartId }
        if (source == null || source.kind != ChartKind.PMTILES_VECTOR) {
            showMessage("La carta activa no es un PMTiles vectorial")
            return
        }
        downloadJob?.cancel()
        downloadJob = viewModelScope.launch {
            _download.value = DownloadState(running = true, message = "Descargando glifos y sprites…")
            try {
                val (ok, total) = withContext(Dispatchers.IO) {
                    container.charts.downloadAllAssets(source.id) { done, all ->
                        _download.value = DownloadState(true, done, all, 0, "Descargando glifos…")
                    }
                }
                _download.value = DownloadState(
                    running = false,
                    message = "Assets: $ok/$total ficheros",
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                _download.value = DownloadState(false, message = "Descarga cancelada")
                throw e
            } catch (e: Exception) {
                _download.value = DownloadState(false, message = "Error: ${e.message}")
            }
        }
    }

    fun addXyz(name: String, template: String, overlay: Boolean) {
        val src = container.charts.addXyz(name, template, overlay = overlay)
        showMessage("Capa añadida: ${src.name}")
    }

    // ------------------------------------------------------------ offline charts

    fun setMapBounds(bounds: GeoBounds?) {
        container.mapBounds.value = bounds
    }

    fun cancelDownload() {
        downloadJob?.cancel()
    }

    /** Downloads a corridor around a route (route legs) as an offline MBTiles chart. */
    fun downloadRoute(route: Route, corridorNm: Double, minZoom: Int, maxZoom: Int) {
        val source = chartSources.value.firstOrNull { it.id == settings.value.activeChartId }
        if (source == null ||
            (source.kind != ChartKind.XYZ && source.kind != ChartKind.SIGNALK)
        ) {
            showMessage("Selecciona una carta XYZ como base para descargar")
            return
        }
        if (!source.allowOfflineDownload) {
            showMessage(
                "Este proveedor no permite descargas masivas. Usa una plantilla XYZ propia " +
                    "o una carta PMTiles de Seamap.",
            )
            return
        }
        val points = route.waypoints.map {
            com.derrotero.util.LatLon(it.position.latitude, it.position.longitude)
        }
        if (points.size < 2) {
            showMessage("La ruta necesita al menos 2 puntos")
            return
        }
        val corridor = (corridorNm.coerceAtLeast(0.5)) * 1852.0
        val safe = (route.name ?: "ruta").ifBlank { "ruta" }
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
        val dir = File(container.app.filesDir, "charts").apply { mkdirs() }
        val target = File(dir, "route-$safe.mbtiles")

        downloadJob?.cancel()
        downloadJob = viewModelScope.launch {
            val estimate = OfflineChartDownloader.countRouteTiles(points, corridor, minZoom, maxZoom)
            _download.value = DownloadState(running = true, total = estimate, message = "Descargando ruta…")
            try {
                val file = withContext(Dispatchers.IO) {
                    container.offlineCharts.downloadRoute(
                        source.urlTemplate, points, corridor, minZoom, maxZoom, target,
                        attribution = source.attribution,
                    ) { p ->
                        _download.value = DownloadState(true, p.done, p.total, p.failed, "Descargando ruta…")
                    }
                }
                val chart = withContext(Dispatchers.IO) {
                    container.charts.registerMbTiles(file, "Ruta ${route.name ?: safe}")
                }
                setActiveChart(chart.id)
                _download.value = DownloadState(false, message = "Carta de ruta lista: ${chart.name}")
            } catch (e: kotlinx.coroutines.CancellationException) {
                _download.value = DownloadState(false, message = "Descarga cancelada")
                throw e
            } catch (e: Exception) {
                _download.value = DownloadState(false, message = "Error: ${e.message}")
            }
        }
    }

    /**
     * Downloads the current map area (from the active XYZ/SignalK chart) into an MBTiles
     * file and registers it as an offline chart.
     */
    fun downloadOffline(name: String, minZoom: Int, maxZoom: Int) {
        val source = chartSources.value.firstOrNull { it.id == settings.value.activeChartId }
        if (source == null ||
            (source.kind != ChartKind.XYZ && source.kind != ChartKind.SIGNALK)
        ) {
            showMessage("Selecciona una carta XYZ como base para descargar")
            return
        }
        if (!source.allowOfflineDownload) {
            showMessage(
                "Este proveedor no permite descargas masivas. Usa una plantilla XYZ propia " +
                    "o una carta PMTiles de Seamap.",
            )
            return
        }
        val bounds = container.mapBounds.value
        if (bounds == null || !bounds.isValid) {
            showMessage("Mueve el mapa a la zona a descargar")
            return
        }
        val safe = name.ifBlank { "offline" }.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val dir = File(container.app.filesDir, "charts").apply { mkdirs() }
        val target = File(dir, "$safe.mbtiles")

        downloadJob?.cancel()
        downloadJob = viewModelScope.launch {
            val estimate = OfflineChartDownloader.countTiles(bounds, minZoom, maxZoom)
            _download.value = DownloadState(running = true, total = estimate, message = "Descargando…")
            try {
                val file = withContext(Dispatchers.IO) {
                    container.offlineCharts.download(
                        source.urlTemplate, bounds, minZoom, maxZoom, target,
                        attribution = source.attribution,
                    ) { p ->
                        _download.value = DownloadState(true, p.done, p.total, p.failed, "Descargando…")
                    }
                }
                val chart = withContext(Dispatchers.IO) {
                    container.charts.registerMbTiles(file, name.ifBlank { safe })
                }
                setActiveChart(chart.id)
                _download.value = DownloadState(false, message = "Carta offline lista: ${chart.name}")
            } catch (e: kotlinx.coroutines.CancellationException) {
                _download.value = DownloadState(false, message = "Descarga cancelada")
                throw e
            } catch (e: Exception) {
                _download.value = DownloadState(false, message = "Error: ${e.message}")
            }
        }
    }

    fun removeChart(id: String) {
        container.charts.remove(id)
        if (settings.value.activeChartId == id) setActiveChart(null)
    }

    fun setChartEnabled(id: String, enabled: Boolean) {
        container.charts.setEnabled(id, enabled)
    }

    /** Makes OSM the base chart, restoring the built-in entry if it had been deleted. */
    fun useOsmBase() {
        container.charts.ensureDefault(DefaultCharts.OSM.id)
        container.charts.setEnabled(DefaultCharts.OSM.id, true)
        setActiveChart(DefaultCharts.OSM.id)
    }

    fun moveChart(id: String, delta: Int) {
        container.charts.movePriority(id, delta)
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            NavViewModel(container) as T
    }
}

private data class AdvanceSnapshot(
    val own: Map<String, kotlinx.serialization.json.JsonElement>,
    val target: NavigationTarget?,
    val cfg: com.derrotero.data.settings.AppSettings,
    val routes: List<Route>,
)

data class MapFocus(
    val lat: Double,
    val lon: Double,
    val zoom: Double,
)
