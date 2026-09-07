package com.podly.network


/** One line of a transcript. [startMs] is null for formats that carry no timings. */
data class TranscriptCue(
    val startMs: Long?,
    val endMs: Long?,
    val speaker: String?,
    val text: String,
)

/**
 * Reads the file a `<podcast:transcript>` points at.
 *
 * Five types are allowed by the spec and they are not equally useful: JSON and
 * VTT carry timings, so a line can be tapped to seek; text/plain carries none,
 * and it is what real feeds actually ship — Acquired's are 217 KB of
 * speaker-prefixed prose. Everything therefore degrades to "readable", and only
 * the timed formats add seeking.
 */
object TranscriptParser {

    fun parse(body: String, type: String?): List<TranscriptCue> {
        val text = body.trim()
        if (text.isEmpty()) return emptyList()
        return when {
            // Sniffed rather than trusted: publishers mislabel, and a VTT served
            // as text/plain would otherwise render its timestamps as prose.
            text.startsWith("WEBVTT") -> parseVtt(text)
            text.startsWith("{") -> parseJson(text)
            type.equals("application/json", true) -> parseJson(text)
            type.equals("text/vtt", true) -> parseVtt(text)
            type.equals("application/srt", true) || type.equals("application/x-subrip", true) ->
                parseSrt(text)
            looksLikeSrt(text) -> parseSrt(text)
            type.equals("text/html", true) -> parseHtml(text)
            else -> parsePlain(text)
        }
    }

    /**
     * The spec's HTML shape: `<cite>` speaker, `<time>` start, `<p>` monologue.
     *
     * Stripping the tags and reading the result as prose would throw away exactly
     * the structure the format exists to carry, and render "Kevin: 0:00 We have
     * an update…" as one run-on line. Falls back to prose when a publisher ships
     * HTML with none of it.
     */
    private fun parseHtml(html: String): List<TranscriptCue> {
        val cues = mutableListOf<TranscriptCue>()
        var speaker: String? = null
        var start: Long? = null
        HTML_ELEMENT.findAll(html).forEach { match ->
            val tag = match.groupValues[1].lowercase()
            val body = stripHtml(match.groupValues[2]).trim()
            when (tag) {
                // The examples write both "Kevin:" and "Alban :".
                "cite" -> speaker = body.trimEnd(':', ' ', '\u00A0').ifEmpty { null }
                "time" -> start = parseTimestamp(body)
                "p" -> if (body.isNotEmpty()) {
                    // With no <cite>, a publisher may still name the speaker
                    // inline, which is how most hand-written HTML reads.
                    val named = speaker
                    if (named != null) {
                        cues += TranscriptCue(start, null, named, body)
                    } else {
                        val (inline, spoken) = splitSpeaker(body)
                        cues += TranscriptCue(start, null, inline, spoken)
                    }
                    start = null
                }
            }
        }
        return cues.ifEmpty { parsePlain(stripHtml(html)) }
    }

    private val HTML_ELEMENT =
        Regex("<(cite|time|p)\\b[^>]*>(.*?)</\\1>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

    /**
     * Deliberately not HtmlCompat: this parser stays on the JVM so it can be
     * tested without Robolectric, which is what the repo asks of parsing code.
     */
    private fun stripHtml(html: String): String =
        html.replace(Regex("(?i)<br\\s*/?>"), "\n")
            .replace(Regex("(?i)</(p|div|li|h[1-6])>"), "\n\n")
            .replace(Regex("<[^>]+>"), "")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")

    private fun looksLikeSrt(text: String): Boolean =
        Regex("^\\d+\\s*\\R\\d{2}:\\d{2}:\\d{2},\\d{3}\\s*-->").containsMatchIn(text)

    private val VTT_TIME = Regex(
        """(\d{2}:)?\d{2}:\d{2}[.,]\d{3}\s*-->\s*(\d{2}:)?\d{2}:\d{2}[.,]\d{3}""",
    )

    private fun parseVtt(text: String): List<TranscriptCue> =
        parseCueBlocks(text.removePrefix("WEBVTT"))

    private fun parseSrt(text: String): List<TranscriptCue> = parseCueBlocks(text)

    /** VTT and SRT differ only in a header and a decimal comma. */
    private fun parseCueBlocks(text: String): List<TranscriptCue> {
        // "Start a new card when the speaker changes" — so a card without a name
        // continues the previous speaker rather than being unattributed.
        var current: String? = null
        return cueBlocks(text).map { cue ->
            if (cue.speaker != null) current = cue.speaker else Unit
            if (cue.speaker == null && current != null) cue.copy(speaker = current) else cue
        }
    }

    private fun cueBlocks(text: String): List<TranscriptCue> =
        text.split(Regex("\\R\\s*\\R")).mapNotNull { block ->
            val lines = block.trim().lines().filter { it.isNotBlank() }
            if (lines.isEmpty()) return@mapNotNull null
            val timingIndex = lines.indexOfFirst { VTT_TIME.containsMatchIn(it) }
            if (timingIndex < 0) return@mapNotNull null
            val timing = VTT_TIME.find(lines[timingIndex])?.value ?: return@mapNotNull null
            val (start, end) = timing.split("-->").map { parseTimestamp(it.trim()) }
            val body = joinWrapped(lines.drop(timingIndex + 1)).trim()
            if (body.isEmpty()) return@mapNotNull null
            val (speaker, spoken) = splitSpeaker(stripVttTags(body))
            TranscriptCue(start, end, speaker, spoken)
        }

    /** `<v Ben>` speaker tags and `<c>` styling are markup, not words. */
    private fun stripVttTags(text: String): String =
        text.replace(Regex("<v\\s+([^>]+)>"), "$1: ")
            .replace(Regex("</?[a-zA-Z][^>]*>"), "")
            .trim()

    private fun parseTimestamp(raw: String): Long? {
        val parts = raw.replace(',', '.').split(':')
        if (parts.size !in 2..3) return null
        val seconds = parts.last().toDoubleOrNull() ?: return null
        val minutes = parts[parts.size - 2].toLongOrNull() ?: return null
        val hours = if (parts.size == 3) parts[0].toLongOrNull() ?: return null else 0L
        return ((hours * 3600 + minutes * 60) * 1000) + (seconds * 1000).toLong()
    }

    /**
     * The Podcasting 2.0 JSON transcript: `{"segments":[{speaker,startTime,body}]}`.
     * Consecutive segments from one speaker are merged — whisper-style output puts
     * a few words in each, which is unreadable one line at a time.
     */
    private fun parseJson(text: String): List<TranscriptCue> {
        val file = runCatching {
            Http.json.decodeFromString<TranscriptJsonFile>(text)
        }.getOrNull() ?: return parsePlain(text)
        val merged = mutableListOf<TranscriptCue>()
        file.segments.forEach { segment ->
            val body = segment.body.trim()
            if (body.isEmpty()) return@forEach
            val last = merged.lastOrNull()
            val sameSpeaker = last != null && last.speaker == segment.speaker
            if (sameSpeaker && (last!!.text.length + body.length) < MERGE_LIMIT) {
                merged[merged.size - 1] = last.copy(
                    endMs = segment.endTime?.let { (it * 1000).toLong() } ?: last.endMs,
                    text = "${last.text} $body",
                )
            } else {
                merged += TranscriptCue(
                    startMs = segment.startTime?.let { (it * 1000).toLong() },
                    endMs = segment.endTime?.let { (it * 1000).toLong() },
                    speaker = segment.speaker?.trim()?.ifEmpty { null },
                    text = body,
                )
            }
        }
        return merged
    }

    /**
     * Turns, not paragraphs.
     *
     * Splitting on blank lines alone was wrong on real files: Darren's feed puts
     * a whole dialogue in one block separated by single newlines, so an entire
     * scene collapsed into one cue attributed to whoever spoke first. A line that
     * opens with a speaker therefore starts a new cue, and everything else is
     * continuation — which still handles the blank-line style Acquired uses.
     */
    private fun parsePlain(text: String): List<TranscriptCue> {
        val cues = mutableListOf<TranscriptCue>()
        val buffer = StringBuilder()
        var speaker: String? = null

        fun flush() {
            val body = buffer.toString().trim()
            if (body.isNotEmpty()) cues += TranscriptCue(null, null, speaker, body)
            buffer.setLength(0)
            speaker = null
        }

        text.lines().forEach { raw ->
            val line = raw.trim()
            // Setext rules and heading markers are furniture, not speech.
            if (line.isEmpty() || line.matches(RULE)) {
                flush()
                return@forEach
            }
            val cleaned = line.trimStart('#').trim()
            if (cleaned.isEmpty()) return@forEach
            val match = SPEAKER.find(cleaned)
            if (match != null) {
                flush()
                speaker = match.groupValues[1].trim()
                buffer.append(match.groupValues[2].trim())
            } else {
                if (buffer.isNotEmpty() && needsSpace(buffer.last(), cleaned.first())) {
                    buffer.append(' ')
                }
                buffer.append(cleaned)
            }
        }
        flush()
        return cues
    }

    private val RULE = Regex("^[=\\-_*]{3,}$")

    /**
     * Rejoins lines a caption file wrapped for display.
     *
     * Chinese does not put spaces between characters, so joining every wrapped
     * line with one inserts a space into the middle of a sentence: a real cue
     * reads `…箱子裡裝著木頭軌道，手裡拿著一台紅色卡車`, wrapped after the comma.
     * A space is still added between Latin fragments, and at a CJK/Latin
     * boundary where it is conventional anyway.
     */
    private fun joinWrapped(parts: List<String>): String {
        val out = StringBuilder()
        parts.filter { it.isNotEmpty() }.forEach { part ->
            if (out.isNotEmpty() && needsSpace(out.last(), part.first())) out.append(' ')
            out.append(part)
        }
        return out.toString()
    }

    private fun needsSpace(previous: Char, next: Char): Boolean =
        !(isCjk(previous) && isCjk(next))

    private fun isCjk(c: Char): Boolean = when (Character.UnicodeBlock.of(c)) {
        Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS,
        Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A,
        Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS,
        Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION,
        Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS,
        Character.UnicodeBlock.HIRAGANA,
        Character.UnicodeBlock.KATAKANA,
        Character.UnicodeBlock.HANGUL_SYLLABLES,
        -> true
        else -> false
    }

    /**
     * "Ben: I was telling my wife" — a leading short name, not any old colon.
     *
     * Accepts the full-width colon and no following space, because that is how
     * Chinese transcripts are written: `小陳：大家好`. An ASCII-only rule read
     * every line of a Mandarin transcript as unattributed prose.
     */
    private fun splitSpeaker(text: String): Pair<String?, String> {
        val match = SPEAKER.find(text) ?: return null to text
        return match.groupValues[1].trim() to match.groupValues[2].trim()
    }

    /** The length cap is what keeps "the question was this:" from being a speaker. */
    private val SPEAKER =
        Regex("""^([^:：\n]{1,24})[:：]\s*(.+)$""", RegexOption.DOT_MATCHES_ALL)

    private const val MERGE_LIMIT = 600
}

@kotlinx.serialization.Serializable
private data class TranscriptJsonFile(val segments: List<TranscriptJsonSegment> = emptyList())

@kotlinx.serialization.Serializable
private data class TranscriptJsonSegment(
    val speaker: String? = null,
    val startTime: Double? = null,
    val endTime: Double? = null,
    val body: String = "",
)
