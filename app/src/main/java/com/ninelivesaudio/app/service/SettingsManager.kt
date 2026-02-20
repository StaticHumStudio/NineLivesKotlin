package com.ninelivesaudio.app.service

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
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
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SettingsManager"

/**
 * Manages application settings and secure token storage.
 *
 * All sensitive data is stored in EncryptedSharedPreferences (AES-256-GCM).
 * On first launch after the migration, any legacy plaintext `settings.json`
 * is imported and deleted.
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

    // Legacy settings file — only used for one-time migration
    private val legacySettingsDir: File
        get() = File(context.filesDir, "NineLivesAudio")

    private val legacySettingsFile: File
        get() = File(legacySettingsDir, "settings.json")

    // Encrypted SharedPreferences for all secure data
    private val masterKey by lazy {
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    private val encryptedPrefs by lazy {
        EncryptedSharedPreferences.create(
            context,
            "nine_lives_secure_prefs",
            masterKey,
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
        Log.d(TAG, "loadSettings: Loading from encrypted storage")
        try {
            // Check for legacy plaintext settings file and migrate if present
            if (legacySettingsFile.exists()) {
                Log.d(TAG, "loadSettings: Found legacy settings.json — migrating to encrypted storage")
                try {
                    val text = legacySettingsFile.readText()
                    val migrated = json.decodeFromString<AppSettings>(text)
                    // Save to encrypted prefs
                    encryptedPrefs.edit()
                        .putString(KEY_SETTINGS, json.encodeToString(migrated))
                        .commit()
                    // Delete the plaintext file
                    legacySettingsFile.delete()
                    Log.d(TAG, "loadSettings: Migration complete, legacy file deleted")
                } catch (e: Exception) {
                    Log.e(TAG, "loadSettings: Migration failed, will use defaults", e)
                    // Delete the broken file anyway to avoid retrying every launch
                    legacySettingsFile.delete()
                }
            }

            val settingsJson = encryptedPrefs.getString(KEY_SETTINGS, null)
            if (settingsJson != null) {
                val loaded = json.decodeFromString<AppSettings>(settingsJson)
                Log.d(TAG, "loadSettings: Loaded settings - unhingedThemeEnabled=${loaded.unhingedThemeEnabled}")
                // Ensure download path has a default
                val withDefaults = if (loaded.downloadPath.isEmpty()) {
                    loaded.copy(downloadPath = defaultDownloadPath())
                } else {
                    loaded
                }
                _settings.value = withDefaults
                Log.d(TAG, "loadSettings: Settings applied to StateFlow")
                withDefaults
            } else {
                Log.d(TAG, "loadSettings: No settings found, creating defaults")
                val defaults = AppSettings(downloadPath = defaultDownloadPath())
                _settings.value = defaults
                saveSettings(defaults)
                defaults
            }
        } catch (e: Exception) {
            Log.e(TAG, "loadSettings: Error loading settings", e)
            val defaults = AppSettings(downloadPath = defaultDownloadPath())
            _settings.value = defaults
            defaults
        }
    }

    suspend fun saveSettings(settings: AppSettings) = withContext(Dispatchers.IO) {
        Log.d(TAG, "saveSettings: Saving settings - unhingedThemeEnabled=${settings.unhingedThemeEnabled}")
        try {
            encryptedPrefs.edit()
                .putString(KEY_SETTINGS, json.encodeToString(settings))
                .commit()
            _settings.value = settings
            Log.d(TAG, "saveSettings: Settings saved successfully and StateFlow updated")
        } catch (e: Exception) {
            Log.e(TAG, "saveSettings: Error saving settings", e)
        }
    }

    suspend fun updateSettings(transform: (AppSettings) -> AppSettings) {
        Log.d(TAG, "updateSettings: Transforming settings")
        val updated = transform(_settings.value)
        Log.d(TAG, "updateSettings: Transformed - unhingedThemeEnabled=${updated.unhingedThemeEnabled}")
        saveSettings(updated)
    }

    // ─── Auth Token ──────────────────────────────────────────────────────

    companion object {
        private const val KEY_AUTH_TOKEN = "auth_token"
        private const val KEY_SETTINGS = "app_settings"
        private const val KEY_DEVICE_ID = "device_id"
    }

    suspend fun getAuthToken(): String? = withContext(Dispatchers.IO) {
        encryptedPrefs.getString(KEY_AUTH_TOKEN, null)
    }

    suspend fun saveAuthToken(token: String) = withContext(Dispatchers.IO) {
        val sanitized = token.trim()
        if (sanitized.isEmpty()) {
            encryptedPrefs.edit().remove(KEY_AUTH_TOKEN).commit()
        } else {
            encryptedPrefs.edit().putString(KEY_AUTH_TOKEN, sanitized).commit()
        }
    }

    suspend fun clearAuthToken() = withContext(Dispatchers.IO) {
        encryptedPrefs.edit().remove(KEY_AUTH_TOKEN).commit()
    }

    // ─── Device ID ────────────────────────────────────────────────────────

    /**
     * Returns a stable, unique device identifier.
     * Generated as a random UUID on first call and persisted in encrypted storage.
     * Used as `deviceId` in Audiobookshelf playback sessions instead of [android.os.Build.MODEL]
     * which is not unique per device (e.g., all Pixel 8 phones share the same model string).
     */
    fun getDeviceId(): String {
        val existing = encryptedPrefs.getString(KEY_DEVICE_ID, null)
        if (existing != null) return existing

        val newId = UUID.randomUUID().toString()
        encryptedPrefs.edit().putString(KEY_DEVICE_ID, newId).commit()
        return newId
    }

    // ─── Helpers ─────────────────────────────────────────────────────────

    private fun defaultDownloadPath(): String {
        val musicDir = context.getExternalFilesDir(android.os.Environment.DIRECTORY_MUSIC)
            ?: context.filesDir
        return File(musicDir, "Audiobookshelf").also { it.mkdirs() }.absolutePath
    }

    /** Path to the settings file (for diagnostics). */
    val settingsFilePath: String
        get() = "encrypted://nine_lives_secure_prefs/app_settings"
}
