package com.ninelivesaudio.app.review

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * When the review prompt was last requested.
 *
 * Its own file rather than a corner of AppSettings, because this is not a user
 * preference and has no business appearing in a settings export or a diagnostics
 * dump.
 *
 * Device-local. It rides Auto Backup like every other unexcluded plain pref,
 * which is the right call: someone who was asked on their old phone should not
 * be asked again the day they restore onto a new one.
 */
@Singleton
class ReviewPrefs @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    var lastAttemptAt: Long?
        get() = prefs.getLong(KEY_LAST_ATTEMPT, 0L).takeIf { it > 0L }
        set(value) {
            prefs.edit().putLong(KEY_LAST_ATTEMPT, value ?: 0L).apply()
        }

    companion object {
        const val FILE_NAME = "nine_lives_review"
        const val KEY_LAST_ATTEMPT = "last_attempt_at"
    }
}
