package com.derrotero.data.vessel

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.derrotero.data.settings.AppSettings
import com.derrotero.data.polar.PolarTable
import com.derrotero.data.signalk.Paths
import com.derrotero.data.signalk.angle
import com.derrotero.data.signalk.latitude
import com.derrotero.data.signalk.longitude
import com.derrotero.data.signalk.number
import com.derrotero.util.Geo
import com.derrotero.util.LatLon
import com.derrotero.util.SailAdvisor
import com.derrotero.util.TackAdvice
import com.derrotero.util.WindMath
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonElement
import kotlin.math.abs
import kotlin.math.cos

/**
 * OpenCPN-style route guidance computed locally from the SignalK data:
 * distance/bearing to the active waypoint, cross-track error (XTE), VMG, ETA and
 * arrival / off-track alarms.
 */
data class NavGuidance(
    val active: Boolean = false,
    val name: String? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    val distanceM: Double? = null,
    val bearingDeg: Double? = null,
    val xteM: Double? = null,
    val vmgMps: Double? = null,
    val timeToGoSec: Double? = null,
    val etaMs: Long? = null,
    val arrived: Boolean = false,
    val offTrack: Boolean = false,
    val routeId: String? = null,
    val waypointIndex: Int? = null,
    val tackAdvice: TackAdvice? = null,
)

class NavMonitor(private val context: Context) {

    private val _state = MutableStateFlow(NavGuidance())
    val state: StateFlow<NavGuidance> = _state.asStateFlow()

    private var currentKey: String? = null
    private var arrivalNotified = false
    private var lastOffTrackNotify = 0L
    private var lastTackNotify = 0L
    private var tackNotified = false

    fun evaluate(
        own: Map<String, JsonElement>,
        target: NavigationTarget?,
        cfg: AppSettings,
        polar: PolarTable? = null,
    ) {
        val key = target?.let { "${it.name}@${it.lat},${it.lon}" }
        if (key != currentKey) {
            currentKey = key
            arrivalNotified = false
            lastOffTrackNotify = 0L
            lastTackNotify = 0L
            tackNotified = false
            if (key == null) cancel()
        }

        val lat = own.latitude()
        val lon = own.longitude()
        if (target == null || lat == null || lon == null) {
            _state.value = NavGuidance(
                active = target != null,
                name = target?.name,
                lat = target?.lat,
                lon = target?.lon,
                routeId = target?.routeId,
                waypointIndex = target?.waypointIndex,
            )
            return
        }

        val ownPos = LatLon(lat, lon)
        val targetPos = LatLon(target.lat, target.lon)
        val distance = Geo.distanceMeters(ownPos, targetPos)
        val bearing = Geo.bearingDegrees(ownPos, targetPos)

        val sog = own.number(Paths.SOG) ?: 0.0
        val cog = own.angle(Paths.COG) ?: own.angle(Paths.COG_MAG)
        val heading = own.angle(Paths.HEADING_TRUE)
            ?: own.angle(Paths.HEADING_MAG)
            ?: own.angle(Paths.HEADING_COMPASS)
            ?: cog
        val bearingRad = Math.toRadians(bearing)
        val vmg = if (cog != null) sog * cos(cog - bearingRad) else null
        val timeToGo = if (vmg != null && vmg > 0.2) distance / vmg else null
        val now = System.currentTimeMillis()
        val eta = timeToGo?.let { now + (it * 1000.0).toLong() }

        val xte = if (target.fromLat != null && target.fromLon != null) {
            Geo.crossTrackMeters(
                LatLon(target.fromLat, target.fromLon),
                targetPos,
                ownPos,
            )
        } else {
            null
        }

        val twsKn = (
            own.number(Paths.WIND_SPEED_TRUE)
                ?: own.number(Paths.WIND_SPEED_APPARENT)
            )?.times(1.943844)
        val trueWindDirection = own.angle(Paths.WIND_DIRECTION_TRUE)
            ?: heading?.let { h ->
                own.angle(Paths.WIND_ANGLE_TRUE)?.let { WindMath.trueWindDirection(h, it) }
            }
        val tackAdvice = SailAdvisor.tackAdvice(
            bearingToTargetDeg = bearing,
            trueWindDirectionDeg = trueWindDirection?.let {
                Math.toDegrees(WindMath.wrap2Pi(it))
            },
            headingDeg = heading?.let { Math.toDegrees(WindMath.wrap2Pi(it)) },
            upwindAngleDeg = SailAdvisor.upwindAngleDeg(polar, twsKn),
        )

        val arrivalRadius = cfg.navigation.arrivalRadiusMeters
        val arrived = distance <= arrivalRadius
        val offTrack = xte != null && abs(xte) > cfg.navigation.xteAlarmMeters

        _state.value = NavGuidance(
            active = true,
            name = target.name,
            lat = target.lat,
            lon = target.lon,
            distanceM = distance,
            bearingDeg = bearing,
            xteM = xte,
            vmgMps = vmg,
            timeToGoSec = timeToGo,
            etaMs = eta,
            arrived = arrived,
            offTrack = offTrack,
            routeId = target.routeId,
            waypointIndex = target.waypointIndex,
            tackAdvice = tackAdvice,
        )

        if (!cfg.navigation.alarmsEnabled) return

        if (arrived && !arrivalNotified) {
            arrivalNotified = true
            notify(
                title = "Waypoint alcanzado",
                text = "${target.name} · a ${"%.0f".format(distance)} m",
                id = ARRIVAL_ID,
            )
        }
        if (offTrack && now - lastOffTrackNotify > OFF_TRACK_COOLDOWN_MS) {
            lastOffTrackNotify = now
            val side = if ((xte ?: 0.0) >= 0) "estribor" else "babor"
            notify(
                title = "Fuera de rumbo (XTE)",
                text = "${"%.0f".format(abs(xte ?: 0.0))} m a $side · rumbo a ${target.name}",
                id = XTE_ID,
            )
        }
        if (!offTrack && lastOffTrackNotify != 0L) {
            lastOffTrackNotify = 0L
            cancel(XTE_ID)
        }

        val tackNow = tackAdvice?.tackNow == true
        if (tackNow && !tackNotified && now - lastTackNotify > TACK_COOLDOWN_MS) {
            tackNotified = true
            lastTackNotify = now
            val suggested = tackAdvice?.suggestedHeadingDeg
                ?.let { String.format(java.util.Locale.US, "%.0f°", it) }
                ?: "—"
            notify(
                title = "Bordo recomendado",
                text = "El destino pasó por proa · virada a " +
                    "${tackAdvice?.tackName ?: "la otra amura"} · rumbo $suggested",
                id = TACK_ID,
            )
        }
        if (!tackNow && tackNotified) {
            tackNotified = false
            cancel(TACK_ID)
        }
    }

    fun clear() {
        cancel()
        currentKey = null
        arrivalNotified = false
        lastOffTrackNotify = 0L
        lastTackNotify = 0L
        tackNotified = false
        _state.value = NavGuidance()
    }

    /** Immediate high-priority notification when MOB is pressed. */
    fun notifyMob(lat: Double, lon: Double) {
        val position = String.format(java.util.Locale.US, "%.5f, %.5f", lat, lon)
        notify("¡MOB! Hombre al agua", "Posición $position · pulsa la carta para navegar", MOB_ID)
    }

    fun clearMobNotification() {
        cancel(MOB_ID)
    }

    private fun notify(title: String, text: String, id: Int) {
        ensureChannel()
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setAutoCancel(true)
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(id, notification)
        }
    }

    private fun cancel(id: Int = -1) {
        runCatching {
            val manager = NotificationManagerCompat.from(context)
            if (id == -1) {
                manager.cancel(ARRIVAL_ID)
                manager.cancel(XTE_ID)
                manager.cancel(TACK_ID)
            } else {
                manager.cancel(id)
            }
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "Navegación y bordo",
                        NotificationManager.IMPORTANCE_HIGH,
                    ).apply {
                        description = "Avisos de llegada, fuera de rumbo y bordo recomendado"
                    },
                )
            }
        }
    }

    companion object {
        private const val CHANNEL_ID = "nav-alarms"
        private const val ARRIVAL_ID = 4801
        private const val XTE_ID = 4802
        private const val MOB_ID = 4803
        private const val TACK_ID = 4804
        private const val OFF_TRACK_COOLDOWN_MS = 180_000L
        private const val TACK_COOLDOWN_MS = 120_000L
    }
}
