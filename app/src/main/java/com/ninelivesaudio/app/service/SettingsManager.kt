package com.ninelivesaudio.app.service

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.ninelivesaudio.app.domain.model.AppSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SettingsManager"

internal fun requireSuccessfulSettingsCommit(committed: Boolean) {
    check(committed) { "Encrypted settings commit failed" }
}

internal fun requireSuccessfulLegacySettingsDelete(deleted: Boolean) {
    check(deleted) { "Legacy settings cleanup failed" }
}

/**
 * Whether a failed legacy-file delete is load-bearing. In every call site
 * inside [SettingsManager.readSettingsFromDisk], the encrypted store is
 * already authoritative by the time the delete runs — either it already held
 * the settings (no re-import needed), the migration's encrypted commit just
 * succeeded, or the legacy file was corrupt and defaults are about to be
 * persisted regardless. A stubborn legacy file in any of those cases is
 * cosmetic: it gets retried on the next load and never causes data loss, so
 * failing the whole load over it (as [requireSuccessfulLegacySettingsDelete]
 * does) would defeat the point of degrading gracefully on storage faults.
 * Returns a log message when cleanup failed, or null when it succeeded.
 */
internal fun legacyCleanupFailureMessage(deleted: Boolean): String? =
    if (deleted) {
        null
    } else {
        "Legacy settings file could not be deleted; encrypted settings are " +
            "authoritative, so this is not fatal — cleanup will retry on the next load."
    }

internal fun shouldImportLegacySettings(encryptedSettingsJson: String?): Boolean =
    encryptedSettingsJson == null

/** Result of one [SettingsManager.loadSettings] attempt. */
internal data class SettingsLoadOutcome(
    val settings: AppSettings,
    val storageUnavailable: Boolean,
)

/**
 * Runs [load] and reports a degraded outcome instead of letting a storage
 * failure propagate. On failure, [retained] (the last known in-memory
 * settings — construction-time defaults on a cold start) is returned
 * untouched: a transient read failure must never fall back to silently
 * persisting defaults over retained server, source, and library selections.
 */
internal suspend fun loadSettingsOrDegrade(
    retained: () -> AppSettings,
    load: suspend () -> AppSettings,
    onFailure: (Exception) -> Unit = {},
): SettingsLoadOutcome = try {
    SettingsLoadOutcome(settings = load(), storageUnavailable = false)
} catch (cancellation: CancellationException) {
    // A cancelled load is not a storage outage. Let it propagate so the
    // caller's scope winds down and the next load retries cleanly.
    throw cancellation
} catch (e: Exception) {
    onFailure(e)
    SettingsLoadOutcome(settings = retained(), storageUnavailable = true)
}

internal fun persistAuthTokenChange(
    token: String?,
    commit: (String?) -> Boolean,
    publish: (Boolean) -> Unit,
) {
    val sanitized = token?.trim()?.takeIf { it.isNotEmpty() }
    requireSuccessfulSettingsCommit(commit(sanitized))
    publish(sanitized != null)
}

/** Serializes the first disk load with every later settings mutation. */
internal class SerializedSettingsState<T>(
    initial: T,
    private val publish: (T) -> Unit = {},
) {
    private val mutex = Mutex()
    private var loaded = false
    private var value = initial

    suspend fun load(read: suspend () -> T): T {
        mutex.lock()
        return try {
            if (!loaded) {
                value = read()
                loaded = true
                publish(value)
            }
            value
        } finally {
            mutex.unlock()
        }
    }

    suspend fun update(
        read: suspend () -> T,
        persist: suspend (T) -> Unit,
        transform: (T) -> T,
    ): T {
        mutex.lock()
        return try {
            if (!loaded) {
                value = read()
                loaded = true
            }
            val updated = transform(value)
            persist(updated)
            value = updated
            publish(value)
            value
        } finally {
            mutex.unlock()
        }
    }

    suspend fun replace(
        read: suspend () -> T,
        persist: suspend (T) -> Unit,
        replacement: T,
    ): T = update(read, persist) { replacement }
}

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
    private val serializedState = SerializedSettingsState(AppSettings()) { _settings.value = it }

    private val _isLoaded = MutableStateFlow(false)
    /** Becomes true after [loadSettings] completes, successfully or not. UI must
     * gate on this, not on [storageUnavailable], so a failed read still reaches
     * an error surface instead of hanging on the launch screen forever. */
    val isLoaded: StateFlow<Boolean> = _isLoaded.asStateFlow()

    private val _storageUnavailable = MutableStateFlow(false)
    /**
     * True when the most recent [loadSettings] could not read encrypted
     * storage. Callers must not treat this as "no settings" — the in-memory
     * [settings] value is left untouched (retained from the last durable read,
     * or construction-time defaults on a cold start) so nothing gets clobbered.
     * Cleared by the next successful [loadSettings].
     */
    val storageUnavailable: StateFlow<Boolean> = _storageUnavailable.asStateFlow()

    private val _hasAuthToken = MutableStateFlow(false)
    val hasAuthToken: StateFlow<Boolean> = _hasAuthToken.asStateFlow()

    /**
     * Serializes every hasAuthToken-affecting read-then-publish (loadSettings'
     * read, and saveAuthToken/clearAuthToken/replaceAuthTokenIfCurrent's own
     * writes) so a slow one can never win a race against a faster one that
     * started later — see the comment in [loadSettings].
     */
    private val authTokenMutex = Mutex()

    val currentSettings: AppSettings
        get() = _settings.value

    // ─── Settings ────────────────────────────────────────────────────────

    suspend fun loadSettings(): AppSettings {
        Log.d(TAG, "loadSettings: Loading from encrypted storage")
        val outcome = loadSettingsOrDegrade(
            retained = { _settings.value },
            load = { serializedState.load { readSettingsFromDisk() } },
            onFailure = { e ->
                Log.e(TAG, "loadSettings: Storage unavailable, keeping retained in-memory settings", e)
            },
        )
        _storageUnavailable.value = outcome.storageUnavailable
        // isLoaded flips true either way — the UI reads storageUnavailable to
        // tell "loaded normally" apart from "loaded degraded", but it still
        // needs a terminal state to stop showing the launch screen.
        _isLoaded.value = true
        if (outcome.storageUnavailable) return outcome.settings

        // Read-then-publish must be serialized with saveAuthToken/clearAuthToken/
        // replaceAuthTokenIfCurrent's own read-then-publish. Without the shared
        // lock, a slow load's read here can land before a concurrent
        // clearAuthToken()'s write but publish AFTER clearAuthToken() already
        // published false — the load's stale "true" would then win.
        // The token lives under its own prefs key, so its AEAD entry can be
        // corrupt even when the settings key just read fine. That partial
        // fault must degrade to "no token" like a full outage, not throw into
        // the caller's scope.
        try {
            authTokenMutex.withLock {
                _hasAuthToken.value = withContext(Dispatchers.IO) {
                    !encryptedPrefs.getString(KEY_AUTH_TOKEN, null).isNullOrBlank()
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            Log.e(TAG, "loadSettings: Auth token unreadable, treating as absent", e)
            _hasAuthToken.value = false
        }
        return outcome.settings
    }

    private suspend fun readSettingsFromDisk(): AppSettings = withContext(Dispatchers.IO) {
        try {
            // Read encrypted state first. A leftover legacy file must never
            // overwrite newer encrypted settings.
            var settingsJson = encryptedPrefs.getString(KEY_SETTINGS, null)

            // Migrate plaintext only when encrypted settings are absent.
            if (legacySettingsFile.exists()) {
                if (!shouldImportLegacySettings(settingsJson)) {
                    // Encrypted settings already hold the authoritative copy —
                    // a stubborn legacy file here is cosmetic, not fatal.
                    Log.d(TAG, "loadSettings: Removing stale legacy settings without re-import")
                    legacyCleanupFailureMessage(legacySettingsFile.delete())
                        ?.let { Log.w(TAG, "loadSettings: $it") }
                } else {
                    Log.d(TAG, "loadSettings: Found legacy settings.json — migrating to encrypted storage")
                    try {
                        val text = legacySettingsFile.readText()
                        val migrated = json.decodeFromString<AppSettings>(text)
                        settingsJson = json.encodeToString(migrated)
                        requireSuccessfulSettingsCommit(
                            encryptedPrefs.edit()
                                .putString(KEY_SETTINGS, settingsJson)
                                .commit(),
                        )
                        // The encrypted commit above already succeeded, so the
                        // migrated settings are durable regardless of whether
                        // this delete does.
                        legacyCleanupFailureMessage(legacySettingsFile.delete())
                            ?.let { Log.w(TAG, "loadSettings: $it") }
                        Log.d(TAG, "loadSettings: Migration complete")
                    } catch (e: SerializationException) {
                        // Legacy data is corrupt and about to be discarded in
                        // favor of defaults either way, so cleanup failing here
                        // does not risk losing anything real.
                        Log.e(TAG, "loadSettings: Legacy settings are invalid, using defaults", e)
                        legacyCleanupFailureMessage(legacySettingsFile.delete())
                            ?.let { Log.w(TAG, "loadSettings: $it") }
                    } catch (e: Exception) {
                        Log.e(TAG, "loadSettings: Migration failed, will retry", e)
                        throw e
                    }
                }
            }

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
                persistSettingsToDisk(defaults)
                defaults
            }
        } catch (e: Exception) {
            Log.e(TAG, "loadSettings: Error loading settings", e)
            // A transient encrypted-storage or decoding failure is not a
            // confirmed empty store. Leave the serialized gate open so the
            // next startup caller retries instead of persisting defaults over
            // retained server, source, and library selections.
            throw e
        }
    }

    private suspend fun persistSettingsToDisk(settings: AppSettings) = withContext(Dispatchers.IO) {
        Log.d(TAG, "saveSettings: Saving settings - unhingedThemeEnabled=${settings.unhingedThemeEnabled}")
        try {
            requireSuccessfulSettingsCommit(
                encryptedPrefs.edit()
                    .putString(KEY_SETTINGS, json.encodeToString(settings))
                    .commit(),
            )
            Log.d(TAG, "saveSettings: Settings saved successfully and StateFlow updated")
        } catch (e: Exception) {
            Log.e(TAG, "saveSettings: Error saving settings", e)
            throw e
        }
    }

    suspend fun saveSettings(settings: AppSettings) {
        serializedState.replace(
            read = { readSettingsFromDisk() },
            persist = { persistSettingsToDisk(it) },
            replacement = settings,
        )
        _isLoaded.value = true
    }

    suspend fun updateSettings(transform: (AppSettings) -> AppSettings) {
        Log.d(TAG, "updateSettings: Transforming settings")
        val updated = serializedState.update(
            read = { readSettingsFromDisk() },
            persist = { persistSettingsToDisk(it) },
            transform = transform,
        )
        _isLoaded.value = true
        Log.d(TAG, "updateSettings: Transformed - unhingedThemeEnabled=${updated.unhingedThemeEnabled}")
    }

    /**
     * Runs a settings mutation only while the auth token remains present.
     * Holding [authTokenMutex] through the serialized settings write makes a
     * sign out and a session-bound settings record one ordered operation.
     */
    internal suspend fun updateSettingsIfAuthenticated(
        transform: (AppSettings) -> AppSettings,
    ): Boolean = authTokenMutex.withLock {
        val authenticated = withContext(Dispatchers.IO) {
            !encryptedPrefs.getString(KEY_AUTH_TOKEN, null).isNullOrBlank()
        }
        if (!authenticated) {
            _hasAuthToken.value = false
            return@withLock false
        }
        serializedState.update(
            read = { readSettingsFromDisk() },
            persist = { persistSettingsToDisk(it) },
            transform = transform,
        )
        _isLoaded.value = true
        true
    }

    suspend fun markChangelogVersionSeen(version: String) {
        if (currentSettings.lastSeenChangelogVersion == version) return
        updateSettings { settings ->
            if (settings.lastSeenChangelogVersion == version) settings else {
                settings.copy(lastSeenChangelogVersion = version)
            }
        }
    }

    // ─── Auth Token ──────────────────────────────────────────────────────

    companion object {
        private const val KEY_AUTH_TOKEN = "auth_token"
        private const val KEY_SETTINGS = "app_settings"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_CURRENT_PLAYBACK_BOOK_ID = "current_playback_book_id"
        private const val KEY_TRUSTED_CERT_FINGERPRINT_PREFIX = "trusted_cert_fingerprint_"
    }

    fun getTrustedCertificateFingerprint(host: String): String? {
        val normalizedHost = host.trim().lowercase()
        if (normalizedHost.isEmpty()) return null
        return encryptedPrefs.getString("$KEY_TRUSTED_CERT_FINGERPRINT_PREFIX$normalizedHost", null)
    }

    fun saveTrustedCertificateFingerprint(host: String, fingerprint: String) {
        val normalizedHost = host.trim().lowercase()
        val normalizedFingerprint = fingerprint.trim().uppercase()
        if (normalizedHost.isEmpty() || normalizedFingerprint.isEmpty()) return
        encryptedPrefs.edit()
            .putString("$KEY_TRUSTED_CERT_FINGERPRINT_PREFIX$normalizedHost", normalizedFingerprint)
            .commit()
    }

    fun clearTrustedCertificateFingerprint(host: String) {
        val normalizedHost = host.trim().lowercase()
        if (normalizedHost.isEmpty()) return
        encryptedPrefs.edit()
            .remove("$KEY_TRUSTED_CERT_FINGERPRINT_PREFIX$normalizedHost")
            .commit()
    }

    suspend fun getAuthToken(): String? = withContext(Dispatchers.IO) {
        encryptedPrefs.getString(KEY_AUTH_TOKEN, null)
    }

    suspend fun saveAuthToken(token: String) = withContext(Dispatchers.IO) {
        authTokenMutex.withLock {
            persistAuthTokenChange(
                token = token,
                commit = { sanitized ->
                    val editor = encryptedPrefs.edit()
                    if (sanitized == null) {
                        editor.remove(KEY_AUTH_TOKEN)
                    } else {
                        editor.putString(KEY_AUTH_TOKEN, sanitized)
                    }
                    editor.commit()
                },
                publish = { _hasAuthToken.value = it },
            )
        }
    }

    suspend fun clearAuthToken() = withContext(Dispatchers.IO) {
        authTokenMutex.withLock {
            persistAuthTokenChange(
                token = null,
                commit = { encryptedPrefs.edit().remove(KEY_AUTH_TOKEN).commit() },
                publish = { _hasAuthToken.value = it },
            )
        }
    }

    suspend fun replaceAuthTokenIfCurrent(expected: String, replacement: String?): Boolean =
        withContext(Dispatchers.IO) {
            authTokenMutex.withLock {
                if (encryptedPrefs.getString(KEY_AUTH_TOKEN, null) != expected.trim()) {
                    return@withLock false
                }
                persistAuthTokenChange(
                    token = replacement,
                    commit = { sanitized ->
                        val editor = encryptedPrefs.edit()
                        if (sanitized == null) {
                            editor.remove(KEY_AUTH_TOKEN)
                        } else {
                            editor.putString(KEY_AUTH_TOKEN, sanitized)
                        }
                        editor.commit()
                    },
                    publish = { _hasAuthToken.value = it },
                )
                true
            }
        }

    fun getCurrentPlaybackBookId(): String? =
        encryptedPrefs.getString(KEY_CURRENT_PLAYBACK_BOOK_ID, null)

    fun saveCurrentPlaybackBookId(bookId: String) {
        encryptedPrefs.edit().putString(KEY_CURRENT_PLAYBACK_BOOK_ID, bookId).commit()
    }

    fun clearCurrentPlaybackBookId() {
        encryptedPrefs.edit().remove(KEY_CURRENT_PLAYBACK_BOOK_ID).commit()
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
