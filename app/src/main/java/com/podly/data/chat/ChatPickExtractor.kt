package com.podly.data.chat

/** One thing a chat recommends: an episode to queue, or a show to try. */
data class ChatPick(
    /** As the chat names it, markup stripped ("說故事的人：逃亡的人"). */
    val title: String,
    /** The link the chat gave for it, if any. */
    val url: String?,
    /** The chat's sentence or two on why; becomes the playlist row's note. */
    val reason: String?,
    val isShow: Boolean,
    /** The show, when the title names it ("說故事的人" in "說故事的人：逃亡的人"). */
    val showName: String?,
)

/**
 * Pulls recommendations out of a ChatGPT answer, in the order the chat gives them.
 *
 * Answers mix Markdown (numbered lists, tables, bold titles) with ChatGPT's own
 * layout tags (`<row>` cards, `<Link>`, `<Entity>`, buttons). A recommendation is a
 * numbered item, a table row, a card or a bullet with a bold title or a link; prose
 * between them is skipped, links and all. The link is a hint, not the identity:
 * two different episodes can share one show-level link, and some have none.
 */
object ChatPickExtractor {

    fun extract(message: ChatSharePage.Message): List<ChatPick> {
        val links = mutableListOf<LinkToken>()
        val text = substituteTags(message.text, message.links, links)
        val picks = mutableListOf<ChatPick>()
        val lines = text.lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            when {
                ROW_OPEN.containsMatchIn(line) -> {
                    val card = mutableListOf<String>()
                    var depth = 0
                    while (i < lines.size) {
                        depth += ROW_OPEN.findAll(lines[i]).count() - ROW_CLOSE.findAll(lines[i]).count()
                        card += lines[i]
                        i++
                        if (depth <= 0) break
                    }
                    picks += fromCard(card, links)
                    continue
                }
                line.trimStart().startsWith("|") -> {
                    val table = mutableListOf<String>()
                    while (i < lines.size && lines[i].trimStart().startsWith("|")) table += lines[i++]
                    picks += fromTable(table, links)
                    continue
                }
                NUMBERED.containsMatchIn(line) || BULLET.containsMatchIn(line) -> {
                    // An item runs on through indented lines (its reason, its link)
                    // up to the next unindented line.
                    val item = StringBuilder(line)
                    var j = i + 1
                    while (j < lines.size && (lines[j].isBlank() || lines[j].startsWith("  ") || lines[j].startsWith("\t")) &&
                        !NUMBERED.containsMatchIn(lines[j]) && !BULLET.containsMatchIn(lines[j]) &&
                        !ROW_OPEN.containsMatchIn(lines[j]) && !lines[j].trimStart().startsWith("|")
                    ) {
                        if (lines[j].isNotBlank()) item.append(' ').append(lines[j].trim())
                        j++
                    }
                    fromListItem(item.toString(), links)?.let { picks += it }
                    i = j
                    continue
                }
            }
            i++
        }
        return picks
    }

    // --- tags -------------------------------------------------------------------

    private data class LinkToken(val text: String, val url: String?)

    private const val LINK_START = '\u0001'
    private const val LINK_END = '\u0002'
    private val TOKEN = Regex("$LINK_START(\\d+)$LINK_END")

    private val LINK_TAG = Regex("""<Link\s+url=(?:\{([A-Za-z0-9_]+)\.url\.uri\}|"([^"]*)")\s*>([^<]*)</Link>""")
    // Not [^>]*: the button's onClick is an arrow function, `()=>GenUI.openUrl(…)`.
    private val BUTTON = Regex("""<button\b.*?openUrl\(([A-Za-z0-9_]+)\.url\.uri\).*?</button>""")
    private val ENTITY = Regex("""<Entity[^>]*?value="([^"]*)"[^>]*/>""")
    private val SELF_CLOSING = Regex("""<(Cite|AsyncImage|divider|icon)\b[^>]*/>""")
    private val MARKDOWN_LINK = Regex("""\[([^\]\n]+)]\((https?://[^)\s]+)\)""")
    private val BARE_URL = Regex("""(?<![\w"(=/])https?://[^\s)<>\]]+""")

    /** Links and buttons become numbered tokens so titles and reasons can find them. */
    private fun substituteTags(text: String, bindings: Map<String, String>, out: MutableList<LinkToken>): String {
        fun token(t: LinkToken): String {
            out += t
            return "$LINK_START${out.size - 1}$LINK_END"
        }
        return text
            .replace(LINK_TAG) { m ->
                val url = m.groupValues[1].ifEmpty { null }?.let(bindings::get) ?: m.groupValues[2].ifEmpty { null }
                token(LinkToken(m.groupValues[3].trim(), url))
            }
            .replace(BUTTON) { m -> token(LinkToken("", bindings[m.groupValues[1]])) }
            // Plain Markdown, for answers without ChatGPT's own layout (and pasted text).
            .replace(MARKDOWN_LINK) { m -> token(LinkToken(m.groupValues[1].trim(), m.groupValues[2])) }
            .replace(BARE_URL) { m -> token(LinkToken("", m.value.trimEnd('.', ',', ';', '，', '。'))) }
            .replace(ENTITY) { m -> m.groupValues[1] }
            .replace(SELF_CLOSING, "")
    }

    /** Text with tokens shown as their link text and every remaining tag dropped. */
    private fun plain(s: String, links: List<LinkToken>): String =
        s.replace(TOKEN) { links[it.groupValues[1].toInt()].text }
            .replace(Regex("<[^>]+>"), " ")
            .replace("**", "")
            .replace(Regex("(?<![\\w*])\\*(?!\\s)([^*]+)(?<!\\s)\\*(?![\\w*])"), "$1")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun tokensIn(s: String, links: List<LinkToken>): List<LinkToken> =
        TOKEN.findAll(s).map { links[it.groupValues[1].toInt()] }.toList()

    // --- blocks -----------------------------------------------------------------

    private val ROW_OPEN = Regex("<row\\b")
    private val ROW_CLOSE = Regex("</row>")
    private val NUMBERED = Regex("^\\s*\\d+\\.\\s+")
    private val BULLET = Regex("^\\s*[-*]\\s+")
    private val BOLD = Regex("\\*\\*(.+?)\\*\\*")
    private val MEDIUM_TEXT = Regex("""<text[^>]*weight="medium"[^>]*>(.*?)</text>""")
    private val CAPTION = Regex("<caption>(.*?)</caption>")
    private val META_TEXT = Regex("""<text[^>]*color="secondary"[^>]*>.*?</text>""")
    private val SMALL_TEXT = Regex("""<text[^>]*size="sm"[^>]*>(.*?)</text>""")
    private val LEADING_META = Regex("^\\(([^)]*)\\)[.,]?\\s*")

    /** Link text that names an action rather than an episode: the link belongs to the card's title. */
    private val NAVIGATION = Regex("(?i)^(|browse.*|open.*|listen.*|play.*|website|link|more)$")
    private val BROWSE = Regex("(?i)^browse.*")

    private fun fromListItem(line: String, links: List<LinkToken>): ChatPick? {
        val body = line.replace(NUMBERED, "").replace(BULLET, "")
        val bold = BOLD.find(body)
        if (bold != null) {
            val titleLinks = tokensIn(bold.groupValues[1], links)
            val title = plain(bold.groupValues[1], links)
            val reason = plain(body.substring(bold.range.last + 1), links).replace(LEADING_META, "")
            // A bold title with its link after it ("**Title** (2021): https://…",
            // "**Title** [Listen](…)") still has a link.
            val after = tokensIn(body.substring(bold.range.last + 1), links)
            val url = titleLinks.firstOrNull()?.url
                ?: (after.firstOrNull { NAVIGATION.matches(it.text) } ?: after.firstOrNull())?.url
            return pick(title, url, reason)
        }
        // No bold: "[Title](url) — reason", or "Title — url".
        val first = TOKEN.find(body) ?: return plainItem(line, body, links)
        val token = links[first.groupValues[1].toInt()]
        val title = token.text.ifEmpty {
            plain(body.substring(0, first.range.first), links).trimEnd(' ', '-', '—', '–', ':', '：', '·')
        }
        val reason = plain(body.substring(first.range.last + 1), links).trimStart(' ', '-', '—', '–', ':', '：', '·')
            .replace(LEADING_META, "")
        return pick(title, token.url, reason)
    }

    /**
     * A numbered item with neither bold nor link ("1. 日谈公园", "2. 故事FM：逃亡的人 — why"):
     * a pick when it reads like a name, not when it reads like a sentence.
     */
    private fun plainItem(line: String, body: String, links: List<LinkToken>): ChatPick? {
        if (!NUMBERED.containsMatchIn(line)) return null
        val text = plain(body, links)
        val dash = Regex("\\s[—–-]\\s").find(text)
        val title = (dash?.let { text.substring(0, it.range.first) } ?: text).trim()
        val reason = dash?.let { text.substring(it.range.last + 1).trim() }
        val looksLikeName = title.codePointCount(0, title.length) <= 60 &&
            title.isNotEmpty() && title.last() !in ".。!！?？:：,，"
        return if (looksLikeName) pick(title, null, reason) else null
    }

    private fun fromCard(card: List<String>, links: List<LinkToken>): List<ChatPick> {
        val joined = card.joinToString("\n")
        val titleSource = BOLD.find(joined)?.groupValues?.get(1)
            ?: MEDIUM_TEXT.find(joined)?.groupValues?.get(1)
            ?: return emptyList()
        val title = plain(titleSource, links).replace(NUMBERED, "")
        val titleLinks = tokensIn(titleSource, links)
        val reason = CAPTION.find(joined)?.let { plain(it.groupValues[1], links) }
            ?: card.filter { line ->
                val t = line.trim()
                t.isNotEmpty() && !t.startsWith("<") && !t.contains("**")
            }.joinToString(" ") { plain(it, links) }
        // Links outside the title: a "Browse the show" or play button is the title's
        // own link; anything else is a part of a series the card introduces.
        val rest = tokensIn(joined.replace(titleSource, "").replace(META_TEXT, ""), links)
        val (navigation, parts) = rest.partition { NAVIGATION.matches(it.text) }
        if (titleLinks.isEmpty() && parts.isNotEmpty() && SMALL_TEXT.containsMatchIn(joined)) {
            val show = splitShow(title)?.first ?: title
            return parts.map { part ->
                ChatPick(title = "$title ${part.text}".trim(), url = part.url, reason = reason.ifBlank { null },
                    isShow = false, showName = show)
            }
        }
        val url = titleLinks.firstOrNull()?.url ?: navigation.firstOrNull()?.url ?: parts.firstOrNull()?.url
        // "Browse the show" under 解鎖地球：旅行、歷史、文化 says the whole title is the show's
        // name, colon and all, not "show：episode".
        val browse = titleLinks.isEmpty() && navigation.any { BROWSE.matches(it.text) }
        return listOfNotNull(pick(title, url, reason, knownShow = browse.takeIf { it }))
    }

    private fun fromTable(rows: List<String>, links: List<LinkToken>): List<ChatPick> {
        val cells = rows.map { row -> row.trim().removePrefix("|").removeSuffix("|").split("|").map { it.trim() } }
        val separator = cells.indexOfFirst { row -> row.all { it.matches(Regex(":?-{2,}:?")) } }
        val body = if (separator >= 0) cells.drop(separator + 1) else cells
        return body.mapNotNull { row ->
            val first = row.firstOrNull() ?: return@mapNotNull null
            val title = plain(first, links).ifBlank { return@mapNotNull null }
            pick(title, tokensIn(first, links).firstOrNull()?.url, row.getOrNull(1)?.let { plain(it, links) })
        }
    }

    // --- classification ----------------------------------------------------------

    private fun pick(title: String, url: String?, reason: String?, knownShow: Boolean? = null): ChatPick? {
        if (title.isBlank()) return null
        val link = url?.let(ChatLink::parse)
        val split = splitShow(title)
        val isShow = knownShow ?: when (link) {
            is ChatLink.Episode -> false
            else -> split == null // a show-level link or none: an episode only if the title names one
        }
        return ChatPick(
            title = title,
            url = url,
            reason = reason?.trim()?.ifBlank { null },
            isShow = isShow,
            showName = if (isShow) title.unquoted() else split?.first,
        )
    }

    private val EPISODE_MARKER = Regex("""^(.+?)\s+(?:EP|Ep|ep|E|Vol\.?|vol\.?|#)\s?\d+""")
    private val NUMBER_COLON = Regex("""^(.+?)\s+\d+\s*[：:]""")
    private val DASH = Regex("""^(.+?)\s+[—–-]\s+(.+)$""")
    private val COLON = Regex("""^(.+?)\s*[：:]\s*(.+)$""")
    private val LEADING_NUMBER = Regex("""^(?i)(?:e|ep|vol\.?|#)?\s?\d+(?:\s|$)""")

    /**
     * (show, episode) when [title] reads "show：episode", "show EP12：…", "show — …";
     * null for a bare name, which is a show (or an episode title with no show, which
     * then has no way to be found).
     */
    internal fun splitShow(title: String): Pair<String, String>? {
        val match = EPISODE_MARKER.find(title) ?: NUMBER_COLON.find(title) ?: DASH.find(title) ?: COLON.find(title)
            ?: return null
        val show = match.groupValues[1].trimEnd(' ', '—', '–', '-', ':', '：').unquoted()
        // "E911 …", "Vol.05 逃出…", "282 基辅…": what precedes is an episode number, not a show.
        if (show.isEmpty() || LEADING_NUMBER.containsMatchIn(show)) return null
        return show to title
    }

    private fun String.unquoted() = trim().removePrefix("《").removeSuffix("》").trim()
}

/** What a recommendation link points at, as far as finding the audio goes. */
sealed interface ChatLink {
    sealed interface Episode : ChatLink

    data class XiaoyuzhouEpisode(val eid: String) : Episode
    data class AppleEpisode(val showId: String, val episodeId: String) : Episode

    /** pod.link's episode id is the MD5 of the feed item's GUID (checked on 說故事的人). */
    data class PodLinkEpisode(val showId: String, val guidMd5: String) : Episode
    data class XiaoyuzhouShow(val pid: String) : ChatLink
    data class AppleShow(val showId: String) : ChatLink
    data object Other : ChatLink

    companion object {
        private val XYZ_EPISODE = Regex("""xiaoyuzhoufm\.com/episode/([0-9a-f]{24})""")
        private val XYZ_SHOW = Regex("""xiaoyuzhoufm\.com/podcast/([0-9a-f]{24})""")
        private val POD_LINK = Regex("""pod\.link/(\d+)(?:/episode/([0-9a-f]{32}))?""")

        fun parse(url: String): ChatLink {
            XYZ_EPISODE.find(url)?.let { return XiaoyuzhouEpisode(it.groupValues[1]) }
            XYZ_SHOW.find(url)?.let { return XiaoyuzhouShow(it.groupValues[1]) }
            POD_LINK.find(url)?.let { m ->
                val guid = m.groupValues[2]
                return if (guid.isEmpty()) AppleShow(m.groupValues[1]) else PodLinkEpisode(m.groupValues[1], guid)
            }
            val apple = com.podly.network.PodcastLink.parse(url) as? com.podly.network.PodcastLink.Apple
            if (apple != null) {
                return apple.episodeId?.let { AppleEpisode(apple.collectionId, it) } ?: AppleShow(apple.collectionId)
            }
            return Other
        }
    }
}
