package com.podly.network

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.time.Instant

/** A 小宇宙 (Xiaoyuzhou) episode as its public web page describes it. */
data class XiaoyuzhouEpisode(
    val eid: String,
    val title: String,
    val pubDateMs: Long,
    val durationMs: Long?,
    /** Public audio on media.xyzcdn.net; null for paid or withdrawn episodes. */
    val audioUrl: String?,
    val description: String?,
    val show: XiaoyuzhouShow,
)

data class XiaoyuzhouShow(
    val pid: String,
    val title: String,
    val author: String?,
    val imageUrl: String?,
    /** As many as the show's page lists (its newest, typically 15). */
    val episodes: List<XiaoyuzhouEpisode> = emptyList(),
) {
    val pageUrl: String get() = "https://www.xiaoyuzhoufm.com/podcast/$pid"
}

/**
 * Reads 小宇宙's web pages, which are server-rendered with the page data in a
 * `__NEXT_DATA__` script: the episode's title, date, length and public audio, and
 * the show's name. ChatGPT links mainland shows there rather than to a feed. No API
 * key and no documented contract (layout read 2026-10-29), so a change shows up as
 * a parse failure, and callers treat that as "not found".
 */
object XiaoyuzhouApi {

    suspend fun episode(eid: String): XiaoyuzhouEpisode =
        parseEpisodePage(Http.get("https://www.xiaoyuzhoufm.com/episode/$eid"))

    suspend fun show(pid: String): XiaoyuzhouShow =
        parseShowPage(Http.get("https://www.xiaoyuzhoufm.com/podcast/$pid"))

    fun parseEpisodePage(html: String): XiaoyuzhouEpisode {
        val episode = pageProps(html)["episode"] as? JsonObject ?: error("No episode on this 小宇宙 page")
        val show = episode["podcast"] as? JsonObject ?: error("No show on this 小宇宙 episode")
        return episodeOf(episode, showOf(show))
    }

    fun parseShowPage(html: String): XiaoyuzhouShow {
        val podcast = pageProps(html)["podcast"] as? JsonObject ?: error("No show on this 小宇宙 page")
        val show = showOf(podcast)
        val episodes = (podcast["episodes"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonObject)?.let { e -> runCatching { episodeOf(e, show) }.getOrNull() } }
        return show.copy(episodes = episodes)
    }

    private fun pageProps(html: String): JsonObject {
        val open = html.indexOf("<script id=\"__NEXT_DATA__\"").takeIf { it >= 0 } ?: error("Not a 小宇宙 page")
        val start = html.indexOf('>', open) + 1
        val end = html.indexOf("</script>", start)
        val data = Http.json.parseToJsonElement(html.substring(start, end)).jsonObject
        return (data["props"] as? JsonObject)?.get("pageProps") as? JsonObject ?: error("Not a 小宇宙 page")
    }

    private fun episodeOf(e: JsonObject, show: XiaoyuzhouShow): XiaoyuzhouEpisode {
        val audio = e.obj("enclosure")?.str("url")
            ?: e.obj("media")?.obj("source")?.str("url")
        return XiaoyuzhouEpisode(
            eid = e.str("eid") ?: error("Episode without an id"),
            title = e.str("title") ?: error("Episode without a title"),
            pubDateMs = e.str("pubDate")?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0,
            durationMs = (e["duration"] as? JsonPrimitive)?.longOrNull?.times(1000),
            audioUrl = audio.takeIf { e.str("payType").let { it == null || it == "FREE" } },
            description = e.str("description"),
            show = show,
        )
    }

    private fun showOf(p: JsonObject) = XiaoyuzhouShow(
        pid = p.str("pid") ?: error("Show without an id"),
        title = p.str("title") ?: error("Show without a title"),
        author = p.str("author"),
        imageUrl = p.obj("image")?.str("picUrl"),
    )

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
}
