package com.podly.data

import com.podly.data.db.PodcastEntity
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.Reader
import java.io.StringWriter
import java.util.Locale

data class OpmlOutline(
    val title: String,
    val feedUrl: String,
)

data class OpmlImportResult(
    val total: Int,
    val added: Int,
    val alreadySubscribed: Int,
    val refreshed: Int,
    val failedRefreshes: Int,
)

class OpmlParser {
    fun parse(reader: Reader): List<OpmlOutline> {
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(reader)

        val outlines = mutableListOf<OpmlOutline>()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name.lowercase(Locale.ROOT) == "outline") {
                val feedUrl = parser.getAttributeValue(null, "xmlUrl")?.trim()
                if (!feedUrl.isNullOrBlank()) {
                    val title = parser.getAttributeValue(null, "title")
                        ?: parser.getAttributeValue(null, "text")
                        ?: feedUrl
                    outlines += OpmlOutline(title.trim().ifBlank { feedUrl }, feedUrl)
                }
            }
            event = parser.next()
        }
        return outlines.distinctBy { it.feedUrl }
    }
}

object OpmlExporter {
    fun export(podcasts: List<PodcastEntity>): String {
        val writer = StringWriter()
        writer.appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        writer.appendLine("""<opml version="2.0">""")
        writer.appendLine("  <head>")
        writer.appendLine("    <title>Podly Subscriptions</title>")
        writer.appendLine("  </head>")
        writer.appendLine("  <body>")
        podcasts.sortedBy { it.title.lowercase(Locale.ROOT) }.forEach { podcast ->
            writer.append("    <outline type=\"rss\" text=\"")
                .append(podcast.title.xmlEscaped())
                .append("\" title=\"")
                .append(podcast.title.xmlEscaped())
                .append("\" xmlUrl=\"")
                .append(podcast.feedUrl.xmlEscaped())
                .appendLine("\" />")
        }
        writer.appendLine("  </body>")
        writer.appendLine("</opml>")
        return writer.toString()
    }

    private fun String.xmlEscaped(): String =
        buildString(length) {
            this@xmlEscaped.forEach { char ->
                when (char) {
                    '&' -> append("&amp;")
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    '"' -> append("&quot;")
                    '\'' -> append("&apos;")
                    else -> append(char)
                }
            }
        }
}
