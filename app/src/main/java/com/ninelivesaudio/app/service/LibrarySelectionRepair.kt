package com.ninelivesaudio.app.service

import com.ninelivesaudio.app.domain.model.AppSettings
import com.ninelivesaudio.app.domain.model.Library

internal fun repairedServerLibraryId(
    selectedLibraryId: String?,
    libraries: List<Library>,
): String? {
    val selectedLibrary = libraries.firstOrNull { it.id == selectedLibraryId }
    return if (selectedLibrary?.isLocal == true) {
        libraries.firstOrNull { !it.isLocal }?.id
    } else {
        selectedLibraryId
    }
}

internal fun repairedLibrarySelections(
    settings: AppSettings,
    libraries: List<Library>,
): AppSettings = settings.copy(
    selectedLibraryId = repairedServerLibraryId(
        selectedLibraryId = settings.selectedLibraryId,
        libraries = libraries,
    ),
)
