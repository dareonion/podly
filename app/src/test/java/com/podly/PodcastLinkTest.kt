package com.podly

import com.podly.network.PodcastLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** URL shapes are where guessing fails quietly, so each form is pinned here. */
class PodcastLinkTest {

    @Test
    fun `an apple show link yields its collection id`() {
        assertEquals(
            PodcastLink.Apple("1200361736"),
            PodcastLink.parse("https://podcasts.apple.com/us/podcast/the-daily/id1200361736"),
        )
    }

    @Test
    fun `an apple episode link still resolves the show`() {
        // Apple's share sheet produces this from an episode page; the show id is
        // in the path, so sharing an episode subscribes you to the right show.
        val link = PodcastLink.parse(
            "https://podcasts.apple.com/tw/podcast/%E6%95%85%E4%BA%8Bfm/id1097581683?i=1000654321",
        )
        assertEquals(PodcastLink.Apple("1097581683", "1000654321"), link)
    }

    @Test
    fun `the older itunes forms work too`() {
        assertEquals(
            PodcastLink.Apple("1200361736"),
            PodcastLink.parse("https://itunes.apple.com/gb/podcast/whatever/id1200361736"),
        )
        assertEquals(
            PodcastLink.Apple("1200361736"),
            PodcastLink.parse("https://itunes.apple.com/lookup?id=1200361736"),
        )
    }

    @Test
    fun `an apple link with no show in it is not a subscribe`() {
        // Browse and search pages have no id; treating one as a feed would
        // subscribe to an HTML page that can never parse.
        assertNull(PodcastLink.parse("https://podcasts.apple.com/us/browse"))
        assertNull(PodcastLink.parse("https://podcasts.apple.com/us/search?term=news"))
    }

    @Test
    fun `any other URL is taken as a feed`() {
        assertEquals(
            PodcastLink.Feed("https://feeds.megaphone.fm/coerced"),
            PodcastLink.parse("https://feeds.megaphone.fm/coerced"),
        )
        // http is kept as pasted; the fetch upgrades it.
        assertEquals(
            PodcastLink.Feed("http://feeds.pbs.org/x"),
            PodcastLink.parse("  http://feeds.pbs.org/x  "),
        )
    }

    @Test
    fun `a slug that looks like an id does not win over the real one`() {
        assertEquals(
            PodcastLink.Apple("1200361736"),
            PodcastLink.parse("https://podcasts.apple.com/us/podcast/id1-and-id42/id1200361736?uo=4"),
        )
    }

    @Test
    fun `apple's own share parameters are ignored`() {
        // Real share URLs carry ?uo=4, which must not be read as an episode id.
        assertEquals(
            PodcastLink.Apple("1256399960"),
            PodcastLink.parse("https://podcasts.apple.com/tw/podcast/%E6%95%85%E4%BA%8Bfm/id1256399960?uo=4"),
        )
    }

    @Test
    fun `a search term is not a link`() {
        assertNull(PodcastLink.parse("the daily"))
        assertNull(PodcastLink.parse("podcasts.apple.com/us/podcast/id123"))
        assertNull(PodcastLink.parse(""))
    }

    @Test
    fun `a lookalike host is not Apple`() {
        // podcasts.apple.com.evil.example is not Apple, and must not be handed to
        // the directory as though its path held a collection id.
        assertEquals(
            PodcastLink.Feed("https://podcasts.apple.com.evil.example/us/podcast/id1200361736"),
            PodcastLink.parse("https://podcasts.apple.com.evil.example/us/podcast/id1200361736"),
        )
    }
}
