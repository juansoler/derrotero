package com.derrotero.ui.charts

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Satellite
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.derrotero.data.charts.ChartKind
import com.derrotero.data.charts.ChartSource
import com.derrotero.data.charts.DefaultCharts
import com.derrotero.data.charts.OfflineChartDownloader
import com.derrotero.ui.NavViewModel
import com.derrotero.ui.components.GlassCard
import com.derrotero.ui.components.ScreenHeader
import com.derrotero.ui.components.SectionHeader
import com.derrotero.ui.theme.MarineColors

@Composable
fun ChartsScreen(vm: NavViewModel) {
    val charts by vm.chartSources.collectAsStateWithLifecycle()
    val scan by vm.scan.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val bounds by vm.mapBounds.collectAsStateWithLifecycle()
    val download by vm.download.collectAsStateWithLifecycle()
    var showXyzDialog by remember { mutableStateOf(false) }
    var showOfflineDialog by remember { mutableStateOf(false) }
    val activeSource = charts.firstOrNull { it.id == settings.activeChartId }

    // Draw the selected download area on the map while the dialog is open.
    DisposableEffect(showOfflineDialog) {
        vm.setDownloadAreaVisible(showOfflineDialog)
        onDispose { vm.setDownloadAreaVisible(false) }
    }

    val treePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri -> uri?.let(vm::scanSd) }

    val mbTilesPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { vm.addMbTiles(it, it.lastPathSegment?.substringAfterLast('/')) } }

    val pmTilesPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { vm.addPmTiles(it, it.lastPathSegment?.substringAfterLast('/')) } }

    val kapPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { vm.addBsbKap(it, it.lastPathSegment?.substringAfterLast('/')) } }

    val gpkgPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { vm.addGeoPackage(it, it.lastPathSegment?.substringAfterLast('/')) } }

    val s57Picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> if (uris.isNotEmpty()) vm.addS57(uris) }

    val nosPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> if (uris.isNotEmpty()) vm.addNos(uris) }

    val shortcuts = listOf(
        ChartShortcut(
            "Tarjeta SD",
            "Escanear cartas",
            Icons.Filled.FolderOpen,
            MarineColors.Harbor,
        ) { treePicker.launch(null) },
        ChartShortcut(
            "MBTiles",
            "Archivo local",
            Icons.Filled.Map,
            MarineColors.Mint,
        ) { mbTilesPicker.launch(arrayOf("*/*")) },
        ChartShortcut(
            "PMTiles",
            "Raster o vectorial",
            Icons.Filled.Archive,
            MarineColors.Sand,
        ) { pmTilesPicker.launch(arrayOf("*/*")) },
        ChartShortcut(
            "XYZ",
            "URL con {z}/{x}/{y}",
            Icons.Filled.Public,
            MarineColors.HarborSoft,
        ) { showXyzDialog = true },
        ChartShortcut(
            "BSB / KAP",
            "Cartas ráster NOAA",
            Icons.Filled.Map,
            MarineColors.Harbor,
        ) { kapPicker.launch(arrayOf("*/*")) },
        ChartShortcut(
            "GeoPackage",
            "Ráster .gpkg",
            Icons.Filled.Storage,
            MarineColors.Mint,
        ) { gpkgPicker.launch(arrayOf("*/*")) },
        ChartShortcut(
            "S-57 ENC",
            ".000 + actualizaciones",
            Icons.Filled.Satellite,
            MarineColors.Sand,
        ) { s57Picker.launch(arrayOf("*/*")) },
        ChartShortcut(
            "NOS / GEO",
            "Cartas raster NOAA",
            Icons.Filled.FolderOpen,
            MarineColors.HarborSoft,
        ) { nosPicker.launch(arrayOf("*/*")) },
        ChartShortcut(
            "Descargar área",
            "Guardar teselas offline",
            Icons.Filled.Download,
            MarineColors.Mint,
        ) { showOfflineDialog = true },
    )

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 26.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ScreenHeader(
                title = "Cartas",
                subtitle = "Fuentes, capas y descargas offline",
            )
        }

        if (busy) {
            item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        }

        item {
            GlassCard(
                Modifier.fillMaxWidth(),
                accent = MarineColors.Harbor,
            ) {
                SectionHeader(
                    title = "Carta activa",
                    subtitle = activeSource?.name ?: "Carta base por defecto (OSM)",
                    icon = Icons.Filled.Layers,
                )
                Spacer(Modifier.height(8.dp))
                if (activeSource != null) {
                    Text(
                        chartKindLabel(activeSource.kind) +
                            " · z${activeSource.minZoom}-${activeSource.maxZoom}" +
                            " · prioridad ${activeSource.priority}" +
                            if (activeSource.isOverlay) " · overlay" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MarineColors.Harbor,
                    )
                    activeSource.bounds?.let {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "Cobertura ${it.summary()}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MarineColors.TextDim,
                        )
                    }
                } else {
                    Text(
                        "Se usa OpenStreetMap como carta base.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MarineColors.TextDim,
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            charts.firstOrNull { it.id == "seamap" }?.let {
                                vm.setActiveChart(it.id)
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("Seamap", maxLines = 1) }
                    Button(
                        onClick = {
                            vm.addXyz(
                                "OpenSeaMap (balizas/boyas)",
                                "https://tiles.openseamap.org/seamark/{z}/{x}/{y}.png",
                                overlay = true,
                            )
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MarineColors.Harbor.copy(alpha = 0.16f),
                            contentColor = MarineColors.Harbor,
                        ),
                    ) { Text("OpenSeaMap", maxLines = 1) }
                }
                if (activeSource?.kind == ChartKind.PMTILES_VECTOR) {
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { vm.downloadChartAssets() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.Download, contentDescription = null)
                        Text("  Descargar glifos y sprites", maxLines = 1)
                    }
                }
            }
        }

        item {
            GlassCard(Modifier.fillMaxWidth()) {
                SectionHeader(
                    title = "Añadir origen",
                    subtitle = "Se guardan y se combinan por prioridad",
                    icon = Icons.Filled.Add,
                )
                Spacer(Modifier.height(10.dp))
                shortcuts.chunked(2).forEachIndexed { rowIndex, pair ->
                    if (rowIndex > 0) Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        pair.forEach { shortcut ->
                            ChartShortcutTile(shortcut, Modifier.weight(1f))
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "También puedes descargar la zona visible (rectángulo azul) para navegar " +
                        "sin conexión.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MarineColors.TextDim,
                )
            }
        }

        if (download.running || download.message != null) {
            item {
                GlassCard(
                    Modifier.fillMaxWidth(),
                    accent = if (download.running) MarineColors.Harbor else MarineColors.Mint,
                ) {
                    SectionHeader(
                        title = "Descarga offline",
                        subtitle = if (download.running) "En curso" else "Último resultado",
                        icon = Icons.Filled.Download,
                    )
                    if (download.total > 0) {
                        Spacer(Modifier.height(10.dp))
                        LinearProgressIndicator(
                            progress = { download.done.toFloat() / download.total },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "${download.done}/${download.total} teselas · fallos ${download.failed}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MarineColors.TextDim,
                        )
                    }
                    download.message?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MarineColors.TextDim,
                        )
                    }
                    if (download.running) {
                        TextButton(onClick = { vm.cancelDownload() }) {
                            Text("Cancelar descarga")
                        }
                    }
                }
            }
        }

        scan?.let { result ->
            item {
                GlassCard(Modifier.fillMaxWidth(), accent = MarineColors.Sand) {
                    SectionHeader(
                        title = "Tarjeta SD",
                        subtitle = result.root,
                        icon = Icons.Filled.FolderOpen,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "${result.totalFiles} ficheros · ${result.totalBytes / 1024 / 1024} MB",
                        style = MaterialTheme.typography.bodySmall,
                        color = MarineColors.TextDim,
                    )
                    result.formats.entries
                        .sortedByDescending { it.value }
                        .take(8)
                        .forEach { (ext, count) ->
                            Text(
                                "· .$ext: $count",
                                style = MaterialTheme.typography.labelSmall,
                                color = MarineColors.TextDim,
                            )
                        }
                    result.notes.forEach {
                        Text(
                            "• $it",
                            style = MaterialTheme.typography.bodySmall,
                            color = MarineColors.Amber,
                        )
                    }
                    if (result.userData.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Datos de usuario",
                            style = MaterialTheme.typography.labelMedium,
                            color = MarineColors.Text,
                        )
                        result.userData.take(5).forEach {
                            Text(
                                "· $it",
                                style = MaterialTheme.typography.labelSmall,
                                color = MarineColors.TextDim,
                            )
                        }
                    }
                    if (result.chartPackages.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Paquetes de cartas",
                            style = MaterialTheme.typography.labelMedium,
                            color = MarineColors.Text,
                        )
                        result.chartPackages.take(5).forEach {
                            Text(
                                "· $it",
                                style = MaterialTheme.typography.labelSmall,
                                color = MarineColors.TextDim,
                            )
                        }
                    }
                }
            }
        }

        item {
            SectionHeader(
                title = "Capas (${charts.size})",
                subtitle = "La capa marcada es la carta activa; el orden define el quilt",
                icon = Icons.Filled.Layers,
            )
        }

        items(charts, key = { it.id }) { source ->
            val index = charts.indexOfFirst { it.id == source.id }
            GlassCard(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = settings.activeChartId == source.id,
                        onClick = { vm.setActiveChart(source.id) },
                        enabled = source.enabled,
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            source.name,
                            style = MaterialTheme.typography.titleSmall,
                            color = MarineColors.Text,
                        )
                        Text(
                            chartKindLabel(source.kind) +
                                " · z${source.minZoom}-${source.maxZoom}" +
                                if (source.isOverlay) " · overlay" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MarineColors.TextDim,
                        )
                        source.bounds?.let {
                            Text(
                                "Cobertura ${it.summary()}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MarineColors.TextDim,
                            )
                        }
                        if (source.attribution.isNotBlank()) {
                            Text(
                                source.attribution,
                                style = MaterialTheme.typography.labelSmall,
                                color = MarineColors.TextDim,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Switch(
                        checked = source.enabled,
                        onCheckedChange = { vm.setChartEnabled(source.id, it) },
                    )
                }
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Prioridad ${source.priority}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MarineColors.TextDim,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = { vm.moveChart(source.id, -1) },
                        enabled = index > 0,
                    ) {
                        Icon(Icons.Filled.ArrowUpward, contentDescription = "Subir prioridad")
                    }
                    IconButton(
                        onClick = { vm.moveChart(source.id, 1) },
                        enabled = index in 0 until charts.lastIndex,
                    ) {
                        Icon(Icons.Filled.ArrowDownward, contentDescription = "Bajar prioridad")
                    }
                    if (source.id !in DefaultCharts.defaults.map { it.id } &&
                        (source.kind == ChartKind.MBTILES || source.kind == ChartKind.XYZ ||
                            source.kind == ChartKind.PMTILES ||
                            source.kind == ChartKind.PMTILES_VECTOR ||
                            source.kind == ChartKind.BSB_KAP ||
                            source.kind == ChartKind.GEOPACKAGE ||
                            source.kind == ChartKind.S57)
                    ) {
                        IconButton(onClick = { vm.removeChart(source.id) }) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = "Quitar",
                                tint = MarineColors.Alert,
                            )
                        }
                    }
                }
            }
        }
    }

    if (showXyzDialog) {
        AddXyzDialog(
            onDismiss = { showXyzDialog = false },
            onAdd = { name, template, overlay ->
                vm.addXyz(name, template, overlay)
                showXyzDialog = false
            },
        )
    }

    if (showOfflineDialog) {
        OfflineDownloadDialog(
            boundsSummary = bounds?.summary(),
            vectorBase = activeSource?.kind == ChartKind.STYLE,
            offlineAllowed = activeSource?.let {
                (it.kind == ChartKind.XYZ || it.kind == ChartKind.SIGNALK) &&
                    it.allowOfflineDownload
            } ?: false,
            estimate = { minZoom, maxZoom ->
                bounds?.let { OfflineChartDownloader.countTiles(it, minZoom, maxZoom) } ?: 0
            },
            onDismiss = { showOfflineDialog = false },
            onStart = { name, minZoom, maxZoom ->
                vm.downloadOffline(name, minZoom, maxZoom)
                showOfflineDialog = false
            },
        )
    }
}

private data class ChartShortcut(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val accent: Color,
    val onClick: () -> Unit,
)

@Composable
private fun ChartShortcutTile(shortcut: ChartShortcut, modifier: Modifier = Modifier) {
    GlassCard(onClick = shortcut.onClick, modifier = modifier.height(104.dp)) {
        Box(
            Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(shortcut.accent.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                shortcut.icon,
                contentDescription = null,
                tint = shortcut.accent,
                modifier = Modifier.size(17.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            shortcut.title,
            style = MaterialTheme.typography.titleSmall,
            color = MarineColors.Text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            shortcut.subtitle,
            style = MaterialTheme.typography.labelSmall,
            color = MarineColors.TextDim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun chartKindLabel(kind: ChartKind): String = when (kind) {
    ChartKind.STYLE -> "vectorial · estilo MapLibre"
    ChartKind.MBTILES -> "MBTiles local"
    ChartKind.PMTILES -> "PMTiles local"
    ChartKind.PMTILES_VECTOR -> "PMTiles vectorial (estilo)"
    ChartKind.BSB_KAP -> "BSB/KAP (ráster NOAA)"
    ChartKind.GEOPACKAGE -> "GeoPackage (ráster)"
    ChartKind.S57 -> "S-57 ENC (vectorial)"
    ChartKind.SIGNALK -> "SignalK"
    ChartKind.XYZ -> "raster XYZ"
}

@Composable
private fun OfflineDownloadDialog(
    boundsSummary: String?,
    vectorBase: Boolean,
    offlineAllowed: Boolean,
    estimate: (Int, Int) -> Int,
    onDismiss: () -> Unit,
    onStart: (String, Int, Int) -> Unit,
) {
    var name by remember { mutableStateOf("offline") }
    var minZoomText by remember { mutableStateOf("10") }
    var maxZoomText by remember { mutableStateOf("14") }
    val minZoom = minZoomText.toIntOrNull() ?: 10
    val maxZoom = maxZoomText.toIntOrNull() ?: 14
    val count = if (minZoom <= maxZoom) estimate(minZoom, maxZoom) else 0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Descargar carta offline") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    boundsSummary ?: "Mueve el mapa a la zona que quieras descargar",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (!offlineAllowed) {
                    Text(
                        "La carta activa no es una plantilla XYZ o su proveedor no permite " +
                            "descargas masivas. Usa una plantilla XYZ propia o una carta PMTiles " +
                            "de Seamap.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nombre") },
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
                onClick = { onStart(name, minZoom, maxZoom) },
                enabled = offlineAllowed && count in 1..OfflineChartDownloader.MAX_TILES,
            ) { Text("Descargar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

@Composable
private fun AddXyzDialog(onDismiss: () -> Unit, onAdd: (String, String, Boolean) -> Unit) {
    var name by remember { mutableStateOf("") }
    var template by remember { mutableStateOf("https://tile.openstreetmap.org/{z}/{x}/{y}.png") }
    var overlay by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Añadir capa XYZ") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nombre") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = template,
                    onValueChange = { template = it },
                    label = { Text("Plantilla {z}/{x}/{y}") },
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = overlay, onClick = { overlay = true })
                    Text("Overlay")
                    RadioButton(selected = !overlay, onClick = { overlay = false })
                    Text("Base")
                }
            }
        },
        confirmButton = { TextButton(onClick = { onAdd(name, template, overlay) }) { Text("Añadir") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}
