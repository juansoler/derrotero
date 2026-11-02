package com.derrotero.ui.wind

import android.graphics.Paint
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.derrotero.data.polar.PolarTable
import com.derrotero.data.signalk.Paths
import com.derrotero.data.signalk.angle
import com.derrotero.data.signalk.number
import com.derrotero.ui.NavViewModel
import com.derrotero.ui.components.GlassCard
import com.derrotero.ui.components.MiniReadout
import com.derrotero.ui.components.ScreenHeader
import com.derrotero.ui.theme.MarineColors
import com.derrotero.util.Formatters
import com.derrotero.util.SailAdvisor
import com.derrotero.util.SailingAdvice
import com.derrotero.util.WindMath
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

private enum class WindMode { TRUE, APPARENT }

private data class WindReadings(
    val stwMps: Double?,
    val sogMps: Double?,
    val headingRad: Double?,
    val cogRad: Double?,
    val awsMps: Double?,
    val awaRad: Double?,
    val twsMps: Double?,
    val twaRad: Double?,
    val twdRad: Double?,
    val vmgMps: Double?,
    val vmgWpMps: Double?,
    val dtwM: Double?,
    val twaAbsDeg: Double?,
)

/**
 * Wind page: a boat-referenced wind dial with laylines and the no-go zone,
 * the usual wind/boat metrics and a sail-plan / tack advisor.
 *
 * The layout adapts to the screen: tablets get a two-column layout that fits without
 * scrolling, phones keep a compact scrollable column. The dial is always bounded by the
 * available width/height instead of forcing a square that is taller than the viewport.
 */
@Composable
fun WindScreen(vm: NavViewModel) {
    val state by vm.vesselState.collectAsStateWithLifecycle()
    val polarTable by vm.polarTable.collectAsStateWithLifecycle()
    val guidance by vm.navGuidance.collectAsStateWithLifecycle()

    val csvPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { vm.importPolar(it) } }

    var mode by remember { mutableStateOf(WindMode.TRUE) }
    var tackingDeg by remember { mutableStateOf(90) }

    val stw = state.number(Paths.STW)
    val sog = state.number(Paths.SOG)
    val heading = state.angle(Paths.HEADING_TRUE)
        ?: state.angle(Paths.HEADING_MAG)
        ?: state.angle(Paths.COG)
    val aws = state.number(Paths.WIND_SPEED_APPARENT)
    val awa = state.angle(Paths.WIND_ANGLE_APPARENT)

    val derived = if (aws != null && awa != null && stw != null) {
        WindMath.trueFromApparent(aws, awa, stw)
    } else {
        null
    }
    val tws = state.number(Paths.WIND_SPEED_TRUE) ?: derived?.trueSpeed
    val twa = state.angle(Paths.WIND_ANGLE_TRUE) ?: derived?.trueAngle
    val twd = state.angle(Paths.WIND_DIRECTION_TRUE)
        ?: if (heading != null && twa != null) WindMath.trueWindDirection(heading, twa) else null

    val vmg = if (stw != null && twa != null) WindMath.vmg(stw, twa) else null
    val twaAbsDeg = WindMath.absDeg(twa)
    val twsKn = tws?.times(1.943844)

    val readings = WindReadings(
        stwMps = stw,
        sogMps = sog,
        headingRad = heading,
        cogRad = state.angle(Paths.COG),
        awsMps = aws,
        awaRad = awa,
        twsMps = tws,
        twaRad = twa,
        twdRad = twd,
        vmgMps = vmg,
        vmgWpMps = state.number(Paths.VMG),
        dtwM = state.number(Paths.DTW),
        twaAbsDeg = twaAbsDeg,
    )

    val sailing = remember(
        guidance.bearingDeg,
        twd,
        heading,
        twaAbsDeg,
        twsKn,
        polarTable,
        tackingDeg,
    ) {
        SailAdvisor.recommend(
            bearingToTargetDeg = guidance.bearingDeg,
            trueWindDirectionDeg = twd?.let { Math.toDegrees(WindMath.wrap2Pi(it)) },
            headingDeg = heading?.let { Math.toDegrees(WindMath.wrap2Pi(it)) },
            currentTwaDeg = twaAbsDeg,
            twsKn = twsKn,
            polar = polarTable,
            // The layline chips are an explicit override of the polar's upwind angle.
            upwindOverrideDeg = tackingDeg / 2.0,
        )
    }
    val targetRelativeRad = if (guidance.active) {
        val bearing = guidance.bearingDeg
        if (bearing != null && heading != null) {
            WindMath.wrapPi(Math.toRadians(bearing) - heading)
        } else {
            null
        }
    } else {
        null
    }

    val dialAngle = if (mode == WindMode.TRUE) twa else awa
    val dialSpeed = if (mode == WindMode.TRUE) tws else aws
    val pointOfSail = twaAbsDeg?.let { WindMath.pointOfSail(it) }
    val subtitle = pointOfSail?.let { "Punto de navegación: $it" }
        ?: "Sin datos de viento todavía"
    val statusLabel = if (tws != null || aws != null) "Viento" else null
    val statusActive = tws != null || aws != null

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 640.dp && maxHeight >= 420.dp
        if (wide) {
            Row(
                Modifier
                    .fillMaxSize()
                    .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Column(
                    Modifier.weight(1f).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    ScreenHeader(
                        title = "Viento",
                        subtitle = subtitle,
                        statusLabel = statusLabel,
                        statusActive = statusActive,
                    )
                    WindModeRow(
                        mode = mode,
                        onModeChange = { mode = it },
                        pointOfSail = pointOfSail,
                    )
                    DialCard(
                        readings = readings,
                        mode = mode,
                        laylineDeg = tackingDeg.toDouble(),
                        targetAngleRad = targetRelativeRad,
                        dialSize = null,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    )
                    LaylineBar(
                        tackingDeg = tackingDeg,
                        onTackingDegChange = { tackingDeg = it },
                        onImportPolar = { csvPicker.launch(arrayOf("*/*")) },
                        onResetPolar = { vm.resetPolar() },
                    )
                }
                Column(
                    Modifier.weight(1f).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    SailAdvisorCard(
                        sailing = sailing,
                        destination = guidance.name,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    )
                    MetricGrid(readings = readings, compact = true)
                    PolarSummary(
                        table = polarTable,
                        twsKn = twsKn,
                        twaDeg = twaAbsDeg,
                        stwKn = stw?.times(1.943844),
                    )
                    Text(
                        "Dial relativo a proa · verde: rumbos de ceñida · amarillo: destino",
                        style = MaterialTheme.typography.labelSmall,
                        color = MarineColors.TextDim,
                        maxLines = 2,
                    )
                }
            }
        } else {
            val dialSize = (maxWidth - 62.dp)
                .coerceAtMost(maxHeight * 0.40f)
                .coerceAtLeast(160.dp)
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 26.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ScreenHeader(
                    title = "Viento",
                    subtitle = subtitle,
                    statusLabel = statusLabel,
                    statusActive = statusActive,
                )
                WindModeRow(
                    mode = mode,
                    onModeChange = { mode = it },
                    pointOfSail = pointOfSail,
                )
                DialCard(
                    readings = readings,
                    mode = mode,
                    laylineDeg = tackingDeg.toDouble(),
                    targetAngleRad = targetRelativeRad,
                    dialSize = dialSize,
                    modifier = Modifier.fillMaxWidth(),
                )
                SailAdvisorCard(
                    sailing = sailing,
                    destination = guidance.name,
                    modifier = Modifier.fillMaxWidth(),
                )
                MetricGrid(readings = readings, compact = false)
                PolarSummary(
                    table = polarTable,
                    twsKn = twsKn,
                    twaDeg = twaAbsDeg,
                    stwKn = stw?.times(1.943844),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { csvPicker.launch(arrayOf("*/*")) }) {
                        Text("Importar polar CSV")
                    }
                    TextButton(onClick = { vm.resetPolar() }) { Text("Restablecer polar") }
                }
                Text(
                    "Laylines",
                    style = MaterialTheme.typography.titleMedium,
                    color = MarineColors.Text,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(80, 90, 100, 110).forEach { deg ->
                        FilterChip(
                            selected = tackingDeg == deg,
                            onClick = { tackingDeg = deg },
                            label = { Text("$deg°") },
                        )
                    }
                }
                Text(
                    "Dial relativo a proa: líneas verdes = rumbos de ceñida, cuña = zona muerta, " +
                        "punto amarillo = destino. Si el servidor no publica viento real se deriva " +
                        "de aparente + corredera.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MarineColors.TextDim,
                )
            }
        }
    }
}

@Composable
private fun WindModeRow(
    mode: WindMode,
    onModeChange: (WindMode) -> Unit,
    pointOfSail: String?,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = mode == WindMode.TRUE,
            onClick = { onModeChange(WindMode.TRUE) },
            label = { Text("Viento real") },
        )
        FilterChip(
            selected = mode == WindMode.APPARENT,
            onClick = { onModeChange(WindMode.APPARENT) },
            label = { Text("Aparente") },
        )
        Spacer(Modifier.weight(1f))
        Text(
            pointOfSail ?: "Sin viento",
            style = MaterialTheme.typography.labelMedium,
            color = MarineColors.Harbor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun DialCard(
    readings: WindReadings,
    mode: WindMode,
    laylineDeg: Double,
    targetAngleRad: Double?,
    dialSize: Dp?,
    modifier: Modifier = Modifier,
) {
    val dialAngle = if (mode == WindMode.TRUE) readings.twaRad else readings.awaRad
    val dialSpeed = if (mode == WindMode.TRUE) readings.twsMps else readings.awsMps
    GlassCard(modifier, accent = MarineColors.Harbor) {
        if (dialSize != null) {
            Box(
                Modifier.fillMaxWidth().height(dialSize),
                contentAlignment = Alignment.Center,
            ) {
                WindDial(
                    apparentAngleRad = readings.awaRad,
                    trueAngleRad = readings.twaRad,
                    laylineDeg = laylineDeg,
                    mode = mode,
                    targetAngleRad = targetAngleRad,
                    modifier = Modifier.size(dialSize),
                )
            }
        } else {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                WindDial(
                    apparentAngleRad = readings.awaRad,
                    trueAngleRad = readings.twaRad,
                    laylineDeg = laylineDeg,
                    mode = mode,
                    targetAngleRad = targetAngleRad,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            BigValue(
                if (mode == WindMode.TRUE) "TWS" else "AWS",
                Formatters.knots(dialSpeed),
            )
            BigValue(
                if (mode == WindMode.TRUE) "TWA" else "AWA",
                Formatters.windAngle(dialAngle),
            )
        }
    }
}

@Composable
private fun BigValue(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MarineColors.TextDim)
        Text(value, style = MaterialTheme.typography.headlineSmall, color = MarineColors.Harbor)
    }
}

@Composable
private fun MetricGrid(readings: WindReadings, compact: Boolean) {
    val rows = if (compact) {
        listOf(
            listOf(
                "TWS" to Formatters.knots(readings.twsMps),
                "TWA" to Formatters.windAngle(readings.twaRad),
                "TWD" to Formatters.degrees(readings.twdRad),
                "AWS" to Formatters.knots(readings.awsMps),
            ),
            listOf(
                "AWA" to Formatters.windAngle(readings.awaRad),
                "STW" to Formatters.knots(readings.stwMps),
                "SOG" to Formatters.knots(readings.sogMps),
                "VMG" to Formatters.knots(readings.vmgMps),
            ),
        )
    } else {
        listOf(
            listOf(
                "TWS" to Formatters.knots(readings.twsMps),
                "TWA" to Formatters.windAngle(readings.twaRad),
                "TWD" to Formatters.degrees(readings.twdRad),
            ),
            listOf(
                "AWS" to Formatters.knots(readings.awsMps),
                "AWA" to Formatters.windAngle(readings.awaRad),
                "STW" to Formatters.knots(readings.stwMps),
            ),
            listOf(
                "SOG" to Formatters.knots(readings.sogMps),
                "Rumbo" to Formatters.degrees(readings.headingRad),
                "COG" to Formatters.degrees(readings.cogRad),
            ),
            listOf(
                "VMG viento" to Formatters.knots(readings.vmgMps),
                "VMG WP" to Formatters.knots(readings.vmgWpMps),
                "Dist WP" to Formatters.distance(readings.dtwM),
            ),
        )
    }
    GlassCard(Modifier.fillMaxWidth()) {
        rows.forEachIndexed { index, row ->
            if (index > 0) Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { (label, value) ->
                    MiniReadout(label, value, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun PolarSummary(
    table: PolarTable,
    twsKn: Double?,
    twaDeg: Double?,
    stwKn: Double?,
) {
    GlassCard(Modifier.fillMaxWidth()) {
        if (twsKn == null) {
            Text(
                "Polares: sin TWS disponible",
                style = MaterialTheme.typography.bodySmall,
                color = MarineColors.TextDim,
            )
        } else {
            val upwind = table.bestUpwind(twsKn)
            val downwind = table.bestDownwind(twsKn)
            Text(
                "Polares · ceñida %.0f° %.1f kn · popa %.0f° %.1f kn".format(
                    upwind.twaDeg,
                    upwind.boatSpeedKn,
                    downwind.twaDeg,
                    downwind.boatSpeedKn,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MarineColors.TextDim,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (twaDeg != null && stwKn != null) {
                Text(
                    "Ahora %.1f kn · objetivo %.1f kn (%.0f %%)".format(
                        stwKn,
                        table.speedKn(twsKn, twaDeg),
                        table.performancePercent(stwKn, twsKn, twaDeg) ?: 0.0,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MarineColors.Harbor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun LaylineBar(
    tackingDeg: Int,
    onTackingDegChange: (Int) -> Unit,
    onImportPolar: () -> Unit,
    onResetPolar: () -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                "Laylines",
                style = MaterialTheme.typography.labelSmall,
                color = MarineColors.TextDim,
            )
            listOf(80, 90, 100, 110).forEach { deg ->
                FilterChip(
                    selected = tackingDeg == deg,
                    onClick = { onTackingDegChange(deg) },
                    label = { Text("$deg°") },
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = onImportPolar) { Text("Importar polar CSV") }
            TextButton(onClick = onResetPolar) { Text("Restablecer polar") }
        }
    }
}

@Composable
private fun SailAdvisorCard(
    sailing: SailingAdvice,
    destination: String?,
    modifier: Modifier = Modifier,
) {
    val plan = sailing.plan
    val tack = sailing.tack
    GlassCard(
        modifier,
        accent = when {
            tack?.tackNow == true -> MarineColors.Alert
            plan != null -> MarineColors.Harbor
            else -> null
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "PLAN DE VELAS",
                style = MaterialTheme.typography.labelMedium,
                color = MarineColors.TextDim,
            )
            Spacer(Modifier.weight(1f))
            destination?.let {
                Text(
                    "→ $it",
                    style = MaterialTheme.typography.labelSmall,
                    color = MarineColors.Harbor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        if (plan == null) {
            Text(
                "Falta el viento real (TWS/TWA) para recomendar la configuración de velas.",
                style = MaterialTheme.typography.bodySmall,
                color = MarineColors.TextDim,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            Text(
                "${plan.pointOfSail} · ${plan.sails}",
                style = MaterialTheme.typography.titleSmall,
                color = MarineColors.Text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                plan.trim,
                style = MaterialTheme.typography.bodySmall,
                color = MarineColors.TextDim,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (tack != null) {
            Spacer(Modifier.height(6.dp))
            if (tack.active) {
                Text(
                    "Rumbo ${Formatters.degreesDirect(tack.suggestedHeadingDeg)} · " +
                        (tack.tackName ?: "ceñida"),
                    style = MaterialTheme.typography.titleSmall,
                    color = if (tack.tackNow) MarineColors.Alert else MarineColors.Harbor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    tack.message ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (tack.tackNow) MarineColors.Alert else MarineColors.TextDim,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Text(
                    "Destino a TWA ${tack.targetTwaDeg?.roundToInt() ?: "?"}° · rumbo directo, " +
                        "sin viradas necesarias",
                    style = MaterialTheme.typography.bodySmall,
                    color = MarineColors.Mint,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        } else if (destination == null) {
            Text(
                "Marca un destino en la carta para recibir el consejo de bordo.",
                style = MaterialTheme.typography.labelSmall,
                color = MarineColors.TextDim,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun WindDial(
    apparentAngleRad: Double?,
    trueAngleRad: Double?,
    laylineDeg: Double,
    mode: WindMode,
    targetAngleRad: Double?,
    modifier: Modifier = Modifier,
) {
    val labelPaint = remember {
        Paint().apply {
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }
    }

    Canvas(modifier) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val radius = min(size.width, size.height) / 2f * 0.94f
        val ring = Color(0xFF2A4A66)

        drawCircle(Color(0xFF0E1F31), radius, center)
        drawCircle(ring, radius, center, style = Stroke(2.dp.toPx()))

        // No-go wedge between the laylines around the true wind angle.
        trueAngleRad?.let { ta ->
            val half = Math.toRadians(laylineDeg / 2.0)
            drawArc(
                color = Color(0x3322AA55),
                startAngle = Math.toDegrees(ta - half).toFloat() - 90f,
                sweepAngle = Math.toDegrees(2 * half).toFloat(),
                useCenter = true,
                topLeft = Offset(center.x - radius, center.y - radius),
                size = Size(radius * 2, radius * 2),
            )
        }

        // Ticks and labels.
        labelPaint.color = 0xFFB6C7D8.toInt()
        labelPaint.textSize = 11.dp.toPx()
        for (deg in 0..180 step 10) {
            val sides = if (deg == 0) listOf(1) else listOf(-1, 1)
            for (side in sides) {
                val a = Math.toRadians(deg.toDouble()) * side
                val dir = Offset(sin(a).toFloat(), -cos(a).toFloat())
                val major = deg % 30 == 0
                drawLine(
                    color = ring,
                    start = center + dir * radius,
                    end = center + dir * (radius * if (major) 0.80f else 0.89f),
                    strokeWidth = if (major) 3f else 1.5f,
                )
                if (major && deg != 180) {
                    val p = center + dir * (radius * 0.70f)
                    drawContext.canvas.nativeCanvas.drawText(
                        deg.toString(),
                        p.x,
                        p.y + labelPaint.textSize / 3f,
                        labelPaint,
                    )
                }
            }
        }

        // Laylines (close-hauled headings).
        trueAngleRad?.let { ta ->
            val half = Math.toRadians(laylineDeg / 2.0)
            for (sign in listOf(-1, 1)) {
                val a = ta + sign * half
                val dir = Offset(sin(a).toFloat(), -cos(a).toFloat())
                drawLine(
                    color = Color(0xFF7CE38B),
                    start = center,
                    end = center + dir * (radius * 0.95f),
                    strokeWidth = 2.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f)),
                )
            }
        }

        // Destination bearing relative to the bow.
        targetAngleRad?.let { target ->
            val dir = Offset(sin(target).toFloat(), -cos(target).toFloat())
            val marker = center + dir * (radius * 0.82f)
            drawCircle(Color(0xFFFFE082), radius = 5.5.dp.toPx(), center = marker)
            drawCircle(
                Color(0xFF0E1F31),
                radius = 5.5.dp.toPx(),
                center = marker,
                style = Stroke(1.8.dp.toPx()),
            )
        }

        // Wind arrow(s).
        if (mode == WindMode.APPARENT) {
            apparentAngleRad?.let { arrow(it, Color(0xFF37C6F0), center, radius) }
        } else {
            trueAngleRad?.let { arrow(it, Color(0xFFFFC65C), center, radius) }
        }

        // Boat.
        val boat = Path().apply {
            moveTo(center.x, center.y - 20.dp.toPx())
            lineTo(center.x + 11.dp.toPx(), center.y + 15.dp.toPx())
            lineTo(center.x, center.y + 9.dp.toPx())
            lineTo(center.x - 11.dp.toPx(), center.y + 15.dp.toPx())
            close()
        }
        drawPath(boat, Color(0xFFE8D9A0))
    }
}

private fun DrawScope.arrow(angleRad: Double, color: Color, center: Offset, radius: Float) {
    val dir = Offset(sin(angleRad).toFloat(), -cos(angleRad).toFloat())
    val tip = center + dir * (radius * 0.86f)
    drawLine(
        color = color,
        start = center,
        end = tip,
        strokeWidth = 4.dp.toPx(),
        cap = StrokeCap.Round,
    )
    val perp = Offset(-dir.y, dir.x)
    val back = tip - dir * 18.dp.toPx()
    val head = Path().apply {
        moveTo(tip.x, tip.y)
        lineTo(back.x + perp.x * 9.dp.toPx(), back.y + perp.y * 9.dp.toPx())
        lineTo(back.x - perp.x * 9.dp.toPx(), back.y - perp.y * 9.dp.toPx())
        close()
    }
    drawPath(head, color)
}
