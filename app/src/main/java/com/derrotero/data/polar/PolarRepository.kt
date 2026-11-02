package com.derrotero.data.polar

import com.derrotero.data.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Holds the active polar table (built-in default or a user-imported CSV). */
class PolarRepository(
    private val settings: SettingsStore,
    scope: CoroutineScope,
) {
    val table: StateFlow<PolarTable> = settings.settings
        .map { s -> s.polarCsv?.let { PolarTable.parse(it) } ?: PolarTable.default() }
        .stateIn(scope, SharingStarted.Eagerly, PolarTable.default())

    /** Returns false when the CSV could not be parsed. */
    suspend fun importCsv(csv: String): Boolean {
        if (PolarTable.parse(csv) == null) return false
        settings.setPolarCsv(csv)
        return true
    }

    suspend fun reset() = settings.setPolarCsv(null)
}
