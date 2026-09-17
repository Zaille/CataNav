package com.catanav

import android.app.Application
import com.catanav.data.CataNavDatabase
import com.catanav.data.MapRepository
import com.catanav.data.TripRepository
import com.catanav.settings.SettingsStore
import com.catanav.trip.TripSession

/** Tiny hand-rolled service locator — one shared object graph for UI and Service. */
class ServiceLocator(app: Application) {
    val database: CataNavDatabase by lazy { CataNavDatabase.get(app) }
    val repository: TripRepository by lazy {
        TripRepository(database.tripDao(), database.trackPointDao(), database.tripMapDao())
    }
    val mapRepository: MapRepository by lazy {
        MapRepository(database.mapDao(), database.anchorDao(), database.calibrationTestDao())
    }
    val settings: SettingsStore by lazy { SettingsStore(app) }
    val tripSession: TripSession by lazy { TripSession(repository, mapRepository, settings) }
}

class CataNavApp : Application() {

    val locator: ServiceLocator by lazy { ServiceLocator(this) }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: CataNavApp
            private set
    }
}
