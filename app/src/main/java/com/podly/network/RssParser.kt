package com.podly.network

import com.podly.data.db.EpisodeEntity
import com.podly.data.db.PodcastEntity
import com.podly.data.db.stableId
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.Reader
import java.text.SimpleDateFormat
import java.util.Locale

data class ParsedFeed(
    val title: String?,
    val author: String?,
    val description: String?,
    val imageUrl: String?,
    val episodes: List<ParsedEpisode>,
)

data class ParsedEpisode(
    val guid: String?,
    val title: String,
    val description: String?,
    val audioUrl: String,
    val pubDateMs: Long,
    val durationMs: Long?,
    val imageUrl: String?,
)

/**
 * Minimal RSS 2.0 + itunes-namespace parser. Namespace-unaware on purpose:
 * tag names are matched on their prefixed form as commonly emitted by podcast hosts.
 */
class RssParser {

    fun parse(reader: Reader): ParsedFeed {
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(reader)

        var channelTitle: String? = null
        var channelAuthor: String? = null
        var channelDescription: String? = null
        var channelImage: String? = null
        val episodes = mutableListOf<ParsedEpisode>()

        var inItem = false
        var itemTitle: String? = null
        var itemGuid: String? = null
        var itemDescription: String? = null
        var itemAudioUrl: String? = null
        var itemPubDate: String? = null
        var itemDuration: String? = null
        var itemImage: String? = null

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val tag = parser.name.lowercase(Locale.ROOT)
                    when {
                        tag == "item" -> {
                            inItem = true
                            itemTitle = null; itemGuid = null; itemDescription = null
                            itemAudioUrl = null; itemPubDate = null; itemDuration = null; itemImage = null
                        }
                        inItem -> when (tag) {
                            "title" -> itemTitle = parser.nextTextSafe()
                            "guid" -> itemGuid = parser.nextTextSafe()
                            "description" -> if (itemDescription == null) itemDescription = parser.nextTextSafe()
                            "itunes:summary" -> if (itemDescription == null) itemDescription = parser.nextTextSafe()
                            "content:encoded" -> if (itemDescription == null) itemDescription = parser.nextTextSafe()
                            "enclosure" -> {
                                val type = parser.getAttributeValue(null, "type") ?: ""
                                val url = parser.getAttributeValue(null, "url")
                                if (itemAudioUrl == null && url != null && (type.startsWith("audio") || type.isEmpty())) {
                                    itemAudioUrl = url
                                }
                            }
                            "pubdate" -> itemPubDate = parser.nextTextSafe()
                            "itunes:duration" -> itemDuration = parser.nextTextSafe()
                            "itunes:image" -> itemImage = parser.getAttributeValue(null, "href") ?: itemImage
                        }
                        else -> when (tag) {
                            "title" -> if (channelTitle == null) channelTitle = parser.nextTextSafe()
                            "itunes:author" -> channelAuthor = parser.nextTextSafe()
                            "description" -> if (channelDescription == null) channelDescription = parser.nextTextSafe()
                            "itunes:image" -> channelImage = parser.getAttributeValue(null, "href") ?: channelImage
                            "url" -> if (channelImage == null) channelImage = parser.nextTextSafe()
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name.lowercase(Locale.ROOT) == "item") {
                        inItem = false
                        val audioUrl = itemAudioUrl
                        val title = itemTitle
                        if (audioUrl != null && title != null) {
                            episodes += ParsedEpisode(
                                guid = itemGuid,
                                title = title,
                                description = itemDescription,
                                audioUrl = audioUrl,
                                pubDateMs = parseRfc822(itemPubDate),
                                durationMs = parseDuration(itemDuration),
                                imageUrl = itemImage,
                            )
                        }
                    }
                }
            }
            event = parser.next()
        }

        return ParsedFeed(channelTitle, channelAuthor, channelDescription, channelImage, episodes)
    }

    private fun XmlPullParser.nextTextSafe(): String? = try {
        nextText().trim().ifEmpty { null }
    } catch (e: Exception) {
        null
    }

    companion object {
        private val RFC822_PATTERNS = listOf(
            "EEE, dd MMM yyyy HH:mm:ss Z",
            "EEE, dd MMM yyyy HH:mm:ss zzz",
            "EEE, dd MMM yyyy HH:mm Z",
            "dd MMM yyyy HH:mm:ss Z",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
        )

        fun parseRfc822(text: String?): Long {
            if (text.isNullOrBlank()) return 0L
            for (pattern in RFC822_PATTERNS) {
                try {
                    return SimpleDateFormat(pattern, Locale.US).parse(text.trim())!!.time
                } catch (_: Exception) {
                }
            }
            return 0L
        }

        /** Accepts "HH:MM:SS", "MM:SS", or plain seconds. */
        fun parseDuration(text: String?): Long? {
            if (text.isNullOrBlank()) return null
            val parts = text.trim().split(":")
            return try {
                val seconds = when (parts.size) {
                    1 -> parts[0].toDouble().toLong()
                    2 -> parts[0].toLong() * 60 + parts[1].toLong()
                    3 -> parts[0].toLong() * 3600 + parts[1].toLong() * 60 + parts[2].toLong()
                    else -> return null
                }
                seconds * 1000
            } catch (_: NumberFormatException) {
                null
            }
        }
    }
}

/** Maps a parsed feed onto entity rows for a given podcast. */
fun ParsedFeed.toEpisodeEntities(podcast: PodcastEntity): List<EpisodeEntity> =
    episodes.map { episode ->
        EpisodeEntity(
            id = stableId(episode.guid ?: episode.audioUrl),
            podcastId = podcast.id,
            podcastTitle = title ?: podcast.title,
            guid = episode.guid,
            title = episode.title,
            description = episode.description,
            audioUrl = episode.audioUrl,
            pubDateMs = episode.pubDateMs,
            durationMs = episode.durationMs,
            artworkUrl = episode.imageUrl ?: imageUrl ?: podcast.artworkUrl,
        )
    }
