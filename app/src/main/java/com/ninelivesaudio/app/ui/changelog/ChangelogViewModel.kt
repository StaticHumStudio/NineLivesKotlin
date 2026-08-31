package com.ninelivesaudio.app.ui.changelog

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ninelivesaudio.app.BuildConfig
import com.ninelivesaudio.app.service.SettingsManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ChangelogViewModel @Inject constructor(
    private val settingsManager: SettingsManager,
) : ViewModel() {

    val lastSeenChangelogVersion: StateFlow<String> = settingsManager.settings
        .map { it.lastSeenChangelogVersion }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = settingsManager.currentSettings.lastSeenChangelogVersion,
        )

    fun markCurrentVersionSeen() {
        viewModelScope.launch {
            settingsManager.markChangelogVersionSeen(BuildConfig.VERSION_NAME)
        }
    }
}
