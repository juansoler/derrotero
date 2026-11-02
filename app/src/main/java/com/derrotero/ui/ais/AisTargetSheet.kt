package com.derrotero.ui.ais

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddLocationAlt
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.derrotero.data.ais.ShipType
import com.derrotero.data.vessel.AisTarget
import com.derrotero.util.Cpa
import com.derrotero.util.CpaResult
import com.derrotero.util.Formatters
import com.derrotero.util.Geo
import com.derrotero.util.LatLon

private enum class AisFilter(val label: String) {
    ALL("Todos"),
    DANGER("Peligro"),
    CLASS_A("Clase A"),
    MOVING("En movimiento"),
}

private data class AisRowData(
    val target: AisTarget,
    val distanceM: Double?,
    val cpa: CpaResult?,
)

/**
 * OpenCPN-style AIS target list: distance-sorted, with class/movement/danger filters,
 * CPA/TCPA per contact and actions to centre the chart or create a waypoint on the target.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AisTargetSheet(
    targets: Collection<AisTarget>,
    ownLat: Double?,
    ownLon: Double?,
    ownSogMps: Double?,
    ownCogRad: Double?,
    alarmIds: Set<String>,
    onClose: () -> Unit,
    onCenter: (AisTarget) -> Unit,
    onWaypoint: (AisTarget) -> Unit,
    onDetails: (AisTarget) -> Unit,
) {
    var filter by remember { mutableStateOf(AisFilter.ALL) }

    val rows = remember(targets, ownLat, ownLon, ownSogMps, ownCogRad, alarmIds, filter) {
        targets.map { target ->
            val distance = if (ownLat != null && ownLon != null) {
                Geo.distanceMeters(LatLon(ownLat, ownLon), LatLon(target.lat, target.lon))
            } else {
                null
            }
            val cpa = if (ownLat != null && ownLon != null) {
                Cpa.compute(
                    ownLat, ownLon, ownSogMps, ownCogRad,
                    target.lat, target.lon, target.sogMps, target.cogRad,
                )
            } else {
                null
            }
            AisRowData(target, distance, cpa)
        }.filter { row ->
            when (filter) {
                AisFilter.ALL -> true
                AisFilter.DANGER -> row.target.id in alarmIds
                AisFilter.CLASS_A -> row.target.vesselClass.equals("A", ignoreCase = true)
                AisFilter.MOVING -> (row.target.sogMps ?: 0.0) >= 0.5
            }
        }.sortedWith(
            compareBy({ it.distanceM ?: Double.MAX_VALUE }, { it.target.label }),
        )
    }

    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Objetivos AIS (${targets.size})",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                if (alarmIds.isNotEmpty()) {
                    Icon(
                        Icons.Filled.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                    Text(
                        " ${alarmIds.size}",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
            Text(
                "Ordenados por distancia · toca un objetivo para ver el detalle",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AisFilter.entries.forEach { option ->
                    FilterChip(
                        selected = filter == option,
                        onClick = { filter = option },
                        label = { Text(option.label) },
                    )
                }
            }
            if (rows.isEmpty()) {
                Text("Sin objetivos con este filtro.", style = MaterialTheme.typography.bodyMedium)
            } else {
                LazyColumn(
                    Modifier.heightIn(max = 520.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(rows, key = { it.target.id }) { row ->
                        AisRow(
                            row = row,
                            danger = row.target.id in alarmIds,
                            onClick = { onDetails(row.target) },
                            onCenter = { onCenter(row.target) },
                            onWaypoint = { onWaypoint(row.target) },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AisRow(
    row: AisRowData,
    danger: Boolean,
    onClick: () -> Unit,
    onCenter: () -> Unit,
    onWaypoint: () -> Unit,
) {
    val target = row.target
    Card(
        onClick = onClick,
        colors = if (danger) {
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            )
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Row(
            Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(target.label, style = MaterialTheme.typography.titleSmall)
                Text(
                    buildString {
                        append("MMSI ${target.mmsi ?: "—"}")
                        append(" · ${ShipType.label(target.category)}")
                        append(" · ${if (target.source == "internet") "Internet" else "Signal K/NMEA"}")
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "Dist ${Formatters.distance(row.distanceM)} · " +
                        "SOG ${Formatters.knots(target.sogMps)} · " +
                        "COG ${Formatters.degrees(target.cogRad)}",
                    style = MaterialTheme.typography.bodySmall,
                )
                row.cpa?.let { cpa ->
                    Text(
                        "CPA ${Formatters.distance(cpa.dcpaM)} · " +
                            "TCPA ${cpa.tcpaSec?.let { formatTcpa(it) } ?: "—"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (danger) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
            IconButton(onClick = onCenter) {
                Icon(Icons.Filled.MyLocation, contentDescription = "Centrar en el objetivo")
            }
            IconButton(onClick = onWaypoint) {
                Icon(Icons.Filled.AddLocationAlt, contentDescription = "Crear waypoint en el objetivo")
            }
        }
    }
}

private fun formatTcpa(seconds: Double): String = when {
    seconds >= 3600 -> "%.1f h".format(seconds / 3600)
    seconds >= 60 -> "%.0f min".format(seconds / 60)
    else -> "%.0f s".format(seconds)
}
