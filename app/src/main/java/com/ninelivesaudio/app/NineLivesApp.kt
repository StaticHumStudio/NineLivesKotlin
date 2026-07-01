package com.ninelivesaudio.app

import android.app.Application
import android.content.Context
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import com.ninelivesaudio.app.data.remote.ApiService
import com.ninelivesaudio.app.service.ConnectivityMonitor
import com.ninelivesaudio.app.service.SettingsManager
import com.ninelivesaudio.app.service.SyncManager
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.acra.ReportField
import org.acra.config.dialog
import org.acra.config.mailSender
import org.acra.data.StringFormat
import org.acra.ktx.initAcra
import javax.inject.Inject

@HiltAndroidApp
class NineLivesApp : Application(), ImageLoaderFactory {

    @Inject
    lateinit var connectivityMonitor: ConnectivityMonitor

    @Inject
    lateinit var syncManager: SyncManager

    @Inject
    lateinit var settingsManager: SettingsManager

    @Inject
    lateinit var apiService: ApiService

    // The app's OkHttpClient carries the auth token, self-signed cert config, and
    // dynamic base URL, so cover requests authenticate like every other call.
    @Inject
    lateinit var okHttpClient: OkHttpClient

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
                mailTo = "Static@StaticHum.Studio"
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

        // Load settings from disk FIRST, then bring up everything that depends
        // on them. Order matters: the server URL and auth token must be in
        // place before any network call fires. Otherwise the first request
        // races against the empty placeholder base URL (http://localhost/) and
        // fails with ConnectException, which the app would misread as a
        // "session expired" / signed-out state on every cold start.
        appScope.launch {
            settingsManager.loadSettings()
            // In LOCAL mode the app-wide selectedLibraryId must track the local
            // library; heal any server id a prior ABS session left behind so the
            // Home/Library shelves show local books on launch, not server ones.
            settingsManager.reconcileSelectedLibraryForMode()
            apiService.initializeFromSettings()
            // serverUrl + token are now loaded, so it is safe to probe the
            // server and start syncing.
            connectivityMonitor.startMonitoring()
            syncManager.start()
        }
    }

    override fun onTerminate() {
        connectivityMonitor.stopMonitoring()
        syncManager.stop()
        super.onTerminate()
    }

    /**
     * Coil image loader for the whole app. Reuses the authenticated OkHttpClient
     * so server covers carry the auth token, and adds a persistent disk cache so
     * covers fetched once survive offline instead of relying on Coil's defaults.
     */
    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .okHttpClient { okHttpClient }
            .crossfade(true)
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizePercent(0.05)
                    .build()
            }
            .build()
    }
}
