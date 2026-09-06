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
        RadioPoolEntity::class,
        RadioFeedbackEntity::class,
    ],
    version = 7,
    exportSchema = true,
)
abstract class PodlyDatabase : RoomDatabase() {
    abstract fun podcastDao(): PodcastDao
    abstract fun episodeDao(): EpisodeDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun radioDao(): RadioDao

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

        /**
         * Radio mode. No DEFAULT clauses in these CREATE TABLEs: Kotlin constructor
         * defaults are not @ColumnInfo(defaultValue = ...), so Room's expected schema
         * carries none and validateMigration would reject them on a migrated device.
         * Index names must match Room's generated `index_<table>_<column>` exactly.
         */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE listening_segments ADD COLUMN profileId TEXT")
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS radio_pool (
                        profileId TEXT NOT NULL,
                        episodeId TEXT NOT NULL,
                        source TEXT NOT NULL,
                        reason TEXT,
                        language TEXT,
                        priority REAL NOT NULL,
                        catalogVersion INTEGER NOT NULL,
                        addedAt INTEGER NOT NULL,
                        expiresAt INTEGER,
                        PRIMARY KEY(profileId, episodeId)
                    )"""
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_radio_pool_episodeId ON radio_pool(episodeId)")
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS radio_feedback (
                        profileId TEXT NOT NULL,
                        episodeId TEXT NOT NULL,
                        lastServedAt INTEGER NOT NULL,
                        serveCount INTEGER NOT NULL,
                        lastSkippedAt INTEGER NOT NULL,
                        skipCount INTEGER NOT NULL,
                        listenedMs INTEGER NOT NULL,
                        blockedUntil INTEGER NOT NULL,
                        PRIMARY KEY(profileId, episodeId)
                    )"""
                )
            }
        }

        internal val MIGRATIONS =
            arrayOf(
                MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4,
                MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7,
            )

        fun build(context: Context): PodlyDatabase =
            Room.databaseBuilder(context, PodlyDatabase::class.java, "podly.db")
                .addMigrations(*MIGRATIONS)
                .build()
    }
}
