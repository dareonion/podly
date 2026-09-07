package com.podly.radio

/**
 * A radio listener persona: what radio may play and how it blends sources.
 *
 * Profiles are configuration rather than user data, so the catalogue lives in code.
 * Everything *stored* about a profile is a plain id string, so a future table of
 * user-defined profiles needs no migration of the tagging columns.
 */
data class RadioProfile(
    val id: String,
    val displayName: String,
    val tagline: String,
    /** BCP-47 primary subtags. Applied only to candidates that declare a language. */
    val languages: Set<String>,
    val maxDurationMs: Long? = null,
    val minDurationMs: Long? = null,
    /** Chance that a batch slot is filled from discovery rather than the backlog. */
    val discoveryShare: Float = 0.3f,
    /**
     * Whether radio may also play episodes already in the database from shows the
     * user never subscribed to — ones opened from Discover or pulled in by a picks
     * import. This is the discovery that needs no generated pool.
     */
    val includeUnsubscribed: Boolean = true,
    /**
     * Whether the notable pool — award winners and widely-heard episodes — is
     * blended into this profile's picks. Off for the toddler: that pool is
     * adult material by default, and nothing about a Pulitzer makes an episode
     * suitable for a three-year-old.
     */
    val includeNotable: Boolean = true,
    /**
     * When true, the backlog is limited to shows explicitly chosen for this profile.
     * The toddler profile ships with this on and an empty allowlist: nothing plays
     * until a grown-up picks the shows, which is the only content gate that exists.
     */
    val restrictBacklogToSelectedShows: Boolean = false,
    /**
     * Show genres this profile never plays, lowercased to match `podcast_categories`.
     *
     * Applied to the backlog as well as to generated picks: the shows a listener
     * subscribes to for someone else in the house are exactly the ones that keep
     * turning up uninvited.
     */
    val excludedCategories: Set<String> = emptySet(),
    /**
     * Genres that mean "not for this profile" only when nothing in
     * [exemptCategories] says otherwise.
     *
     * "Kids & Family" is the whole reason this exists: Apple files both a
     * toddler's story show and "Good Inside with Dr. Becky" under it, so it
     * cannot be read as either on its own.
     */
    val ambiguousCategories: Set<String> = emptySet(),
    /**
     * Genres that rescue a show from [ambiguousCategories] — never from
     * [excludedCategories]. Publishers tag liberally: 交通工具故事大集合 declares
     * Parenting *and* Stories for Kids, and it is a children's show. An
     * unambiguous genre therefore has the last word.
     */
    val exemptCategories: Set<String> = emptySet(),
    val weights: RadioWeights = RadioWeights(),
)

/**
 * Apple's genre names, lowercased, grouped so a profile can name a whole subtree.
 *
 * Excluding [KIDS] has to name the top-level "Kids & Family", because plenty of
 * children's shows declare nothing more specific. [PARENTING] then rescues the
 * shows filed there that are aimed at adults.
 */
object RadioCategories {
    /** Unambiguously programming for children, whatever else a show also claims. */
    val KIDS_PROGRAMMING = setOf(
        "stories for kids", "education for kids", "kids", "children", "children's",
    )

    /** Holds children's shows and shows for their parents alike. */
    val KIDS_FAMILY = setOf("kids & family", "kids and family")
    val TRUE_CRIME = setOf("true crime", "crime")

    /**
     * For grown-ups about children, which is not children's programming.
     *
     * Just parenting. "Pets & Animals" also sits under Kids & Family, but
     * exempting it would hand back every children's animal show — and nobody
     * asked for pets, so the rescue is not worth the hole.
     */
    val PARENTING = setOf("parenting")
    val RELIGION = setOf(
        "religion & spirituality", "religion and spirituality", "religion",
        "spirituality", "christianity", "buddhism", "hinduism", "islam", "judaism",
    )

    /**
     * "kids, true crime, religion" for a profile's exclusions, or null when it
     * excludes nothing. Named groups only: a list that read out all twenty raw
     * genre names would be noise, and silently filtering is worse than either.
     */
    fun label(profile: RadioProfile): String? {
        val excluded = profile.excludedCategories + profile.ambiguousCategories
        val groups = buildList {
            if (excluded.containsAll(KIDS_PROGRAMMING)) add("kids")
            if (excluded.containsAll(TRUE_CRIME)) add("true crime")
            if (excluded.containsAll(RELIGION)) add("religion")
        }
        return groups.joinToString(", ").ifEmpty { null }
    }
}

/** Scoring weights, all in one place so tuning is a single diff. */
data class RadioWeights(
    val priority: Double = 1.0,
    val rating: Double = 0.6,
    val freshness: Double = 0.8,
    val resume: Double = 0.5,
    val jitter: Double = 0.6,
    val servedRecently: Double = 0.7,
    val skipped: Double = 1.2,
)

object RadioProfiles {

    val YOU = RadioProfile(
        id = "you",
        displayName = "You",
        tagline = "Your backlog, plus new picks",
        languages = setOf("en", "zh"),
        minDurationMs = 3 * 60_000L,
        discoveryShare = 0.3f,
        // Darren's standing preference for his own radio. The shows are still in
        // the library and still play when picked by hand; they just stop being
        // suggested. The toddler profile carries no exclusions, which is why this
        // belongs to the profile rather than to a global setting.
        excludedCategories = RadioCategories.KIDS_PROGRAMMING +
            RadioCategories.TRUE_CRIME + RadioCategories.RELIGION,
        ambiguousCategories = RadioCategories.KIDS_FAMILY,
        exemptCategories = RadioCategories.PARENTING,
    )

    val TODDLER_ZH = RadioProfile(
        id = "toddler_zh",
        displayName = "Toddler — Chinese",
        tagline = "Mandarin stories, short episodes",
        languages = setOf("zh"),
        maxDurationMs = 15 * 60_000L,
        discoveryShare = 0.5f,
        includeNotable = false,
        restrictBacklogToSelectedShows = true,
    )

    val ALL = listOf(YOU, TODDLER_ZH)

    /**
     * Episodes singled out for acclaim or for how widely they were heard. Not a
     * listening profile — it has no chip and you do not "run radio as Notable" —
     * but it is a pool, so it downloads, hydrates and is browsable through
     * exactly the same machinery.
     */
    const val NOTABLE_ID = "notable"

    /** Every pool the app downloads, which is the profiles plus Notable. */
    val POOL_IDS = ALL.map { it.id } + NOTABLE_ID

    val DEFAULT_ID = YOU.id

    /** Unknown ids resolve to the default rather than crashing. */
    fun byId(id: String?): RadioProfile = ALL.firstOrNull { it.id == id } ?: YOU

    /**
     * A label for history and chips. Deliberately not [byId]: an id we no longer know
     * must render as itself, not be silently relabelled as "You".
     */
    fun labelFor(id: String?): String = when {
        id == null -> "Not in radio"
        else -> ALL.firstOrNull { it.id == id }?.displayName ?: id
    }
}
