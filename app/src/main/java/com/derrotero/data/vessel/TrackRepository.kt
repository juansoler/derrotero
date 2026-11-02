package com.derrotero.data.vessel

import android.app.Application
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

data class TrackPoint(val lat: Double, val lon: Double, val ts: Long)

/**
 * Persists the vessel's track ("estela") in `filesDir/track.csv`.
 * Points are appended while recording is enabled; the file is reloaded on startup.
 */
class TrackRepository(app: Application) {

    private val file = File(app.filesDir, "track.csv")
    private val prefs = app.getSharedPreferences("track", 0)

    private val _points = MutableStateFlow<List<TrackPoint>>(emptyList())
    val points = _points.asStateFlow()

    private val _enabled = MutableStateFlow(prefs.getBoolean("enabled", false))
    val enabled = _enabled.asStateFlow()

    private var lastLat: Double? = null
    private var lastLon: Double? = null
    private var lastTs: Long = 0L

    init {
        load()
    }

    private fun load() {
        if (!file.exists()) return
        val loaded = runCatching {
            file.useLines { lines ->
                lines.mapNotNull { line ->
                    val p = line.split(',')
                    if (p.size < 3) return@mapNotNull null
                    val lat = p[0].toDoubleOrNull()
                    val lon = p[1].toDoubleOrNull()
                    val ts = p[2].toLongOrNull()
                    if (lat != null && lon != null && ts != null && lat in -90.0..90.0 && lon in -180.0..180.0) {
                        TrackPoint(lat, lon, ts)
                    } else {
                        null
                    }
                }.toList()
            }
        }.getOrDefault(emptyList())
        _points.value = loaded
        loaded.lastOrNull()?.let {
            lastLat = it.lat
            lastLon = it.lon
            lastTs = it.ts
        }
    }

    fun setEnabled(value: Boolean) {
        _enabled.value = value
        prefs.edit().putBoolean("enabled", value).apply()
    }

    @Synchronized
    fun add(lat: Double, lon: Double, force: Boolean = false) {
        if (!_enabled.value) return
        val now = System.currentTimeMillis()
        val prevLat = lastLat
        val prevLon = lastLon
        if (!force && prevLat != null && prevLon != null) {
            val moved = distanceMeters(prevLat, prevLon, lat, lon)
            // Record when moved at least ~8 m; also drop a slow-drift point every 60 s.
            if (moved < 8.0 && now - lastTs < 60_000) return
        }
        val point = TrackPoint(lat, lon, now)
        _points.value = _points.value + point
        lastLat = lat
        lastLon = lon
        lastTs = now
        runCatching { file.appendText("$lat,$lon,$now\n") }
    }

    @Synchronized
    fun clear() {
        _points.value = emptyList()
        lastLat = null
        lastLon = null
        lastTs = 0L
        runCatching { file.delete() }
    }

    private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLon / 2) * Math.sin(dLon / 2)
        return 2 * r * Math.asin(Math.sqrt(a))
    }
}
