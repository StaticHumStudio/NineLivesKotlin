package com.ninelivesaudio.app.di

import android.content.Context
import androidx.room.Room
import com.ninelivesaudio.app.data.local.AppDatabase
import com.ninelivesaudio.app.data.local.dao.*
import com.ninelivesaudio.app.data.local.migration.ALL_MIGRATIONS
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            AppDatabase.DATABASE_NAME,
        )
        .addMigrations(*ALL_MIGRATIONS)
        .fallbackToDestructiveMigrationOnDowngrade()
        .build()
    }

    @Provides
    fun provideLibraryDao(db: AppDatabase): LibraryDao = db.libraryDao()

    @Provides
    fun provideAudioBookDao(db: AppDatabase): AudioBookDao = db.audioBookDao()

    @Provides
    fun provideDownloadItemDao(db: AppDatabase): DownloadItemDao = db.downloadItemDao()

    @Provides
    fun providePlaybackProgressDao(db: AppDatabase): PlaybackProgressDao = db.playbackProgressDao()

    @Provides
    fun providePendingProgressDao(db: AppDatabase): PendingProgressDao = db.pendingProgressDao()
}
