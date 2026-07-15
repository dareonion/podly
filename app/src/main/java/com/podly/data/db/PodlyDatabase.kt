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
    version = 6,
    exportSchema = true,
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

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE podcasts ADD COLUMN episodeSortOrder TEXT NOT NULL DEFAULT 'NEWEST_FIRST'")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE episodes ADD COLUMN autoDownloadBlocked INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE podcasts ADD COLUMN etag TEXT")
                db.execSQL("ALTER TABLE podcasts ADD COLUMN lastModified TEXT")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_podcasts_subscribed ON podcasts(subscribed)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_episodes_inLibrary ON episodes(inLibrary)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_episodes_downloadStatus ON episodes(downloadStatus)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_episodes_lastPlayedAt ON episodes(lastPlayedAt)")
            }
        }

        fun build(context: Context): PodlyDatabase =
            Room.databaseBuilder(context, PodlyDatabase::class.java, "podly.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                .build()
    }
}
