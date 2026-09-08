package com.ninelivesaudio.app.ui.home

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ninelivesaudio.app.NineLivesApp
import com.ninelivesaudio.app.data.local.dao.AudioBookDao
import com.ninelivesaudio.app.data.local.entity.AudioBookEntity
import com.ninelivesaudio.app.data.local.entity.RecentlyPlayedResult
import com.ninelivesaudio.app.domain.model.AppMode
import com.ninelivesaudio.app.domain.model.AppSettings
import com.ninelivesaudio.app.service.SettingsManager
import com.ninelivesaudio.app.service.local.LocalFolderAccess
import java.io.File
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Keeps Home construction on the main thread while the selected remote source
 * and a nonempty recent-books flow are both immediately available.
 */
@RunWith(AndroidJUnit4::class)
class HomeViewModelStartupInstrumentedTest {

    @Test
    fun eagerRecentlyPlayedBookCompletesHomeStartupWithFirstLifeLabel() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation
            .targetContext.applicationContext as NineLivesApp
        val fixtureContext = HomeStartupFixtureContext(instrumentation.targetContext)
        val fixtureSettings = SettingsManager(fixtureContext)
        val viewModelStore = ViewModelStore()
        var viewModel: HomeViewModel? = null

        try {
            fixtureSettings.saveSettings(
                AppSettings(
                    appMode = AppMode.AUDIOBOOKSHELF,
                    serverUrl = FIXTURE_SERVER_URL,
                    selectedLibraryId = FIXTURE_LIBRARY_ID,
                ),
            )
            fixtureSettings.saveAuthToken(
                token = "startup-fixture-token",
                serverUrl = FIXTURE_SERVER_URL,
                accountId = "startup-fixture-account",
            )

            instrumentation.runOnMainSync {
                viewModel = HomeViewModel(
                    audioBookDao = eagerRecentlyPlayedDao(),
                    connectivityMonitor = app.connectivityMonitor,
                    syncManager = app.syncManager,
                    settingsManager = fixtureSettings,
                    libraryRepository = app.libraryRepository,
                    localFolderAccess = LocalFolderAccess(app),
                )
                viewModelStore.put(VIEW_MODEL_STORE_KEY, requireNotNull(viewModel))
            }

            val state = withTimeout(5_000) {
                requireNotNull(viewModel).uiState.filter { it.lives.isNotEmpty() }.first()
            }
            assertEquals("LIFE I", state.lives.first().lifeLabel)
        } finally {
            instrumentation.runOnMainSync { viewModelStore.clear() }
            fixtureContext.clearFixtureStorage()
        }
    }

    private fun eagerRecentlyPlayedDao(): AudioBookDao = Proxy.newProxyInstance(
        AudioBookDao::class.java.classLoader,
        arrayOf(AudioBookDao::class.java),
        InvocationHandler { proxy, method, args ->
            when (method.name) {
                "observeRecentlyPlayedByLibrary" -> flowOf(listOf(recentlyPlayedResult()))
                "toString" -> "EagerRecentlyPlayedAudioBookDao"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.singleOrNull()
                else -> error("Unexpected AudioBookDao call: ${method.name}")
            }
        },
    ) as AudioBookDao

    private fun recentlyPlayedResult() = RecentlyPlayedResult(
        audioBook = AudioBookEntity(
            id = "startup-book",
            libraryId = FIXTURE_LIBRARY_ID,
            title = "Startup Fixture",
            author = "Fixture Author",
            currentTimeSeconds = 3_600.0,
        ),
        lastPlayedAt = null,
    )

    private companion object {
        const val FIXTURE_LIBRARY_ID = "startup-library"
        const val FIXTURE_SERVER_URL = "https://startup-fixture.invalid"
        const val VIEW_MODEL_STORE_KEY = "startup-home-view-model"
    }
}

private class HomeStartupFixtureContext(
    baseContext: Context,
) : ContextWrapper(baseContext) {
    private val sharedPreferenceNames = linkedSetOf<String>()
    private val fixtureFilesDir = File(baseContext.cacheDir, FIXTURE_FILES_DIRECTORY)

    override fun getApplicationContext(): Context = this

    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
        sharedPreferenceNames += name
        return baseContext.getSharedPreferences("$PREFS_PREFIX$name", mode)
    }

    override fun deleteSharedPreferences(name: String): Boolean =
        baseContext.deleteSharedPreferences("$PREFS_PREFIX$name")

    override fun getFilesDir(): File = fixtureFilesDir.apply { mkdirs() }

    override fun getFileStreamPath(name: String): File = File(filesDir, name)

    override fun deleteFile(name: String): Boolean = getFileStreamPath(name).delete()

    fun clearFixtureStorage() {
        sharedPreferenceNames.forEach(::deleteSharedPreferences)
        deleteFile("NineLivesAudio/settings.json")
        deleteFile("NineLivesAudio")
    }

    private companion object {
        const val PREFS_PREFIX = "home-startup-test-"
        const val FIXTURE_FILES_DIRECTORY = "home-startup-test-files"
    }
}
