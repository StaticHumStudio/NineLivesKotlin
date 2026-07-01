package com.ninelivesaudio.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.ninelivesaudio.app.data.local.dao.*
import com.ninelivesaudio.app.data.local.entity.*

@Database(
    entities = [
        LibraryEntity::class,
        AudioBookEntity::class,
        DownloadItemEntity::class,
        PlaybackProgressEntity::class,
        PendingProgressEntity::class,
        LocalListeningSessionEntity::class,
        LocalBookmarkEntity::class,
    ],
    version = 7,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun libraryDao(): LibraryDao
    abstract fun audioBookDao(): AudioBookDao
    abstract fun downloadItemDao(): DownloadItemDao
    abstract fun playbackProgressDao(): PlaybackProgressDao
    abstract fun pendingProgressDao(): PendingProgressDao
    abstract fun localListeningSessionDao(): LocalListeningSessionDao
    abstract fun localBookmarkDao(): LocalBookmarkDao

    companion object {
        const val DATABASE_NAME = "audiobookshelf.db"
    }
}
