package com.derrotero.ui.map

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import com.derrotero.data.resources.Waypoint
import com.derrotero.util.CoordinateParser
import com.derrotero.util.Formatters

/**
 * OpenCPN-style "Go to position": accepts decimal, degrees/minutes or DMS coordinates and
 * also searches the waypoint library by name.
 */
@Composable
fun GoToDialog(
    waypoints: List<Waypoint>,
    onDismiss: () -> Unit,
    onGoTo: (Double, Double, String) -> Unit,
    onCenter: (Double, Double) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    val parsed = remember(text) { CoordinateParser.parse(text) }
    val matches = remember(text, waypoints) {
        val query = text.trim()
        if (query.isEmpty() || parsed != null) {
            emptyList()
        } else {
            waypoints.filter { it.name?.contains(query, ignoreCase = true) == true }.take(8)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ir a posición") },
        text = {
            Column(
                Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Coordenadas o nombre") },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Formatos: 43.3000, -2.9500 · 43 18.02 N 2 56.97 W · " +
                        "43°18.02'N 2°56.97'W · 43 18 1.2 N 2 56 58.2 W",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (parsed != null) {
                    Text(
                        Formatters.position(parsed.lat, parsed.lon),
                        style = MaterialTheme.typography.titleSmall,
                    )
                } else if (text.isNotBlank()) {
                    Text(
                        "No se reconoce la coordenada.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (matches.isNotEmpty()) {
                    HorizontalDivider()
                    Text("Waypoints", style = MaterialTheme.typography.titleSmall)
                    matches.forEach { waypoint ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                onCenter(waypoint.position.latitude, waypoint.position.longitude)
                            }.padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(Icons.Filled.Place, contentDescription = null)
                            Column(Modifier.weight(1f)) {
                                Text(
                                    waypoint.name ?: "(sin nombre)",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    Formatters.position(
                                        waypoint.position.latitude,
                                        waypoint.position.longitude,
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            TextButton(
                                onClick = {
                                    onGoTo(
                                        waypoint.position.latitude,
                                        waypoint.position.longitude,
                                        waypoint.name ?: "Waypoint",
                                    )
                                },
                            ) { Text("Ir") }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    parsed?.let { onGoTo(it.lat, it.lon, "Coordenadas") }
                },
                enabled = parsed != null,
            ) { Text("Ir") }
        },
        dismissButton = {
            Row {
                TextButton(
                    onClick = { parsed?.let { onCenter(it.lat, it.lon) } },
                    enabled = parsed != null,
                ) { Text("Centrar") }
                TextButton(onClick = onDismiss) { Text("Cancelar") }
            }
        },
    )
}
