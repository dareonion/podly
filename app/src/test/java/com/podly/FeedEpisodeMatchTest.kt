package com.podly

import com.podly.data.chat.FeedEpisodeMatch
import com.podly.data.db.EpisodeEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/** A pick whose link names one episode only ever gets that episode. */
class FeedEpisodeMatchTest {

    private fun dayMs(iso: String) = LocalDate.parse(iso).toEpochDay() * 86_400_000L

    private fun ep(id: String, title: String, guid: String?, date: String) = EpisodeEntity(
        id = id, podcastId = "p", podcastTitle = "說故事的人", guid = guid, title = title, description = null,
        audioUrl = "https://example.com/$id.mp3", pubDateMs = dayMs(date), durationMs = null, artworkUrl = null,
    )

    // 疫情下的人's real GUID; pod.link's id for it is its MD5 (checked against the live feed).
    private val pandemic = ep("pandemic", "疫情下的人", "2c766ca5-9f70-43d1-a00e-72f24d8dd94a", "2021-05-23")
    private val exile = ep("exile", "逃亡的人", "guid-exile", "2021-05-30")

    @Test
    fun `a GUID or its hash finds the episode whatever its title`() {
        val feed = listOf(exile, pandemic)
        assertEquals("pandemic", FeedEpisodeMatch.find(feed, "EP8", null, null, guid = pandemic.guid))
        assertEquals(
            "pandemic",
            FeedEpisodeMatch.find(feed, "說故事的人 EP8：疫情下的人", null, "說故事的人", guidMd5 = "fac7363035378121645ea6e9d6b503ca"),
        )
    }

    @Test
    fun `a same-titled episode with another GUID is a different episode`() {
        val rerun = ep("rerun", "疫情下的人", "guid-rerun", "2024-01-01")
        val feed = listOf(exile, rerun)
        // Apple dates its episode 2021; the feed's same-titled one is from 2024.
        assertNull(FeedEpisodeMatch.find(feed, "疫情下的人", null, null, guid = pandemic.guid, appleReleaseMs = dayMs("2021-05-23")))
        // pod.link's hash is not the GUID's MD5 on every host (在人间's ximalaya feed): one
        // that matches nothing leaves the title to decide.
        assertEquals("rerun", FeedEpisodeMatch.find(feed, "疫情下的人", null, null, guidMd5 = "fac7363035378121645ea6e9d6b503ca"))
        // A feed that rewrote its GUIDs: same title, same day.
        assertEquals(
            "rerun",
            FeedEpisodeMatch.find(feed, "疫情下的人", null, null, guid = pandemic.guid, appleReleaseMs = dayMs("2024-01-01")),
        )
    }

    @Test
    fun `without an identity the title decides`() {
        assertEquals("exile", FeedEpisodeMatch.find(listOf(pandemic, exile), "說故事的人：逃亡的人", null, "說故事的人"))
    }

    @Test
    fun `a rewritten GUID needs Apple's exact title, not just its day`() {
        val near = ep("near", "疫情下的人 番外", "guid-near", "2021-05-23")
        val same = ep("same", "疫情下的人", "guid-same", "2021-05-24")
        val rerun = ep("rerun", "疫情下的人", "guid-rerun", "2024-01-01")
        val apple = dayMs("2021-05-23")
        assertNull(FeedEpisodeMatch.find(listOf(near), "疫情下的人", null, null, guid = pandemic.guid, appleReleaseMs = apple))
        assertEquals("same", FeedEpisodeMatch.find(listOf(rerun, same), "疫情下的人", null, null, guid = pandemic.guid, appleReleaseMs = apple))
    }
}
