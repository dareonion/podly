package com.podly

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.podly.data.db.PodlyDatabase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * Rebuilds a real version-5 database from the exported schema JSON in
 * app/schemas, seeds it with the data a botched migration would cost the most
 * (playback progress, download state, listening history), then opens it through
 * the production `PodlyDatabase.build` path. Room runs the hand-written
 * migrations and validates every table — including index names — against the
 * current entities, so schema drift fails here instead of on a device.
 *
 * The schema JSON is read straight from the repo because AGP doesn't package
 * assets for JVM unit tests (which rules out Room's MigrationTestHelper).
 * Schema export began at version 5; when the DB version bumps, this test keeps
 * starting from the oldest schema a real install might still be on.
 */
// Plain Application: PodlyApp's onCreate spins up WorkManager, which these tests don't need.
@Config(application = Application::class)
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @Test
    fun v5DatabaseMigratesAndKeepsUserData() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        createSeededDatabaseAtVersion5(context)

        val db = PodlyDatabase.build(context)
        try {
            // First access triggers migrate + Room's schema validation.
            val sql = db.openHelper.readableDatabase

            sql.query("SELECT playbackPositionMs, downloadStatus, userNote FROM episodes WHERE id = 'e1'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(123_456L, c.getLong(0))
                assertEquals("DOWNLOADED", c.getString(1))
                assertEquals("great ep", c.getString(2))
            }
            // The new conditional-GET columns must start unset, not clobber rows.
            sql.query("SELECT subscribed, etag, lastModified FROM podcasts WHERE id = 'p1'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(1, c.getInt(0))
                assertTrue(c.isNull(1))
                assertTrue(c.isNull(2))
            }
            sql.query("SELECT COUNT(*) FROM listening_segments").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(1, c.getInt(0))
            }
        } finally {
            db.close()
        }
    }

    /** Executes the createSql statements from the checked-in 5.json, then seeds rows. */
    private fun createSeededDatabaseAtVersion5(context: Context) {
        val schema = Json.parseToJsonElement(schemaFile(5).readText())
            .jsonObject.getValue("database").jsonObject
        // Same file Room's builder will open: context.getDatabasePath("podly.db").
        val db = context.openOrCreateDatabase("podly.db", Context.MODE_PRIVATE, null)
        db.use {
            for (entity in schema.getValue("entities").jsonArray) {
                val obj = entity.jsonObject
                val table = obj.getValue("tableName").jsonPrimitive.content
                it.execSQL(obj.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                for (index in obj["indices"]?.jsonArray.orEmpty()) {
                    it.execSQL(index.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                }
            }
            it.execSQL(
                """INSERT INTO podcasts (id, title, author, feedUrl, subscribed, addedAt, episodeSortOrder)
                   VALUES ('p1', 'Show', 'Host', 'https://example.com/feed', 1, 42, 'NEWEST_FIRST')""",
            )
            it.execSQL(
                """INSERT INTO episodes (id, podcastId, podcastTitle, title, audioUrl, pubDateMs,
                       inLibrary, downloadStatus, autoDownloadBlocked, playbackPositionMs, completed,
                       lastPlayedAt, userNote)
                   VALUES ('e1', 'p1', 'Show', 'Ep 1', 'https://example.com/1.mp3', 7,
                       1, 'DOWNLOADED', 0, 123456, 0, 99, 'great ep')""",
            )
            it.execSQL(
                """INSERT INTO listening_segments (episodeId, startPositionMs, endPositionMs, startedAt, endedAt)
                   VALUES ('e1', 0, 60000, 1000, 61000)""",
            )
            it.version = 5
        }
    }

    /** Locates the exported schema JSON whether the test runs from app/ or the repo root. */
    private fun schemaFile(version: Int): File {
        val rel = "schemas/com.podly.data.db.PodlyDatabase/$version.json"
        return listOf(File(rel), File("app/$rel")).firstOrNull { it.exists() }
            ?: error("Exported schema $rel not found — is Room schema export still on?")
    }
}
