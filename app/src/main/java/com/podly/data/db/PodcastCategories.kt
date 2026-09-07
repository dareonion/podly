package com.podly.data.db

/**
 * Turns a feed's declared categories into the vocabulary profiles filter on.
 *
 * Feeds declare `<itunes:category>` in the publisher's own language, so a
 * Taiwanese children's show says `兒童與家庭`, not "Kids & Family" — and an
 * English-only exclusion list silently matches none of them. That is not
 * hypothetical: on this device 58 of the stored category rows were Chinese, and
 * they were the kids' shows the filter existed to catch.
 *
 * Every alias below was read back from the iTunes API for a real show rather
 * than translated by hand; guessed strings fail silently, which is the whole
 * problem. Traditional and Simplified are separate entries because Apple
 * translates some genres differently in each (Religion & Spirituality is
 * 宗教與精神生活 in Taiwan and 宗教与心灵 in mainland China).
 */
object PodcastCategories {

    private val ALIASES: Map<String, String> = mapOf(
        // Kids & Family and its subgenres.
        "兒童與家庭" to "kids & family",
        "儿童与家庭" to "kids & family",
        "兒童故事" to "stories for kids",
        "儿童故事" to "stories for kids",
        "兒童教育" to "education for kids",
        "儿童教育" to "education for kids",
        // Parenting: filed under Kids & Family, but written for grown-ups.
        "子女教養" to "parenting",
        "子女教养" to "parenting",
        "寵物與動物" to "pets & animals",
        "宠物与动物" to "pets & animals",
        // True Crime.
        "犯罪紀實" to "true crime",
        "犯罪纪实" to "true crime",
        // Religion & Spirituality.
        "宗教與精神生活" to "religion & spirituality",
        "宗教与心灵" to "religion & spirituality",
        "宗教" to "religion",
        "基督教" to "christianity",
        "佛教" to "buddhism",
    )

    /**
     * Lowercased, de-duplicated, with the canonical English genre added for any
     * name we recognise. The original is kept too: it costs one row and leaves
     * the stored data explainable when a filter behaves unexpectedly.
     */
    fun normalize(raw: List<String>): List<String> {
        val out = LinkedHashSet<String>()
        raw.forEach { entry ->
            val trimmed = entry.trim()
            if (trimmed.isEmpty()) return@forEach
            out += trimmed.lowercase()
            ALIASES[trimmed]?.let { out += it }
        }
        return out.toList()
    }
}
