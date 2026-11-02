package com.derrotero.data.vessel

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.derrotero.data.settings.AppSettings
import com.derrotero.data.signalk.latitude
import com.derrotero.data.signalk.longitude
import com.derrotero.util.Geo
import com.derrotero.util.LatLon
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonElement

/**
 * Anchor watch: keeps the distance to the anchor point and raises a high-priority
 * notification when the vessel drifts outside the configured radius.
 */
class AnchorMonitor(private val context: Context) {

    data class State(
        val set: Boolean = false,
        val lat: Double? = null,
        val lon: Double? = null,
        val radiusMeters: Double = 30.0,
        val distanceMeters: Double? = null,
        val bearingDeg: Double? = null,
        val alarm: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var lastNotified = 0L

    fun evaluate(own: Map<String, JsonElement>, cfg: AppSettings) {
        val anchor = cfg.anchor
        if (!anchor.enabled || anchor.lat == null || anchor.lon == null) {
            if (_state.value.set) cancel()
            lastNotified = 0L
            _state.value = State(set = false, radiusMeters = anchor.radiusMeters)
            return
        }

        val lat = own.latitude()
        val lon = own.longitude()
        if (lat == null || lon == null) {
            _state.value = State(
                set = true,
                lat = anchor.lat,
                lon = anchor.lon,
                radiusMeters = anchor.radiusMeters,
            )
            return
        }

        val anchorPoint = LatLon(anchor.lat, anchor.lon)
        val distance = Geo.distanceMeters(anchorPoint, LatLon(lat, lon))
        val bearing = Geo.bearingDegrees(anchorPoint, LatLon(lat, lon))
        val alarm = distance > anchor.radiusMeters

        _state.value = State(
            set = true,
            lat = anchor.lat,
            lon = anchor.lon,
            radiusMeters = anchor.radiusMeters,
            distanceMeters = distance,
            bearingDeg = bearing,
            alarm = alarm,
        )

        val now = System.currentTimeMillis()
        if (alarm && now - lastNotified > COOLDOWN_MS) {
            lastNotified = now
            notify(distance, anchor.radiusMeters)
        } else if (!alarm && lastNotified != 0L) {
            lastNotified = 0L
            cancel()
        }
    }

    fun clear() {
        cancel()
        lastNotified = 0L
        _state.value = State()
    }

    private fun notify(distance: Double, radius: Double) {
        ensureChannel()
        val text = "Deriva %.0f m del ancla (radio %.0f m)".format(distance, radius)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Alarma de fondeo")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        }
    }

    private fun cancel() {
        runCatching {
            NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "Alarma de fondeo",
                        NotificationManager.IMPORTANCE_HIGH,
                    ).apply { description = "Aviso cuando el barco deriva fuera del radio de fondeo" },
                )
            }
        }
    }

    companion object {
        private const val CHANNEL_ID = "anchor-alarm"
        private const val NOTIFICATION_ID = 4712
        private const val COOLDOWN_MS = 120_000L
    }
}
