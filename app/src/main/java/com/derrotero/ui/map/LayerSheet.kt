package com.derrotero.ui.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.derrotero.data.settings.DisplaySettings

/**
 * OpenCPN-style chart layer panel: toggle AIS, waypoints, route, track, heading, current,
 * vectors, arrival ring and the chart overlays (scale, compass, follow, course-up).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LayerSheet(
    display: DisplaySettings,
    onChange: (DisplaySettings) -> Unit,
    onClose: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 620.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Capas de la carta", style = MaterialTheme.typography.titleLarge)
            Text(
                "Las cartas locales se combinan por prioridad; aquí controlas qué se dibuja encima.",
                style = MaterialTheme.typography.bodySmall,
            )

            Text(
                "Navegación",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 8.dp),
            )
            LayerRow("Objetivos AIS", display.showAis) {
                onChange(display.copy(showAis = it))
            }
            LayerRow("Waypoints", display.showWaypoints) {
                onChange(display.copy(showWaypoints = it))
            }
            LayerRow("Ruta activa", display.showRoute) {
                onChange(display.copy(showRoute = it))
            }
            LayerRow("Estela", display.showTrack) {
                onChange(display.copy(showTrack = it))
            }
            LayerRow("Línea de rumbo", display.showHeading) {
                onChange(display.copy(showHeading = it))
            }
            LayerRow("Flecha de corriente", display.showCurrent) {
                onChange(display.copy(showCurrent = it))
            }
            LayerRow("Círculo de llegada", display.showArrivalRing) {
                onChange(display.copy(showArrivalRing = it))
            }
            LayerRow("Vectores AIS (6 min)", display.aisVectors) {
                onChange(display.copy(aisVectors = it))
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text("Carta e indicadores", style = MaterialTheme.typography.titleSmall)
            LayerRow("Escala gráfica", display.showScaleBar) {
                onChange(display.copy(showScaleBar = it))
            }
            LayerRow("Compás / rumbo", display.showCompass) {
                onChange(display.copy(showCompass = it))
            }
            LayerRow("Seguir al barco", display.followBoat) {
                onChange(display.copy(followBoat = it))
            }
            LayerRow("Rumbo arriba", display.courseUp) {
                onChange(display.copy(courseUp = it))
            }
            Text(
                "Si activas «rumbo arriba» la carta gira con el COG/rumbo. «Seguir al barco» " +
                    "vuelve a centrar la vista en cada posición.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun LayerRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
