package com.podly

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.podly.data.db.PodcastEntity
import com.podly.data.db.PodlyDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** How category rows are written, which decides what the content filter can see. */
@Config(application = Application::class)
@RunWith(AndroidJUnit4::class)
class RadioCategoryWritesTest {

    private lateinit var db: PodlyDatabase

    @Before
    fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), PodlyDatabase::class.java,
        ).allowMainThreadQueries().build()
        db.podcastDao().insertIgnore(podcast("show", subscribed = false))
    }

    @After
    fun tearDown() = db.close()

    private fun podcast(id: String, subscribed: Boolean) = PodcastEntity(
        id = id, title = "show $id", author = "a", feedUrl = "https://f/$id",
        artworkUrl = null, description = null, subscribed = subscribed,
    )

    @Test
    fun `a partial source adds rather than narrowing what is known`() = runBlocking {
        val dao = db.podcastDao()
        dao.replaceCategories("show", listOf("Society & Culture", "Documentary", "True Crime"))
        // The pool ships only the first few of a show's Apple genres. Replacing
        // with those would drop the genre the show is being filtered on.
        dao.addCategories("show", listOf("Society & Culture", "Documentary"))
        assertTrue("true crime" in dao.categoriesFor("show"))
    }

    @Test
    fun `the feed remains authoritative and may remove a category`() = runBlocking {
        val dao = db.podcastDao()
        dao.replaceCategories("show", listOf("True Crime"))
        dao.replaceCategories("show", listOf("Comedy"))
        assertEquals(listOf("comedy"), dao.categoriesFor("show"))
    }

    @Test
    fun `renormalising adds aliases for rows written before they existed`() = runBlocking {
        val dao = db.podcastDao()
        // Simulate a row stored by a build that had no alias table.
        dao.insertCategories(
            listOf(com.podly.data.db.PodcastCategoryEntity("show", "兒童與家庭")),
        )
        assertTrue("kids & family" !in dao.categoriesFor("show"))
        assertEquals(1, dao.renormalizeCategories())
        assertTrue("kids & family" in dao.categoriesFor("show"))
        // Idempotent: running again adds nothing.
        assertEquals(0, dao.renormalizeCategories())
    }

    @Test
    fun `pruning a show takes its categories with it`() = runBlocking {
        val dao = db.podcastDao()
        dao.replaceCategories("show", listOf("True Crime"))
        dao.pruneOrphansAndCategories()
        // Categories have no foreign key, so without the explicit sweep the rows
        // would outlive the show — and be inherited by anything reusing the id.
        assertTrue(dao.categoriesFor("show").isEmpty())
        assertTrue(dao.allCategories().isEmpty())
    }
}
