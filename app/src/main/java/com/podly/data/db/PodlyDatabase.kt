package com.podly.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        PodcastEntity::class,
        EpisodeEntity::class,
        PlaylistEntity::class,
        PlaylistItemEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class PodlyDatabase : RoomDatabase() {
    abstract fun podcastDao(): PodcastDao
    abstract fun episodeDao(): EpisodeDao
    abstract fun playlistDao(): PlaylistDao

    companion object {
        fun build(context: Context): PodlyDatabase =
            Room.databaseBuilder(context, PodlyDatabase::class.java, "podly.db")
                .fallbackToDestructiveMigration()
                .build()
    }
}
