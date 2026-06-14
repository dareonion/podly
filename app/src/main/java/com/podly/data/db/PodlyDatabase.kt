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
        ListeningSegmentEntity::class,
        PlaylistEntity::class,
        PlaylistItemEntity::class,
    ],
    version = 3,
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

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE episodes ADD COLUMN userNote TEXT")
                db.execSQL("ALTER TABLE episodes ADD COLUMN userRating INTEGER")
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS listening_segments (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        episodeId TEXT NOT NULL,
                        startPositionMs INTEGER NOT NULL,
                        endPositionMs INTEGER NOT NULL,
                        startedAt INTEGER NOT NULL,
                        endedAt INTEGER NOT NULL
                    )"""
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_listening_segments_episodeId ON listening_segments(episodeId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_listening_segments_endedAt ON listening_segments(endedAt)")
            }
        }

        fun build(context: Context): PodlyDatabase =
            Room.databaseBuilder(context, PodlyDatabase::class.java, "podly.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
    }
}
