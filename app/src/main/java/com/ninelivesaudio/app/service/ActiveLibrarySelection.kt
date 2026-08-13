package com.ninelivesaudio.app.service

import com.ninelivesaudio.app.domain.model.AppMode
import com.ninelivesaudio.app.domain.model.AppSettings
import com.ninelivesaudio.app.domain.model.Library

internal data class ActiveLibrarySelection(
    val library: Library?,
    val settings: AppSettings,
    val requiresPersistence: Boolean,
)

internal fun resolveActiveLibrarySelection(
    libraries: List<Library>,
    settings: AppSettings,
): ActiveLibrarySelection {
    val localMode = settings.appMode == AppMode.LOCAL
    val eligibleLibraries = libraries.filter { it.isLocal == localMode }
    if (eligibleLibraries.isEmpty()) {
        return ActiveLibrarySelection(
            library = null,
            settings = settings,
            requiresPersistence = false,
        )
    }
    val selected = eligibleLibraries.firstOrNull { it.id == settings.activeLibraryId }
        ?: eligibleLibraries.firstOrNull()
    val selectedId = selected?.id
    val updatedSettings = when (settings.appMode) {
        AppMode.LOCAL -> settings.copy(selectedLocalLibraryId = selectedId)
        AppMode.AUDIOBOOKSHELF -> settings.copy(selectedLibraryId = selectedId)
    }

    return ActiveLibrarySelection(
        library = selected,
        settings = updatedSettings,
        requiresPersistence = updatedSettings != settings,
    )
}
