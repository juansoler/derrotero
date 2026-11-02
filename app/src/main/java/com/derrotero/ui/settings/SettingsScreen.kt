package com.derrotero.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Anchor
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.derrotero.data.settings.InternetAisProvider
import com.derrotero.data.settings.NmeaTransport
import com.derrotero.data.vessel.SignalKStatus
import com.derrotero.ui.NavViewModel
import com.derrotero.ui.components.GlassCard
import com.derrotero.ui.components.ScreenHeader
import com.derrotero.ui.components.SectionHeader
import com.derrotero.ui.components.StatusPill
import com.derrotero.ui.theme.MarineColors
import com.derrotero.util.Formatters

@Composable
fun SettingsScreen(vm: NavViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val info by vm.serverInfo.collectAsStateWithLifecycle()
    val anchor by vm.anchorState.collectAsStateWithLifecycle()
    val diagnostics by vm.diagnostics.collectAsStateWithLifecycle()
    val selfContext by vm.selfContext.collectAsStateWithLifecycle()
    val selfMmsi by vm.selfMmsi.collectAsStateWithLifecycle()
    val lastUpdate by vm.lastUpdate.collectAsStateWithLifecycle()
    val nmeaStatus by vm.nmeaStatus.collectAsStateWithLifecycle()
    val nmeaOutputStatus by vm.nmeaOutputStatus.collectAsStateWithLifecycle()
    val serviceRunning by vm.serviceRunning.collectAsStateWithLifecycle()

    var host by remember(settings.signalK.host) { mutableStateOf(settings.signalK.host) }
    var port by remember(settings.signalK.port) { mutableStateOf(settings.signalK.port.toString()) }
    var tls by remember(settings.signalK.useTls) { mutableStateOf(settings.signalK.useTls) }
    var context by remember(settings.signalK.context) { mutableStateOf(settings.signalK.context) }
    var token by remember(settings.signalK.token) { mutableStateOf(settings.signalK.token) }

    var nmeaEnabled by remember(settings.nmea.enabled) { mutableStateOf(settings.nmea.enabled) }
    var nmeaTransport by remember(settings.nmea.transport) {
        mutableStateOf(settings.nmea.transport)
    }
    var nmeaHost by remember(settings.nmea.host) { mutableStateOf(settings.nmea.host) }
    var nmeaPort by remember(settings.nmea.port) { mutableStateOf(settings.nmea.port.toString()) }
    var nmeaSendNavigation by remember(settings.nmea.sendNavigation) {
        mutableStateOf(settings.nmea.sendNavigation)
    }
    var nmeaOutputHost by remember(settings.nmea.outputHost) {
        mutableStateOf(settings.nmea.outputHost)
    }
    var nmeaOutputPort by remember(settings.nmea.outputPort) {
        mutableStateOf(
            if (settings.nmea.outputPort in 1..65535) settings.nmea.outputPort.toString() else "",
        )
    }
    var nmeaOutputTransport by remember(settings.nmea.outputTransport) {
        mutableStateOf(settings.nmea.outputTransport)
    }
    val signalKHostOnly = remember(settings.signalK.host) {
        settings.signalK.host.trim()
            .substringAfter("://", settings.signalK.host.trim())
            .substringBefore('/')
            .substringBefore(':')
            .trim()
    }

    var navAlarms by remember(settings.navigation.alarmsEnabled) {
        mutableStateOf(settings.navigation.alarmsEnabled)
    }
    var arrivalText by remember(settings.navigation.arrivalRadiusMeters) {
        mutableStateOf(settings.navigation.arrivalRadiusMeters.toInt().toString())
    }
    var xteText by remember(settings.navigation.xteAlarmMeters) {
        mutableStateOf(settings.navigation.xteAlarmMeters.toInt().toString())
    }
    var autoAdvance by remember(settings.navigation.autoAdvance) {
        mutableStateOf(settings.navigation.autoAdvance)
    }
    var nightDim by remember(settings.display.nightDim) { mutableStateOf(settings.display.nightDim) }
    var aisVectors by remember(settings.display.aisVectors) { mutableStateOf(settings.display.aisVectors) }
    var showLicenses by remember { mutableStateOf(false) }

    var alarmEnabled by remember(settings.alarms.enabled) { mutableStateOf(settings.alarms.enabled) }
    var cpaText by remember(settings.alarms.cpaMeters) {
        mutableStateOf(settings.alarms.cpaMeters.toInt().toString())
    }
    var tcpaText by remember(settings.alarms.tcpaMinutes) {
        mutableStateOf(settings.alarms.tcpaMinutes.toInt().toString())
    }
    var radiusText by remember(settings.alarms.radiusMeters) {
        mutableStateOf(settings.alarms.radiusMeters.toInt().toString())
    }
    var minSpeedText by remember(settings.alarms.minSpeedKn) {
        mutableStateOf(settings.alarms.minSpeedKn.toInt().toString())
    }
    var onlyClassA by remember(settings.alarms.onlyClassA) {
        mutableStateOf(settings.alarms.onlyClassA)
    }
    var anchorRadiusText by remember(settings.anchor.radiusMeters) {
        mutableStateOf(settings.anchor.radiusMeters.toInt().toString())
    }
    var internetKey by remember(settings.internetAis.apiKey) {
        mutableStateOf(settings.internetAis.apiKey)
    }
    var internetUser by remember(settings.internetAis.username) {
        mutableStateOf(settings.internetAis.username)
    }
    var internetProvider by remember(settings.internetAis.provider) {
        mutableStateOf(settings.internetAis.provider)
    }
    val internetStatus by vm.internetAisStatus.collectAsStateWithLifecycle()
    val internetMessages by vm.internetAisMessages.collectAsStateWithLifecycle()
    val internetLast by vm.internetAisLastMessage.collectAsStateWithLifecycle()
    val internetError by vm.internetAisError.collectAsStateWithLifecycle()

    val connected = status is SignalKStatus.Streaming ||
        status is SignalKStatus.Connected ||
        status is SignalKStatus.Nmea

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenHeader(
            title = "Ajustes",
            subtitle = "Conexión, alarmas y pantalla",
            statusLabel = status.label,
            statusActive = connected,
        )

        // Service control first: this is the place to really stop the app in the background.
        GlassCard(Modifier.fillMaxWidth(), accent = MarineColors.Alert) {
            SectionHeader(
                title = "Servicio en segundo plano",
                subtitle = if (serviceRunning) {
                    "Activo · notificación con datos y botón PARAR"
                } else {
                    "Detenido · la app no consume datos al cerrarse"
                },
                icon = Icons.Filled.PowerSettingsNew,
                trailing = {
                    StatusPill(
                        label = if (serviceRunning) "Activo" else "Detenido",
                        active = serviceRunning,
                    )
                },
            )
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = settings.keepAlive,
                    onCheckedChange = { vm.setKeepAlive(it) },
                )
                Column(Modifier.padding(start = 10.dp)) {
                    Text(
                        "Mantener conexión al cerrar la app",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "SignalK, NMEA y AIS siguen activos con una notificación fija.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MarineColors.TextDim,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = { vm.stopBackground() },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MarineColors.Alert.copy(alpha = 0.18f),
                    contentColor = MarineColors.Alert,
                ),
            ) {
                Text("Detener servicio y conexión")
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "Parar cancela SignalK y NMEA, retira la notificación y evita que el " +
                    "servicio se reinicie solo. Al volver a abrir la app la conexión se " +
                    "restablece.",
                style = MaterialTheme.typography.labelSmall,
                color = MarineColors.TextDim,
            )
        }

        GlassCard(Modifier.fillMaxWidth()) {
            SectionHeader(
                title = "Servidor SignalK",
                subtitle = "REST + WebSocket",
                icon = Icons.Filled.Router,
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = host,
                onValueChange = { host = it },
                label = { Text("Host / IP") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = port,
                onValueChange = { port = it.filter(Char::isDigit) },
                label = { Text("Puerto") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = context,
                onValueChange = { context = it },
                label = { Text("Contexto (vessels.self)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = { Text("Token de Signal K (opcional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Solo para REST/WebSocket de Signal K. La salida NMEA 0183 al piloto no usa token.",
                style = MaterialTheme.typography.labelSmall,
                color = MarineColors.TextDim,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "URL: ${settings.signalK.httpBase}",
                style = MaterialTheme.typography.bodySmall,
                color = MarineColors.Harbor,
            )
            Row(
                Modifier.padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Switch(checked = tls, onCheckedChange = { tls = it })
                Text("  HTTPS/WSS", style = MaterialTheme.typography.bodyMedium)
            }
            Row(
                Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = {
                    vm.saveServer(host, port.toIntOrNull() ?: 3000, tls, context, token)
                }) { Text("Guardar") }
                Button(onClick = { vm.testConnection() }) { Text("Probar") }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "Estado: ${status.label}",
                style = MaterialTheme.typography.bodySmall,
                color = if (connected) MarineColors.Mint else MarineColors.TextDim,
            )
            info?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MarineColors.TextDim)
            }
            Text(
                "Self resuelto: " +
                    (if (status is SignalKStatus.Nmea) "— (fuente NMEA 0183)" else selfContext) +
                    (selfMmsi?.let { " · MMSI $it" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = MarineColors.TextDim,
            )
            Text(
                "Stream: ${diagnostics.streamState} · ${diagnostics.messages} deltas · " +
                    "${diagnostics.values} valores · último ${ageSeconds(lastUpdate)}",
                style = MaterialTheme.typography.bodySmall,
                color = MarineColors.TextDim,
            )
            Text(
                "Modelo REST: ${ageSeconds(diagnostics.fullModelAt)}",
                style = MaterialTheme.typography.bodySmall,
                color = MarineColors.TextDim,
            )
            diagnostics.lastError?.let {
                Text(
                    "Error: $it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        GlassCard(Modifier.fillMaxWidth()) {
            SectionHeader(
                title = "NMEA 0183 (Wi-Fi)",
                subtitle = "Entrada de datos y salida independiente al piloto",
                icon = Icons.Filled.Wifi,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Entrada de datos",
                style = MaterialTheme.typography.titleSmall,
                color = MarineColors.Text,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = nmeaEnabled, onCheckedChange = { nmeaEnabled = it })
                Text("  Activar entrada NMEA", style = MaterialTheme.typography.bodyMedium)
            }
            Row(
                Modifier.padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = nmeaTransport == NmeaTransport.TCP,
                    onClick = { nmeaTransport = NmeaTransport.TCP },
                    label = { Text("TCP (cliente)") },
                )
                FilterChip(
                    selected = nmeaTransport == NmeaTransport.UDP,
                    onClick = { nmeaTransport = NmeaTransport.UDP },
                    label = { Text("UDP (escucha)") },
                )
            }
            if (nmeaTransport == NmeaTransport.TCP) {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = nmeaHost,
                    onValueChange = { nmeaHost = it },
                    label = { Text("Host del multiplexor") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = nmeaPort,
                onValueChange = { nmeaPort = it.filter(Char::isDigit) },
                label = { Text("Puerto (10110 por defecto)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Se aceptan GGA/RMC/VTG/GLL/HDT/HDM/VHW/DPT/DBT/MTW/MWV/VWR/MDA/GSA/" +
                    "RMB/BWC/APB/XTE y AIS !AIVDM. Mientras Signal K esté en vivo tiene " +
                    "prioridad; NMEA rellena huecos y toma el relevo si cae.",
                style = MaterialTheme.typography.labelSmall,
                color = MarineColors.TextDim,
            )

            Spacer(Modifier.height(16.dp))
            Text(
                "Salida al piloto automático",
                style = MaterialTheme.typography.titleSmall,
                color = MarineColors.Text,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = nmeaSendNavigation,
                    onCheckedChange = { nmeaSendNavigation = it },
                )
                Text(
                    "  Enviar guía al piloto (APB/RMB/RMC/XTE)",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Row(
                Modifier.padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = nmeaOutputTransport == NmeaTransport.UDP,
                    onClick = { nmeaOutputTransport = NmeaTransport.UDP },
                    label = { Text("UDP (Signal K)") },
                )
                FilterChip(
                    selected = nmeaOutputTransport == NmeaTransport.TCP,
                    onClick = { nmeaOutputTransport = NmeaTransport.TCP },
                    label = { Text("TCP (gateway directo)") },
                )
            }
            Text(
                "Conexión propia, cuatro frases por segundo. Con UDP la app envía las frases " +
                    "a Signal K y el servidor las reenvía al piloto por el puerto serie " +
                    "(conexión NMEA 0183 UDP con evento de entrada «autopilot»; conexión " +
                    "serie con evento de salida «autopilot»). Con TCP conecta directamente " +
                    "al multiplexor/gateway, como OpenCPN. No depende de la entrada NMEA ni " +
                    "del token de Signal K.",
                style = MaterialTheme.typography.labelSmall,
                color = MarineColors.TextDim,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = nmeaOutputHost,
                onValueChange = { nmeaOutputHost = it },
                label = {
                    Text(
                        if (nmeaOutputTransport == NmeaTransport.UDP) {
                            "Host de Signal K"
                        } else {
                            "Host del gateway"
                        },
                    )
                },
                placeholder = {
                    Text(
                        if (nmeaOutputTransport == NmeaTransport.UDP) {
                            "IP de Signal K (ej. 192.168.1.103)"
                        } else {
                            "IP del gateway (ej. 192.168.1.103)"
                        },
                    )
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (nmeaOutputTransport == NmeaTransport.UDP &&
                nmeaOutputHost.isBlank() &&
                signalKHostOnly.isNotBlank()
            ) {
                TextButton(onClick = { nmeaOutputHost = signalKHostOnly }) {
                    Text("Usar host de Signal K ($signalKHostOnly)")
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = nmeaOutputPort,
                onValueChange = { nmeaOutputPort = it.filter(Char::isDigit) },
                label = {
                    Text(
                        if (nmeaOutputTransport == NmeaTransport.UDP) {
                            "Puerto UDP de Signal K"
                        } else {
                            "Puerto TCP del gateway"
                        },
                    )
                },
                placeholder = {
                    Text(
                        if (nmeaOutputTransport == NmeaTransport.UDP) {
                            "Ej. 10119"
                        } else {
                            "Ej. 10110"
                        },
                    )
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            if (nmeaOutputTransport == NmeaTransport.UDP) {
                Text(
                    "En Signal K: Data Connections → NMEA 0183 → UDP :" +
                        nmeaOutputPort.ifBlank { "10119" } +
                        " con evento de entrada «autopilot»; la conexión serie al piloto " +
                        "con evento de salida «autopilot».",
                    style = MaterialTheme.typography.labelSmall,
                    color = MarineColors.TextDim,
                )
            }
            Spacer(Modifier.height(8.dp))
            Button(onClick = {
                vm.setNmea(
                    nmeaEnabled,
                    nmeaTransport,
                    nmeaHost,
                    nmeaPort.toIntOrNull() ?: 10110,
                    nmeaSendNavigation,
                    nmeaOutputTransport,
                    nmeaOutputHost,
                    nmeaOutputPort.toIntOrNull() ?: 0,
                )
            }) { Text("Guardar NMEA") }
            Spacer(Modifier.height(10.dp))
            Text(
                "Entrada: ${nmeaStatus.state} · ${nmeaStatus.sentences} frases · " +
                    "${nmeaStatus.ownSentences} propias · ${nmeaStatus.aisMessages} AIS",
                style = MaterialTheme.typography.bodySmall,
                color = MarineColors.TextDim,
            )
            Text(
                "Checksum: ${nmeaStatus.checksumErrors} errores · " +
                    "parseo: ${nmeaStatus.parseErrors}",
                style = MaterialTheme.typography.bodySmall,
                color = MarineColors.TextDim,
            )
            nmeaStatus.lastError?.let {
                Text(
                    "Error de entrada: $it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            nmeaStatus.lastSentence?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MarineColors.TextDim)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "Salida: ${nmeaOutputStatus.state}" +
                    if (nmeaOutputStatus.host.isNotBlank()) {
                        " → ${nmeaOutputStatus.host}:${nmeaOutputStatus.port}" +
                            " · ${nmeaOutputStatus.sentencesOut} enviadas"
                    } else {
                        ""
                    },
                style = MaterialTheme.typography.bodySmall,
                color = if (nmeaOutputStatus.lastError == null) {
                    MarineColors.TextDim
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
            nmeaOutputStatus.lastError?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "La salida necesita posición propia y un waypoint activo. Si los datos llegan " +
                    "por Signal K, la entrada NMEA puede quedar desactivada; con UDP la app " +
                    "manda la guía a Signal K y este la reenvía al piloto por el puerto serie. " +
                    "El piloto debe estar en modo Track/Nav. Auto/Standby, ±1° y las viradas " +
                    "necesitan Signal K/SeaTalk.",
                style = MaterialTheme.typography.labelSmall,
                color = MarineColors.TextDim,
            )
        }

        GlassCard(Modifier.fillMaxWidth()) {
            SectionHeader(
                title = "Alarmas AIS (CPA/TCPA)",
                subtitle = "Avisos de proximidad y rumbo de colisión",
                icon = Icons.Filled.Radar,
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = alarmEnabled, onCheckedChange = { alarmEnabled = it })
                Text("  Activar alarmas", style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = cpaText,
                    onValueChange = { cpaText = it.filter(Char::isDigit) },
                    label = { Text("CPA (m)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = tcpaText,
                    onValueChange = { tcpaText = it.filter(Char::isDigit) },
                    label = { Text("TCPA (min)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = radiusText,
                    onValueChange = { radiusText = it.filter(Char::isDigit) },
                    label = { Text("Radio (m)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = minSpeedText,
                    onValueChange = { minSpeedText = it.filter(Char::isDigit) },
                    label = { Text("Vel. mín (kn)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(
                Modifier.padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Switch(checked = onlyClassA, onCheckedChange = { onlyClassA = it })
                Text("  Solo transpondedores clase A", style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(8.dp))
            Button(onClick = {
                vm.setAlarmSettings(
                    alarmEnabled,
                    (cpaText.toDoubleOrNull() ?: 500.0).coerceAtLeast(50.0),
                    (tcpaText.toDoubleOrNull() ?: 10.0).coerceAtLeast(1.0),
                    (radiusText.toDoubleOrNull() ?: 250.0).coerceAtLeast(50.0),
                    onlyClassA,
                    (minSpeedText.toDoubleOrNull() ?: 0.0).coerceAtLeast(0.0),
                )
            }) { Text("Guardar alarmas") }
            Spacer(Modifier.height(6.dp))
            Text(
                "Alarma si CPA < umbral y TCPA < umbral, o si el contacto entra en el radio. " +
                    "Filtros opcionales por clase AIS y velocidad mínima. Reaviso cada 2 min.",
                style = MaterialTheme.typography.labelSmall,
                color = MarineColors.TextDim,
            )
        }

        GlassCard(Modifier.fillMaxWidth()) {
            SectionHeader(
                title = "Navegación y alarmas de ruta",
                subtitle = "Llegada a waypoint y XTE",
                icon = Icons.Filled.Info,
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = navAlarms, onCheckedChange = { navAlarms = it })
                Text(
                    "  Avisar llegada y fuera de rumbo",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = arrivalText,
                    onValueChange = { arrivalText = it.filter(Char::isDigit) },
                    label = { Text("Radio llegada (m)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = xteText,
                    onValueChange = { xteText = it.filter(Char::isDigit) },
                    label = { Text("XTE máx (m)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(
                Modifier.padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Switch(checked = autoAdvance, onCheckedChange = { autoAdvance = it })
                Text(
                    "  Avanzar al siguiente waypoint al llegar",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Spacer(Modifier.height(8.dp))
            Button(onClick = {
                vm.setNavigationSettings(
                    alarmsEnabled = navAlarms,
                    arrivalRadiusMeters = (arrivalText.toDoubleOrNull() ?: 60.0)
                        .coerceIn(10.0, 2_000.0),
                    xteAlarmMeters = (xteText.toDoubleOrNull() ?: 120.0)
                        .coerceIn(20.0, 5_000.0),
                    autoAdvance = autoAdvance,
                )
            }) { Text("Guardar navegación") }
            Spacer(Modifier.height(6.dp))
            Text(
                "Igual que en OpenCPN: la guía muestra DTW, demora, XTE, VMG y ETA; al entrar " +
                    "en el radio de llegada se avisa (y se avanza de tramo) y si el XTE supera " +
                    "el límite se avisa de que estás fuera de rumbo.",
                style = MaterialTheme.typography.labelSmall,
                color = MarineColors.TextDim,
            )
        }

        GlassCard(Modifier.fillMaxWidth()) {
            SectionHeader(
                title = "Pantalla y carta",
                subtitle = "Luz nocturna y vectores AIS",
                icon = Icons.Filled.Settings,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "Luz nocturna: ${(nightDim * 100).toInt()}% (0 = apagada)",
                style = MaterialTheme.typography.bodySmall,
                color = MarineColors.TextDim,
            )
            Slider(
                value = nightDim,
                onValueChange = { nightDim = it },
                onValueChangeFinished = { vm.setNightDim(nightDim) },
                valueRange = 0f..0.8f,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = aisVectors,
                    onCheckedChange = {
                        aisVectors = it
                        vm.setAisVectors(it)
                    },
                )
                Text(
                    "  Vectores de predicción AIS (6 min)",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        GlassCard(Modifier.fillMaxWidth()) {
            SectionHeader(
                title = "AIS por Internet",
                subtitle = "Contactos fuera del alcance del receptor",
                icon = Icons.Filled.Wifi,
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = settings.internetAis.enabled,
                    onCheckedChange = {
                        vm.setInternetAis(it, internetProvider, internetKey, internetUser)
                    },
                )
                Text("  Activar", style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = internetProvider == InternetAisProvider.AISSTREAM,
                    onClick = { internetProvider = InternetAisProvider.AISSTREAM },
                    label = { Text("aisstream.io") },
                )
                FilterChip(
                    selected = internetProvider == InternetAisProvider.AISHUB,
                    onClick = { internetProvider = InternetAisProvider.AISHUB },
                    label = { Text("AISHub") },
                )
            }
            Spacer(Modifier.height(8.dp))
            if (internetProvider == InternetAisProvider.AISSTREAM) {
                OutlinedTextField(
                    value = internetKey,
                    onValueChange = { internetKey = it },
                    label = { Text("API key de aisstream.io") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                OutlinedTextField(
                    value = internetUser,
                    onValueChange = { internetUser = it },
                    label = { Text("Usuario de AISHub") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(8.dp))
            Button(onClick = {
                vm.setInternetAis(
                    settings.internetAis.enabled, internetProvider, internetKey, internetUser,
                )
            }) { Text("Guardar") }
            Spacer(Modifier.height(10.dp))
            Text(
                "Estado: $internetStatus",
                style = MaterialTheme.typography.bodySmall,
                color = MarineColors.TextDim,
            )
            Text(
                "Mensajes recibidos: $internetMessages · último: " +
                    if (internetLast == 0L) "—" else
                        "${(System.currentTimeMillis() - internetLast) / 1000}s",
                style = MaterialTheme.typography.bodySmall,
                color = MarineColors.TextDim,
            )
            internetError?.let {
                Text(
                    "Error: $it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "aisstream.io ha tenido caídas en las que conecta pero no entrega mensajes " +
                    "(issue #15). Si ves 0 mensajes, prueba AISHub (usuario gratuito, exige " +
                    "aportar un receptor) o usa el AIS que llegue por SignalK.",
                style = MaterialTheme.typography.labelSmall,
                color = MarineColors.TextDim,
            )
        }

        GlassCard(Modifier.fillMaxWidth()) {
            SectionHeader(
                title = "Alarma de fondeo",
                subtitle = "Vigila que el barco no garree",
                icon = Icons.Filled.Anchor,
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = settings.anchor.enabled,
                    onCheckedChange = {
                        vm.setAnchorAlarm(
                            it,
                            (anchorRadiusText.toDoubleOrNull() ?: 30.0).coerceAtLeast(5.0),
                        )
                    },
                )
                Text("  Activar", style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = anchorRadiusText,
                    onValueChange = { anchorRadiusText = it.filter(Char::isDigit) },
                    label = { Text("Radio (m)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Button(onClick = { vm.setAnchorHere() }) { Text("Fijar aquí") }
                TextButton(onClick = { vm.clearAnchor() }) { Text("Borrar") }
            }
            Spacer(Modifier.height(6.dp))
            if (anchor.set) {
                Text(
                    "Distancia ${Formatters.meters(anchor.distanceMeters)} · " +
                        if (anchor.alarm) "FUERA del radio" else "dentro del radio",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (anchor.alarm) MarineColors.Alert else MarineColors.Mint,
                )
            } else {
                Text(
                    "Ancla no fijada",
                    style = MaterialTheme.typography.bodySmall,
                    color = MarineColors.TextDim,
                )
            }
        }

        GlassCard(Modifier.fillMaxWidth()) {
            SectionHeader(
                title = "Modo demo",
                subtitle = "Datos simulados sin servidor",
                icon = Icons.Filled.Info,
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = settings.demoMode,
                    onCheckedChange = { vm.setDemoMode(it) },
                )
                Text("  Datos simulados (sin servidor)", style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "Genera a 1 Hz posición en movimiento, viento real/aparente, profundidad, " +
                    "tres objetivos AIS y waypoints/rutas de prueba. Útil para ver la " +
                    "pantalla Viento, las alarmas y las cartas sin SignalK.",
                style = MaterialTheme.typography.labelSmall,
                color = MarineColors.TextDim,
            )
        }

        GlassCard(Modifier.fillMaxWidth()) {
            SectionHeader(
                title = "Cómo funciona",
                subtitle = "Arquitectura de datos y cartas",
                icon = Icons.Filled.Info,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "· Datos del barco: SignalK REST + dos streams WebSocket (self y all).\n" +
                    "· El contexto real del barco (urn:mrn:imo:mmsi:…) se resuelve solo, y " +
                    "los deltas se reintentan con espera exponencial y watchdog.\n" +
                    "· Waypoints/rutas: API resources de SignalK, incluidas las rutas que " +
                    "referencian waypoints por href.\n" +
                    "· Cartas: MBTiles/XYZ/GPKG, S-57, BSB/KAP y las que publique SignalK.",
                style = MaterialTheme.typography.bodySmall,
                color = MarineColors.TextDim,
            )
        }

        GlassCard(Modifier.fillMaxWidth()) {
            SectionHeader(
                title = "Acerca de",
                subtitle = "Versión, licencia y avisos",
                icon = Icons.Filled.Info,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Derrotero 0.9.4",
                style = MaterialTheme.typography.titleSmall,
                color = MarineColors.Text,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Compañero de navegación para Android. Se conecta a Signal K y a redes " +
                    "NMEA 0183/2000. Kotlin + Jetpack Compose + MapLibre. No está afiliado, " +
                    "patrocinado ni respaldado por ningún fabricante de electrónica marina.",
                style = MaterialTheme.typography.bodySmall,
                color = MarineColors.TextDim,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Copyright 2026 Derrotero contributors. Distribuido bajo la licencia " +
                    "Apache-2.0, sin garantías.",
                style = MaterialTheme.typography.bodySmall,
                color = MarineColors.TextDim,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Aviso de seguridad: esta app es una ayuda a la navegación, no un " +
                    "instrumento homologado. No lo uses como medio primario. Lleva cartas " +
                    "oficiales actualizadas y mantén el control manual del barco, " +
                    "especialmente al usar el piloto automático.",
                style = MaterialTheme.typography.bodySmall,
                color = MarineColors.Alert,
            )
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = { showLicenses = true }) {
                Text("Licencias y avisos de terceros")
            }
        }
    }

    if (showLicenses) {
        AlertDialog(
            onDismissRequest = { showLicenses = false },
            title = { Text("Licencias y avisos") },
            text = {
                Column(
                    Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        "Derrotero se distribuye bajo la licencia Apache-2.0. " +
                            "Copyright 2026 Derrotero contributors.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "Componentes de terceros:\n" +
                            "· AndroidX, Jetpack Compose, Kotlin y kotlinx — Apache-2.0\n" +
                            "· OkHttp — Apache-2.0\n" +
                            "· MapLibre Native — BSD-2-Clause\n" +
                            "· NanoHTTPD — BSD-3-Clause\n" +
                            "· JUnit — EPL-1.0 (solo pruebas, no se distribuye)",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "Datos de cartas y teselas:\n" +
                            "· © OpenStreetMap contributors — ODbL\n" +
                            "· © Open Waters: Seamap — CC BY 4.0\n" +
                            "· EMODnet — datos marinos abiertos\n" +
                            "· OpenSeaMap — CC BY-SA\n" +
                            "La atribución se muestra en el mapa. Cada proveedor tiene sus " +
                            "propios términos de uso; respétalos al descargar cartas.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "Los textos completos de las licencias están en LICENSE y " +
                            "THIRD_PARTY_NOTICES.md, en el repositorio del proyecto.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MarineColors.TextDim,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showLicenses = false }) { Text("Cerrar") }
            },
        )
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
