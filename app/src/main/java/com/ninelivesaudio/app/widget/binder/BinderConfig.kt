package com.ninelivesaudio.app.widget.binder

import android.content.Context
import com.ninelivesaudio.app.ui.library.SortMode
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** What a tap on a Binder cover does. */
internal enum class BinderTap { PLAY, OPEN }

/** The two choices on the Binder's setup screen. Defaults: Play it (J1), recently played (design). */
internal data class BinderConfig(
    val tap: BinderTap = BinderTap.PLAY,
    val sort: SortMode = SortMode.RECENTLY_PLAYED,
)

internal fun encodeBinderConfig(config: BinderConfig): String = "${config.tap.name}|${config.sort.name}"

/** Unknown or missing parts fall back to the default one field at a time. */
internal fun decodeBinderConfig(raw: String?): BinderConfig {
    val parts = raw?.split('|').orEmpty()
    val default = BinderConfig()
    return BinderConfig(
        tap = parts.getOrNull(0)?.let { n -> BinderTap.entries.firstOrNull { it.name == n } } ?: default.tap,
        sort = parts.getOrNull(1)?.let { n -> SortMode.entries.firstOrNull { it.name == n } } ?: default.sort,
    )
}

/** Per widget setup choices, kept in SharedPreferences `widget_binder`. */
@Singleton
class BinderConfigStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences("widget_binder", Context.MODE_PRIVATE)

    internal fun get(appWidgetId: Int): BinderConfig =
        decodeBinderConfig(prefs.getString(key(appWidgetId), null))

    internal fun put(appWidgetId: Int, config: BinderConfig) {
        prefs.edit().putString(key(appWidgetId), encodeBinderConfig(config)).apply()
    }

    fun remove(appWidgetIds: IntArray) {
        val edit = prefs.edit()
        appWidgetIds.forEach { edit.remove(key(it)) }
        edit.apply()
    }

    private fun key(id: Int) = "config_$id"
}
