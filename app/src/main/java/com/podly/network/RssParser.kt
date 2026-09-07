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
    /** Channel `<language>`; a transcript that declares none is in this one. */
    val language: String? = null,
    /** Channel-level `<itunes:category text="...">`, parents and subcategories alike. */
    val categories: List<String> = emptyList(),
)

/** A `<podcast:transcript>` declaration: where the transcript is, and in what format. */
data class ParsedTranscript(
    val url: String,
    val type: String?,
    val language: String?,
    val rel: String?,
)

data class ParsedEpisode(
    val guid: String?,
    val title: String,
    val description: String?,
    val transcripts: List<ParsedTranscript> = emptyList(),
    val audioUrl: String,
    /** Null when the feed has no date or one we can't parse. */
    val pubDateMs: Long?,
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
        var channelLanguage: String? = null
        val channelCategories = mutableListOf<String>()
        val episodes = mutableListOf<ParsedEpisode>()

        var inItem = false
        var itemTitle: String? = null
        var itemGuid: String? = null
        // Kept apart rather than first-wins: these arrive in whatever order a
        // publisher likes, and they are not interchangeable. Megaphone's
        // <content:encoded> runs 2,200 characters longer than its <description>,
        // while The Daily's <itunes:summary> is a quarter the length of both.
        var itemNotes: String? = null
        var itemDescription: String? = null
        var itemSummary: String? = null
        var itemAudioUrl: String? = null
        var itemPubDate: String? = null
        var itemDuration: String? = null
        var itemImage: String? = null
        var itemTranscripts = mutableListOf<ParsedTranscript>()

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val tag = parser.name.lowercase(Locale.ROOT)
                    when {
                        tag == "item" -> {
                            inItem = true
                            itemTitle = null; itemGuid = null; itemDescription = null
                            itemNotes = null; itemSummary = null
                            itemAudioUrl = null; itemPubDate = null; itemDuration = null; itemImage = null
                            itemTranscripts = mutableListOf()
                        }
                        inItem -> when (tag) {
                            "title" -> itemTitle = parser.nextTextSafe()
                            "guid" -> itemGuid = parser.nextTextSafe()
                            "description" -> if (itemDescription == null) itemDescription = parser.nextTextSafe()
                            "itunes:summary" -> if (itemSummary == null) itemSummary = parser.nextTextSafe()
                            "content:encoded" -> if (itemNotes == null) itemNotes = parser.nextTextSafe()
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
                            // Podcasting 2.0. An item may declare several — different
                            // formats and languages of the same episode.
                            "podcast:transcript" ->
                                parser.getAttributeValue(null, "url")
                                    ?.trim()?.takeIf { it.isNotEmpty() }
                                    ?.let { url ->
                                        itemTranscripts += ParsedTranscript(
                                            url = url,
                                            type = parser.getAttributeValue(null, "type"),
                                            language = parser.getAttributeValue(null, "language"),
                                            rel = parser.getAttributeValue(null, "rel"),
                                        )
                                    }
                        }
                        else -> when (tag) {
                            "title" -> if (channelTitle == null) channelTitle = parser.nextTextSafe()
                            "itunes:author" -> channelAuthor = parser.nextTextSafe()
                            "description" -> if (channelDescription == null) channelDescription = parser.nextTextSafe()
                            "itunes:image" -> channelImage = parser.getAttributeValue(null, "href") ?: channelImage
                            "url" -> if (channelImage == null) channelImage = parser.nextTextSafe()
                            "language" ->
                                if (channelLanguage == null) channelLanguage = parser.nextTextSafe()
                            // Subcategories nest inside their parent, and both arrive
                            // here as start tags, so this collects "Kids & Family" and
                            // "Stories for Kids" without tracking depth.
                            "itunes:category" ->
                                parser.getAttributeValue(null, "text")
                                    ?.trim()?.takeIf { it.isNotEmpty() }
                                    ?.let { channelCategories += it }
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
                                // The full show notes when there are any: links,
                                // chapter lists and credits live in content:encoded.
                                description = itemNotes ?: itemDescription ?: itemSummary,
                                transcripts = itemTranscripts.toList(),
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

        return ParsedFeed(
            channelTitle, channelAuthor, channelDescription, channelImage, episodes,
            channelLanguage,
            channelCategories.distinct(),
        )
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

        fun parseRfc822(text: String?): Long? {
            if (text.isNullOrBlank()) return null
            for (pattern in RFC822_PATTERNS) {
                try {
                    return SimpleDateFormat(pattern, Locale.US).parse(text.trim())!!.time
                } catch (_: Exception) {
                }
            }
            return null
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
/**
 * The transcript worth storing, of however many an item declares.
 *
 * Timed formats first: only they let a line be tapped to seek. text/plain is
 * last and is what publishers actually ship, so it is the common outcome rather
 * than the fallback nobody hits.
 */
fun List<ParsedTranscript>.preferred(feedLanguage: String? = null): ParsedTranscript? {
    // Format first, because only the timed ones can be tapped to seek. The spec's
    // canonical SRT type is application/x-subrip; application/srt is tolerated
    // because publishers write it.
    fun format(t: ParsedTranscript) = when (t.type?.lowercase()?.trim()) {
        "application/json" -> 0
        "text/vtt" -> 1
        "application/x-subrip", "application/srt" -> 2
        "text/html" -> 3
        else -> 4
    }
    // "If there is no language attribute given, the linked file is assumed to be
    // the same language that is specified by the RSS <language> element."
    fun wrongLanguage(t: ParsedTranscript): Int {
        val declared = t.language?.substringBefore('-')?.lowercase()?.trim()
        val feed = feedLanguage?.substringBefore('-')?.lowercase()?.trim()
        return if (declared == null || feed == null || declared == feed) 0 else 1
    }
    // Captions are chunked for display; a full transcript reads better when both
    // are offered in the same format.
    fun captions(t: ParsedTranscript) = if (t.rel.equals("captions", true)) 1 else 0
    return minWithOrNull(
        compareBy({ wrongLanguage(it) }, { format(it) }, { captions(it) }),
    )
}

fun ParsedFeed.toEpisodeEntities(podcast: PodcastEntity): List<EpisodeEntity> {
    // Undated episodes sort as "new when first seen" rather than 1970. Stable
    // because refresh inserts are IGNOREd and metadata updates skip pubDateMs.
    val firstSeenMs = System.currentTimeMillis()
    return episodes.map { episode ->
        EpisodeEntity(
            id = stableId(episode.guid ?: episode.audioUrl),
            podcastId = podcast.id,
            podcastTitle = title ?: podcast.title,
            guid = episode.guid,
            title = episode.title,
            description = episode.description,
            audioUrl = episode.audioUrl,
            pubDateMs = episode.pubDateMs ?: firstSeenMs,
            durationMs = episode.durationMs,
            artworkUrl = episode.imageUrl ?: imageUrl ?: podcast.artworkUrl,
            transcriptUrl = episode.transcripts.preferred(language)?.url,
            transcriptType = episode.transcripts.preferred(language)?.type,
        )
    }
}
