package com.ninelivesaudio.app.settings.unhinged

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant

/**
 * DataStore extension for Unhinged settings
 * Separate from main app settings to avoid conflicts
 */
private val Context.unhingedDataStore by preferencesDataStore("unhinged_settings")

/**
 * Copy mode tier - controls how weird the UI text gets.
 */
enum class CopyMode {
    /**
     * Normal mode: Standard labels, no flavor text.
     * The app behaves as if unhinged mode doesn't exist.
     */
    Normal,

    /**
     * Ritual mode: Literal labels + subtle flavor subtitles.
     * Adds a single beat of atmosphere without competing with main text.
     */
    Ritual,

    /**
     * Unhinged mode: Weirder flavor, more frequent whispers, lore-heavy subtitles.
     * The archive knows more than it's saying. Things are slightly off.
     */
    Unhinged
}

/**
 * Notification intensity levels
 * - Quiet: Minimal notifications, no whispers
 * - Standard: Regular notifications + occasional whispers
 * - Unhinged: Maximum chaos - all features enabled
 */
enum class NotificationIntensity {
    Quiet,
    Standard,
    Unhinged
}

/**
 * Central configuration for all Unhinged Mode features.
 *
 * This is the single integration point at the app root. Every unhinged feature
 * checks these flags. Reverting unhinged mode = setting everything to false.
 *
 * NOTE: normalModeEnabled is the MASTER SWITCH
 * - false (default) = Unhinged mode is ACTIVE
 * - true = Normal mode (all unhinged features disabled)
 *
 * Design decision: App defaults to Unhinged, users opt OUT to Normal mode
 */
data class UnhingedSettings(
    // ─── Master Switch ───────────────────────────────────────────────────────

    /** Master toggle - FALSE by default (Unhinged is active on first launch) */
    val normalModeEnabled: Boolean = false,

    /** Convenience property for backwards compatibility */
    val unhingedThemeEnabled: Boolean = !normalModeEnabled,

    /** Convenience property - inverse of normalModeEnabled */
    val isUnhinged: Boolean = !normalModeEnabled,

    // ─── Feature Toggles ─────────────────────────────────────────────────────

    /** Enable anomaly system (screen tears, shadow passes, phantom books) */
    val anomaliesEnabled: Boolean = true,

    /** Enable whisper notifications (subtle cosmic horror messages) */
    val whispersEnabled: Boolean = true,

    /** Enable notification system (loot drops, achievements, milestones) */
    val notificationsEnabled: Boolean = true,

    /** Enable nuclear options (icon variants, NOTE.txt, status bar modifications) */
    val nuclearEnabled: Boolean = false,

    // ─── Modes ───────────────────────────────────────────────────────────────

    /** Copy mode for UI text variations */
    val copyMode: CopyMode = CopyMode.Unhinged,

    /** Notification intensity level */
    val notificationLevel: NotificationIntensity = NotificationIntensity.Standard,

    // ─── Session Tracking ────────────────────────────────────────────────────

    /** Total number of app launches */
    val sessionCount: Int = 0,

    /** Timestamp of last app launch (epoch millis) */
    val lastOpenedTimestamp: Long? = null,

    // ─── Fake Settings (persist but affect nothing) ─────────────────────────

    /** Membrane Permeability (0-10 scale) - does nothing */
    val membranePermeability: Int = 7,

    /** Temporal Alignment - does nothing */
    val temporalAlignment: Boolean = true,

    /** Steven Visibility (he's always watching) - does nothing */
    val stevenVisibility: Boolean = false,

    /** Shadow Staff Scheduling - does nothing */
    val shadowStaffScheduling: Boolean = true,

    // ─── Achievement Tracking ────────────────────────────────────────────────

    /** Set of unlocked achievement IDs */
    val unlockedAchievements: Set<String> = emptySet(),

    // ─── System Accessibility ────────────────────────────────────────────────

    /** System accessibility - should be read from system settings */
    val reduceMotionRequested: Boolean = false
) {
    /**
     * Session seed for deterministic randomization
     * Format: daysSinceEpoch_sessionCount
     * Example: "19745_42" for session 42 on day 19745
     */
    val sessionSeed: String
        get() = "${Instant.now().epochSecond / 86400}_$sessionCount"

    companion object {
        /**
         * Default instance with Unhinged mode DISABLED.
         * Safe fallback for when settings haven't been loaded yet.
         */
        val Default = UnhingedSettings(normalModeEnabled = true)

        // ─── DataStore Keys ──────────────────────────────────────────────────

        internal val NORMAL_MODE = booleanPreferencesKey("normal_mode")
        internal val ANOMALIES = booleanPreferencesKey("anomalies")
        internal val WHISPERS = booleanPreferencesKey("whispers")
        internal val NOTIFICATIONS = booleanPreferencesKey("notifications")
        internal val NUCLEAR = booleanPreferencesKey("nuclear")
        internal val COPY_MODE = stringPreferencesKey("copy_mode")
        internal val NOTIF_LEVEL = stringPreferencesKey("notif_level")
        internal val SESSION_COUNT = intPreferencesKey("session_count")
        internal val LAST_OPENED = longPreferencesKey("last_opened")
        internal val MEMBRANE = intPreferencesKey("membrane")
        internal val TEMPORAL = booleanPreferencesKey("temporal")
        internal val STEVEN = booleanPreferencesKey("steven")
        internal val SHADOW = booleanPreferencesKey("shadow")
        internal val ACHIEVEMENTS = stringSetPreferencesKey("achievements")

        /**
         * Deserialize from DataStore Preferences
         */
        fun fromPreferences(prefs: Preferences): UnhingedSettings {
            return UnhingedSettings(
                normalModeEnabled = prefs[NORMAL_MODE] ?: false,
                anomaliesEnabled = prefs[ANOMALIES] ?: true,
                whispersEnabled = prefs[WHISPERS] ?: true,
                notificationsEnabled = prefs[NOTIFICATIONS] ?: true,
                nuclearEnabled = prefs[NUCLEAR] ?: false,
                copyMode = try {
                    CopyMode.valueOf(prefs[COPY_MODE] ?: "Unhinged")
                } catch (e: IllegalArgumentException) {
                    CopyMode.Unhinged
                },
                notificationLevel = try {
                    NotificationIntensity.valueOf(prefs[NOTIF_LEVEL] ?: "Standard")
                } catch (e: IllegalArgumentException) {
                    NotificationIntensity.Standard
                },
                sessionCount = prefs[SESSION_COUNT] ?: 0,
                lastOpenedTimestamp = prefs[LAST_OPENED],
                membranePermeability = prefs[MEMBRANE] ?: 7,
                temporalAlignment = prefs[TEMPORAL] ?: true,
                stevenVisibility = prefs[STEVEN] ?: false,
                shadowStaffScheduling = prefs[SHADOW] ?: true,
                unlockedAchievements = prefs[ACHIEVEMENTS] ?: emptySet()
            )
        }

        /**
         * Create from existing AppSettings (for migration/compatibility)
         */
        fun fromAppSettings(
            unhingedThemeEnabled: Boolean,
            anomaliesEnabled: Boolean,
            whispersEnabled: Boolean,
            copyModeString: String,
            reduceMotionRequested: Boolean = false
        ): UnhingedSettings {
            val copyMode = when (copyModeString) {
                "Ritual" -> CopyMode.Ritual
                "Unhinged" -> CopyMode.Unhinged
                else -> CopyMode.Normal
            }

            return UnhingedSettings(
                normalModeEnabled = !unhingedThemeEnabled,
                anomaliesEnabled = anomaliesEnabled,
                whispersEnabled = whispersEnabled,
                copyMode = copyMode,
                reduceMotionRequested = reduceMotionRequested
            )
        }
    }
}

/**
 * Repository for Unhinged settings using Jetpack DataStore
 * Handles persistence and reactive updates
 */
class UnhingedSettingsRepository(private val context: Context) {

    /**
     * Reactive Flow of UnhingedSettings
     * Emits updates whenever settings change
     */
    val settingsFlow: Flow<UnhingedSettings> = context.unhingedDataStore.data
        .map { prefs -> UnhingedSettings.fromPreferences(prefs) }

    /**
     * Update settings using a transform function
     * Example: updateSettings { it.copy(normalModeEnabled = true) }
     */
    suspend fun updateSettings(transform: (UnhingedSettings) -> UnhingedSettings) {
        context.unhingedDataStore.edit { prefs ->
            val current = UnhingedSettings.fromPreferences(prefs)
            val updated = transform(current)

            prefs[UnhingedSettings.NORMAL_MODE] = updated.normalModeEnabled
            prefs[UnhingedSettings.ANOMALIES] = updated.anomaliesEnabled
            prefs[UnhingedSettings.WHISPERS] = updated.whispersEnabled
            prefs[UnhingedSettings.NOTIFICATIONS] = updated.notificationsEnabled
            prefs[UnhingedSettings.NUCLEAR] = updated.nuclearEnabled
            prefs[UnhingedSettings.COPY_MODE] = updated.copyMode.name
            prefs[UnhingedSettings.NOTIF_LEVEL] = updated.notificationLevel.name
            prefs[UnhingedSettings.SESSION_COUNT] = updated.sessionCount
            updated.lastOpenedTimestamp?.let { prefs[UnhingedSettings.LAST_OPENED] = it }
            prefs[UnhingedSettings.MEMBRANE] = updated.membranePermeability
            prefs[UnhingedSettings.TEMPORAL] = updated.temporalAlignment
            prefs[UnhingedSettings.STEVEN] = updated.stevenVisibility
            prefs[UnhingedSettings.SHADOW] = updated.shadowStaffScheduling
            prefs[UnhingedSettings.ACHIEVEMENTS] = updated.unlockedAchievements
        }
    }

    /**
     * Increment session count and update last opened timestamp
     * Call this in MainActivity.onCreate()
     */
    suspend fun incrementSession() {
        updateSettings { it.copy(
            sessionCount = it.sessionCount + 1,
            lastOpenedTimestamp = System.currentTimeMillis()
        )}
    }

    /**
     * Unlock an achievement
     * Returns true if newly unlocked, false if already had it
     */
    suspend fun unlockAchievement(achievementId: String): Boolean {
        var wasNewlyUnlocked = false
        updateSettings { current ->
            wasNewlyUnlocked = achievementId !in current.unlockedAchievements
            current.copy(
                unlockedAchievements = current.unlockedAchievements + achievementId
            )
        }
        return wasNewlyUnlocked
    }

    /**
     * Reset all unhinged settings to defaults (for testing)
     */
    suspend fun resetToDefaults() {
        context.unhingedDataStore.edit { it.clear() }
    }
}
