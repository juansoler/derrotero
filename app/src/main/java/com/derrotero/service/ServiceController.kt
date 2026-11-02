package com.derrotero.service

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Small helper so the UI does not need to know about the service class. */
object ServiceController {

    private val _running = MutableStateFlow(false)

    /** Real state of the foreground service, mirrored from its lifecycle. */
    val running: StateFlow<Boolean> = _running.asStateFlow()

    fun start(context: Context) {
        runCatching {
            ContextCompat.startForegroundService(
                context,
                Intent(context, SignalKService::class.java),
            )
        }
    }

    fun stop(context: Context) {
        runCatching { context.stopService(Intent(context, SignalKService::class.java)) }
    }

    internal fun setRunning(running: Boolean) {
        _running.value = running
    }
}
