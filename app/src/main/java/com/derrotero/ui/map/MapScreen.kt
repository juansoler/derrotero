package com.derrotero.ui.map

import android.Manifest
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddLocationAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Emergency
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.derrotero.data.charts.ChartKind
import com.derrotero.data.charts.ChartSource
import com.derrotero.data.charts.DefaultCharts
import com.derrotero.data.charts.GeoBounds
import com.derrotero.data.ais.ShipType
import com.derrotero.data.resources.Waypoint
import com.derrotero.data.resources.Route
import com.derrotero.data.settings.MobMarker
import com.derrotero.data.signalk.Paths
import com.derrotero.data.signalk.angle
import com.derrotero.data.signalk.depthBest
import com.derrotero.data.signalk.latitude
import com.derrotero.data.signalk.longitude
import com.derrotero.data.signalk.number
import com.derrotero.data.vessel.AisAlarm
import com.derrotero.data.vessel.AisTarget
import com.derrotero.data.vessel.AnchorMonitor
import com.derrotero.data.vessel.NavGuidance
import com.derrotero.data.vessel.NavigationTarget
import com.derrotero.data.vessel.TrackPoint
import com.derrotero.ui.NavViewModel
import com.derrotero.ui.ais.AisTargetSheet
import com.derrotero.ui.components.GlassCard
import com.derrotero.ui.components.MiniReadout
import com.derrotero.ui.components.StatusPill
import com.derrotero.ui.theme.MarineColors
import com.derrotero.util.Cpa
import com.derrotero.util.Formatters
import com.derrotero.util.Geo
import com.derrotero.util.LatLon
import com.derrotero.util.SailAdvisor
import com.derrotero.util.SailPlan
import com.derrotero.util.TackAdvice
import com.derrotero.util.WindMath
import kotlinx.coroutines.delay
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.Layer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.android.style.sources.TileSet
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon

/**
 * Minimal style used for raster charts: dark background + demotiles glyphs so the AIS labels
 * can be rendered. Vector sources (e.g. Seamap) use their own style URL instead.
 */
private const val BLANK_STYLE = """
{"version":8,
 "glyphs":"https://demotiles.maplibre.org/font/{fontstack}/{range}.pbf",
 "sources":{},
 "layers":[{"id":"bg","type":"background","paint":{"background-color":"#0a1a2b"}}]}
"""

/** How long a remote/offline style may stay silent before the OSM fallback is used. */
private const val STYLE_LOAD_TIMEOUT_MS = 15_000L

/** Invisible layer kept at the bottom of the style. Chart tiles are inserted below it. */
private const val CHART_ANCHOR_SOURCE = "chart-anchor-source"
private const val CHART_ANCHOR_LAYER = "chart-anchor-layer"

@Composable
fun MapScreen(vm: NavViewModel) {
    val context = LocalContext.current
    val state by vm.vesselState.collectAsStateWithLifecycle()
    val charts by vm.chartSources.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val waypoints by vm.waypoints.collectAsStateWithLifecycle()
    val ais by vm.aisTargets.collectAsStateWithLifecycle()
    val internetAis by vm.internetAisTargets.collectAsStateWithLifecycle()
    val alarms by vm.aisAlarms.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val anchor by vm.anchorState.collectAsStateWithLifecycle()
    val downloadArea by vm.downloadAreaVisible.collectAsStateWithLifecycle()
    val bounds by vm.mapBounds.collectAsStateWithLifecycle()
    val activeRouteId by vm.activeRouteId.collectAsStateWithLifecycle()
    val navTarget by vm.navTarget.collectAsStateWithLifecycle()
    val guidance by vm.navGuidance.collectAsStateWithLifecycle()
    val polarTable by vm.polarTable.collectAsStateWithLifecycle()
    val mob by vm.mob.collectAsStateWithLifecycle()
    val trackPoints by vm.trackPoints.collectAsStateWithLifecycle()
    val trackEnabled by vm.trackEnabled.collectAsStateWithLifecycle()
    val routes by vm.routes.collectAsStateWithLifecycle()
    val mapFocus by vm.mapFocus.collectAsStateWithLifecycle()
    val activeRoute = routes.firstOrNull { it.id == activeRouteId }

    val lifecycleOwner = LocalLifecycleOwner.current

    var mapView by remember { mutableStateOf<MapView?>(null) }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var ready by remember { mutableStateOf(false) }
    var centered by remember { mutableStateOf(false) }
    var cameraReady by remember { mutableStateOf(false) }
    var metersPerPixel by remember { mutableDoubleStateOf(0.0) }
    var pendingPoint by remember { mutableStateOf<PendingPoint?>(null) }
    var movingWaypoint by remember { mutableStateOf<Waypoint?>(null) }
    var moveTarget by remember { mutableStateOf<Waypoint?>(null) }
    var selectedAisId by remember { mutableStateOf<String?>(null) }
    var appliedBaseKey by remember { mutableStateOf<String?>(null) }
    var appliedToken by remember { mutableStateOf(-1) }
    var reloadToken by remember { mutableStateOf(0) }
    var styleAttempt by remember { mutableStateOf(0) }
    var chartLoading by remember { mutableStateOf(false) }
    var chartError by remember { mutableStateOf<String?>(null) }
    var chartFallback by remember { mutableStateOf(false) }
    var lastMapFailure by remember { mutableStateOf<String?>(null) }
    var showChartSwitcher by remember { mutableStateOf(false) }
    var measureMode by remember { mutableStateOf(false) }
    var measureStart by remember { mutableStateOf<LatLon?>(null) }
    var measureEnd by remember { mutableStateOf<LatLon?>(null) }
    var showAisSheet by remember { mutableStateOf(false) }
    var showLayers by remember { mutableStateOf(false) }
    var showGoTo by remember { mutableStateOf(false) }
    val appliedLayers = remember { mutableListOf<String>() }
    val appliedS57 = remember { mutableListOf<String>() }

    val baseSource = charts.firstOrNull {
        it.id == settings.activeChartId && it.enabled && it.kind != ChartKind.S57
    }
        ?: charts.firstOrNull {
            !it.isOverlay && it.enabled &&
                it.kind != ChartKind.STYLE &&
                it.kind != ChartKind.PMTILES_VECTOR &&
                it.kind != ChartKind.S57
        }
        ?: charts.firstOrNull { it.enabled && it.kind != ChartKind.S57 }
    // The style content matters: a restored PMTiles/vector chart gets a fresh local port and
    // patched style JSON, and reloading the old style would leave blank tiles.
    val baseKey = buildString {
        append(baseSource?.id ?: "none")
        append(':').append(baseSource?.kind?.name ?: "none")
        append(':').append(baseSource?.urlTemplate?.hashCode() ?: 0)
        append(':').append(baseSource?.styleJson?.hashCode() ?: 0)
    }

    val lat = state.latitude()
    val lon = state.longitude()
    val hasFix = lat != null && lon != null
    val cog = state.angle(Paths.COG) ?: state.angle(Paths.COG_MAG)
    val sogMps = state.number(Paths.SOG)
    val heading = state.angle(Paths.HEADING_TRUE)
        ?: state.angle(Paths.HEADING_MAG)
        ?: state.angle(Paths.HEADING_COMPASS)
        ?: cog
    val currentSet = state.angle(Paths.CURRENT_SET_TRUE)
    val currentDrift = state.number(Paths.CURRENT_DRIFT)
    val depthM = state.depthBest()
    val windKn = (
        state.number(Paths.WIND_SPEED_APPARENT)
            ?: state.number(Paths.WIND_SPEED_TRUE)
        )?.times(1.943844)
    val trueWindKn = (
        state.number(Paths.WIND_SPEED_TRUE)
            ?: state.number(Paths.WIND_SPEED_APPARENT)
        )?.times(1.943844)
    val trueWindDirection = state.angle(Paths.WIND_DIRECTION_TRUE)
        ?: heading?.let { h ->
            state.angle(Paths.WIND_ANGLE_TRUE)?.let { WindMath.trueWindDirection(h, it) }
        }
    val trueWindAngleDeg = state.angle(Paths.WIND_ANGLE_TRUE)?.let {
        Math.toDegrees(kotlin.math.abs(it))
    }
    val sailingAdvice = remember(
        guidance.bearingDeg,
        trueWindDirection,
        heading,
        trueWindAngleDeg,
        trueWindKn,
        polarTable,
    ) {
        SailAdvisor.recommend(
            bearingToTargetDeg = guidance.bearingDeg,
            trueWindDirectionDeg = trueWindDirection?.let {
                Math.toDegrees(WindMath.wrap2Pi(it))
            },
            headingDeg = heading?.let { Math.toDegrees(WindMath.wrap2Pi(it)) },
            currentTwaDeg = trueWindAngleDeg,
            twsKn = trueWindKn,
            polar = polarTable,
        )
    }
    val chartName = if (chartFallback) {
        "OpenStreetMap · respaldo"
    } else {
        baseSource?.name ?: "Sin carta base"
    }
    val baseCharts = charts.filter { !it.isOverlay && it.kind != ChartKind.S57 }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { }
    LaunchedEffect(Unit) {
        permissionLauncher.launch(
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
        )
    }

    /** Adds every overlay/vector layer on top of a freshly loaded style. */
    fun decorateMap(style: Style, activeId: String?, forceOsmBase: Boolean = false) {
        appliedLayers.clear()
        // Invisible bottom anchor: all chart tiles go below it so later chart reloads
        // cannot end up over the track, heading, current or route overlays.
        addChartAnchor(style)
        addCharts(style, charts, activeId, appliedLayers)
        appliedS57.clear()
        addS57Charts(style, charts, appliedS57)
        addVesselLayer(style)
        addAisLayer(style, aisFont(baseSource))
        addInternetAisLayers(style, aisFont(baseSource))
        addAisDangerLayer(style)
        addWaypointLayer(style)
        addAnchorLayers(style)
        addAreaLayer(style)
        addNavLayers(style)
        addTargetRingLayer(style)
        addHeadingLayer(style)
        addCurrentLayer(style)
        addTrackLayer(style)
        addRouteLayer(style)
        addRouteLegLayer(style)
        addMeasureLayers(style)
        addMobLayer(style)
        addAisVectorLayers(style)
        if (forceOsmBase && "chart-osm" !in appliedLayers) {
            // Last-resort base: always paint OSM, even if the user had disabled it.
            addRasterLayers(style, listOf(DefaultCharts.OSM), appliedLayers)
        }
        chartFallback = forceOsmBase
        ready = true
        chartLoading = false
    }

    /** Without a GPS fix, frame the chart coverage so the user sees something useful. */
    fun fitChartBounds(m: MapLibreMap) {
        if (centered || hasFix) return
        val coverage = baseSource?.bounds ?: return
        if (!coverage.isValid) return
        runCatching {
            val box = LatLngBounds.Builder()
                .include(LatLng(coverage.north, coverage.west))
                .include(LatLng(coverage.south, coverage.east))
                .build()
            m.moveCamera(CameraUpdateFactory.newLatLngBounds(box, 56))
        }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                MapView(ctx).also { view ->
                    mapView = view
                    view.onCreate(null)
                    view.addOnDidFailLoadingMapListener { message ->
                        lastMapFailure = message
                    }
                    view.getMapAsync { m ->
                        map = m
                        m.uiSettings.isCompassEnabled = false
                        m.uiSettings.isLogoEnabled = false
                        m.uiSettings.isAttributionEnabled = false
                        m.addOnMapLongClickListener { latLng ->
                            if (measureMode) {
                                val point = LatLon(latLng.latitude, latLng.longitude)
                                if (measureStart == null) {
                                    measureStart = point
                                    measureEnd = null
                                } else {
                                    measureEnd = point
                                }
                                return@addOnMapLongClickListener true
                            }
                            // While moving a waypoint, a long press places it.
                            moveTarget?.let { moving ->
                                val id = moving.id
                                if (id != null) {
                                    vm.moveWaypoint(
                                        id,
                                        moving.name ?: "Waypoint",
                                        latLng.latitude,
                                        latLng.longitude,
                                    )
                                } else {
                                    vm.showMessage("Este waypoint no tiene id")
                                }
                                moveTarget = null
                                return@addOnMapLongClickListener true
                            }

                            val screen = m.projection.toScreenLocation(latLng)
                            // Long press over a waypoint: open its actions (move / go / delete).
                            val hitRect = android.graphics.RectF(
                                screen.x - 14f,
                                screen.y - 14f,
                                screen.x + 14f,
                                screen.y + 14f,
                            )
                            val hitId = runCatching {
                                m.queryRenderedFeatures(hitRect, "waypoints-layer")
                                    .firstOrNull()?.getStringProperty("id")
                            }.getOrNull()
                            val hit = hitId?.let { id -> waypoints.firstOrNull { it.id == id } }
                            if (hit != null) {
                                movingWaypoint = hit
                                return@addOnMapLongClickListener true
                            }

                            val offsets = listOf(
                                0f to 0f, 5f to 0f, -5f to 0f, 0f to 5f, 0f to -5f, 9f to 9f, -9f to -9f,
                            )
                            val features = offsets.flatMap { (dx, dy) ->
                                runCatching {
                                    m.queryRenderedFeatures(
                                        android.graphics.PointF(screen.x + dx, screen.y + dy),
                                    )
                                }.getOrDefault(emptyList())
                            }
                            pendingPoint = PendingPoint(
                                lat = latLng.latitude,
                                lon = latLng.longitude,
                                features = features.map { it.toInfo() }
                                    .distinctBy { it.title + it.rows.joinToString() }
                                    .take(4),
                            )
                            true
                        }
                        m.addOnCameraIdleListener {
                            val b = m.projection.visibleRegion.latLngBounds
                            metersPerPixel = m.projection.getMetersPerPixelAtLatitude(
                                m.cameraPosition.target?.latitude ?: 0.0,
                            )
                            vm.setMapBounds(
                                GeoBounds(
                                    north = b.latitudeNorth,
                                    south = b.latitudeSouth,
                                    east = b.longitudeEast,
                                    west = b.longitudeWest,
                                ),
                            )
                        }
                        m.addOnMapClickListener { latLng ->
                            if (measureMode) {
                                val point = LatLon(latLng.latitude, latLng.longitude)
                                if (measureStart == null) {
                                    measureStart = point
                                    measureEnd = null
                                } else {
                                    measureEnd = point
                                }
                                return@addOnMapClickListener true
                            }
                            moveTarget?.let { moving ->
                                val id = moving.id
                                if (id != null) {
                                    vm.moveWaypoint(
                                        id,
                                        moving.name ?: "Waypoint",
                                        latLng.latitude,
                                        latLng.longitude,
                                    )
                                } else {
                                    vm.showMessage("Este waypoint no tiene id")
                                }
                                moveTarget = null
                                return@addOnMapClickListener true
                            }

                            val point = m.projection.toScreenLocation(latLng)
                            val id = m.queryRenderedFeatures(point, "ais-layer", "ais-net-layer")
                                .firstOrNull()?.getStringProperty("id")
                            if (id != null) {
                                selectedAisId = id
                                true
                            } else {
                                false
                            }
                        }
                    }
                }
            },
            onRelease = { view ->
                if (mapView === view) {
                    mapView = null
                    map = null
                }
                ready = false
                chartLoading = false
                appliedBaseKey = null
                appliedToken = -1
                appliedLayers.clear()
                appliedS57.clear()
                runCatching { view.onPause() }
                runCatching { view.onStop() }
                runCatching { view.onDestroy() }
            },
        )

        // (Re)apply the base style whenever the active chart (or its local tile port) changes.
        // A remote style that never finishes loading is detected by timeout and replaced by a
        // raster OSM fallback, so the chart screen never stays as a dead black rectangle.
        LaunchedEffect(baseKey, reloadToken, map) {
            val m = map ?: return@LaunchedEffect
            if (appliedBaseKey == baseKey && appliedToken == reloadToken) return@LaunchedEffect
            appliedBaseKey = baseKey
            appliedToken = reloadToken
            ready = false
            chartError = null
            lastMapFailure = null
            chartLoading = true
            chartFallback = false
            styleAttempt += 1
            val attempt = styleAttempt
            val base = baseSource

            val builder = try {
                when {
                    base?.styleJson != null -> Style.Builder().fromJson(base.styleJson)
                    base?.kind == ChartKind.STYLE ->
                        Style.Builder().fromUri(base.urlTemplate)
                    else -> Style.Builder().fromJson(BLANK_STYLE)
                }
            } catch (e: Exception) {
                chartError = "Estilo de carta inválido: ${e.message ?: "error de formato"}"
                chartLoading = false
                return@LaunchedEffect
            }

            val started = runCatching {
                m.setStyle(builder) { style ->
                    if (attempt == styleAttempt) {
                        decorateMap(style, baseSource?.id)
                        fitChartBounds(m)
                    }
                }
            }.isSuccess

            if (!started) {
                chartError = "No se pudo aplicar la carta ${base?.name ?: "base"}"
                chartLoading = false
                return@LaunchedEffect
            }

            // Style callbacks are only delivered on success. Wait for one, then fall back.
            var waited = 0
            while (!ready && attempt == styleAttempt && waited < STYLE_LOAD_TIMEOUT_MS) {
                delay(250)
                waited += 250
            }
            if (!ready && attempt == styleAttempt) {
                chartError = lastMapFailure?.takeIf { it.isNotBlank() }?.let {
                    "No se pudo cargar ${base?.name ?: "la carta"}: $it"
                } ?: "No se pudo cargar ${base?.name ?: "la carta"}. Se muestra OpenStreetMap."
                chartLoading = false
                styleAttempt += 1
                val fallbackAttempt = styleAttempt
                runCatching {
                    m.setStyle(Style.Builder().fromJson(BLANK_STYLE)) { style ->
                        if (fallbackAttempt == styleAttempt) {
                            decorateMap(style, activeId = null, forceOsmBase = true)
                            fitChartBounds(m)
                        }
                    }
                }.onFailure { failure ->
                    if (fallbackAttempt == styleAttempt) {
                        chartError = "No se pudo iniciar el mapa: " +
                            (failure.message ?: "error desconocido")
                    }
                }
            }
        }

        LaunchedEffect(charts, settings.activeChartId, chartFallback, ready) {
            val style = map?.style ?: return@LaunchedEffect
            if (!ready) return@LaunchedEffect
            addCharts(
                style,
                charts,
                if (chartFallback) null else baseSource?.id,
                appliedLayers,
            )
            if (chartFallback && "chart-osm" !in appliedLayers) {
                // Keep the fallback base even if the user had OSM disabled.
                addRasterLayers(style, listOf(DefaultCharts.OSM), appliedLayers)
            }
        }

        LaunchedEffect(charts, ready) {
            val style = map?.style ?: return@LaunchedEffect
            if (!ready) return@LaunchedEffect
            addS57Charts(style, charts, appliedS57)
        }

        LaunchedEffect(navTarget, lat, lon, ready) {
            val style = map?.style ?: return@LaunchedEffect
            if (!ready) return@LaunchedEffect
            updateNav(style, lat, lon, navTarget)
        }

        LaunchedEffect(lat, lon, heading, sogMps, settings.display.showHeading, ready) {
            val style = map?.style ?: return@LaunchedEffect
            if (!ready) return@LaunchedEffect
            updateHeading(
                style,
                lat,
                lon,
                if (settings.display.showHeading) heading else null,
                sogMps,
            )
        }

        LaunchedEffect(trackPoints, settings.display.showTrack, ready) {
            val style = map?.style ?: return@LaunchedEffect
            if (!ready) return@LaunchedEffect
            updateTrack(
                style,
                if (settings.display.showTrack) trackPoints else emptyList(),
            )
        }

        LaunchedEffect(activeRoute, settings.display.showRoute, ready) {
            val style = map?.style ?: return@LaunchedEffect
            if (!ready) return@LaunchedEffect
            updateRoute(
                style,
                if (settings.display.showRoute) activeRoute else null,
            )
        }

        LaunchedEffect(navTarget, ready) {
            val style = map?.style ?: return@LaunchedEffect
            if (!ready) return@LaunchedEffect
            updateRouteLeg(style, navTarget)
        }

        LaunchedEffect(
            navTarget,
            settings.navigation.arrivalRadiusMeters,
            settings.display.showArrivalRing,
            ready,
        ) {
            val style = map?.style ?: return@LaunchedEffect
            if (!ready) return@LaunchedEffect
            updateTargetRing(
                style,
                if (settings.display.showArrivalRing) navTarget else null,
                settings.navigation.arrivalRadiusMeters,
            )
        }

        LaunchedEffect(
            lat, lon, waypoints, ais, internetAis,
            settings.display.showWaypoints, settings.display.showAis,
            settings.display.courseUp, heading, cog, ready,
        ) {
            val m = map ?: return@LaunchedEffect
            val style = m.style ?: return@LaunchedEffect
            if (!ready) return@LaunchedEffect
            updateVessel(style, lat, lon)
            updateWaypoints(
                style,
                if (settings.display.showWaypoints) waypoints else emptyList(),
            )
            updateAis(
                style,
                if (settings.display.showAis) ais.values.toList() else emptyList(),
            )
            updateInternetAis(
                style,
                if (settings.display.showAis) internetAis.values.toList() else emptyList(),
            )
            if (hasFix && !centered) {
                centered = true
                val bearing = if (settings.display.courseUp) {
                    Math.toDegrees(heading ?: cog ?: 0.0)
                } else {
                    0.0
                }
                m.animateCamera(
                    CameraUpdateFactory.newCameraPosition(
                        CameraPosition.Builder()
                            .target(LatLng(lat!!, lon!!))
                            .zoom(11.0)
                            .bearing(bearing)
                            .build(),
                    ),
                    800,
                )
            }
        }

        LaunchedEffect(centered) {
            if (centered) {
                delay(900)
                cameraReady = true
            }
        }

        LaunchedEffect(ais, internetAis, alarms, settings.display.showAis, ready) {
            val style = map?.style ?: return@LaunchedEffect
            if (!ready) return@LaunchedEffect
            updateAisDanger(
                style,
                if (settings.display.showAis) {
                    ais.values + internetAis.values
                } else {
                    emptyList()
                },
                if (settings.display.showAis) {
                    alarms.map { it.targetId }.toSet()
                } else {
                    emptySet()
                },
            )
        }

        LaunchedEffect(lat, lon, currentSet, currentDrift, settings.display.showCurrent, ready) {
            val style = map?.style ?: return@LaunchedEffect
            if (!ready) return@LaunchedEffect
            updateCurrent(
                style,
                lat,
                lon,
                if (settings.display.showCurrent) currentSet else null,
                if (settings.display.showCurrent) currentDrift else null,
            )
        }

        LaunchedEffect(mapFocus, ready) {
            val m = map ?: return@LaunchedEffect
            if (!ready) return@LaunchedEffect
            val target = mapFocus ?: return@LaunchedEffect
            m.animateCamera(
                CameraUpdateFactory.newLatLngZoom(
                    LatLng(target.lat, target.lon),
                    target.zoom,
                ),
                800,
            )
            vm.clearMapFocus()
        }

        LaunchedEffect(
            settings.display.courseUp,
            settings.display.followBoat,
            heading,
            cog,
            lat,
            lon,
            map,
            ready,
            cameraReady,
        ) {
            val m = map ?: return@LaunchedEffect
            if (!ready) return@LaunchedEffect
            if (!cameraReady) return@LaunchedEffect
            if (m.cameraPosition.zoom < 1.0) return@LaunchedEffect
            if (settings.display.followBoat && (lat == null || lon == null)) {
                return@LaunchedEffect
            }
            val bearing = if (settings.display.courseUp) {
                Math.toDegrees(heading ?: cog ?: 0.0)
            } else {
                0.0
            }
            val target = if (settings.display.followBoat && lat != null && lon != null) {
                LatLng(lat, lon)
            } else {
                m.cameraPosition.target ?: return@LaunchedEffect
            }
            if (!settings.display.courseUp &&
                !settings.display.followBoat &&
                m.cameraPosition.bearing == 0.0
            ) {
                return@LaunchedEffect
            }
            m.animateCamera(
                CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder()
                        .target(target)
                        .zoom(m.cameraPosition.zoom)
                        .bearing(bearing)
                        .build(),
                ),
                400,
            )
        }

        LaunchedEffect(anchor, bounds, downloadArea, ready) {
            val style = map?.style ?: return@LaunchedEffect
            if (!ready) return@LaunchedEffect
            updateAnchor(style, anchor)
            updateArea(style, if (downloadArea) bounds else null)
        }

        LaunchedEffect(measureStart, measureEnd, ready) {
            val style = map?.style ?: return@LaunchedEffect
            if (!ready) return@LaunchedEffect
            updateMeasure(style, measureStart, measureEnd)
        }

        LaunchedEffect(mob, lat, lon, ready) {
            val style = map?.style ?: return@LaunchedEffect
            if (!ready) return@LaunchedEffect
            updateMob(style, mob, lat, lon)
        }

        LaunchedEffect(
            ais, internetAis, lat, lon,
            settings.display.aisVectors, settings.display.showAis, ready,
        ) {
            val style = map?.style ?: return@LaunchedEffect
            if (!ready) return@LaunchedEffect
            updateAisVectors(
                style,
                ais.values + internetAis.values,
                settings.display.aisVectors && settings.display.showAis,
            )
        }

        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> mapView?.onStart()
                    Lifecycle.Event.ON_RESUME -> mapView?.onResume()
                    Lifecycle.Event.ON_PAUSE -> mapView?.onPause()
                    Lifecycle.Event.ON_STOP -> mapView?.onStop()
                    else -> Unit
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }

        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MapHud(
                statusLabel = status.label,
                active = hasFix,
                lat = lat,
                lon = lon,
                sogMps = sogMps,
                cogRad = cog,
                headingRad = heading,
                depthM = depthM,
                windKn = windKn,
                aisCount = ais.size + internetAis.size,
                chartName = chartName,
                guidance = guidance,
                navTargetName = navTarget?.name,
                sailPlan = sailingAdvice.plan,
                tackAdvice = sailingAdvice.tack,
                onCenter = {
                    if (lat != null && lon != null) {
                        map?.animateCamera(
                            CameraUpdateFactory.newLatLngZoom(
                                LatLng(lat, lon),
                                map?.cameraPosition?.zoom ?: 11.0,
                            ),
                            600,
                        )
                    }
                },
                onOpenCharts = { showChartSwitcher = true },
                onClearNav = { vm.clearNavTarget() },
            )
            if (chartLoading) {
                ChartLoadingBanner()
            }
            chartError?.let { message ->
                ChartErrorBanner(
                    message = message,
                    onRetry = {
                        chartError = null
                        chartFallback = false
                        reloadToken += 1
                    },
                    onUseOsm = {
                        chartError = null
                        chartFallback = false
                        vm.useOsmBase()
                    },
                    onDismiss = { chartError = null },
                )
            }
            moveTarget?.let { wp ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Mueve «${wp.name ?: "WP"}»: toca el nuevo sitio",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { moveTarget = null }) { Text("Cancelar") }
                    }
                }
            }
            if (alarms.isNotEmpty()) {
                AlarmBanner(alarms)
            }
            mob?.let { marker ->
                MobBanner(
                    marker = marker,
                    ownLat = lat,
                    ownLon = lon,
                    onClear = { vm.clearMob() },
                )
            }
        }

        if (measureMode) {
            MeasureCard(
                start = measureStart,
                end = measureEnd,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 12.dp, end = 12.dp, bottom = 86.dp),
                onClear = {
                    measureStart = null
                    measureEnd = null
                },
                onExit = {
                    measureMode = false
                    measureStart = null
                    measureEnd = null
                },
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (settings.display.showCompass) {
                SmallFloatingActionButton(
                    onClick = {
                        vm.setDisplaySettings(
                            settings.display.copy(courseUp = !settings.display.courseUp),
                        )
                    },
                ) {
                    Text(
                        if (settings.display.courseUp) "COG" else "N",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            SmallFloatingActionButton(
                onClick = { map?.animateCamera(CameraUpdateFactory.zoomIn()) },
            ) { Icon(Icons.Filled.Add, contentDescription = "Acercar") }
            SmallFloatingActionButton(
                onClick = { map?.animateCamera(CameraUpdateFactory.zoomOut()) },
            ) { Icon(Icons.Filled.Remove, contentDescription = "Alejar") }
        }

        MapActionDock(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 12.dp)
                .padding(bottom = 10.dp),
            trackEnabled = trackEnabled,
            trackPointsCount = trackPoints.size,
            vectors = settings.display.aisVectors,
            measureMode = measureMode,
            aisCount = ais.size + internetAis.size,
            onMob = { vm.setMob() },
            onWaypoint = {
                vm.createWaypointAtVessel("WP ${System.currentTimeMillis() % 100000}")
            },
            onMeasure = {
                measureMode = !measureMode
                measureStart = null
                measureEnd = null
            },
            onAis = { showAisSheet = true },
            onLayers = { showLayers = true },
            onGoTo = { showGoTo = true },
            onTrack = { vm.setTrackEnabled(!trackEnabled) },
            onVectors = { vm.setAisVectors(!settings.display.aisVectors) },
            onClearTrack = { vm.clearTrack() },
        )

        if (settings.display.showScaleBar) {
            ScaleBar(
                metersPerPixel = metersPerPixel,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 8.dp, bottom = 80.dp),
            )
        }

        val attributionText = buildList {
            if (chartFallback) add(DefaultCharts.OSM.attribution)
            baseSource?.attribution?.takeIf { it.isNotBlank() }?.let(::add)
            charts.filter { it.kind == ChartKind.S57 && it.enabled }
                .map { it.attribution }
                .filter { it.isNotBlank() }
                .forEach(::add)
        }.joinToString(" · ")

        if (attributionText.isNotBlank()) {
            Text(
                attributionText,
                style = MaterialTheme.typography.labelSmall,
                color = MarineColors.TextDim,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 8.dp, bottom = 60.dp),
            )
        }
    }

    pendingPoint?.let { point ->
        PointDialog(
            point = point,
            ownLat = lat,
            ownLon = lon,
            hasActiveRoute = activeRouteId != null,
            vectorBase = !chartFallback && (
                baseSource?.kind == ChartKind.STYLE ||
                    baseSource?.kind == ChartKind.PMTILES_VECTOR
                ),
            onDismiss = { pendingPoint = null },
            onWaypoint = { name ->
                vm.createWaypoint(name.ifBlank { "WP" }, point.lat, point.lon)
                pendingPoint = null
            },
            onGoTo = { name ->
                vm.goTo(point.lat, point.lon, name.ifBlank { "GoTo" })
                pendingPoint = null
            },
            onAddToRoute = { name ->
                vm.addToActiveRoute(point.lat, point.lon, name.ifBlank { "WP" })
                pendingPoint = null
            },
        )
    }

    movingWaypoint?.let { wp ->
        AlertDialog(
            onDismissRequest = { movingWaypoint = null },
            title = { Text(wp.name ?: "Waypoint") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        Formatters.position(wp.position.latitude, wp.position.longitude),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "Puedes moverlo a otra posición, ir hacia él o borrarlo.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = {
                        moveTarget = wp
                        movingWaypoint = null
                    }) { Text("Mover") }
                    TextButton(onClick = {
                        vm.goTo(wp.position.latitude, wp.position.longitude, wp.name ?: "Waypoint")
                        movingWaypoint = null
                    }) { Text("Ir al waypoint") }
                    TextButton(onClick = {
                        wp.id?.let(vm::deleteWaypoint)
                        movingWaypoint = null
                    }) { Text("Borrar") }
                }
            },
            dismissButton = {
                TextButton(onClick = { movingWaypoint = null }) { Text("Cerrar") }
            },
        )
    }

    selectedAisId?.let { id -> ais[id] ?: internetAis[id] }?.let { target ->
        AisDetailDialog(
            target = target,
            ownLat = lat,
            ownLon = lon,
            ownSog = state.number(Paths.SOG),
            ownCog = cog,
            onDismiss = { selectedAisId = null },
        )
    }

    if (showAisSheet) {
        AisTargetSheet(
            targets = ais.values + internetAis.values,
            ownLat = lat,
            ownLon = lon,
            ownSogMps = sogMps,
            ownCogRad = cog,
            alarmIds = alarms.map { it.targetId }.toSet(),
            onClose = { showAisSheet = false },
            onCenter = { target ->
                vm.focusAis(target.lat, target.lon)
                showAisSheet = false
            },
            onWaypoint = { target ->
                vm.createWaypoint(target.name ?: target.label, target.lat, target.lon)
            },
            onDetails = { target ->
                selectedAisId = target.id
                showAisSheet = false
            },
        )
    }

    if (showLayers) {
        LayerSheet(
            display = settings.display,
            onChange = { vm.setDisplaySettings(it) },
            onClose = { showLayers = false },
        )
    }

    if (showGoTo) {
        GoToDialog(
            waypoints = waypoints,
            onDismiss = { showGoTo = false },
            onGoTo = { lat, lon, name ->
                vm.goTo(lat, lon, name)
                showGoTo = false
            },
            onCenter = { lat, lon ->
                vm.focusMap(lat, lon, 12.0)
                showGoTo = false
            },
        )
    }

    if (showChartSwitcher) {
        ChartSwitcherSheet(
            charts = baseCharts,
            activeId = if (chartFallback) null else settings.activeChartId,
            fallback = chartFallback,
            onSelect = { chart ->
                vm.setChartEnabled(chart.id, true)
                vm.setActiveChart(chart.id)
                showChartSwitcher = false
            },
            onManage = {
                showChartSwitcher = false
                vm.showMessage("Gestiona cartas y capas en la pestaña Cartas")
            },
            onDismiss = { showChartSwitcher = false },
        )
    }
}

@Composable
private fun AlarmBanner(alarms: List<AisAlarm>) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Warning, contentDescription = null)
                Text("  Alarma AIS (${alarms.size})", style = MaterialTheme.typography.titleSmall)
            }
            alarms.take(2).forEach { alarm ->
                Text(
                    "${alarm.label} · CPA ${Formatters.distance(alarm.dcpaM)} · TCPA ${formatTcpa(alarm.tcpaSec)}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun MapHud(
    statusLabel: String,
    active: Boolean,
    lat: Double?,
    lon: Double?,
    sogMps: Double?,
    cogRad: Double?,
    headingRad: Double?,
    depthM: Double?,
    windKn: Double?,
    aisCount: Int,
    chartName: String,
    guidance: NavGuidance,
    navTargetName: String?,
    sailPlan: SailPlan?,
    tackAdvice: TackAdvice?,
    onCenter: () -> Unit,
    onOpenCharts: () -> Unit,
    onClearNav: () -> Unit,
) {
    GlassCard(
        Modifier.fillMaxWidth(),
        accent = if (tackAdvice?.tackNow == true) MarineColors.Alert else MarineColors.Harbor,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatusPill(label = statusLabel, active = active)
            Spacer(Modifier.weight(1f))
            HudIconButton(
                icon = Icons.Filled.MyLocation,
                description = "Centrar en el barco",
                onClick = onCenter,
            )
            HudIconButton(
                icon = Icons.Filled.Layers,
                description = "Cambiar carta",
                onClick = onOpenCharts,
            )
        }

        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                Formatters.position(lat, lon),
                style = MaterialTheme.typography.bodySmall,
                color = MarineColors.TextDim,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                chartName,
                style = MaterialTheme.typography.labelMedium,
                color = MarineColors.Harbor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            MiniReadout("SOG", Formatters.knots(sogMps))
            MiniReadout("COG", Formatters.degrees(cogRad))
            MiniReadout("Rumbo", Formatters.degrees(headingRad))
            MiniReadout("Prof.", Formatters.meters(depthM))
            MiniReadout("Viento", windKn?.let { String.format("%.1f kn", it) } ?: "—")
            MiniReadout(
                "AIS",
                aisCount.toString(),
                valueColor = if (aisCount > 0) MarineColors.Harbor else MarineColors.Text,
            )
        }

        if (guidance.active) {
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "→ ${guidance.name ?: "destino"}",
                    style = MaterialTheme.typography.titleSmall,
                    color = MarineColors.Harbor,
                    maxLines = 1,
                )
                Text("DTW ${Formatters.distance(guidance.distanceM)}", style = MaterialTheme.typography.bodySmall)
                Text(
                    "Brg ${Formatters.degreesDirect(guidance.bearingDeg)}",
                    style = MaterialTheme.typography.bodySmall,
                )
                guidance.xteM?.let {
                    Text(
                        "XTE ${Formatters.distance(kotlin.math.abs(it))} ${if (it >= 0) "Er" else "Br"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (guidance.offTrack) MarineColors.Alert else MarineColors.Text,
                    )
                }
                Text("VMG ${Formatters.knots(guidance.vmgMps)}", style = MaterialTheme.typography.bodySmall)
                Text(
                    "ETA ${formatEta(guidance.etaMs)} (${formatTtg(guidance.timeToGoSec)})",
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = onClearNav, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)) {
                    Text("Cancelar")
                }
            }
            if (guidance.offTrack) {
                Text(
                    "⚠ Fuera de rumbo: corrige hacia ${guidance.name ?: "el waypoint"}",
                    color = MarineColors.Alert,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            sailPlan?.let { plan ->
                Spacer(Modifier.height(4.dp))
                Text(
                    "Velas · ${plan.pointOfSail}: ${plan.sails}",
                    color = MarineColors.Mint,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            tackAdvice?.takeIf { it.active }?.let { advice ->
                advice.message?.let { message ->
                    Text(
                        if (advice.tackNow) "⚠ $message" else message,
                        color = if (advice.tackNow) MarineColors.Alert else MarineColors.TextDim,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        } else if (navTargetName != null) {
            Text(
                "→ $navTargetName",
                style = MaterialTheme.typography.bodySmall,
                color = MarineColors.TextDim,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun HudIconButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = MarineColors.Harbor.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, MarineColors.Harbor.copy(alpha = 0.28f)),
    ) {
        Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) {
            Icon(
                icon,
                contentDescription = description,
                tint = MarineColors.Harbor,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun ChartLoadingBanner() {
    GlassCard(Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = MarineColors.Harbor,
            )
            Text(
                "Cargando carta…",
                style = MaterialTheme.typography.bodySmall,
                color = MarineColors.TextDim,
            )
        }
    }
}

@Composable
private fun ChartErrorBanner(
    message: String,
    onRetry: () -> Unit,
    onUseOsm: () -> Unit,
    onDismiss: () -> Unit,
) {
    GlassCard(Modifier.fillMaxWidth(), accent = MarineColors.Alert) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.Warning,
                contentDescription = null,
                tint = MarineColors.Alert,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = MarineColors.Text,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDismiss) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Cerrar aviso",
                    tint = MarineColors.TextDim,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onUseOsm) { Text("Usar OSM") }
            TextButton(onClick = onRetry) {
                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Reintentar")
            }
        }
    }
}

@Composable
private fun MapActionDock(
    modifier: Modifier = Modifier,
    trackEnabled: Boolean,
    trackPointsCount: Int,
    vectors: Boolean,
    measureMode: Boolean,
    aisCount: Int,
    onMob: () -> Unit,
    onWaypoint: () -> Unit,
    onMeasure: () -> Unit,
    onAis: () -> Unit,
    onLayers: () -> Unit,
    onGoTo: () -> Unit,
    onTrack: () -> Unit,
    onVectors: () -> Unit,
    onClearTrack: () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = MarineColors.Surface.copy(alpha = 0.96f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.07f)),
        shadowElevation = 12.dp,
    ) {
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 6.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DockAction(
                label = "MOB",
                icon = Icons.Filled.Emergency,
                tint = MarineColors.Alert,
                onClick = onMob,
            )
            DockAction(
                label = "Waypoint",
                icon = Icons.Filled.AddLocationAlt,
                onClick = onWaypoint,
            )
            DockAction(
                label = "Medir",
                icon = Icons.Filled.Straighten,
                active = measureMode,
                onClick = onMeasure,
            )
            DockAction(
                label = if (aisCount > 0) "AIS $aisCount" else "AIS",
                icon = Icons.Filled.Radar,
                active = aisCount > 0,
                onClick = onAis,
            )
            DockAction(
                label = "Capas",
                icon = Icons.Filled.Layers,
                onClick = onLayers,
            )
            DockAction(
                label = "Ir a",
                icon = Icons.Filled.Place,
                onClick = onGoTo,
            )
            DockAction(
                label = "Estela",
                icon = Icons.Filled.Timeline,
                active = trackEnabled,
                onClick = onTrack,
            )
            DockAction(
                label = "Vectores",
                icon = Icons.Filled.Navigation,
                active = vectors,
                onClick = onVectors,
            )
            if (trackPointsCount > 0) {
                DockAction(
                    label = "Limpiar",
                    icon = Icons.Filled.Delete,
                    onClick = onClearTrack,
                )
            }
        }
    }
}

@Composable
private fun DockAction(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    active: Boolean = false,
    tint: Color = if (active) MarineColors.Harbor else MarineColors.TextDim,
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (active) MarineColors.Harbor.copy(alpha = 0.14f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.height(3.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            maxLines = 1,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChartSwitcherSheet(
    charts: List<ChartSource>,
    activeId: String?,
    fallback: Boolean,
    onSelect: (ChartSource) -> Unit,
    onManage: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MarineColors.Surface,
        contentColor = MarineColors.Text,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Carta base", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Elige la carta que se dibuja debajo de las capas.",
                style = MaterialTheme.typography.bodySmall,
                color = MarineColors.TextDim,
            )
            Spacer(Modifier.height(4.dp))
            if (charts.isEmpty()) {
                Text(
                    "No hay cartas instaladas. Añade una en la pestaña Cartas.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MarineColors.TextDim,
                )
            }
            charts.forEach { chart ->
                val selected = !fallback && chart.id == activeId
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            if (selected) MarineColors.Harbor.copy(alpha = 0.10f)
                            else Color.Transparent,
                        )
                        .clickable { onSelect(chart) }
                        .padding(horizontal = 6.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = selected,
                        onClick = { onSelect(chart) },
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            chart.name,
                            style = MaterialTheme.typography.titleSmall,
                            color = MarineColors.Text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "${chartKindLabel(chart.kind)} · z${chart.minZoom}–${chart.maxZoom}" +
                                if (chart.enabled) "" else " · apagada",
                            style = MaterialTheme.typography.labelSmall,
                            color = MarineColors.TextDim,
                            maxLines = 1,
                        )
                    }
                }
            }
            if (fallback) {
                Text(
                    "Mostrando OpenStreetMap como respaldo.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MarineColors.Alert,
                )
            }
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = onManage) { Text("Gestionar cartas y capas") }
        }
    }
}

private fun chartKindLabel(kind: ChartKind): String = when (kind) {
    ChartKind.XYZ -> "teselas XYZ"
    ChartKind.STYLE -> "estilo vectorial"
    ChartKind.MBTILES -> "MBTiles"
    ChartKind.PMTILES -> "PMTiles"
    ChartKind.PMTILES_VECTOR -> "PMTiles vectorial"
    ChartKind.BSB_KAP -> "BSB/KAP"
    ChartKind.GEOPACKAGE -> "GeoPackage"
    ChartKind.S57 -> "S-57"
    ChartKind.SIGNALK -> "SignalK"
}

@Composable
private fun MobBanner(
    marker: MobMarker,
    ownLat: Double?,
    ownLon: Double?,
    onClear: () -> Unit,
) {
    val distance = if (ownLat != null && ownLon != null) {
        Geo.distanceMeters(LatLon(ownLat, ownLon), LatLon(marker.lat, marker.lon))
    } else {
        null
    }
    val bearing = if (ownLat != null && ownLon != null) {
        Geo.bearingDegrees(LatLon(ownLat, ownLon), LatLon(marker.lat, marker.lon))
    } else {
        null
    }
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Emergency, contentDescription = null)
            Text(
                "  MOB · ${Formatters.distance(distance)} · ${Formatters.degreesDirect(bearing)}",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onClear) { Text("Borrar") }
        }
    }
}

@Composable
private fun MeasureCard(
    start: LatLon?,
    end: LatLon?,
    modifier: Modifier = Modifier,
    onClear: () -> Unit,
    onExit: () -> Unit,
) {
    Card(modifier, shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Medir distancia", style = MaterialTheme.typography.titleSmall)
            when {
                start == null -> Text(
                    "Toca el punto de salida en la carta.",
                    style = MaterialTheme.typography.bodySmall,
                )
                end == null -> Text(
                    "Salida ${Formatters.position(start.lat, start.lon)} · toca el punto de llegada.",
                    style = MaterialTheme.typography.bodySmall,
                )
                else -> {
                    Text(
                        "Distancia ${Formatters.distance(Geo.distanceMeters(start, end))} · " +
                            "demora ${Formatters.degreesDirect(Geo.bearingDegrees(start, end))}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        Formatters.position(start.lat, start.lon) + " → " +
                            Formatters.position(end.lat, end.lon),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onClear) { Text("Borrar") }
                TextButton(onClick = onExit) { Text("Salir") }
            }
        }
    }
}

private data class MapFeatureInfo(
    val title: String,
    val rows: List<Pair<String, String>>,
)

private data class PendingPoint(
    val lat: Double,
    val lon: Double,
    val features: List<MapFeatureInfo>,
)

private fun Feature.toInfo(): MapFeatureInfo {
    val props = runCatching { properties() }.getOrNull()

    fun value(key: String): String? = runCatching {
        val element = props?.get(key) ?: return@runCatching null
        if (element.isJsonPrimitive) element.asString.takeIf { it.isNotBlank() } else null
    }.getOrNull()

    val title = value("name") ?: value("seamark:name") ?: value("seamark:type") ?: "Punto"
    val fields = listOf(
        "seamark:type" to "Tipo",
        "seamark:seabed_area:surface" to "Fondo",
        "seamark:seabed_area:surface_qualification" to "Fondo (detalle)",
        "seamark:seabed_area:quality" to "Calidad del fondo",
        "seabed_depth" to "Sonda",
        "seamark:depth" to "Profundidad",
        "seamark:rock:quality" to "Roca",
        "seamark:obstruction:surface" to "Obstrucción",
        "seamark:wreck:type" to "Pecio",
        "seamark:light:character" to "Luz",
        "seamark:light:range" to "Alcance",
        "k" to "Objeto S-57",
        "depth" to "Sonda",
        "layer" to "Capa",
    )
    val rows = fields.mapNotNull { (key, label) ->
        value(key)?.takeIf { it.isNotBlank() }?.let { label to it }
    }
    return MapFeatureInfo(title, rows)
}

@Composable
private fun PointDialog(
    point: PendingPoint,
    ownLat: Double?,
    ownLon: Double?,
    hasActiveRoute: Boolean,
    vectorBase: Boolean,
    onDismiss: () -> Unit,
    onWaypoint: (String) -> Unit,
    onGoTo: (String) -> Unit,
    onAddToRoute: (String) -> Unit,
) {
    var name by remember {
        mutableStateOf(
            point.features.firstOrNull()?.title?.takeIf { it.isNotBlank() && it != "Punto" } ?: "WP",
        )
    }
    var confirmingGoTo by remember { mutableStateOf(false) }

    val distance = if (ownLat != null && ownLon != null) {
        Geo.distanceMeters(LatLon(ownLat, ownLon), LatLon(point.lat, point.lon))
    } else {
        null
    }
    val bearing = if (ownLat != null && ownLon != null) {
        Geo.bearingDegrees(LatLon(ownLat, ownLon), LatLon(point.lat, point.lon))
    } else {
        null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (confirmingGoTo) "Confirmar rumbo al punto"
                else point.features.firstOrNull()?.title ?: "Punto",
            )
        },
        text = {
            if (confirmingGoTo) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "El piloto automático navegará a este punto. Se creará una ruta de un " +
                            "punto y se activará.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    DetailRow("Distancia", Formatters.distance(distance))
                    DetailRow("Demora", Formatters.degreesDirect(bearing))
                    DetailRow("Posición", Formatters.position(point.lat, point.lon))
                    if (distance == null) {
                        Text(
                            "Sin posición propia: no se puede calcular la distancia.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            } else {
                Column(
                    Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        Formatters.position(point.lat, point.lon),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    point.features.forEach { info ->
                        if (info.rows.isNotEmpty()) {
                            Text(info.title, style = MaterialTheme.typography.titleSmall)
                            info.rows.forEach { (label, value) -> DetailRow(label, value) }
                        }
                    }
                    val hasSeabed = point.features.any { info ->
                        info.rows.any { it.first == "Fondo" }
                    }
                    if (!hasSeabed) {
                        Text("Fondo marino", style = MaterialTheme.typography.titleSmall)
                        Text(
                            if (vectorBase) {
                                "Sin datos para este punto; amplía el mapa."
                            } else {
                                "Solo disponible con la carta Seamap (vectorial)."
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Nombre") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(onClick = { onWaypoint(name) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Añadir waypoint")
                    }
                    Button(
                        onClick = { confirmingGoTo = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Ir aquí (piloto automático)")
                    }
                    Button(
                        onClick = { onAddToRoute(name) },
                        enabled = hasActiveRoute,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (hasActiveRoute) "Añadir a ruta activa" else "Sin ruta activa")
                    }
                }
            }
        },
        confirmButton = {
            if (confirmingGoTo) {
                TextButton(onClick = { onGoTo(name) }) { Text("Confirmar") }
            } else {
                TextButton(onClick = onDismiss) { Text("Cerrar") }
            }
        },
        dismissButton = if (confirmingGoTo) {
            { TextButton(onClick = { confirmingGoTo = false }) { Text("Cancelar") } }
        } else {
            null
        },
    )
}

@Composable
private fun AisDetailDialog(
    target: AisTarget,
    ownLat: Double?,
    ownLon: Double?,
    ownSog: Double?,
    ownCog: Double?,
    onDismiss: () -> Unit,
) {
    val cpa = if (ownLat != null && ownLon != null) {
        Cpa.compute(
            ownLat, ownLon, ownSog, ownCog,
            target.lat, target.lon, target.sogMps, target.cogRad,
        )
    } else {
        null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(target.name ?: "Objetivo AIS") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                DetailRow("MMSI", target.mmsi ?: "—")
                if (target.category != null) DetailRow("Tipo", ShipType.label(target.category))
                if (target.source != null) {
                    DetailRow("Origen", if (target.source == "internet") "AIS Internet" else "SignalK")
                }
                DetailRow("Clase", target.vesselClass ?: "—")
                DetailRow("Posición", Formatters.position(target.lat, target.lon))
                DetailRow("SOG", Formatters.knots(target.sogMps))
                DetailRow("COG", Formatters.degrees(target.cogRad))
                DetailRow("Rumbo", Formatters.degrees(target.headingRad))
                target.destination?.takeIf { it.isNotBlank() }?.let { DetailRow("Destino", it) }
                target.navStatus?.takeIf { it.isNotBlank() }?.let { DetailRow("Estado", it) }
                target.lengthM?.let { DetailRow("Eslora", "%.1f m".format(it)) }
                target.beamM?.let { DetailRow("Manga", "%.1f m".format(it)) }
                target.draughtM?.let { DetailRow("Calado", "%.1f m".format(it)) }
                target.rotDegPerMin?.let { DetailRow("ROT", "%.0f °/min".format(it)) }
                if (cpa != null) {
                    DetailRow("Distancia", Formatters.distance(cpa.distanceM))
                    DetailRow("Demora", Formatters.degreesDirect(cpa.bearingDeg))
                    DetailRow("CPA", cpa.dcpaM?.let { Formatters.distance(it) } ?: "—")
                    DetailRow("TCPA", cpa.tcpaSec?.let { formatTcpa(it) } ?: "—")
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } },
    )
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun formatTcpa(seconds: Double): String = when {
    seconds >= 3600 -> "%.1f h".format(seconds / 3600)
    seconds >= 60 -> "%.0f min".format(seconds / 60)
    else -> "%.0f s".format(seconds)
}

private fun formatTtg(seconds: Double?): String {
    if (seconds == null || !seconds.isFinite() || seconds <= 0) return "—"
    val totalMinutes = (seconds / 60).toInt()
    val h = totalMinutes / 60
    val m = totalMinutes % 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}

private fun formatEta(epochMs: Long?): String {
    if (epochMs == null) return "—"
    return runCatching {
        java.time.Instant.ofEpochMilli(epochMs)
            .atZone(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
    }.getOrDefault("—")
}

@Composable
private fun ScaleBar(metersPerPixel: Double, modifier: Modifier = Modifier) {
    if (!metersPerPixel.isFinite() || metersPerPixel <= 0.0) return
    val distance = niceDistance(metersPerPixel * 90.0)
    val widthDp = with(LocalDensity.current) {
        (distance / metersPerPixel).toFloat().toDp()
    }
    Column(
        modifier
            .background(Color(0x99000000), RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 3.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        Text(
            Formatters.distance(distance),
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
        )
        Box(Modifier.width(widthDp).height(2.dp).background(Color.White))
    }
}

private fun niceDistance(target: Double): Double {
    val candidates = doubleArrayOf(
        10.0, 20.0, 50.0, 100.0, 200.0, 500.0,
        1_000.0, 2_000.0, 5_000.0, 10_000.0, 20_000.0, 50_000.0, 100_000.0, 200_000.0,
    )
    return candidates.firstOrNull { it >= target } ?: candidates.last()
}

private fun aisFont(@Suppress("UNUSED_PARAMETER") base: ChartSource?): String =
    // Both the demotiles glyph server (blank raster style) and the vector styles ship
    // Noto Sans Regular. "Open Sans Regular" 404s on demotiles and labels used to render
    // as missing-glyph placeholders (magenta boxes).
    "Noto Sans Regular"

private fun addCharts(
    style: Style,
    charts: List<ChartSource>,
    activeId: String?,
    applied: MutableList<String>,
) {
    applied.forEach { id ->
        runCatching { style.removeLayer("layer-$id") }
        runCatching { style.removeSource(id) }
    }
    applied.clear()

    val active = charts.firstOrNull { it.id == activeId }

    val localKinds = setOf(
        ChartKind.MBTILES,
        ChartKind.PMTILES,
        ChartKind.BSB_KAP,
        ChartKind.GEOPACKAGE,
    )

    // A vector style is the background itself: raster overlays and local chart packages are
    // painted on top of it (so a BSB/KAP or GeoPackage chart still works over Seamap).
    if (active != null && active.enabled &&
        (active.kind == ChartKind.STYLE || active.kind == ChartKind.PMTILES_VECTOR)
    ) {
        val local = charts
            .filter { !it.isOverlay && it.enabled && it.kind in localKinds }
            .sortedBy { it.priority }
        val overlays = charts
            .filter { it.isOverlay && it.enabled && it.kind != ChartKind.S57 }
            .sortedBy { it.priority }
        addRasterLayers(style, local + overlays, applied)
        return
    }

    val remoteKinds = setOf(ChartKind.XYZ, ChartKind.SIGNALK)
    val remote = charts.filter { !it.isOverlay && it.enabled && it.kind in remoteKinds }
    val local = charts.filter { !it.isOverlay && it.enabled && it.kind in localKinds }

    val ordered = ArrayList<ChartSource>()
    // Remote base: the active one, or OSM as a fallback under local chart packages.
    val activeRemote = remote.firstOrNull { it.id == activeId }
    if (activeRemote != null) {
        ordered.add(activeRemote)
    } else {
        remote.firstOrNull { it.id == "osm" }?.let { ordered.add(it) }
            ?: remote.firstOrNull()?.let { ordered.add(it) }
    }

    // Local chart packages are quilted on top of the remote base, sorted by priority.
    val localOrder = local.sortedBy { it.priority }.toMutableList()
    if (active != null && active.enabled && active.kind in localKinds) {
        localOrder.removeAll { it.id == active.id }
        localOrder.add(active)
    }
    ordered.addAll(localOrder)

    // Overlays (OpenSeaMap, user XYZ overlays) go last/top; S-57 has its own layers.
    ordered.addAll(
        charts.filter { it.isOverlay && it.enabled && it.kind != ChartKind.S57 }
            .sortedBy { it.priority },
    )

    addRasterLayers(style, ordered, applied)
}

private fun addChartAnchor(style: Style) {
    if (style.getLayer(CHART_ANCHOR_LAYER) != null) return
    runCatching {
        style.addSource(
            GeoJsonSource(CHART_ANCHOR_SOURCE, FeatureCollection.fromFeatures(emptyArray<Feature>())),
        )
        style.addLayer(
            LineLayer(CHART_ANCHOR_LAYER, CHART_ANCHOR_SOURCE).withProperties(
                PropertyFactory.lineOpacity(0f),
            ),
        )
    }
}

private fun addRasterLayers(
    style: Style,
    sources: List<ChartSource>,
    applied: MutableList<String>,
) {
    for (source in sources) {
        if (source.kind == ChartKind.STYLE ||
            source.kind == ChartKind.PMTILES_VECTOR ||
            source.kind == ChartKind.S57
        ) {
            continue
        }
        val id = "chart-${source.id}"
        runCatching {
            val minZoom = source.minZoom
            val maxZoom = source.maxZoom.coerceAtLeast(minZoom)
            val tiles = TileSet("2.1.0", source.urlTemplate).apply {
                this.minZoom = minZoom.toFloat()
                this.maxZoom = maxZoom.toFloat()
            }
            style.addSource(RasterSource(id, tiles, source.tileSize))
            val layer = RasterLayer("layer-$id", id)
            runCatching { style.addLayerBelow(layer, CHART_ANCHOR_LAYER) }
                .recoverCatching { style.addLayerBelow(layer, "vessel-layer") }
                .recoverCatching { style.addLayer(layer) }
            applied.add(id)
        }
    }
}

private val S57_LAYERS = listOf(
    "depth", "land", "area", "sector", "outline", "line", "point", "sound", "icon", "label",
)

/**
 * Draws every enabled S-57 chart as a set of MapLibre layers: depth fills, land, translucent
 * restricted/fairway areas, polygon outlines, lines, point symbols and soundings.
 */
private fun addS57Charts(
    style: Style,
    charts: List<ChartSource>,
    applied: MutableList<String>,
) {
    for (sourceId in applied.toList()) {
        for (suffix in S57_LAYERS) runCatching { style.removeLayer("$sourceId-$suffix") }
        runCatching { style.removeSource(sourceId) }
    }
    applied.clear()

    fun layerIs(value: String): Expression =
        Expression.eq(Expression.get("layer"), Expression.literal(value))

    if (charts.any { it.kind == ChartKind.S57 && it.enabled }) {
        addS57Icons(style)
    }

    for (chart in charts) {
        val geoJson = chart.geoJson ?: continue
        if (chart.kind != ChartKind.S57 || !chart.enabled) continue
        val sourceId = "s57-${chart.id}"

        runCatching {
            style.addSource(GeoJsonSource(sourceId, geoJson))

            addS57Layer(
                style,
                FillLayer("$sourceId-depth", sourceId).withFilter(layerIs("depth")).withProperties(
                    PropertyFactory.fillColor(Expression.toColor(Expression.get("fill"))),
                    PropertyFactory.fillOpacity(Expression.get("opacity")),
                ),
                chart,
            )
            addS57Layer(
                style,
                FillLayer("$sourceId-land", sourceId).withFilter(layerIs("land")).withProperties(
                    PropertyFactory.fillColor(Expression.toColor(Expression.get("fill"))),
                    PropertyFactory.fillOpacity(Expression.get("opacity")),
                ),
                chart,
            )
            addS57Layer(
                style,
                FillLayer("$sourceId-area", sourceId).withFilter(layerIs("area")).withProperties(
                    PropertyFactory.fillColor(Expression.toColor(Expression.get("fill"))),
                    PropertyFactory.fillOpacity(Expression.get("opacity")),
                ),
                chart,
            )
            addS57Layer(
                style,
                FillLayer("$sourceId-sector", sourceId).withFilter(layerIs("sector")).withProperties(
                    PropertyFactory.fillColor(Expression.toColor(Expression.get("fill"))),
                    PropertyFactory.fillOpacity(Expression.get("opacity")),
                    PropertyFactory.fillOutlineColor(Expression.toColor(Expression.get("stroke"))),
                ),
                chart,
            )
            addS57Layer(
                style,
                LineLayer("$sourceId-outline", sourceId).withFilter(layerIs("area")).withProperties(
                    PropertyFactory.lineColor(Expression.toColor(Expression.get("stroke"))),
                    PropertyFactory.lineWidth(Expression.get("width")),
                    PropertyFactory.lineOpacity(0.85f),
                ),
                chart,
            )
            addS57Layer(
                style,
                LineLayer("$sourceId-line", sourceId).withFilter(layerIs("line")).withProperties(
                    PropertyFactory.lineColor(Expression.toColor(Expression.get("stroke"))),
                    PropertyFactory.lineWidth(Expression.get("width")),
                ),
                chart,
            )
            addS57Layer(
                style,
                CircleLayer("$sourceId-point", sourceId).withFilter(layerIs("point")).withProperties(
                    PropertyFactory.circleColor(Expression.toColor(Expression.get("fill"))),
                    PropertyFactory.circleRadius(Expression.get("radius")),
                    PropertyFactory.circleStrokeColor("#FFFFFF"),
                    PropertyFactory.circleStrokeWidth(1f),
                ),
                chart,
            )
            val icons = SymbolLayer("$sourceId-icon", sourceId)
                .withFilter(Expression.has("icon"))
                .withProperties(
                    PropertyFactory.iconImage(Expression.get("icon")),
                    PropertyFactory.iconSize(0.5f),
                    PropertyFactory.iconAllowOverlap(true),
                    PropertyFactory.iconIgnorePlacement(true),
                    PropertyFactory.iconOptional(true),
                )
            addS57Layer(style, icons, chart)
            icons.minZoom = maxOf(chart.minZoom, 11).toFloat()
            val soundings = CircleLayer("$sourceId-sound", sourceId)
                .withFilter(layerIs("sound"))
                .withProperties(
                    PropertyFactory.circleColor(Expression.toColor(Expression.get("fill"))),
                    PropertyFactory.circleRadius(Expression.get("radius")),
                )
            addS57Layer(style, soundings, chart)
            soundings.minZoom = maxOf(chart.minZoom, 13).toFloat()

            val labels = SymbolLayer("$sourceId-label", sourceId)
                .withFilter(layerIs("point"))
                .withProperties(
                    PropertyFactory.textField(Expression.get("label")),
                    PropertyFactory.textFont(arrayOf("Noto Sans Regular")),
                    PropertyFactory.textSize(10f),
                    PropertyFactory.textColor("#111111"),
                    PropertyFactory.textHaloColor("#FFFFFF"),
                    PropertyFactory.textHaloWidth(1.5f),
                    PropertyFactory.textOffset(arrayOf(0f, -1.4f)),
                    PropertyFactory.textAllowOverlap(false),
                )
            addS57Layer(style, labels, chart)
            labels.minZoom = maxOf(chart.minZoom, 12).toFloat()
        }.onFailure {
            for (suffix in S57_LAYERS) runCatching { style.removeLayer("$sourceId-$suffix") }
            runCatching { style.removeSource(sourceId) }
        }
        applied.add(sourceId)
    }
}

private fun addS57Layer(style: Style, layer: Layer, chart: ChartSource) {
    layer.minZoom = chart.minZoom.toFloat()
    layer.maxZoom = chart.maxZoom.toFloat()
    runCatching { style.addLayerBelow(layer, CHART_ANCHOR_LAYER) }
        .recoverCatching { style.addLayerBelow(layer, "vessel-layer") }
        .recoverCatching { style.addLayer(layer) }
}

/** Adds the small S-52 style symbols used by the ENC point layer. */
private fun addS57Icons(style: Style) {
    val names = listOf(
        "s57-buoy-port", "s57-buoy-starboard", "s57-cardinal", "s57-buoy-safe",
        "s57-buoy-danger", "s57-buoy-special", "s57-light", "s57-wreck",
        "s57-rock", "s57-obstruction", "s57-mooring",
    )
    for (name in names) {
        runCatching {
            if (style.getImage(name) == null) style.addImage(name, drawS57Icon(name))
        }
    }
}

/** Draws one 48×48 symbol at runtime; no external assets or fonts are needed. */
private fun drawS57Icon(kind: String): Bitmap {
    val bitmap = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.5f
        color = 0xFFFFFFFF.toInt()
    }

    fun path(block: Path.() -> Unit): Path = Path().apply(block)

    fun drawPath(p: Path, color: Int) {
        halo.strokeWidth = 3.5f
        halo.color = 0xFFFFFFFF.toInt()
        canvas.drawPath(p, halo)
        fill.color = color
        canvas.drawPath(p, fill)
    }

    fun drawCircle(cx: Float, cy: Float, radius: Float, color: Int) {
        halo.strokeWidth = 3.5f
        halo.color = 0xFFFFFFFF.toInt()
        canvas.drawCircle(cx, cy, radius, halo)
        fill.color = color
        canvas.drawCircle(cx, cy, radius, fill)
    }

    fun drawRect(left: Float, top: Float, right: Float, bottom: Float, color: Int) {
        halo.strokeWidth = 3.5f
        halo.color = 0xFFFFFFFF.toInt()
        canvas.drawRect(left, top, right, bottom, halo)
        fill.color = color
        canvas.drawRect(left, top, right, bottom, fill)
    }

    when (kind) {
        "s57-buoy-port" -> drawPath(
            path { addRoundRect(14f, 8f, 34f, 41f, 6f, 6f, Path.Direction.CW) },
            0xFFD32F2F.toInt(),
        )

        "s57-buoy-starboard" -> drawPath(
            path { moveTo(24f, 6f); lineTo(39f, 41f); lineTo(9f, 41f); close() },
            0xFF2E7D32.toInt(),
        )

        "s57-cardinal" -> {
            drawPath(
                path { moveTo(24f, 4f); lineTo(43f, 24f); lineTo(24f, 44f); lineTo(5f, 24f); close() },
                0xFFF9A825.toInt(),
            )
            fill.color = 0xFF111111.toInt()
            canvas.drawRect(10f, 21f, 38f, 27f, fill)
        }

        "s57-buoy-safe" -> {
            drawCircle(24f, 24f, 18f, 0xFFD32F2F.toInt())
            fill.color = 0xFFFFFFFF.toInt()
            canvas.drawRect(7f, 19f, 41f, 29f, fill)
        }

        "s57-buoy-danger" -> {
            drawCircle(24f, 24f, 18f, 0xFFD32F2F.toInt())
            fill.color = 0xFF111111.toInt()
            canvas.drawRect(7f, 19f, 41f, 29f, fill)
        }

        "s57-buoy-special" -> drawCircle(24f, 24f, 16f, 0xFFF9A825.toInt())

        "s57-light" -> {
            drawCircle(24f, 24f, 9f, 0xFFC2185B.toInt())
            halo.color = 0xFFC2185B.toInt()
            halo.strokeWidth = 2.5f
            for (i in 0 until 8) {
                val angle = Math.toRadians(i * 45.0)
                val x1 = 24f + (13f * Math.cos(angle)).toFloat()
                val y1 = 24f + (13f * Math.sin(angle)).toFloat()
                val x2 = 24f + (21f * Math.cos(angle)).toFloat()
                val y2 = 24f + (21f * Math.sin(angle)).toFloat()
                canvas.drawLine(x1, y1, x2, y2, halo)
            }
        }

        "s57-wreck" -> {
            drawRect(20f, 8f, 28f, 40f, 0xFF6D4C41.toInt())
            drawRect(8f, 20f, 40f, 28f, 0xFF6D4C41.toInt())
        }

        "s57-rock" -> drawPath(
            path { moveTo(24f, 8f); lineTo(39f, 24f); lineTo(24f, 40f); lineTo(9f, 24f); close() },
            0xFF616161.toInt(),
        )

        "s57-obstruction" -> {
            drawRect(21f, 8f, 27f, 40f, 0xFF616161.toInt())
            drawRect(8f, 21f, 40f, 27f, 0xFF616161.toInt())
        }

        "s57-mooring" -> {
            halo.color = 0xFF757575.toInt()
            halo.strokeWidth = 6f
            canvas.drawCircle(24f, 24f, 13f, halo)
            halo.color = 0xFFFFFFFF.toInt()
            halo.strokeWidth = 2.5f
            canvas.drawCircle(24f, 24f, 13f, halo)
        }
    }
    return bitmap
}

private fun addNavLayers(style: Style) {
    runCatching {
        style.addSource(GeoJsonSource("nav-line", FeatureCollection.fromFeatures(emptyArray<Feature>())))
        val line = LineLayer("nav-line-layer", "nav-line").withProperties(
            PropertyFactory.lineColor(0xFFFFD54F.toInt()),
            PropertyFactory.lineWidth(2.5f),
            PropertyFactory.lineDasharray(arrayOf(3f, 2f)),
            PropertyFactory.lineOpacity(0.95f),
        )
        runCatching { style.addLayerBelow(line, "vessel-layer") }
            .recoverCatching { style.addLayer(line) }
        style.addSource(GeoJsonSource("nav-target", FeatureCollection.fromFeatures(emptyArray<Feature>())))
        style.addLayer(
            CircleLayer("nav-target-layer", "nav-target").withProperties(
                PropertyFactory.circleRadius(8f),
                PropertyFactory.circleColor(0xFFFFD54F.toInt()),
                PropertyFactory.circleStrokeWidth(2f),
                PropertyFactory.circleStrokeColor(0xFF08131F.toInt()),
            ),
        )
    }
}

private fun updateNav(style: Style, lat: Double?, lon: Double?, target: NavigationTarget?) {
    val line = style.getSourceAs<GeoJsonSource>("nav-line") ?: return
    val point = style.getSourceAs<GeoJsonSource>("nav-target") ?: return
    if (lat == null || lon == null || target == null) {
        line.setGeoJson(FeatureCollection.fromFeatures(emptyArray<Feature>()))
        point.setGeoJson(FeatureCollection.fromFeatures(emptyArray<Feature>()))
        return
    }
    line.setGeoJson(
        FeatureCollection.fromFeatures(
            arrayOf(
                Feature.fromGeometry(
                    LineString.fromLngLats(
                        listOf(
                            Point.fromLngLat(lon, lat),
                            Point.fromLngLat(target.lon, target.lat),
                        ),
                    ),
                ),
            ),
        ),
    )
    point.setGeoJson(
        FeatureCollection.fromFeatures(
            arrayOf(
                Feature.fromGeometry(Point.fromLngLat(target.lon, target.lat)).apply {
                    addStringProperty("name", target.name)
                },
            ),
        ),
    )
}

private fun addTargetRingLayer(style: Style) {
    runCatching {
        style.addSource(
            GeoJsonSource("target-ring", FeatureCollection.fromFeatures(emptyArray<Feature>())),
        )
        style.addLayer(
            FillLayer("target-ring-fill", "target-ring").withProperties(
                PropertyFactory.fillColor(0x22FFD54F.toInt()),
            ),
        )
        style.addLayer(
            LineLayer("target-ring-line", "target-ring").withProperties(
                PropertyFactory.lineColor(0xFFFFD54F.toInt()),
                PropertyFactory.lineWidth(1.5f),
                PropertyFactory.lineDasharray(arrayOf(2f, 2f)),
                PropertyFactory.lineOpacity(0.9f),
            ),
        )
    }
}

/** Arrival radius around the active waypoint (OpenCPN-style arrival circle). */
private fun updateTargetRing(style: Style, target: NavigationTarget?, radiusMeters: Double) {
    val source = style.getSourceAs<GeoJsonSource>("target-ring") ?: return
    if (target == null || radiusMeters <= 0.0) {
        source.setGeoJson(FeatureCollection.fromFeatures(emptyArray<Feature>()))
        return
    }
    val ring = Geo.circlePolygon(LatLon(target.lat, target.lon), radiusMeters)
    val coords = ring.map { Point.fromLngLat(it.lon, it.lat) }
    source.setGeoJson(
        FeatureCollection.fromFeatures(
            arrayOf(Feature.fromGeometry(Polygon.fromLngLats(listOf(coords)))),
        ),
    )
}

private fun addHeadingLayer(style: Style) {
    runCatching {
        style.addSource(GeoJsonSource("vessel-heading", FeatureCollection.fromFeatures(emptyArray<Feature>())))
        val line = LineLayer("vessel-heading-layer", "vessel-heading").withProperties(
            PropertyFactory.lineColor(0xFF00E5FF.toInt()),
            PropertyFactory.lineWidth(4f),
            PropertyFactory.lineOpacity(1f),
        )
        runCatching { style.addLayerBelow(line, "vessel-layer") }
            .recoverCatching { style.addLayer(line) }
    }
}

private fun updateHeading(
    style: Style,
    lat: Double?,
    lon: Double?,
    headingRad: Double?,
    sogMps: Double?,
) {
    val source = style.getSourceAs<GeoJsonSource>("vessel-heading") ?: return
    if (lat == null || lon == null || headingRad == null) {
        source.setGeoJson(FeatureCollection.fromFeatures(emptyArray<Feature>()))
        return
    }
    // ~2 minutes of travel, clamped so it stays visible at any zoom.
    val distance = (400.0 + (sogMps ?: 0.0) * 120.0).coerceIn(400.0, 2_500.0)
    val earth = 6_371_000.0
    val lat1 = Math.toRadians(lat)
    val lon1 = Math.toRadians(lon)
    val lat2 = Math.asin(
        Math.sin(lat1) * Math.cos(distance / earth) +
            Math.cos(lat1) * Math.sin(distance / earth) * Math.cos(headingRad),
    )
    val lon2 = lon1 + Math.atan2(
        Math.sin(headingRad) * Math.sin(distance / earth) * Math.cos(lat1),
        Math.cos(distance / earth) - Math.sin(lat1) * Math.sin(lat2),
    )
    source.setGeoJson(
        FeatureCollection.fromFeatures(
            arrayOf(
                Feature.fromGeometry(
                    LineString.fromLngLats(
                        listOf(
                            Point.fromLngLat(lon, lat),
                            Point.fromLngLat(Math.toDegrees(lon2), Math.toDegrees(lat2)),
                        ),
                    ),
                ),
            ),
        ),
    )
}

private fun addCurrentLayer(style: Style) {
    runCatching {
        style.addSource(
            GeoJsonSource("current-arrow", FeatureCollection.fromFeatures(emptyArray<Feature>())),
        )
        val line = LineLayer("current-arrow-layer", "current-arrow").withProperties(
            PropertyFactory.lineColor(0xFF4DD0E1.toInt()),
            PropertyFactory.lineWidth(4f),
            PropertyFactory.lineOpacity(0.9f),
        )
        runCatching { style.addLayerBelow(line, "vessel-layer") }
            .recoverCatching { style.addLayer(line) }
    }
}

/**
 * OpenCPN-style set & drift arrow: one hour of current from the vessel position,
 * scaled so light currents remain visible.
 */
private fun updateCurrent(
    style: Style,
    lat: Double?,
    lon: Double?,
    setRad: Double?,
    driftMps: Double?,
) {
    val source = style.getSourceAs<GeoJsonSource>("current-arrow") ?: return
    if (lat == null || lon == null || setRad == null || driftMps == null || driftMps < 0.02) {
        source.setGeoJson(FeatureCollection.fromFeatures(emptyArray<Feature>()))
        return
    }
    val length = (driftMps * 3600.0).coerceIn(120.0, 3_000.0)
    val end = Geo.offset(LatLon(lat, lon), Math.toDegrees(setRad), length)
    source.setGeoJson(
        FeatureCollection.fromFeatures(
            arrayOf(
                Feature.fromGeometry(
                    LineString.fromLngLats(
                        listOf(
                            Point.fromLngLat(lon, lat),
                            Point.fromLngLat(end.lon, end.lat),
                        ),
                    ),
                ),
            ),
        ),
    )
}

private const val TRACK_MAX_RENDER_POINTS = 5_000
private const val TRACK_SEGMENT_GAP_MS = 30 * 60_000L
private const val TRACK_SEGMENT_JUMP_M = 5_000.0

private fun addTrackLayer(style: Style) {
    runCatching {
        style.addSource(GeoJsonSource("track", FeatureCollection.fromFeatures(emptyArray<Feature>())))
        val casing = LineLayer("track-casing-layer", "track").withProperties(
            PropertyFactory.lineColor(0xFF07131F.toInt()),
            PropertyFactory.lineWidth(6.5f),
            PropertyFactory.lineOpacity(0.65f),
        )
        runCatching { style.addLayerBelow(casing, "vessel-layer") }
            .recoverCatching { style.addLayer(casing) }
        val line = LineLayer("track-layer", "track").withProperties(
            PropertyFactory.lineColor(0xFFE040FB.toInt()),
            PropertyFactory.lineWidth(3.5f),
            PropertyFactory.lineOpacity(0.95f),
        )
        runCatching { style.addLayerBelow(line, "vessel-layer") }
            .recoverCatching { style.addLayer(line) }
    }
}

private fun updateTrack(style: Style, points: List<TrackPoint>) {
    val source = style.getSourceAs<GeoJsonSource>("track") ?: return
    // Decimate (instead of truncating) long tracks: the whole route stays visible while
    // the GeoJSON source keeps a bounded number of vertices.
    val visible = if (points.size > TRACK_MAX_RENDER_POINTS) {
        val step = (points.size + TRACK_MAX_RENDER_POINTS - 1) / TRACK_MAX_RENDER_POINTS
        points.filterIndexed { index, _ ->
            index % step == 0 || index == points.lastIndex
        }
    } else {
        points
    }
    if (visible.size < 2) {
        source.setGeoJson(FeatureCollection.fromFeatures(emptyArray<Feature>()))
        return
    }

    // Break the trail on long time gaps or position jumps (app restarted, GPS lost,
    // boat relocated) instead of drawing a misleading straight line across the chart.
    val features = ArrayList<Feature>()
    var segment = ArrayList<Point>()
    fun flush() {
        if (segment.size >= 2) {
            features.add(Feature.fromGeometry(LineString.fromLngLats(segment)))
        }
        segment = ArrayList()
    }
    visible.forEachIndexed { index, point ->
        if (index > 0) {
            val previous = visible[index - 1]
            val gap = point.ts - previous.ts > TRACK_SEGMENT_GAP_MS
            val jump = Geo.distanceMeters(
                LatLon(previous.lat, previous.lon),
                LatLon(point.lat, point.lon),
            ) > TRACK_SEGMENT_JUMP_M
            if (gap || jump) flush()
        }
        segment.add(Point.fromLngLat(point.lon, point.lat))
    }
    flush()
    source.setGeoJson(FeatureCollection.fromFeatures(features.toTypedArray()))
}

private fun addRouteLayer(style: Style) {
    runCatching {
        style.addSource(GeoJsonSource("route-line", FeatureCollection.fromFeatures(emptyArray<Feature>())))
        val line = LineLayer("route-line-layer", "route-line").withProperties(
            PropertyFactory.lineColor(0xFF80D8FF.toInt()),
            PropertyFactory.lineWidth(2.5f),
            PropertyFactory.lineDasharray(arrayOf(2f, 2f)),
            PropertyFactory.lineOpacity(0.9f),
        )
        runCatching { style.addLayerBelow(line, "waypoints-layer") }
            .recoverCatching { style.addLayerBelow(line, "vessel-layer") }
            .recoverCatching { style.addLayer(line) }
    }
}

private fun updateRoute(style: Style, route: Route?) {
    val source = style.getSourceAs<GeoJsonSource>("route-line") ?: return
    val wps = route?.waypoints.orEmpty()
    if (wps.size < 2) {
        source.setGeoJson(FeatureCollection.fromFeatures(emptyArray<Feature>()))
        return
    }
    source.setGeoJson(
        FeatureCollection.fromFeatures(
            arrayOf(
                Feature.fromGeometry(
                    LineString.fromLngLats(
                        wps.map { Point.fromLngLat(it.position.longitude, it.position.latitude) },
                    ),
                ),
            ),
        ),
    )
}

private fun addRouteLegLayer(style: Style) {
    runCatching {
        style.addSource(
            GeoJsonSource("route-leg", FeatureCollection.fromFeatures(emptyArray<Feature>())),
        )
        val line = LineLayer("route-leg-layer", "route-leg").withProperties(
            PropertyFactory.lineColor(0xFFFFD54F.toInt()),
            PropertyFactory.lineWidth(4f),
            PropertyFactory.lineOpacity(0.95f),
        )
        runCatching { style.addLayerBelow(line, "waypoints-layer") }
            .recoverCatching { style.addLayerBelow(line, "vessel-layer") }
            .recoverCatching { style.addLayer(line) }
    }
}

/** OpenCPN-style active leg: previous waypoint → current target. */
private fun updateRouteLeg(style: Style, target: NavigationTarget?) {
    val source = style.getSourceAs<GeoJsonSource>("route-leg") ?: return
    val fromLat = target?.fromLat
    val fromLon = target?.fromLon
    if (target == null || fromLat == null || fromLon == null) {
        source.setGeoJson(FeatureCollection.fromFeatures(emptyArray<Feature>()))
        return
    }
    source.setGeoJson(
        FeatureCollection.fromFeatures(
            arrayOf(
                Feature.fromGeometry(
                    LineString.fromLngLats(
                        listOf(
                            Point.fromLngLat(fromLon, fromLat),
                            Point.fromLngLat(target.lon, target.lat),
                        ),
                    ),
                ),
            ),
        ),
    )
}

private fun addVesselLayer(style: Style) {
    runCatching {
        style.addSource(GeoJsonSource("vessel", FeatureCollection.fromFeatures(emptyArray<Feature>())))
        style.addLayer(
            CircleLayer("vessel-layer", "vessel").withProperties(
                PropertyFactory.circleRadius(8f),
                PropertyFactory.circleColor(0xFF37C6F0.toInt()),
                PropertyFactory.circleStrokeWidth(2f),
                PropertyFactory.circleStrokeColor(0xFFFFFFFF.toInt()),
            ),
        )
    }
}

private fun addAisLayer(style: Style, font: String) {
    runCatching {
        style.addSource(GeoJsonSource("ais", FeatureCollection.fromFeatures(emptyArray<Feature>())))
        style.addLayer(
            CircleLayer("ais-layer", "ais").withProperties(
                PropertyFactory.circleRadius(6f),
                PropertyFactory.circleColor(categoryColor()),
                PropertyFactory.circleStrokeWidth(1.5f),
                PropertyFactory.circleStrokeColor(0xFF08131F.toInt()),
            ),
        )
        style.addLayer(
            SymbolLayer("ais-label", "ais").withProperties(
                PropertyFactory.textField(Expression.get("label")),
                PropertyFactory.textFont(arrayOf(font)),
                PropertyFactory.textSize(11f),
                PropertyFactory.textColor(0xFFCDEBD6.toInt()),
                PropertyFactory.textHaloColor(0xFF08131F.toInt()),
                PropertyFactory.textHaloWidth(1.5f),
                PropertyFactory.textOffset(arrayOf(0f, 1.2f)),
                PropertyFactory.textAllowOverlap(false),
            ),
        )
    }
}

private fun addInternetAisLayers(style: Style, font: String) {
    runCatching {
        style.addSource(GeoJsonSource("ais-net", FeatureCollection.fromFeatures(emptyArray<Feature>())))
        style.addLayer(
            CircleLayer("ais-net-layer", "ais-net").withProperties(
                PropertyFactory.circleRadius(6f),
                PropertyFactory.circleColor(categoryColor()),
                PropertyFactory.circleStrokeWidth(1.5f),
                PropertyFactory.circleStrokeColor(0xFF08131F.toInt()),
            ),
        )
        style.addLayer(
            SymbolLayer("ais-net-label", "ais-net").withProperties(
                PropertyFactory.textField(Expression.get("code")),
                PropertyFactory.textFont(arrayOf(font)),
                PropertyFactory.textSize(9f),
                PropertyFactory.textColor(0xFF0A1A2B.toInt()),
                PropertyFactory.textAllowOverlap(true),
            ),
        )
    }
}

/** Colour by AIS ship-type category (cargo, tanker, passenger/ferry, fishing…). */
private fun categoryColor(): Expression = Expression.match(
    Expression.get("category"),
    Expression.literal("cargo"), Expression.literal("#D9534F"),
    Expression.literal("tanker"), Expression.literal("#F0AD4E"),
    Expression.literal("passenger"), Expression.literal("#4FC3F7"),
    Expression.literal("fishing"), Expression.literal("#66BB6A"),
    Expression.literal("pleasure"), Expression.literal("#BA68C8"),
    Expression.literal("tug"), Expression.literal("#A1887F"),
    Expression.literal("sar"), Expression.literal("#FFD54F"),
    Expression.literal("military"), Expression.literal("#90A4AE"),
    Expression.literal("hsc"), Expression.literal("#26C6DA"),
    // MapLibre match: the default is the LAST argument, not the first.
    Expression.literal("#B0BEC5"),
)

private fun updateInternetAis(style: Style, targets: List<AisTarget>) {
    val source = style.getSourceAs<GeoJsonSource>("ais-net") ?: return
    val features = targets.map { target ->
        Feature.fromGeometry(Point.fromLngLat(target.lon, target.lat)).apply {
            addStringProperty("id", target.id)
            addStringProperty("label", target.label)
            addStringProperty("category", target.category ?: "other")
            addStringProperty("code", target.code ?: "?")
        }
    }.toTypedArray()
    source.setGeoJson(FeatureCollection.fromFeatures(features))
}

private fun addWaypointLayer(style: Style) {    runCatching {
        style.addSource(GeoJsonSource("waypoints", FeatureCollection.fromFeatures(emptyArray<Feature>())))
        style.addLayer(
            CircleLayer("waypoints-layer", "waypoints").withProperties(
                PropertyFactory.circleRadius(6f),
                PropertyFactory.circleColor(0xFFE8D9A0.toInt()),
                PropertyFactory.circleStrokeWidth(2f),
                PropertyFactory.circleStrokeColor(0xFF08131F.toInt()),
            ),
        )
        style.addLayer(
            SymbolLayer("waypoints-label", "waypoints").withProperties(
                PropertyFactory.textField(Expression.get("name")),
                PropertyFactory.textFont(arrayOf("Noto Sans Regular")),
                PropertyFactory.textSize(11f),
                PropertyFactory.textColor(0xFFE8D9A0.toInt()),
                PropertyFactory.textHaloColor(0xFF08131F.toInt()),
                PropertyFactory.textHaloWidth(1.5f),
                PropertyFactory.textOffset(arrayOf(0f, 1.4f)),
                PropertyFactory.textAllowOverlap(false),
            ),
        )
    }
}

private fun updateVessel(style: Style, lat: Double?, lon: Double?) {
    val source = style.getSourceAs<GeoJsonSource>("vessel") ?: return
    val features = if (lat != null && lon != null) {
        arrayOf(Feature.fromGeometry(Point.fromLngLat(lon, lat)))
    } else {
        emptyArray<Feature>()
    }
    source.setGeoJson(FeatureCollection.fromFeatures(features))
}

private fun updateAis(style: Style, targets: List<AisTarget>) {
    val source = style.getSourceAs<GeoJsonSource>("ais") ?: return
    val features = targets.map { target ->
        Feature.fromGeometry(Point.fromLngLat(target.lon, target.lat)).apply {
            addStringProperty("id", target.id)
            addStringProperty("label", target.label)
            addStringProperty("category", target.category ?: "other")
            addNumberProperty("sog", target.sogMps ?: 0.0)
        }
    }.toTypedArray()
    source.setGeoJson(FeatureCollection.fromFeatures(features))
}

/** Red halo around AIS contacts currently raising a CPA/TCPA or guard-zone alarm. */
private fun addAisDangerLayer(style: Style) {
    runCatching {
        style.addSource(
            GeoJsonSource("ais-danger", FeatureCollection.fromFeatures(emptyArray<Feature>())),
        )
        style.addLayer(
            CircleLayer("ais-danger-layer", "ais-danger").withProperties(
                PropertyFactory.circleRadius(13f),
                PropertyFactory.circleColor(0x33FF1744.toInt()),
                PropertyFactory.circleStrokeWidth(2.5f),
                PropertyFactory.circleStrokeColor(0xFFFF1744.toInt()),
            ),
        )
    }
}

private fun updateAisDanger(style: Style, targets: Collection<AisTarget>, ids: Set<String>) {
    val source = style.getSourceAs<GeoJsonSource>("ais-danger") ?: return
    if (ids.isEmpty()) {
        source.setGeoJson(FeatureCollection.fromFeatures(emptyArray<Feature>()))
        return
    }
    val features = targets
        .filter { it.id in ids }
        .map { Feature.fromGeometry(Point.fromLngLat(it.lon, it.lat)) }
        .toTypedArray()
    source.setGeoJson(FeatureCollection.fromFeatures(features))
}

private fun updateWaypoints(style: Style, waypoints: List<Waypoint>) {
    val source = style.getSourceAs<GeoJsonSource>("waypoints") ?: return
    val features = waypoints.map { wp ->
        Feature.fromGeometry(Point.fromLngLat(wp.position.longitude, wp.position.latitude))
            .apply {
                addStringProperty("id", wp.id ?: "")
                addStringProperty("name", wp.name ?: "")
            }
    }.toTypedArray()
    source.setGeoJson(FeatureCollection.fromFeatures(features))
}

private fun addAnchorLayers(style: Style) {
    runCatching {
        style.addSource(GeoJsonSource("anchor-circle", FeatureCollection.fromFeatures(emptyArray<Feature>())))
        style.addLayer(
            FillLayer("anchor-fill", "anchor-circle").withProperties(
                PropertyFactory.fillColor(0x22FF6B6B.toInt()),
            ),
        )
        style.addLayer(
            LineLayer("anchor-line", "anchor-circle").withProperties(
                PropertyFactory.lineColor(0xFFFF6B6B.toInt()),
                PropertyFactory.lineWidth(2f),
            ),
        )
        style.addSource(GeoJsonSource("anchor-point", FeatureCollection.fromFeatures(emptyArray<Feature>())))
        style.addLayer(
            CircleLayer("anchor-point-layer", "anchor-point").withProperties(
                PropertyFactory.circleRadius(6f),
                PropertyFactory.circleColor(0xFFFF6B6B.toInt()),
                PropertyFactory.circleStrokeWidth(2f),
                PropertyFactory.circleStrokeColor(0xFFFFFFFF.toInt()),
            ),
        )
    }
}

private fun addAreaLayer(style: Style) {
    runCatching {
        style.addSource(GeoJsonSource("area", FeatureCollection.fromFeatures(emptyArray<Feature>())))
        style.addLayer(
            FillLayer("area-fill", "area").withProperties(
                PropertyFactory.fillColor(0x2237C6F0.toInt()),
            ),
        )
        style.addLayer(
            LineLayer("area-line", "area").withProperties(
                PropertyFactory.lineColor(0xFF37C6F0.toInt()),
                PropertyFactory.lineWidth(2f),
                PropertyFactory.lineDasharray(arrayOf(2f, 2f)),
            ),
        )
    }
}

private fun updateAnchor(style: Style, state: AnchorMonitor.State) {
    val circle = style.getSourceAs<GeoJsonSource>("anchor-circle") ?: return
    val point = style.getSourceAs<GeoJsonSource>("anchor-point") ?: return

    if (!state.set || state.lat == null || state.lon == null) {
        circle.setGeoJson(FeatureCollection.fromFeatures(emptyArray<Feature>()))
        point.setGeoJson(FeatureCollection.fromFeatures(emptyArray<Feature>()))
        return
    }

    val ring = Geo.circlePolygon(LatLon(state.lat, state.lon), state.radiusMeters)
    val coords = ring.map { Point.fromLngLat(it.lon, it.lat) }
    circle.setGeoJson(
        FeatureCollection.fromFeatures(
            arrayOf(Feature.fromGeometry(Polygon.fromLngLats(listOf(coords)))),
        ),
    )
    point.setGeoJson(
        FeatureCollection.fromFeatures(
            arrayOf(Feature.fromGeometry(Point.fromLngLat(state.lon, state.lat))),
        ),
    )
}

private fun updateArea(style: Style, bounds: GeoBounds?) {
    val source = style.getSourceAs<GeoJsonSource>("area") ?: return
    if (bounds == null || !bounds.isValid) {
        source.setGeoJson(FeatureCollection.fromFeatures(emptyArray<Feature>()))
        return
    }
    val coords = listOf(
        Point.fromLngLat(bounds.west, bounds.north),
        Point.fromLngLat(bounds.east, bounds.north),
        Point.fromLngLat(bounds.east, bounds.south),
        Point.fromLngLat(bounds.west, bounds.south),
        Point.fromLngLat(bounds.west, bounds.north),
    )
    source.setGeoJson(
        FeatureCollection.fromFeatures(
            arrayOf(Feature.fromGeometry(Polygon.fromLngLats(listOf(coords)))),
        ),
    )
}

// ------------------------------------------------------------------- measure

private fun addMeasureLayers(style: Style) {
    runCatching {
        style.addSource(GeoJsonSource("measure-line", FeatureCollection.fromFeatures(emptyArray<Feature>())))
        val line = LineLayer("measure-line-layer", "measure-line").withProperties(
            PropertyFactory.lineColor(0xFFFF80AB.toInt()),
            PropertyFactory.lineWidth(2f),
            PropertyFactory.lineDasharray(arrayOf(2f, 2f)),
            PropertyFactory.lineOpacity(0.95f),
        )
        style.addLayer(line)

        style.addSource(GeoJsonSource("measure-points", FeatureCollection.fromFeatures(emptyArray<Feature>())))
        style.addLayer(
            CircleLayer("measure-points-layer", "measure-points").withProperties(
                PropertyFactory.circleRadius(6f),
                PropertyFactory.circleColor(0xFFFF80AB.toInt()),
                PropertyFactory.circleStrokeWidth(2f),
                PropertyFactory.circleStrokeColor(0xFFFFFFFF.toInt()),
            ),
        )
    }
}

private fun updateMeasure(style: Style, start: LatLon?, end: LatLon?) {
    val line = style.getSourceAs<GeoJsonSource>("measure-line") ?: return
    val points = style.getSourceAs<GeoJsonSource>("measure-points") ?: return
    if (start == null) {
        line.setGeoJson(FeatureCollection.fromFeatures(emptyArray<Feature>()))
        points.setGeoJson(FeatureCollection.fromFeatures(emptyArray<Feature>()))
        return
    }
    val markers = ArrayList<Feature>(2)
    markers.add(Feature.fromGeometry(Point.fromLngLat(start.lon, start.lat)))
    if (end != null) {
        markers.add(Feature.fromGeometry(Point.fromLngLat(end.lon, end.lat)))
        line.setGeoJson(
            FeatureCollection.fromFeatures(
                arrayOf(
                    Feature.fromGeometry(
                        LineString.fromLngLats(
                            listOf(
                                Point.fromLngLat(start.lon, start.lat),
                                Point.fromLngLat(end.lon, end.lat),
                            ),
                        ),
                    ),
                ),
            ),
        )
    } else {
        line.setGeoJson(FeatureCollection.fromFeatures(emptyArray<Feature>()))
    }
    points.setGeoJson(FeatureCollection.fromFeatures(markers.toTypedArray()))
}

// ----------------------------------------------------------------------- MOB

private fun addMobLayer(style: Style) {
    runCatching {
        style.addSource(GeoJsonSource("mob-line", FeatureCollection.fromFeatures(emptyArray<Feature>())))
        val line = LineLayer("mob-line-layer", "mob-line").withProperties(
            PropertyFactory.lineColor(0xFFFF5252.toInt()),
            PropertyFactory.lineWidth(2f),
            PropertyFactory.lineDasharray(arrayOf(2f, 2f)),
            PropertyFactory.lineOpacity(0.95f),
        )
        runCatching { style.addLayerBelow(line, "vessel-layer") }
            .recoverCatching { style.addLayer(line) }

        style.addSource(GeoJsonSource("mob-point", FeatureCollection.fromFeatures(emptyArray<Feature>())))
        style.addLayer(
            CircleLayer("mob-point-layer", "mob-point").withProperties(
                PropertyFactory.circleRadius(10f),
                PropertyFactory.circleColor(0xFFFF1744.toInt()),
                PropertyFactory.circleStrokeWidth(3f),
                PropertyFactory.circleStrokeColor(0xFFFFFFFF.toInt()),
            ),
        )
    }
}

private fun updateMob(style: Style, mob: MobMarker?, lat: Double?, lon: Double?) {
    val line = style.getSourceAs<GeoJsonSource>("mob-line") ?: return
    val point = style.getSourceAs<GeoJsonSource>("mob-point") ?: return
    if (mob == null) {
        line.setGeoJson(FeatureCollection.fromFeatures(emptyArray<Feature>()))
        point.setGeoJson(FeatureCollection.fromFeatures(emptyArray<Feature>()))
        return
    }
    point.setGeoJson(
        FeatureCollection.fromFeatures(
            arrayOf(Feature.fromGeometry(Point.fromLngLat(mob.lon, mob.lat))),
        ),
    )
    if (lat != null && lon != null) {
        line.setGeoJson(
            FeatureCollection.fromFeatures(
                arrayOf(
                    Feature.fromGeometry(
                        LineString.fromLngLats(
                            listOf(
                                Point.fromLngLat(lon, lat),
                                Point.fromLngLat(mob.lon, mob.lat),
                            ),
                        ),
                    ),
                ),
            ),
        )
    } else {
        line.setGeoJson(FeatureCollection.fromFeatures(emptyArray<Feature>()))
    }
}

// -------------------------------------------------------------- AIS vectors

private fun addAisVectorLayers(style: Style) {
    runCatching {
        style.addSource(GeoJsonSource("ais-vectors", FeatureCollection.fromFeatures(emptyArray<Feature>())))
        val line = LineLayer("ais-vectors-layer", "ais-vectors").withProperties(
            PropertyFactory.lineColor(0xFF7CE38B.toInt()),
            PropertyFactory.lineWidth(1.4f),
            PropertyFactory.lineOpacity(0.8f),
        )
        runCatching { style.addLayerBelow(line, "vessel-layer") }
            .recoverCatching { style.addLayer(line) }
    }
}

/**
 * OpenCPN-style predicted-position vectors: each AIS target is projected along its COG for
 * six minutes at its current SOG.
 */
private fun updateAisVectors(style: Style, targets: List<AisTarget>, show: Boolean) {
    val source = style.getSourceAs<GeoJsonSource>("ais-vectors") ?: return
    if (!show) {
        source.setGeoJson(FeatureCollection.fromFeatures(emptyArray<Feature>()))
        return
    }
    val features = targets.mapNotNull { target ->
        val sog = target.sogMps ?: return@mapNotNull null
        val cog = target.cogRad ?: return@mapNotNull null
        if (sog < 0.3) return@mapNotNull null
        val predicted = Geo.offset(LatLon(target.lat, target.lon), Math.toDegrees(cog), sog * 360.0)
        Feature.fromGeometry(
            LineString.fromLngLats(
                listOf(
                    Point.fromLngLat(target.lon, target.lat),
                    Point.fromLngLat(predicted.lon, predicted.lat),
                ),
            ),
        )
    }.toTypedArray()
    source.setGeoJson(FeatureCollection.fromFeatures(features))
}
