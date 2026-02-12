package com.ninelivesaudio.app.di

import android.content.Context
import com.ninelivesaudio.app.settings.unhinged.UnhingedSettingsRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module for Unhinged Mode dependencies
 * Provides UnhingedSettingsRepository as a singleton
 */
@Module
@InstallIn(SingletonComponent::class)
object UnhingedModule {

    /**
     * Provides UnhingedSettingsRepository singleton
     * Uses DataStore for persistent settings storage
     */
    @Provides
    @Singleton
    fun provideUnhingedSettingsRepository(
        @ApplicationContext context: Context
    ): UnhingedSettingsRepository {
        return UnhingedSettingsRepository(context)
    }
}
