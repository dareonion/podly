package com.podly

import com.podly.data.db.PodcastCategories
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Feeds name their genre in the publisher's language. Every string asserted here
 * was read back from the iTunes API for a real show — the failure mode of a
 * guessed translation is that the filter silently matches nothing.
 */
class PodcastCategoriesTest {

    @Test
    fun `traditional Chinese kids genres map to the English ones`() {
        // These three are what 58 of the category rows on the real device held.
        assertTrue("kids & family" in PodcastCategories.normalize(listOf("兒童與家庭")))
        assertTrue("stories for kids" in PodcastCategories.normalize(listOf("兒童故事")))
        assertTrue("education for kids" in PodcastCategories.normalize(listOf("兒童教育")))
    }

    @Test
    fun `parenting keeps its exemption in Chinese too`() {
        val normalized = PodcastCategories.normalize(listOf("兒童與家庭", "子女教養"))
        assertTrue("kids & family" in normalized)
        assertTrue("parenting" in normalized)
    }

    @Test
    fun `Taiwan and mainland translate religion differently`() {
        assertTrue("religion & spirituality" in PodcastCategories.normalize(listOf("宗教與精神生活")))
        assertTrue("religion & spirituality" in PodcastCategories.normalize(listOf("宗教与心灵")))
        assertTrue("true crime" in PodcastCategories.normalize(listOf("犯罪紀實")))
        assertTrue("true crime" in PodcastCategories.normalize(listOf("犯罪纪实")))
    }

    @Test
    fun `the publisher's own wording is kept alongside the canonical name`() {
        assertEquals(listOf("兒童與家庭", "kids & family"), PodcastCategories.normalize(listOf("兒童與家庭")))
    }

    @Test
    fun `unknown names pass through lowercased, blanks are dropped`() {
        assertEquals(listOf("true crime"), PodcastCategories.normalize(listOf("True Crime", " ", "true crime")))
        assertEquals(listOf("休閒"), PodcastCategories.normalize(listOf("休閒")))
        assertTrue(PodcastCategories.normalize(listOf("", "   ")).isEmpty())
    }
}
