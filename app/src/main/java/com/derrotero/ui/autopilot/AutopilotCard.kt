package com.derrotero.ui.autopilot

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.derrotero.data.signalk.Paths
import com.derrotero.data.signalk.angle
import com.derrotero.data.signalk.text
import com.derrotero.ui.components.GlassCard
import com.derrotero.ui.components.SectionHeader
import com.derrotero.ui.theme.MarineColors
import com.derrotero.util.Formatters
import kotlinx.serialization.json.JsonElement

/**
 * OpenCPN-style autopilot panel over the Signal K autopilot API.
 * The server must publish `steering.autopilot.*`; the controls are best-effort and report
 * the error in the snackbar if the plugin is not installed.
 */
@Composable
fun AutopilotCard(
    state: Map<String, JsonElement>,
    onSetState: (String) -> Unit,
    onAdjustHeading: (Double) -> Unit,
    onTack: (String) -> Unit,
    nmeaOutput: Boolean = false,
) {
    val apState = state.text(Paths.AP_STATE)
    val target = state.angle(Paths.AP_TARGET_HEADING_MAG)
        ?: state.angle(Paths.AP_TARGET_HEADING_TRUE)
    val ownHeading = state.angle(Paths.HEADING_MAG) ?: state.angle(Paths.HEADING_TRUE)

    GlassCard(Modifier.fillMaxWidth(), accent = MarineColors.Harbor) {
        SectionHeader(
            title = "Piloto automático",
            subtitle = "${stateLabel(apState)} · objetivo ${Formatters.degrees(target)} · " +
                "rumbo ${Formatters.degrees(ownHeading)}",
            icon = Icons.Filled.Explore,
        )
        Column(
            Modifier.padding(top = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(
                    "standby" to "Standby",
                    "auto" to "Auto",
                    "wind" to "Viento",
                    "route" to "Ruta",
                ).forEach { (value, label) ->
                    FilterChip(
                        selected = apState.equals(value, ignoreCase = true),
                        onClick = { onSetState(value) },
                        label = { Text(label) },
                    )
                }
            }
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Rumbo", style = MaterialTheme.typography.labelMedium)
                listOf(-10.0, -1.0, 1.0, 10.0).forEach { delta ->
                    AssistChip(
                        onClick = { onAdjustHeading(delta) },
                        label = { Text(if (delta > 0) "+${delta.toInt()}°" else "${delta.toInt()}°") },
                    )
                }
            }
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AssistChip(
                    onClick = { onTack("port") },
                    label = { Text("Virada babor") },
                )
                AssistChip(
                    onClick = { onTack("starboard") },
                    label = { Text("Virada estribor") },
                )
            }
            Text(
                if (nmeaOutput) {
                    "Guía de ruta activa por NMEA 0183: APB/RMB/RMC/XTE salen por UDP a " +
                        "Signal K (o por TCP directo al gateway) sin token; pon el piloto en " +
                        "modo Track. Auto/Standby, ±1/±10 y viradas siguen necesitando " +
                        "Signal K/SeaTalk."
                } else {
                    "El piloto debe publicarse vía Signal K (steering.autopilot.*). " +
                        "Con solo NMEA 0183 se puede usar el modo Track del propio piloto " +
                        "(APB/RMB/XTE); Auto/Standby, +1/+10 y las viradas necesitan SeaTalk 1 " +
                        "o NMEA 2000 y un proveedor de piloto compatible."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MarineColors.TextDim,
            )
        }
    }
}

private fun stateLabel(value: String?): String = when (value?.lowercase()) {
    "standby" -> "Standby"
    "auto" -> "Auto"
    "wind" -> "Viento"
    "route" -> "Ruta"
    else -> value ?: "—"
}
