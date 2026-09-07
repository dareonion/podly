package com.podly.network

/**
 * What someone pasted into the search box.
 *
 * Every URL used to be treated as a feed, so an Apple Podcasts link produced a
 * subscription whose "feed" was an HTML page: it looked like it worked and then
 * never parsed. Classifying first is what makes the failure impossible.
 */
sealed interface PodcastLink {

    /** A feed to fetch as-is. */
    data class Feed(val url: String) : PodcastLink

    /**
     * An Apple Podcasts show, to resolve through the directory.
     *
     * [episodeId] is set when the link points at one episode — Apple's share
     * sheet does that from an episode page. The show id is in the path either
     * way, so the show still resolves; the episode id is kept because the link
     * says which episode was meant.
     */
    data class Apple(val collectionId: String, val episodeId: String? = null) : PodcastLink

    companion object {
        private val APPLE_HOSTS = setOf(
            "podcasts.apple.com", "itunes.apple.com", "www.podcasts.apple.com",
        )

        /** `…/id1200361736`, or `…?id=1200361736` on the older itunes.com form. */
        private val COLLECTION_ID = Regex("""/id(\d+)""")
        private val COLLECTION_ID_PARAM = Regex("""[?&]id=(\d+)""")
        private val EPISODE_ID_PARAM = Regex("""[?&]i=(\d+)""")

        /**
         * Null when [input] is not a link at all, which means it is a search term.
         *
         * A URL that is not recognisably Apple's is treated as a feed rather than
         * rejected: plenty of feeds live on hosts nobody has heard of, and the
         * fetch is the only honest test of whether one is a feed.
         */
        fun parse(input: String): PodcastLink? {
            val trimmed = input.trim()
            if (!trimmed.startsWith("http://", ignoreCase = true) &&
                !trimmed.startsWith("https://", ignoreCase = true)
            ) {
                return null
            }
            val host = hostOf(trimmed) ?: return null
            if (host.lowercase() !in APPLE_HOSTS) return Feed(trimmed)
            val collectionId =
                // The last one: Apple puts the id in the final path segment, and a
                // slug is free to contain something that looks like an earlier one.
                COLLECTION_ID.findAll(trimmed).lastOrNull()?.groupValues?.get(1)
                    ?: COLLECTION_ID_PARAM.find(trimmed)?.groupValues?.get(1)
                    // An Apple link with no id is a browse or search page, not a
                    // show. Falling back to Feed would subscribe to an HTML page.
                    ?: return null
            return Apple(collectionId, EPISODE_ID_PARAM.find(trimmed)?.groupValues?.get(1))
        }

        private fun hostOf(url: String): String? =
            url.substringAfter("://", "")
                .substringBefore('/')
                .substringBefore('?')
                .substringAfter('@')       // strip any userinfo
                .substringBefore(':')      // and any port
                .ifEmpty { null }
    }
}
