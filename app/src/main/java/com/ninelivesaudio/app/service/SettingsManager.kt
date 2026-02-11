package com.ninelivesaudio.app.service

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.ninelivesaudio.app.domain.model.AppSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages application settings and secure token storage.
 *
 * Settings are stored as JSON in a file (matching the MAUI pattern).
 * Auth token is stored in EncryptedSharedPreferences for security.
 */
@Singleton
class SettingsManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    // Settings file in app-private storage
    private val settingsDir: File
        get() = File(context.filesDir, "NineLivesAudio").also { it.mkdirs() }

    private val settingsFile: File
        get() = File(settingsDir, "settings.json")

    // Encrypted SharedPreferences for auth token
    private val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)

    private val encryptedPrefs by lazy {
        EncryptedSharedPreferences.create(
            "nine_lives_secure_prefs",
            masterKeyAlias,
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    val currentSettings: AppSettings
        get() = _settings.value

    // ─── Settings ────────────────────────────────────────────────────────

    suspend fun loadSettings(): AppSettings = withContext(Dispatchers.IO) {
        try {
            if (settingsFile.exists()) {
                val text = settingsFile.readText()
                val loaded = json.decodeFromString<AppSettings>(text)
                // Ensure download path has a default
                val withDefaults = if (loaded.downloadPath.isEmpty()) {
                    loaded.copy(
                        downloadPath = defaultDownloadPath()
                    )
                } else {
                    loaded
                }
                _settings.value = withDefaults
                withDefaults
            } else {
                val defaults = AppSettings(downloadPath = defaultDownloadPath())
                _settings.value = defaults
                saveSettings(defaults)
                defaults
            }
        } catch (e: Exception) {
            val defaults = AppSettings(downloadPath = defaultDownloadPath())
            _settings.value = defaults
            defaults
        }
    }

    suspend fun saveSettings(settings: AppSettings) = withContext(Dispatchers.IO) {
        try {
            settingsFile.writeText(json.encodeToString(settings))
            _settings.value = settings
        } catch (e: Exception) {
            // Log error in future phases
        }
    }

    suspend fun updateSettings(transform: (AppSettings) -> AppSettings) {
        val updated = transform(_settings.value)
        saveSettings(updated)
    }

    // ─── Auth Token ──────────────────────────────────────────────────────

    companion object {
        private const val KEY_AUTH_TOKEN = "auth_token"
    }

    suspend fun getAuthToken(): String? = withContext(Dispatchers.IO) {
        encryptedPrefs.getString(KEY_AUTH_TOKEN, null)
    }

    suspend fun saveAuthToken(token: String) = withContext(Dispatchers.IO) {
        encryptedPrefs.edit().putString(KEY_AUTH_TOKEN, token).apply()
    }

    suspend fun clearAuthToken() = withContext(Dispatchers.IO) {
        encryptedPrefs.edit().remove(KEY_AUTH_TOKEN).apply()
    }

    // ─── Helpers ─────────────────────────────────────────────────────────

    private fun defaultDownloadPath(): String {
        val musicDir = context.getExternalFilesDir(android.os.Environment.DIRECTORY_MUSIC)
        return File(musicDir, "AudioBookshelf").also { it.mkdirs() }.absolutePath
    }

    /** Path to the settings file (for diagnostics). */
    val settingsFilePath: String
        get() = settingsFile.absolutePath
}
