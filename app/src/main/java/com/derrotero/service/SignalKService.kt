package com.derrotero.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.derrotero.MainActivity
import com.derrotero.DerroteroApp
import com.derrotero.R
import com.derrotero.data.signalk.Paths
import com.derrotero.data.signalk.angle
import com.derrotero.data.signalk.depthBest
import com.derrotero.data.signalk.latitude
import com.derrotero.data.signalk.longitude
import com.derrotero.data.signalk.number
import com.derrotero.util.Formatters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch

/**
 * Keeps the app process (SignalK stream, AIS tracker and the local MBTiles tile server)
 * alive while the app is in the background.
 *
 * The connection itself is owned by [com.derrotero.core.AppContainer]; this service just
 * holds a foreground notification with a live data bar. The notification's **PARAR** button
 * (and its action) cancels every feed, clears the "keep alive" preference and removes the
 * notification so the app really stops working in the background.
 */
class SignalKService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        ServiceController.setRunning(true)
        createChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(NavSnapshot.initial()),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )

        val container = (application as DerroteroApp).container
        scope.launch {
            @OptIn(FlowPreview::class)
            combine(
                container.vessel.status,
                container.vessel.state,
                container.ais.targets,
            ) { status, state, ais ->
                NavSnapshot(
                    status = status.label,
                    position = Formatters.position(state.latitude(), state.longitude()),
                    sog = Formatters.knots(state.number(Paths.SOG)),
                    cog = Formatters.degrees(state.angle(Paths.COG) ?: state.angle(Paths.COG_MAG)),
                    depth = Formatters.meters(state.depthBest()),
                    wind = Formatters.knots(
                        state.number(Paths.WIND_SPEED_APPARENT)
                            ?: state.number(Paths.WIND_SPEED_TRUE),
                    ),
                    aisCount = ais.size,
                )
            }
                .sample(2_500L)
                .distinctUntilChanged()
                .collect { snapshot -> notify(buildNotification(snapshot)) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopEverything()
        }
        // Never restart by itself: the user explicitly controls the connection, and a sticky
        // service was the reason the app kept coming back after being stopped.
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        ServiceController.setRunning(false)
        scope.cancel()
        super.onDestroy()
    }

    /** Stops the feeds, removes the notification and finishes the service. */
    private fun stopEverything() {
        val container = (application as DerroteroApp).container
        container.stopBackground()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(snapshot: NavSnapshot): Notification {
        val open = PendingIntent.getActivity(
            this,
            REQUEST_OPEN,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this,
            REQUEST_STOP,
            Intent(this, SignalKService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val content = RemoteViews(packageName, R.layout.notification_nav).apply {
            setTextViewText(R.id.notif_title, "Derrotero")
            setTextViewText(
                R.id.notif_status,
                buildString {
                    append(snapshot.status)
                    append(" · ")
                    append(snapshot.aisCount)
                    append(" AIS")
                },
            )
            setTextViewText(R.id.notif_position, snapshot.position)
            setTextViewText(R.id.notif_sog, snapshot.sog)
            setTextViewText(R.id.notif_cog, snapshot.cog)
            setTextViewText(R.id.notif_depth, snapshot.depth)
            setTextViewText(R.id.notif_wind, snapshot.wind)
            setOnClickPendingIntent(R.id.notif_root, open)
            setOnClickPendingIntent(R.id.notif_stop, stop)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_nav)
            .setContentTitle("Derrotero")
            .setContentText(snapshot.status)
            .setCustomContentView(content)
            .setCustomBigContentView(content)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
            .addAction(R.drawable.ic_notif_open, "Abrir", open)
            .addAction(R.drawable.ic_notif_stop, "Parar", stop)
            .build()
    }

    private fun notify(notification: Notification) {
        runCatching {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                "SignalK en segundo plano",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Datos de navegación y botón para detener la conexión"
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            }
            manager.createNotificationChannel(channel)
        }
    }

    private data class NavSnapshot(
        val status: String,
        val position: String,
        val sog: String,
        val cog: String,
        val depth: String,
        val wind: String,
        val aisCount: Int,
    ) {
        companion object {
            fun initial() = NavSnapshot(
                status = "Conectando…",
                position = "Sin posición",
                sog = "—",
                cog = "—",
                depth = "—",
                wind = "—",
                aisCount = 0,
            )
        }
    }

    companion object {
        const val CHANNEL_ID = "signalk-foreground"
        const val NOTIFICATION_ID = 4711
        const val ACTION_STOP = "com.derrotero.action.STOP"

        private const val REQUEST_OPEN = 1
        private const val REQUEST_STOP = 2
    }
}
