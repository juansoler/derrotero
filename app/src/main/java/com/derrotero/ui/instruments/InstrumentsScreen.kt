package com.derrotero.ui.instruments

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Anchor
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.derrotero.data.ais.ShipType
import com.derrotero.data.signalk.Paths
import com.derrotero.data.signalk.angle
import com.derrotero.data.signalk.angleDegrees
import com.derrotero.data.signalk.depthBest
import com.derrotero.data.signalk.kelvin
import com.derrotero.data.signalk.latitude
import com.derrotero.data.signalk.longitude
import com.derrotero.data.signalk.number
import com.derrotero.data.signalk.pascal
import com.derrotero.data.signalk.text
import com.derrotero.data.vessel.SignalKStatus
import com.derrotero.ui.NavViewModel
import com.derrotero.ui.autopilot.AutopilotCard
import com.derrotero.ui.components.GlassCard
import com.derrotero.ui.components.InstrumentTile
import com.derrotero.ui.components.MiniReadout
import com.derrotero.ui.components.ScreenHeader
import com.derrotero.ui.components.SectionHeader
import com.derrotero.ui.theme.MarineColors
import com.derrotero.util.Formatters
import com.derrotero.util.Geo
import com.derrotero.util.LatLon
import java.util.Locale
import kotlin.math.abs

@Composable
fun InstrumentsScreen(vm: NavViewModel) {
    val state by vm.vesselState.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val ais by vm.aisTargets.collectAsStateWithLifecycle()
    val internetAis by vm.internetAisTargets.collectAsStateWithLifecycle()
    val alarms by vm.aisAlarms.collectAsStateWithLifecycle()
    val anchor by vm.anchorState.collectAsStateWithLifecycle()
    val guidance by vm.navGuidance.collectAsStateWithLifecycle()
    val diagnostics by vm.diagnostics.collectAsStateWithLifecycle()
    val selfContext by vm.selfContext.collectAsStateWithLifecycle()
    val lastUpdate by vm.lastUpdate.collectAsStateWithLifecycle()
    val nmeaStatus by vm.nmeaStatus.collectAsStateWithLifecycle()

    val allAis = ais + internetAis
    val myLat = state.latitude()
    val myLon = state.longitude()
    val connected = status is SignalKStatus.Streaming ||
        status is SignalKStatus.Connected ||
        status is SignalKStatus.Nmea

    val nearest = remember(allAis, myLat, myLon) {
        allAis.values.map { target ->
            val distance = if (myLat != null && myLon != null) {
                Geo.distanceMeters(LatLon(myLat, myLon), LatLon(target.lat, target.lon))
            } else {
                null
            }
            target to distance
        }.sortedBy { it.second ?: Double.MAX_VALUE }.take(5)
    }

    val sogKn = state.number(Paths.SOG)?.times(1.943844)
    val depth = state.depthBest()
    val windKn = state.number(Paths.WIND_SPEED_APPARENT)
        ?: state.number(Paths.WIND_SPEED_TRUE)
    val heading = state.angle(Paths.HEADING_TRUE)
        ?: state.angle(Paths.HEADING_MAG)
        ?: state.angle(Paths.HEADING_COMPASS)

    val rows = listOf(
        "Rumbo" to Formatters.degrees(heading),
        "Rumbo mag." to Formatters.degrees(state.angle(Paths.HEADING_MAG)),
        "STW" to Formatters.knots(state.number(Paths.STW)),
        "Viento real" to Formatters.knots(state.number(Paths.WIND_SPEED_TRUE)),
        "Ángulo viento" to Formatters.degrees(state.angle(Paths.WIND_ANGLE_APPARENT)),
        "Temp. agua" to Formatters.celsius(state.kelvin(Paths.WATER_TEMP)),
        "Temp. aire" to Formatters.celsius(state.kelvin(Paths.AIR_TEMP)),
        "Presión" to Formatters.pressure(state.pascal(Paths.PRESSURE)),
        "Satélites GNSS" to Formatters.int(state.number(Paths.GNSS_SATELLITES)),
        "Corriente (set)" to Formatters.degrees(state.angle(Paths.CURRENT_SET_TRUE)),
        "Deriva" to Formatters.knots(state.number(Paths.CURRENT_DRIFT)),
        "XTE" to Formatters.distance(state.number(Paths.XTE)),
        "Rumbo al WP" to Formatters.degreesDirect(state.angleDegrees(Paths.BTW)),
        "Distancia WP" to Formatters.distance(state.number(Paths.DTW)),
        "VMG" to Formatters.knots(state.number(Paths.VMG)),
        "Batería" to (state.number(Paths.BATTERY_VOLTAGE)?.let {
            String.format(Locale.US, "%.1f V", it)
        } ?: "—"),
    )

    var showDiagnostics by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 26.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ScreenHeader(
                title = "Instrumentos",
                subtitle = if (myLat != null && myLon != null) {
                    Formatters.position(myLat, myLon)
                } else {
                    "Sin posición GPS · esperando datos"
                },
                statusLabel = status.label,
                statusActive = connected,
            )
        }

        item {
            GlassCard(Modifier.fillMaxWidth()) {
                SectionHeader(
                    title = "Enlace de datos",
                    subtitle = if (state.isEmpty()) "Sin datos del barco" else
                        "${state.size} valores · último ${ageSeconds(lastUpdate)}",
                    icon = Icons.Filled.Wifi,
                    trailing = {
                        TextButton(onClick = { showDiagnostics = !showDiagnostics }) {
                            Text(if (showDiagnostics) "Ocultar" else "Detalles")
                            Icon(
                                if (showDiagnostics) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    },
                )
                Row(
                    Modifier.fillMaxWidth().padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    MiniReadout("Deltas", diagnostics.messages.toString(), Modifier.weight(1f))
                    MiniReadout("AIS", (ais.size + internetAis.size).toString(), Modifier.weight(1f))
                    MiniReadout(
                        "Fuente",
                        state.text("name") ?: "—",
                        Modifier.weight(1.4f),
                    )
                }
                AnimatedVisibility(visible = showDiagnostics) {
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Spacer(Modifier.height(8.dp))
                        val selfLabel =
                            if (status is SignalKStatus.Nmea) {
                                "— (fuente NMEA 0183)"
                            } else {
                                selfContext
                            }
                        Text(
                            "Self: $selfLabel" +
                                (diagnostics.selfMmsi?.let { " · MMSI $it" } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = MarineColors.TextDim,
                        )
                        Text(
                            "Stream: ${diagnostics.streamState} · modelo " +
                                ageSeconds(diagnostics.fullModelAt),
                            style = MaterialTheme.typography.bodySmall,
                            color = MarineColors.TextDim,
                        )
                        if (nmeaStatus.enabled || nmeaStatus.sentences > 0) {
                            Text(
                                "NMEA 0183: ${nmeaStatus.state} · ${nmeaStatus.sentences} frases · " +
                                    "${nmeaStatus.ownSentences} propias · ${nmeaStatus.aisMessages} AIS",
                                style = MaterialTheme.typography.bodySmall,
                                color = MarineColors.TextDim,
                            )
                        }
                        nmeaStatus.lastError?.let {
                            Text(
                                "NMEA: $it",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        diagnostics.lastError?.let {
                            Text(
                                "Último error: $it",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                InstrumentTile(
                    label = "SOG",
                    value = sogKn?.let { String.format(Locale.US, "%.1f", it) } ?: "—",
                    caption = "Velocidad de fondo (kn)",
                    accent = MarineColors.Harbor,
                    highlighted = true,
                    modifier = Modifier.weight(1f),
                )
                InstrumentTile(
                    label = "Profundidad",
                    value = depth?.let { String.format(Locale.US, "%.1f", it) } ?: "—",
                    caption = "Sonda (m)",
                    accent = MarineColors.Mint,
                    highlighted = true,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                InstrumentTile(
                    label = "COG",
                    value = Formatters.degrees(
                        state.angle(Paths.COG) ?: state.angle(Paths.COG_MAG),
                    ),
                    caption = "Rumbo sobre el fondo",
                    accent = MarineColors.Sand,
                    modifier = Modifier.weight(1f),
                )
                InstrumentTile(
                    label = "Viento aparente",
                    value = windKn?.let { String.format(Locale.US, "%.1f", it) } ?: "—",
                    caption = "AWS (kn)",
                    accent = MarineColors.HarborSoft,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        if (guidance.active) {
            item {
                GlassCard(Modifier.fillMaxWidth(), accent = MarineColors.Harbor) {
                    SectionHeader(
                        title = "Navegación → ${guidance.name ?: "destino"}",
                        subtitle = "Guía de ruta activa",
                        icon = Icons.Filled.Navigation,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        MiniReadout(
                            "Distancia",
                            Formatters.distance(guidance.distanceM),
                            Modifier.weight(1f),
                            MarineColors.Harbor,
                        )
                        MiniReadout(
                            "Demora",
                            Formatters.degreesDirect(guidance.bearingDeg),
                            Modifier.weight(1f),
                        )
                        MiniReadout(
                            "VMG",
                            Formatters.knots(guidance.vmgMps),
                            Modifier.weight(1f),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "XTE " + (guidance.xteM?.let {
                            Formatters.distance(abs(it)) + if (it >= 0) " estribor" else " babor"
                        } ?: "—") + " · ETA ${formatEta(guidance.etaMs)} · faltan " +
                            formatTtg(guidance.timeToGoSec),
                        style = MaterialTheme.typography.bodySmall,
                        color = MarineColors.TextDim,
                    )
                    if (guidance.offTrack) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "⚠ Fuera de rumbo (límite " +
                                "${"%.0f".format(vm.settings.value.navigation.xteAlarmMeters)} m)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (guidance.arrived) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Waypoint alcanzado (radio " +
                                "${"%.0f".format(vm.settings.value.navigation.arrivalRadiusMeters)} m)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MarineColors.Mint,
                        )
                    }
                }
            }
        }

        item {
            AutopilotCard(
                state = state,
                onSetState = vm::setAutopilotState,
                onAdjustHeading = vm::adjustAutopilotHeading,
                onTack = vm::autopilotTack,
                nmeaOutput = vm.settings.value.nmea.sendsToAutopilot,
            )
        }

        if (alarms.isNotEmpty()) {
            item {
                GlassCard(Modifier.fillMaxWidth(), accent = MarineColors.Alert) {
                    SectionHeader(
                        title = "Alarmas AIS (${alarms.size})",
                        subtitle = "CPA / TCPA",
                        icon = Icons.Filled.Warning,
                    )
                    alarms.take(4).forEach { alarm ->
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "${alarm.label} · CPA ${Formatters.distance(alarm.dcpaM)} · " +
                                "TCPA ${"%.0f min".format(alarm.tcpaSec / 60)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }
        }

        item {
            GlassCard(Modifier.fillMaxWidth()) {
                SectionHeader(
                    title = "Tráfico AIS",
                    subtitle = "${ais.size} por SignalK · ${internetAis.size} por Internet",
                    icon = Icons.Filled.Radar,
                )
                Spacer(Modifier.height(8.dp))
                if (nearest.isEmpty()) {
                    Text(
                        "Sin contactos",
                        style = MaterialTheme.typography.bodySmall,
                        color = MarineColors.TextDim,
                    )
                } else {
                    nearest.forEachIndexed { index, (target, distance) ->
                        if (index > 0) {
                            HorizontalDivider(
                                Modifier.padding(vertical = 7.dp),
                                color = MarineColors.Line.copy(alpha = 0.7f),
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (alarms.any { it.targetId == target.id }) {
                                            MarineColors.Alert
                                        } else {
                                            MarineColors.Harbor
                                        },
                                    ),
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    target.label,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                )
                                Text(
                                    ShipType.label(target.category),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MarineColors.TextDim,
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    Formatters.distance(distance),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MarineColors.Harbor,
                                )
                                Text(
                                    Formatters.knots(target.sogMps),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MarineColors.TextDim,
                                )
                            }
                        }
                    }
                }
            }
        }

        if (anchor.set) {
            item {
                GlassCard(Modifier.fillMaxWidth(), accent = MarineColors.Mint) {
                    SectionHeader(
                        title = "Fondeo",
                        subtitle = if (anchor.alarm) "FUERA DEL RADIO" else "Dentro del radio",
                        icon = Icons.Filled.Anchor,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        MiniReadout(
                            "Distancia",
                            Formatters.meters(anchor.distanceMeters),
                            Modifier.weight(1f),
                            if (anchor.alarm) MarineColors.Alert else MarineColors.Mint,
                        )
                        MiniReadout(
                            "Demora",
                            Formatters.degreesDirect(anchor.bearingDeg),
                            Modifier.weight(1f),
                        )
                        MiniReadout(
                            "Radio",
                            "${anchor.radiusMeters.toInt()} m",
                            Modifier.weight(1f),
                        )
                    }
                }
            }
        }

        item {
            SectionHeader(
                title = "Todos los datos",
                subtitle = "Valores recibidos del barco",
                icon = Icons.Filled.Wifi,
            )
        }

        items(rows.chunked(2)) { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                pair.forEach { (label, value) ->
                    InstrumentTile(
                        label = label,
                        value = value,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }

        item {
            Text(
                "Los valores se actualizan con los deltas de SignalK, la entrada NMEA 0183 " +
                    "o el modo demo.",
                style = MaterialTheme.typography.labelSmall,
                color = MarineColors.TextDim,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

private fun ageSeconds(timestamp: Long): String {
    if (timestamp <= 0L) return "—"
    val seconds = ((System.currentTimeMillis() - timestamp) / 1000).coerceAtLeast(0)
    return when {
        seconds < 60 -> "${seconds}s"
        seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
        else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
    }
}

private fun formatTtg(seconds: Double?): String {
    if (seconds == null || !seconds.isFinite() || seconds <= 0) return "—"
    val total = (seconds / 60).toInt()
    return if (total >= 60) "${total / 60}h ${total % 60}m" else "${total}m"
}

private fun formatEta(epochMs: Long?): String {
    if (epochMs == null) return "—"
    return runCatching {
        java.time.Instant.ofEpochMilli(epochMs)
            .atZone(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
    }.getOrDefault("—")
}
