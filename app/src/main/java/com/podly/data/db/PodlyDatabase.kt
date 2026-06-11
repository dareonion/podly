package com.podly.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        PodcastEntity::class,
        EpisodeEntity::class,
        PlaylistEntity::class,
        PlaylistItemEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class PodlyDatabase : RoomDatabase() {
    abstract fun podcastDao(): PodcastDao
    abstract fun episodeDao(): EpisodeDao
    abstract fun playlistDao(): PlaylistDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE episodes ADD COLUMN lastPlayedAt INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun build(context: Context): PodlyDatabase =
            Room.databaseBuilder(context, PodlyDatabase::class.java, "podly.db")
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
