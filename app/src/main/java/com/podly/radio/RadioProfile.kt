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
     * When true, the backlog is limited to shows explicitly chosen for this profile.
     * The toddler profile ships with this on and an empty allowlist: nothing plays
     * until a grown-up picks the shows, which is the only content gate that exists.
     */
    val restrictBacklogToSelectedShows: Boolean = false,
    val weights: RadioWeights = RadioWeights(),
)

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
    )

    val TODDLER_ZH = RadioProfile(
        id = "toddler_zh",
        displayName = "Toddler — Chinese",
        tagline = "Mandarin stories, short episodes",
        languages = setOf("zh"),
        maxDurationMs = 15 * 60_000L,
        discoveryShare = 0.5f,
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
