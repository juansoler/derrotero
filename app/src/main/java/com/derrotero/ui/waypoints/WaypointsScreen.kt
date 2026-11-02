package com.derrotero.ui.waypoints

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddLocationAlt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.derrotero.data.charts.OfflineChartDownloader
import com.derrotero.data.resources.Route
import com.derrotero.data.resources.RouteWaypoint
import com.derrotero.data.resources.Waypoint
import com.derrotero.data.signalk.Paths
import com.derrotero.data.signalk.number
import com.derrotero.ui.NavViewModel
import com.derrotero.ui.components.GlassCard
import com.derrotero.ui.components.ScreenHeader
import com.derrotero.ui.components.SectionHeader
import com.derrotero.ui.theme.MarineColors
import com.derrotero.util.Formatters
import com.derrotero.util.Geo
import com.derrotero.util.LatLon

@Composable
fun WaypointsScreen(vm: NavViewModel) {
    val waypoints by vm.waypoints.collectAsStateWithLifecycle()
    val routes by vm.routes.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val activeRouteId by vm.activeRouteId.collectAsStateWithLifecycle()
    val vessel by vm.vesselState.collectAsStateWithLifecycle()
    val track by vm.trackPoints.collectAsStateWithLifecycle()
    val sogMps = vessel.number(Paths.SOG)

    var name by remember { mutableStateOf("") }
    var routeToDownload by remember { mutableStateOf<Route?>(null) }
    var editingWaypoint by remember { mutableStateOf<Waypoint?>(null) }
    var editingRoute by remember { mutableStateOf<Route?>(null) }
    var routeDetails by remember { mutableStateOf<Route?>(null) }
    val download by vm.download.collectAsStateWithLifecycle()

    val gpxPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { vm.importGpx(it, pushToSignalK = true) } }

    val gpxExport = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/gpx+xml"),
    ) { uri -> uri?.let { vm.exportGpx(it) } }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 26.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            ScreenHeader(
                title = "Waypoints y rutas",
                subtitle = "${waypoints.size} " +
                    "${if (waypoints.size == 1) "waypoint" else "waypoints"} · " +
                    "${routes.size} ${if (routes.size == 1) "ruta" else "rutas"}",
                statusLabel = if (activeRouteId != null) "Ruta activa" else null,
                statusActive = activeRouteId != null,
            )
        }

        if (busy) {
            item {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }

        if (download.running || download.message != null) {
            item {
                GlassCard(Modifier.fillMaxWidth(), accent = MarineColors.Harbor) {
                    SectionHeader(
                        title = "Carta de la ruta",
                        icon = Icons.Filled.Download,
                    )
                    Column(
                        Modifier.padding(top = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (download.total > 0) {
                            LinearProgressIndicator(
                                progress = { download.done.toFloat() / download.total },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                "${download.done}/${download.total} teselas · fallos ${download.failed}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MarineColors.TextDim,
                            )
                        }
                        download.message?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MarineColors.TextDim,
                            )
                        }
                        if (download.running) {
                            TextButton(onClick = { vm.cancelDownload() }) { Text("Cancelar") }
                        }
                    }
                }
            }
        }

        if (track.isNotEmpty()) {
            item {
                val trackStats = remember(track) {
                    var distance = 0.0
                    for (i in 1 until track.size) {
                        distance += Geo.distanceMeters(
                            LatLon(track[i - 1].lat, track[i - 1].lon),
                            LatLon(track[i].lat, track[i].lon),
                        )
                    }
                    val duration = if (track.size >= 2) {
                        ((track.last().ts - track.first().ts) / 1000.0).coerceAtLeast(0.0)
                    } else {
                        0.0
                    }
                    Triple(track.size, distance, duration)
                }
                GlassCard(Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                "Estela",
                                style = MaterialTheme.typography.titleMedium,
                                color = MarineColors.Text,
                            )
                            Text(
                                "${trackStats.first} puntos · " +
                                    "${Formatters.distance(trackStats.second)} · " +
                                    formatDuration(trackStats.third),
                                style = MaterialTheme.typography.bodySmall,
                                color = MarineColors.TextDim,
                            )
                        }
                        TextButton(onClick = { vm.clearTrack() }) { Text("Borrar") }
                    }
                }
            }
        }

        item {
            GlassCard(Modifier.fillMaxWidth(), accent = MarineColors.Harbor) {
                SectionHeader(
                    title = "Enviar waypoint",
                    subtitle = "Se publica en SignalK (resources.waypoints)",
                    icon = Icons.Filled.AddLocationAlt,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nombre") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(onClick = {
                        vm.createWaypointAtVessel(name.ifBlank { "WP" })
                        name = ""
                    }) {
                        Icon(Icons.Filled.AddLocationAlt, contentDescription = null)
                        Text("  Marcación actual")
                    }
                    Button(onClick = { vm.refreshResources() }) { Text("Recargar") }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { gpxPicker.launch(arrayOf("*/*")) }) {
                        Icon(Icons.Filled.FileOpen, contentDescription = "Importar GPX")
                    }
                    IconButton(onClick = { gpxExport.launch("derrotero.gpx") }) {
                        Icon(Icons.Filled.Save, contentDescription = "Exportar GPX")
                    }
                }
                Text(
                    "Los waypoints se publican en SignalK y, con signalk-to-nmea2000, " +
                        "se reenvían al plotter por NMEA 2000.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MarineColors.TextDim,
                )
            }
        }

        item {
            SectionHeader(
                title = "Waypoints",
                subtitle = "${waypoints.size} guardados",
                icon = Icons.Filled.Place,
            )
        }
        if (waypoints.isEmpty()) {
            item {
                Text(
                    "Todavía no hay waypoints. Créalos desde la carta con una pulsación larga.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MarineColors.TextDim,
                )
            }
        }
        items(waypoints, key = { it.id ?: it.position.toString() }) { wp ->
            GlassCard(Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            wp.name ?: "(sin nombre)",
                            style = MaterialTheme.typography.titleSmall,
                            color = MarineColors.Text,
                        )
                        Text(
                            Formatters.position(wp.position.latitude, wp.position.longitude),
                            style = MaterialTheme.typography.bodySmall,
                            color = MarineColors.TextDim,
                        )
                    }
                    IconButton(onClick = { editingWaypoint = wp }) {
                        Icon(Icons.Filled.Edit, contentDescription = "Editar waypoint")
                    }
                    IconButton(onClick = {
                        vm.goTo(wp.position.latitude, wp.position.longitude, wp.name ?: "Waypoint")
                    }) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = "Ir al waypoint")
                    }
                    IconButton(onClick = { wp.id?.let(vm::deleteWaypoint) }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Borrar")
                    }
                }
            }
        }

        item {
            SectionHeader(
                title = "Rutas",
                subtitle = "${routes.size} · toca una ruta para ver sus tramos",
                icon = Icons.Filled.Timeline,
            )
        }
        if (routes.isEmpty()) {
            item {
                Text(
                    "Crea una ruta desde la carta o importa un GPX.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MarineColors.TextDim,
                )
            }
        }
        items(routes, key = { it.id ?: it.name ?: "route" }) { route ->
            val distance = routeDistanceM(route)
            val eta = if (sogMps != null && sogMps > 0.3) distance / sogMps else null
            val isActive = route.id != null && route.id == activeRouteId
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                accent = if (isActive) MarineColors.Mint else null,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f).clickable { routeDetails = route }) {
                        Text(
                            (route.name ?: "(ruta)") + if (isActive) " · ACTIVA" else "",
                            style = MaterialTheme.typography.titleSmall,
                            color = if (isActive) MarineColors.Mint else MarineColors.Text,
                        )
                        Text(
                            "${route.waypoints.size} puntos · ${Formatters.distance(distance)}" +
                                (eta?.let { " · ETA ${formatDuration(it)}" } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = MarineColors.TextDim,
                        )
                    }
                    IconButton(onClick = { editingRoute = route }) {
                        Icon(Icons.Filled.Edit, contentDescription = "Editar ruta")
                    }
                    IconButton(onClick = { vm.reverseRoute(route) }) {
                        Icon(Icons.Filled.SwapVert, contentDescription = "Invertir ruta")
                    }
                    IconButton(onClick = { routeToDownload = route }) {
                        Icon(Icons.Filled.Download, contentDescription = "Descargar carta de la ruta")
                    }
                    IconButton(onClick = { route.id?.let(vm::activateRoute) }) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = "Activar")
                    }
                    IconButton(onClick = { route.id?.let(vm::deleteRoute) }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Borrar")
                    }
                }
            }
        }
    }

    routeToDownload?.let { route ->
        RouteDownloadDialog(
            routeName = route.name ?: "ruta",
            pointCount = route.waypoints.size,
            estimate = { corridorNm, minZoom, maxZoom ->
                val points = route.waypoints.map {
                    LatLon(it.position.latitude, it.position.longitude)
                }
                OfflineChartDownloader.countRouteTiles(points, corridorNm * 1852.0, minZoom, maxZoom)
            },
            onDismiss = { routeToDownload = null },
            onStart = { corridorNm, minZoom, maxZoom ->
                vm.downloadRoute(route, corridorNm, minZoom, maxZoom)
                routeToDownload = null
            },
        )
    }

    editingWaypoint?.let { wp ->
        NameDialog(
            title = "Editar waypoint",
            initial = wp.name ?: "",
            onDismiss = { editingWaypoint = null },
            onSave = { newName ->
                wp.id?.let { id ->
                    vm.renameWaypoint(
                        id,
                        wp.name ?: "",
                        newName,
                        wp.position.latitude,
                        wp.position.longitude,
                    )
                }
                editingWaypoint = null
            },
        )
    }

    editingRoute?.let { route ->
        NameDialog(
            title = "Editar ruta",
            initial = route.name ?: "",
            onDismiss = { editingRoute = null },
            onSave = { newName ->
                vm.renameRoute(route, newName)
                editingRoute = null
            },
        )
    }

    routeDetails?.let { route ->
        RouteDetailsDialog(
            route = route,
            sogMps = sogMps,
            onDismiss = { routeDetails = null },
            onActivate = {
                route.id?.let(vm::activateRoute)
                routeDetails = null
            },
            onDeletePoint = { index ->
                vm.deleteRoutePoint(route, index)
                routeDetails = null
            },
        )
    }
}

@Composable
private fun NameDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var value by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text("Nombre") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(value.trim()) }) { Text("Guardar") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        },
    )
}

@Composable
private fun RouteDetailsDialog(
    route: Route,
    sogMps: Double?,
    onDismiss: () -> Unit,
    onActivate: () -> Unit,
    onDeletePoint: (Int) -> Unit,
) {
    val total = routeDistanceM(route)
    val totalEta = if (sogMps != null && sogMps > 0.3) total / sogMps else null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(route.name ?: "Ruta") },
        text = {
            Column(
                Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "${route.waypoints.size} puntos · ${Formatters.distance(total)}" +
                        (totalEta?.let { " · ETA ${formatDuration(it)}" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                )
                var previous: RouteWaypoint? = null
                route.waypoints.forEachIndexed { index, wp ->
                    val leg = previous?.let {
                        Geo.distanceMeters(
                            LatLon(it.position.latitude, it.position.longitude),
                            LatLon(wp.position.latitude, wp.position.longitude),
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "${index + 1}. ${wp.name ?: "WP"}",
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                Formatters.position(
                                    wp.position.latitude,
                                    wp.position.longitude,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            leg?.let {
                                Text(
                                    "Tramo ${Formatters.distance(it)}" +
                                        if (sogMps != null && sogMps > 0.3) {
                                            " · ${formatDuration(it / sogMps)}"
                                        } else {
                                            ""
                                        },
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                        if (route.waypoints.size > 1) {
                            IconButton(onClick = { onDeletePoint(index) }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Borrar punto")
                            }
                        }
                    }
                    previous = wp
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onActivate) { Text("Activar") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cerrar") }
        },
    )
}

@Composable
private fun RouteDownloadDialog(
    routeName: String,
    pointCount: Int,
    estimate: (Double, Int, Int) -> Int,
    onDismiss: () -> Unit,
    onStart: (Double, Int, Int) -> Unit,
) {
    var corridorText by remember { mutableStateOf("3") }
    var minZoomText by remember { mutableStateOf("10") }
    var maxZoomText by remember { mutableStateOf("14") }
    val corridor = corridorText.toDoubleOrNull() ?: 3.0
    val minZoom = minZoomText.toIntOrNull() ?: 10
    val maxZoom = maxZoomText.toIntOrNull() ?: 14
    val count = if (minZoom <= maxZoom) estimate(corridor, minZoom, maxZoom) else 0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Carta offline de la ruta") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("$routeName · $pointCount puntos", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = corridorText,
                    onValueChange = { corridorText = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("Corredor (NM a cada lado)") },
                    singleLine = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = minZoomText,
                        onValueChange = { minZoomText = it.filter(Char::isDigit) },
                        label = { Text("Zoom mín") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = maxZoomText,
                        onValueChange = { maxZoomText = it.filter(Char::isDigit) },
                        label = { Text("Zoom máx") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                Text("≈ $count teselas", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onStart(corridor, minZoom, maxZoom) },
                enabled = count in 1..OfflineChartDownloader.MAX_TILES,
            ) { Text("Descargar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

private fun routeDistanceM(route: Route): Double {
    if (route.waypoints.size < 2) return 0.0
    var total = 0.0
    for (i in 1 until route.waypoints.size) {
        val a = route.waypoints[i - 1].position
        val b = route.waypoints[i].position
        total += Geo.distanceMeters(
            LatLon(a.latitude, a.longitude),
            LatLon(b.latitude, b.longitude),
        )
    }
    return total
}

private fun formatDuration(seconds: Double): String {
    val total = (seconds / 60).toInt()
    return if (total >= 60) "${total / 60}h ${total % 60}m" else "${total}m"
}
