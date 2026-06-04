package com.ninelivesaudio.app.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ninelivesaudio.app.domain.model.AppMode
import com.ninelivesaudio.app.service.SettingsManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class WelcomeViewModel @Inject constructor(
    private val settingsManager: SettingsManager,
) : ViewModel() {

    /** Persist the first-run source choice and mark onboarding complete. */
    fun choose(mode: AppMode) {
        viewModelScope.launch {
            settingsManager.updateSettings { applyOnboardingChoice(it, mode) }
        }
    }
}
