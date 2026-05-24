package com.ninelivesaudio.app.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class AppMode {
    AUDIOBOOKSHELF,
    LOCAL,
}
