package com.derrotero

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import com.derrotero.core.AppContainer
import com.derrotero.core.LocalContainer
import com.derrotero.ui.NavApp
import com.derrotero.ui.theme.DerroteroTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container: AppContainer = (application as DerroteroApp).container
        setContent {
            CompositionLocalProvider(LocalContainer provides container) {
                DerroteroTheme {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        NavApp()
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // If the user stopped everything from the notification, opening the app reconnects.
        (application as DerroteroApp).container.startLive()
    }

    override fun onStop() {
        super.onStop()
        val container = (application as DerroteroApp).container
        // Without the foreground service there is no reason to keep the feeds (and the radio)
        // running while the app is not visible. Returning to the app reconnects in onResume.
        if (!container.appSettings.value.keepAlive) container.stopLive()
    }
}
