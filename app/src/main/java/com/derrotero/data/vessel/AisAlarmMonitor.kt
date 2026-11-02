package com.derrotero.data.vessel

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.derrotero.data.settings.AppSettings
import com.derrotero.data.signalk.Paths
import com.derrotero.data.signalk.angle
import com.derrotero.data.signalk.latitude
import com.derrotero.data.signalk.longitude
import com.derrotero.data.signalk.number
import com.derrotero.util.Cpa
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonElement
import java.util.concurrent.ConcurrentHashMap

data class AisAlarm(
    val targetId: String,
    val label: String,
    val distanceM: Double,
    val dcpaM: Double,
    val tcpaSec: Double,
)

/**
 * Evaluates CPA/TCPA against the configured thresholds and raises a high-priority
 * notification per threatening target (with a cooldown so it does not spam).
 */
class AisAlarmMonitor(private val context: Context) {

    private val _alarms = MutableStateFlow<List<AisAlarm>>(emptyList())
    val alarms: StateFlow<List<AisAlarm>> = _alarms.asStateFlow()

    private val lastNotified = ConcurrentHashMap<String, Long>()

    fun evaluate(own: Map<String, JsonElement>, targets: Map<String, AisTarget>, cfg: AppSettings) {
        val alarmCfg = cfg.alarms
        if (!alarmCfg.enabled) {
            clear()
            return
        }
        val lat = own.latitude()
        val lon = own.longitude()
        if (lat == null || lon == null) {
            clear()
            return
        }
        val sog = own.number(Paths.SOG)
        val cog = own.angle(Paths.COG) ?: own.angle(Paths.COG_MAG)
        val now = System.currentTimeMillis()
        val cpaLimit = alarmCfg.cpaMeters
        val tcpaLimitSec = alarmCfg.tcpaMinutes * 60.0

        val active = ArrayList<AisAlarm>()
        for (target in targets.values) {
            if (alarmCfg.onlyClassA && target.vesselClass?.uppercase() != "A") continue
            val targetSogKn = (target.sogMps ?: 0.0) * 1.943844
            if (alarmCfg.minSpeedKn > 0.0 && targetSogKn < alarmCfg.minSpeedKn) continue

            val result = Cpa.compute(
                lat, lon, sog, cog,
                target.lat, target.lon, target.sogMps, target.cogRad,
            )
            val dcpa = result.dcpaM
            val tcpa = result.tcpaSec
            val closing = dcpa != null && tcpa != null && dcpa <= cpaLimit && tcpa <= tcpaLimitSec
            val veryClose = result.distanceM <= alarmCfg.radiusMeters
            if (closing || veryClose) {
                val alarm = AisAlarm(
                    targetId = target.id,
                    label = target.label,
                    distanceM = result.distanceM,
                    dcpaM = dcpa ?: result.distanceM,
                    tcpaSec = tcpa ?: 0.0,
                )
                active.add(alarm)
                val last = lastNotified[target.id] ?: 0L
                if (now - last > COOLDOWN_MS) {
                    lastNotified[target.id] = now
                    notifyAlarm(alarm)
                }
            } else {
                val wasNotified = lastNotified.remove(target.id) != null
                if (wasNotified) cancel(target.id)
            }
        }
        _alarms.value = active.sortedBy { it.dcpaM }
    }

    fun clear() {
        if (_alarms.value.isNotEmpty()) {
            _alarms.value.forEach { cancel(it.targetId) }
            _alarms.value = emptyList()
        }
        lastNotified.clear()
    }

    private fun notifyAlarm(alarm: AisAlarm) {
        ensureChannel()
        val text = "CPA ${"%.0f".format(alarm.dcpaM)} m · TCPA ${formatTcpa(alarm.tcpaSec)} · " +
            "dist ${"%.0f".format(alarm.distanceM)} m"
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("AIS: ${alarm.label}")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(alarm.targetId.hashCode(), notification)
        }
    }

    private fun cancel(targetId: String) {
        runCatching {
            NotificationManagerCompat.from(context).cancel(targetId.hashCode())
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "Alarmas AIS",
                        NotificationManager.IMPORTANCE_HIGH,
                    ).apply { description = "Avisos de proximidad CPA/TCPA" },
                )
            }
        }
    }

    private fun formatTcpa(seconds: Double): String = when {
        seconds >= 3600 -> "%.1f h".format(seconds / 3600)
        seconds >= 60 -> "%.0f min".format(seconds / 60)
        else -> "%.0f s".format(seconds)
    }

    companion object {
        private const val CHANNEL_ID = "ais-alarms"
        private const val COOLDOWN_MS = 120_000L
    }
}
