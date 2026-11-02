package com.derrotero.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.derrotero.core.LocalContainer
import com.derrotero.service.ServiceController
import com.derrotero.ui.charts.ChartsScreen
import com.derrotero.ui.components.MarineBackdrop
import com.derrotero.ui.instruments.InstrumentsScreen
import com.derrotero.ui.map.MapScreen
import com.derrotero.ui.settings.SettingsScreen
import com.derrotero.ui.theme.MarineColors
import com.derrotero.ui.waypoints.WaypointsScreen
import com.derrotero.ui.wind.WindScreen

private enum class Dest(val route: String, val label: String, val icon: ImageVector) {
    Map("map", "Carta", Icons.Filled.Map),
    Instruments("instruments", "Datos", Icons.Filled.Speed),
    Wind("wind", "Viento", Icons.Filled.Air),
    Waypoints("waypoints", "Rutas", Icons.Filled.Place),
    Charts("charts", "Cartas", Icons.Filled.Layers),
    Settings("settings", "Ajustes", Icons.Filled.Settings),
}

@Composable
fun NavApp() {
    val container = LocalContainer.current
    val vm: NavViewModel = viewModel(factory = NavViewModel.Factory(container))

    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val message by vm.message.collectAsStateWithLifecycle()
    val appSettings by vm.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Honour the "keep alive" preference from the moment the UI is up.
    LaunchedEffect(appSettings.keepAlive) {
        if (appSettings.keepAlive) ServiceController.start(context)
    }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            vm.clearMessage()
        }
    }
    LaunchedEffect(Unit) { vm.refreshResources() }

    if (!appSettings.disclaimerAccepted) {
        AlertDialog(
            onDismissRequest = { /* must be explicitly accepted */ },
            title = { Text("Antes de navegar") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Derrotero es una ayuda a la navegación, no un instrumento " +
                            "homologado. No lo uses como medio primario: lleva cartas oficiales " +
                            "actualizadas y equipo homologado.",
                    )
                    Text(
                        "Los datos de cartas, AIS y sensores pueden ser incompletos o " +
                            "incorrectos. Se ofrece sin garantía.",
                    )
                    Text(
                        "No está afiliado, patrocinado ni respaldado por Navico, B&G, Simrad, " +
                            "Lowrance, Raymarine, Garmin/Navionics ni OpenCPN.",
                    )
                    Text(
                        "El piloto automático es un sistema crítico. Prueba los comandos con " +
                            "el barco amarrado y mantén el control manual a mano.",
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.acceptDisclaimer() }) { Text("Entendido") }
            },
        )
    }

    // Night mode (OpenCPN-style dimmer). The overlay has no pointer input, so the map and the
    // controls remain fully usable underneath.
    val dim = appSettings.display.nightDim

    MarineBackdrop {
        Scaffold(
            containerColor = Color.Transparent,
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                val backStack by nav.currentBackStackEntryAsState()
                val current = backStack?.destination?.route
                MarineBottomBar(
                    current = current,
                    onSelect = { dest ->
                        nav.navigate(dest.route) {
                            launchSingleTop = true
                            popUpTo(Dest.Map.route) { saveState = true }
                        }
                    },
                )
            },
        ) { padding ->
            NavHost(
                navController = nav,
                startDestination = Dest.Map.route,
                modifier = Modifier.padding(padding),
                enterTransition = { fadeIn(tween(220)) },
                exitTransition = { fadeOut(tween(140)) },
                popEnterTransition = { fadeIn(tween(180)) },
                popExitTransition = { fadeOut(tween(120)) },
            ) {
                composable(Dest.Map.route) { MapScreen(vm) }
                composable(Dest.Instruments.route) { InstrumentsScreen(vm) }
                composable(Dest.Wind.route) { WindScreen(vm) }
                composable(Dest.Waypoints.route) { WaypointsScreen(vm) }
                composable(Dest.Charts.route) { ChartsScreen(vm) }
                composable(Dest.Settings.route) { SettingsScreen(vm) }
            }
        }

        if (dim > 0.01f) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = dim.coerceIn(0f, 0.8f))),
            )
        }
    }
}

/**
 * Floating bottom dock. The default NavigationBar looked generic; a rounded, translucent dock
 * with pills makes the app feel like a chart plotter.
 */
@Composable
private fun MarineBottomBar(
    current: String?,
    onSelect: (Dest) -> Unit,
) {
    Surface(
        color = MarineColors.Surface.copy(alpha = 0.98f),
        shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            Color.White.copy(alpha = 0.07f),
        ),
        shadowElevation = 18.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 6.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Dest.entries.forEach { dest ->
                val selected = current == dest.route
                val tint by animateColorAsState(
                    targetValue = if (selected) MarineColors.Harbor else MarineColors.TextDim,
                    animationSpec = tween(180),
                    label = "dockTint",
                )
                val pill by animateColorAsState(
                    targetValue = if (selected) {
                        MarineColors.Harbor.copy(alpha = 0.14f)
                    } else {
                        Color.Transparent
                    },
                    animationSpec = tween(180),
                    label = "dockPill",
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(16.dp))
                        .clickable { onSelect(dest) }
                        .padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        modifier = Modifier
                            .size(width = 36.dp, height = 30.dp)
                            .clip(RoundedCornerShape(11.dp))
                            .background(pill),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            dest.icon,
                            contentDescription = dest.label,
                            tint = tint,
                            modifier = Modifier.size(19.dp),
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        dest.label,
                        color = tint,
                        fontSize = 9.sp,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
