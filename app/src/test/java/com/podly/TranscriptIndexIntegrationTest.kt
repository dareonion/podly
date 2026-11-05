package com.podly

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.podly.data.SettingsRepository
import com.podly.data.TranscriptIndex
import com.podly.data.TranscriptRepository
import com.podly.data.db.EpisodeEntity
import com.podly.data.db.PodlyDatabase
import com.podly.data.transcriptCacheKey
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

/** Room compare-and-set and DataStore restart recovery require Android types. */
@Config(application = Application::class)
@RunWith(AndroidJUnit4::class)
class TranscriptIndexIntegrationTest {
    private lateinit var db: PodlyDatabase
    private lateinit var settings: SettingsRepository
    private val server = "https://h:8015/a/"
    @get:Rule val temporary = TemporaryFolder()

    @Before
    fun setUp() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(context, PodlyDatabase::class.java).allowMainThreadQueries().build()
        settings = SettingsRepository(context)
        settings.setTranscriptServer("")
        settings.finishTranscriptApply("")
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `matches exact ids replaces its own URL and preserves publisher and other servers`() = runBlocking<Unit> {
        val dao = db.episodeDao()
        dao.insertIgnore(listOf(
            episode("new"), episode("old", "${server}old.vtt"),
            episode("feed", "https://publisher/t.vtt"), episode("other", "https://other/t.vtt"),
            episode("same-guid").copy(guid = "missing"),
        ))
        settings.setTranscriptServer(server)
        val index = TranscriptIndex(dao, settings, fetch = { indexJson("new", "old", "feed", "other", "missing") })
        index.sync()
        assertEquals("${server}new/t.vtt", dao.byId("new")!!.transcriptUrl)
        assertEquals("${server}old/t.vtt", dao.byId("old")!!.transcriptUrl)
        assertEquals("text/vtt", dao.byId("old")!!.transcriptType)
        assertEquals("https://publisher/t.vtt", dao.byId("feed")!!.transcriptUrl)
        assertEquals("https://other/t.vtt", dao.byId("other")!!.transcriptUrl)
        assertNull(dao.byId("same-guid")!!.transcriptUrl)
        assertNull(dao.byId("missing"))
        assertEquals(4, index.status.value.matched)
        assertEquals(1, index.status.value.missing)
        assertEquals(server, settings.current().transcriptAppliedServer)
        assertEquals("", settings.current().transcriptPendingServer)
    }

    @Test
    fun `server change clears old directory but not neighbours and removal works after restart`() = runBlocking<Unit> {
        val dao = db.episodeDao()
        dao.insertIgnore(listOf(episode("one"), episode("ab", "https://h:8015/ab/t.vtt"), episode("port", "https://h:8016/a/t.vtt")))
        settings.setTranscriptServer(server)
        TranscriptIndex(dao, settings, fetch = { indexJson("one") }).sync()
        val newServer = "https://new:8015/"
        settings.setTranscriptServer(newServer)
        TranscriptIndex(dao, settings, fetch = { indexJson("one") }).sync()
        assertEquals("${newServer}one/t.vtt", dao.byId("one")!!.transcriptUrl)
        assertEquals(newServer, settings.current().transcriptAppliedServer)
        settings.setTranscriptServer("")
        TranscriptIndex(dao, settings, fetch = { error("Disabled must not fetch") }).sync()
        assertNull(dao.byId("one")!!.transcriptUrl)
        assertNull(dao.byId("one")!!.transcriptType)
        assertEquals("https://h:8015/ab/t.vtt", dao.byId("ab")!!.transcriptUrl)
        assertEquals("https://h:8016/a/t.vtt", dao.byId("port")!!.transcriptUrl)
        assertEquals("", settings.current().transcriptAppliedServer)
    }

    @Test
    fun `clears old server even when replacement is offline and recovers pending writes`() = runBlocking<Unit> {
        val dao = db.episodeDao()
        dao.insertIgnore(episode("one", "${server}one.vtt"))
        settings.finishTranscriptApply(server)
        val pending = "https://pending/"
        settings.beginTranscriptApply(pending)
        dao.insertIgnore(episode("partial", "${pending}t.vtt"))
        settings.setTranscriptServer("https://offline/")
        val index = TranscriptIndex(dao, settings, fetch = { throw IOException("Offline") })
        index.sync()
        assertNull(dao.byId("one")!!.transcriptUrl)
        assertNull(dao.byId("partial")!!.transcriptUrl)
        assertEquals("Offline", index.status.value.error)
        settings.setTranscriptServer("")
        index.sync()
        assertEquals("", settings.current().transcriptAppliedServer)
        assertEquals("", settings.current().transcriptPendingServer)
    }

    @Test
    fun `throttles fetches reapplies cache to new rows and page opens never refresh cached index`() = runBlocking<Unit> {
        val dao = db.episodeDao()
        var time = 0L
        var calls = 0
        settings.setTranscriptServer(server)
        val index = TranscriptIndex(dao, settings, fetch = {
            calls++
            assertEquals("${server}index.json", it)
            indexJson("one", "two")
        }, elapsedMs = { time })
        index.sync()
        dao.insertIgnore(episode("one"))
        index.sync()
        assertEquals(1, calls)
        assertEquals("${server}one/t.vtt", dao.byId("one")!!.transcriptUrl)
        time = 60_000
        dao.insertIgnore(episode("two"))
        index.applyToEpisode("two")
        assertEquals(1, calls)
        assertEquals("${server}two/t.vtt", dao.byId("two")!!.transcriptUrl)
        index.sync()
        assertEquals(2, calls)
        index.sync(force = true)
        assertEquals(3, calls)
    }

    @Test
    fun `failed fetch retains useful cache and rate limits failures`() = runBlocking<Unit> {
        val dao = db.episodeDao()
        var calls = 0
        var time = 0L
        settings.setTranscriptServer(server)
        val index = TranscriptIndex(dao, settings, fetch = {
            calls++
            if (calls > 1) throw IOException("Offline")
            indexJson("one")
        }, elapsedMs = { time })
        index.sync()
        time = 60_000
        dao.insertIgnore(episode("one"))
        index.sync()
        assertEquals("Offline", index.status.value.error)
        assertNotNull(index.status.value.lastSyncMs)
        assertEquals("${server}one/t.vtt", dao.byId("one")!!.transcriptUrl)
        index.sync()
        assertEquals(2, calls)
    }

    @Test
    fun `feed refresh supersedes injected transcript and stale compare and set cannot undo it`() = runBlocking<Unit> {
        val dao = db.episodeDao()
        dao.insertIgnore(episode("one", "${server}old.vtt"))
        dao.upsertFromFeed(listOf(episode("one")))
        assertEquals("${server}old.vtt", dao.byId("one")!!.transcriptUrl)
        dao.upsertFromFeed(listOf(episode("one", "https://feed/t.vtt")))
        assertEquals(0, dao.replaceTranscript("one", "${server}old.vtt", "${server}new.vtt", "text/vtt"))
        assertEquals(0, dao.replaceTranscript("one", "${server}old.vtt", null, null))
        assertEquals("https://feed/t.vtt", dao.byId("one")!!.transcriptUrl)
    }

    @Test
    fun `cache loads new URL body and forget deletes that version`() = runBlocking<Unit> {
        val dao = db.episodeDao()
        val cache = temporary.newFolder()
        val dir = File(cache, "transcripts").apply { mkdirs() }
        val oldUrl = "${server}old.vtt"
        val newUrl = "${server}new.vtt"
        val oldFile = File(dir, transcriptCacheKey("one", oldUrl)).apply { writeText("Old body") }
        dao.insertIgnore(episode("one", oldUrl))
        val repository = TranscriptRepository(dao, cache)
        assertEquals("Old body", (repository.transcript("one") as TranscriptRepository.Result.Ready).cues.single().text)
        dao.replaceTranscript("one", oldUrl, newUrl, "text/plain")
        // Stands in for the new version's fetch, which happens only once it is current.
        val newFile = File(dir, transcriptCacheKey("one", newUrl)).apply { writeText("New body") }
        assertEquals("New body", (repository.transcript("one") as TranscriptRepository.Result.Ready).cues.single().text)
        // Loading the new version prunes the superseded one, so regeneration can't pile up files.
        assertTrue(!oldFile.exists())
        repository.forget("one")
        assertTrue(!newFile.exists())
    }

    private fun indexJson(vararg ids: String) =
        """{"entries":[${ids.joinToString(",") { """{"id":"$it","url":"$it/t.vtt"}""" }}]}"""

    private fun episode(id: String, url: String? = null) = EpisodeEntity(
        id = id, podcastId = "show", podcastTitle = "Show", guid = id, title = id,
        description = null, audioUrl = "https://audio/$id.mp3", pubDateMs = 0, durationMs = null,
        artworkUrl = null, transcriptUrl = url, transcriptType = if (url == null) null else "text/plain",
    )
}
