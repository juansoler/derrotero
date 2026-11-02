package com.derrotero

import android.app.Application
import com.derrotero.core.AppContainer
import org.maplibre.android.MapLibre

class DerroteroApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        MapLibre.getInstance(this)
        container = AppContainer(this)
        container.start()
    }
}
