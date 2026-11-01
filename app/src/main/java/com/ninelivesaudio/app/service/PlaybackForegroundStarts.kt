package com.ninelivesaudio.app.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Counts each time [PlaybackService] asks to run in the foreground (Media3
 * does that when playback starts). A widget's handoff screen waits for the
 * count to move, so the app still has a screen up at that moment, which
 * Android 12 and later require for a foreground service start.
 */
internal object PlaybackForegroundStarts {
    private val _count = MutableStateFlow(0L)
    val count: StateFlow<Long> = _count.asStateFlow()

    fun onForegroundStart() {
        _count.value = _count.value + 1
    }
}
