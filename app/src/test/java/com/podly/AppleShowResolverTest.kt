package com.podly

import com.podly.data.AppleShow
import com.podly.data.AppleShowResolver
import com.podly.data.FeedDirectory
import com.podly.data.db.stableId
import com.podly.data.problem
import com.podly.network.AppleListing
import com.podly.network.DirectoryFeed
import com.podly.network.ItunesApi
import com.podly.network.PodcastIndexApi
import com.podly.network.TaddyApi
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleShowResolverTest {

    private class FakeDirectory(
        override val name: String,
        private val configured: Boolean = true,
        private val feed: DirectoryFeed? = null,
    ) : FeedDirectory {
        val asked = mutableListOf<String>()
        override suspend fun isConfigured() = configured
        override suspend fun feedForAppleId(appleId: String): DirectoryFeed? {
            asked += appleId
            return feed
        }
    }

    private val hidden = AppleListing(
        title = "School's In",
        author = "Stanford Graduate School of Education",
        artworkUrl = "https://is1-ssl.mzstatic.com/school.jpg",
        feedUrl = null,
    )
    private val captivate = DirectoryFeed(
        feedUrl = "https://feeds.captivate.fm/schools-in/",
        title = "School's In",
    )

    private fun resolver(listing: AppleListing?, vararg directories: FeedDirectory) =
        AppleShowResolver({ listing }, directories.toList(), log = {})

    @Test
    fun `apple's own feed is used without asking anyone else`() = runBlocking {
        val directory = FakeDirectory("PodcastIndex", feed = captivate)
        val listing = hidden.copy(feedUrl = "https://example.com/feed.xml")
        val show = resolver(listing, directory).resolve("1239888602")
        assertEquals("https://example.com/feed.xml", (show as AppleShow.Found).podcast.feedUrl)
        assertTrue(directory.asked.isEmpty())
    }

    @Test
    fun `a hidden feed comes from the first directory that has it, with apple's details`() = runBlocking {
        val miss = FakeDirectory("PodcastIndex")
        val hit = FakeDirectory("Taddy", feed = captivate)
        val podcast = (resolver(hidden, miss, hit).resolve("1239888602") as AppleShow.Found).podcast
        assertEquals("https://feeds.captivate.fm/schools-in/", podcast.feedUrl)
        assertEquals(stableId("https://feeds.captivate.fm/schools-in/"), podcast.id)
        assertEquals("Stanford Graduate School of Education", podcast.author)
        assertEquals("https://is1-ssl.mzstatic.com/school.jpg", podcast.artworkUrl)
        assertEquals(listOf("1239888602"), miss.asked)
    }

    @Test
    fun `unconfigured directories are skipped and the message says how to fix it`() = runBlocking {
        val off = FakeDirectory("PodcastIndex", configured = false, feed = captivate)
        val show = resolver(hidden, off).resolve("1239888602")
        assertEquals(AppleShow.FeedHidden("School's In", asked = emptyList()), show)
        assertTrue(off.asked.isEmpty())
        val message = show.problem()!!
        assertTrue(message, "doesn't share its feed" in message)
        assertTrue(message, "Settings" in message)
    }

    @Test
    fun `when every directory misses the message names them`() = runBlocking {
        val show = resolver(hidden, FakeDirectory("PodcastIndex"), FakeDirectory("Taddy"))
            .resolve("1239888602")
        assertEquals(AppleShow.FeedHidden("School's In", listOf("PodcastIndex", "Taddy")), show)
        assertTrue(show.problem()!!, "PodcastIndex and Taddy couldn't find it" in show.problem()!!)
    }

    @Test
    fun `directories are still asked when apple has no listing`() = runBlocking {
        val podcast = (resolver(null, FakeDirectory("Taddy", feed = captivate)).resolve("1") as AppleShow.Found)
            .podcast
        assertEquals("School's In", podcast.title)
        assertEquals(AppleShow.NotFound, resolver(null, FakeDirectory("Taddy")).resolve("1"))
        assertNull(AppleShow.Found(podcast).problem())
    }

    // The real lookup body for School's In (id 1239888602, 2026-10-04), trimmed to
    // the fields the parser reads: Apple lists the show and leaves out feedUrl.
    @Test
    fun `apple listing without a feed parses with a null feed`() {
        val body = """
            {"resultCount":1,"results":[{"wrapperType":"track","kind":"podcast",
             "collectionId":1239888602,"artistName":"Stanford Graduate School of Education",
             "collectionName":"School's In","trackCount":188,
             "artworkUrl100":"https://is1-ssl.mzstatic.com/image/thumb/Podcasts221/v4/26/ce/0d/26ce0db9-ef08-a365-14b4-3ac68d7af288/mza_6554089682927077659.jpg/100x100bb.jpg",
             "artworkUrl600":"https://is1-ssl.mzstatic.com/image/thumb/Podcasts221/v4/26/ce/0d/26ce0db9-ef08-a365-14b4-3ac68d7af288/mza_6554089682927077659.jpg/600x600bb.jpg",
             "primaryGenreName":"Education","genres":["Education","Podcasts"]}]}
        """.trimIndent()
        val listing = ItunesApi.parseListing(body)!!
        assertEquals("School's In", listing.title)
        assertEquals("Stanford Graduate School of Education", listing.author)
        assertNull(listing.feedUrl)
        assertTrue(listing.artworkUrl!!.endsWith("600x600bb.jpg"))
    }

    @Test
    fun `apple listing with a feed keeps it`() {
        // Stanford Legal (id 1446979113), a sibling show whose feed Apple does share.
        val body = """
            {"resultCount":1,"results":[{"collectionName":"Stanford Legal",
             "artistName":"Stanford Law School","feedUrl":"https://feeds.simplecast.com/_45D2qy7"}]}
        """.trimIndent()
        assertEquals("https://feeds.simplecast.com/_45D2qy7", ItunesApi.parseListing(body)!!.feedUrl)
        assertNull(ItunesApi.parseListing("""{"resultCount":0,"results":[]}"""))
    }

    @Test
    fun `podcastindex byitunesid reads the feed object and treats anything else as a miss`() {
        val found = PodcastIndexApi.parseFeedByItunesId(
            """
            {"status":"true","query":{"id":"1239888602"},"feed":{"id":75075,
             "title":"School's In","url":"https://feeds.captivate.fm/schools-in/",
             "author":"Stanford Graduate School of Education","image":"https://img/i.jpg",
             "artwork":"https://img/a.jpg","itunesId":1239888602},"description":"Found matching feed"}
            """.trimIndent(),
        )!!
        assertEquals("https://feeds.captivate.fm/schools-in/", found.feedUrl)
        assertEquals("School's In", found.title)
        assertEquals("https://img/a.jpg", found.artworkUrl)
        assertNull(PodcastIndexApi.parseFeedByItunesId("""{"status":"true","feed":[],"description":"No feeds match"}"""))
        assertNull(PodcastIndexApi.parseFeedByItunesId("""{"status":"true","description":"No feeds match"}"""))
        assertNull(PodcastIndexApi.parseFeedByItunesId("""{"status":"true","feed":{"id":1,"url":""}}"""))
    }

    @Test
    fun `taddy looks up itunes ids as Float and asks for no episodes`() {
        val query = TaddyApi.query("itunesId", "Float", episodeLimit = 0)
        assertTrue(query, "query(\$v:Float!){getPodcastSeries(itunesId:\$v)" in query)
        assertTrue(query, "episodes" !in query)
    }

    @Test
    fun `taddy feed and name lookups send the query they always did`() {
        assertEquals(
            "query(\$v:String!){getPodcastSeries(rssUrl:\$v){uuid name rssUrl " +
                "episodes(sortOrder:LATEST,limitPerPage:25){" +
                "uuid name description audioUrl datePublished duration guid imageUrl}}}",
            TaddyApi.query("rssUrl", "String", episodeLimit = 25),
        )
    }
}
