package com.derrotero.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.derrotero.ui.theme.MarineColors

/**
 * Full-screen marine background: deep navy with a soft harbour-cyan glow. Keeping it in one
 * place gives every screen the same visual language.
 */
@Composable
fun MarineBackdrop(content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .drawBehind {
                drawRect(
                    Brush.verticalGradient(
                        listOf(
                            MarineColors.Abyss,
                            MarineColors.Deep,
                            Color(0xFF0A2133),
                        ),
                    ),
                )
                val glowCenter = Offset(size.width * 0.86f, size.height * 0.06f)
                val glowRadius = size.width * 0.95f
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            MarineColors.Harbor.copy(alpha = 0.11f),
                            Color.Transparent,
                        ),
                        center = glowCenter,
                        radius = glowRadius,
                    ),
                    radius = glowRadius,
                    center = glowCenter,
                )
                val sandCenter = Offset(size.width * 0.05f, size.height * 0.92f)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            MarineColors.Sand.copy(alpha = 0.05f),
                            Color.Transparent,
                        ),
                        center = sandCenter,
                        radius = size.width * 0.8f,
                    ),
                    radius = size.width * 0.8f,
                    center = sandCenter,
                )
            },
        content = content,
    )
}

/** Frosted instrument card with an optional accent line. */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    accent: Color? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        color = MarineColors.Surface.copy(alpha = 0.94f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.07f)),
        tonalElevation = 0.dp,
    ) {
        CardContent(accent, content)
    }
}

/** Clickable variant used by the chart source picker. */
@Composable
fun GlassCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Color? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        color = MarineColors.Surface.copy(alpha = 0.94f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.07f)),
        tonalElevation = 0.dp,
    ) {
        CardContent(accent, content)
    }
}

@Composable
private fun CardContent(
    accent: Color?,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.padding(15.dp)) {
        if (accent != null) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(
                        Brush.horizontalGradient(
                            listOf(accent, accent.copy(alpha = 0f)),
                        ),
                    ),
            )
            Spacer(Modifier.height(11.dp))
        }
        content()
    }
}

/** Title block used at the top of every screen. */
@Composable
fun ScreenHeader(
    title: String,
    subtitle: String,
    statusLabel: String? = null,
    statusActive: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "DERROTERO",
                style = MaterialTheme.typography.labelMedium,
                color = MarineColors.Harbor.copy(alpha = 0.85f),
            )
            Spacer(Modifier.height(2.dp))
            Text(
                title,
                style = MaterialTheme.typography.headlineMedium,
                color = MarineColors.Text,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MarineColors.TextDim,
            )
        }
        if (statusLabel != null) {
            StatusPill(label = statusLabel, active = statusActive)
        }
    }
}

/** Connection / state pill with a coloured dot. */
@Composable
fun StatusPill(
    label: String,
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    val tint = if (active) MarineColors.Mint else MarineColors.Amber
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = tint.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, tint.copy(alpha = 0.35f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(tint),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = tint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Section heading with an optional leading icon and trailing action. */
@Composable
fun SectionHeader(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = Icons.Filled.Bolt,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Box(
                Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(MarineColors.Harbor.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MarineColors.Harbor,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.width(10.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = MarineColors.Text,
            )
            subtitle?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MarineColors.TextDim,
                )
            }
        }
        trailing?.invoke()
    }
}

/**
 * An instrument readout. [highlighted] enlarges the value for the hero row.
 */
@Composable
fun InstrumentTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    accent: Color = MarineColors.Harbor,
    caption: String? = null,
    highlighted: Boolean = false,
) {
    GlassCard(modifier = modifier, accent = if (highlighted) accent else null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(accent),
            )
            Spacer(Modifier.width(7.dp))
            Text(
                label.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = MarineColors.TextDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(if (highlighted) 8.dp else 6.dp))
        Text(
            value,
            style = if (highlighted) {
                MaterialTheme.typography.headlineMedium
            } else {
                MaterialTheme.typography.headlineSmall
            },
            color = accent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        caption?.let {
            Spacer(Modifier.height(2.dp))
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MarineColors.TextDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Compact label/value block used inside the map HUD. */
@Composable
fun MiniReadout(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MarineColors.Text,
) {
    Column(modifier) {
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MarineColors.TextDim,
            maxLines = 1,
        )
        Text(
            value,
            style = MaterialTheme.typography.titleSmall,
            color = valueColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
