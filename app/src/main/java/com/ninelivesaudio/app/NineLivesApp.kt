package com.ninelivesaudio.app

import android.app.Application
import com.ninelivesaudio.app.data.remote.ApiService
import com.ninelivesaudio.app.service.ConnectivityMonitor
import com.ninelivesaudio.app.service.SettingsManager
import com.ninelivesaudio.app.service.SyncManager
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class NineLivesApp : Application() {

    @Inject
    lateinit var connectivityMonitor: ConnectivityMonitor

    @Inject
    lateinit var syncManager: SyncManager

    @Inject
    lateinit var settingsManager: SettingsManager

    @Inject
    lateinit var apiService: ApiService

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()

        // Start network monitoring (no dependency on settings)
        connectivityMonitor.startMonitoring()

        // Load settings from disk FIRST, then start sync.
        // syncManager.start() must wait for settings to be loaded so it has
        // a valid server URL and auth token for the first sync attempt.
        appScope.launch {
            settingsManager.loadSettings()
            apiService.initializeFromSettings()
            syncManager.start()
        }
    }

    override fun onTerminate() {
        connectivityMonitor.stopMonitoring()
        syncManager.stop()
        super.onTerminate()
    }
}
