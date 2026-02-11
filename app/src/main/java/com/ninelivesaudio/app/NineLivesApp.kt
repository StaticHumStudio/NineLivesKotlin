package com.ninelivesaudio.app

import android.app.Application
import com.ninelivesaudio.app.service.ConnectivityMonitor
import com.ninelivesaudio.app.service.SyncManager
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class NineLivesApp : Application() {

    @Inject
    lateinit var connectivityMonitor: ConnectivityMonitor

    @Inject
    lateinit var syncManager: SyncManager

    override fun onCreate() {
        super.onCreate()

        // Start network monitoring
        connectivityMonitor.startMonitoring()

        // Start periodic sync (will wait for auth token before syncing)
        syncManager.start()
    }

    override fun onTerminate() {
        connectivityMonitor.stopMonitoring()
        syncManager.stop()
        super.onTerminate()
    }
}
