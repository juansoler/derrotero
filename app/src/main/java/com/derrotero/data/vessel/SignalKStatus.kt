package com.derrotero.data.vessel

/** Connection state surfaced to the UI. */
sealed interface SignalKStatus {
    data object Disconnected : SignalKStatus
    data object Connecting : SignalKStatus
    data object Connected : SignalKStatus
    data object Streaming : SignalKStatus
    data object Reconnecting : SignalKStatus
    /** Own-vessel data is arriving from NMEA 0183 because Signal K is not streaming. */
    data object Nmea : SignalKStatus
    data class Error(val message: String) : SignalKStatus

    val label: String
        get() = when (this) {
            Disconnected -> "Desconectado"
            Connecting -> "Conectando…"
            Connected -> "Conectado"
            Streaming -> "En vivo"
            Reconnecting -> "Reconectando…"
            Nmea -> "NMEA 0183"
            is Error -> "Error: $message"
        }
}
