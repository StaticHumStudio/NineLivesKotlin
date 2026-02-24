package com.ninelivesaudio.app

import android.app.Application
import android.content.Context
import com.ninelivesaudio.app.data.remote.ApiService
import com.ninelivesaudio.app.service.ConnectivityMonitor
import com.ninelivesaudio.app.service.SettingsManager
import com.ninelivesaudio.app.service.SyncManager
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.acra.ReportField
import org.acra.config.dialog
import org.acra.config.mailSender
import org.acra.data.StringFormat
import org.acra.ktx.initAcra
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

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        initAcra {
            buildConfigClass = BuildConfig::class.java
            reportFormat = StringFormat.JSON

            // Whitelist only safe fields to prevent leaking auth tokens,
            // server URLs, or user data via crash reports. Deliberately
            // excludes LOGCAT, SHARED_PREFERENCES, SETTINGS_*, and
            // THREAD_DETAILS which could contain sensitive information.
            reportContent = listOf(
                ReportField.REPORT_ID,
                ReportField.APP_VERSION_CODE,
                ReportField.APP_VERSION_NAME,
                ReportField.ANDROID_VERSION,
                ReportField.PHONE_MODEL,
                ReportField.BRAND,
                ReportField.PRODUCT,
                ReportField.BUILD,
                ReportField.STACK_TRACE,
                ReportField.CRASH_CONFIGURATION,
                ReportField.TOTAL_MEM_SIZE,
                ReportField.AVAILABLE_MEM_SIZE,
                ReportField.USER_COMMENT,
            )

            mailSender {
                mailTo = "Hum@StaticHum.Studio"
                reportAsFile = true
                reportFileName = "NineLives_crash.txt"
            }

            dialog {
                text = getString(R.string.crash_dialog_text)
                commentPrompt = getString(R.string.crash_dialog_comment)
            }
        }
    }

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
