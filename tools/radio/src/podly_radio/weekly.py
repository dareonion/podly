"""The weekly digest: last week's best episodes, in English and Chinese, with blurbs.

Four stages, and the models only do the parts that need judgement:

1. Candidates. Code collects every episode the charting shows in four
   storefronts (US, UK, Taiwan, China) published during the week. Separately,
   Claude and Codex each search the web for what critics, editors and listeners
   singled out that week.
2. Proof. A web nomination counts only once it is found in a real feed, with
   audio, dated inside the week — the same inversion notable.py relies on. A
   nomination that cannot be located is dropped, never published on trust.
3. Judgement. Both models rank the same verified catalogue, referring to
   episodes by ref only, so neither can invent one. An episode both pick
   outranks one that only one of them picks.
4. Blurbs, written from each episode's own show notes, in its own language.

Either model failing at any stage costs that model's contribution, not the run.
"""

from __future__ import annotations

import datetime
import html
import json
import logging
import re
from collections.abc import Callable
from concurrent.futures import ThreadPoolExecutor
from dataclasses import asdict, dataclass, field, replace
from pathlib import Path
from urllib.parse import urlparse

import httpx

from . import agents
from .build import fetch_feed, useful_genres
from .config import ProfileConfig
from .feeds import ParsedEpisode, ParsedFeed
from .harvest import harvest, is_suitable
from .ids import episode_id, podcast_id
from .lang import _cjk_ratio, detect
from .match import best_match
from .schema import PoolEntry, PoolEpisode, PoolFile, PoolPodcast
from .sources import Show, search

LOG = logging.getLogger(__name__)

FAMILIES = ("en", "zh")
FAMILY_NAMES = {"en": "English", "zh": "Mandarin Chinese"}
# Feeds stamp times in every zone there is. Twelve hours either side keeps a
# Sunday-night US release and a Monday-morning Taipei one in the week they
# belong to; the previous issue's episodes are excluded, so nothing repeats.
GRACE_HOURS = 12
MIN_MINUTES = 5
MAX_MINUTES = 240
PER_SHOW = 3
CATALOGUE_NOTES = 160
BLURB_NOTES = 2500
PUBLISHED_NOTES = 10_000

Ask = Callable[..., dict]


# --------------------------------------------------------------------------- week


@dataclass(frozen=True, slots=True)
class Week:
    """A Monday-to-Sunday week, named by its ISO week."""

    start: datetime.date

    @property
    def end(self) -> datetime.date:
        return self.start + datetime.timedelta(days=6)

    @property
    def id(self) -> str:
        iso = self.start.isocalendar()
        return f"{iso.year}-W{iso.week:02d}"

    @property
    def pool_id(self) -> str:
        return f"weekly-{self.id.lower()}"

    @property
    def label(self) -> str:
        start, end = self.start, self.end
        if start.month == end.month:
            return f"{start:%b} {start.day}–{end.day}, {end.year}"
        if start.year == end.year:
            return f"{start:%b} {start.day} – {end:%b} {end.day}, {end.year}"
        return f"{start:%b} {start.day}, {start.year} – {end:%b} {end.day}, {end.year}"

    def window_ms(self, grace_hours: int = GRACE_HOURS) -> tuple[int, int]:
        utc = datetime.timezone.utc
        grace = datetime.timedelta(hours=grace_hours)
        lo = datetime.datetime.combine(self.start, datetime.time(), utc) - grace
        hi = datetime.datetime.combine(
            self.end + datetime.timedelta(days=1), datetime.time(), utc
        ) + grace
        return int(lo.timestamp() * 1000), int(hi.timestamp() * 1000)

    def contains(self, pub_date_ms: int | None) -> bool:
        if not pub_date_ms:
            return False
        lo, hi = self.window_ms()
        return lo <= pub_date_ms < hi


def previous_week(today: datetime.date) -> Week:
    """The last complete Monday-to-Sunday week before [today]."""
    this_monday = today - datetime.timedelta(days=today.weekday())
    return Week(this_monday - datetime.timedelta(days=7))


def parse_week(text: str) -> Week:
    match = re.fullmatch(r"(\d{4})-W(\d{2})", text.strip())
    if not match:
        raise ValueError(f"not an ISO week like 2026-W37: {text!r}")
    return Week(datetime.date.fromisocalendar(int(match[1]), int(match[2]), 1))


# ------------------------------------------------------------------- candidates


@dataclass(frozen=True, slots=True)
class Nomination:
    agent: str
    family: str
    show: str
    episode: str
    why: str
    source: str


@dataclass(slots=True)
class Candidate:
    ref: str
    show: Show
    feed: ParsedFeed
    episode: ParsedEpisode
    language: str
    nominations: list[Nomination] = field(default_factory=list)

    @property
    def family(self) -> str:
        return "zh" if self.language.startswith("zh") else "en"

    @property
    def id(self) -> str:
        return episode_id(self.episode.guid, self.episode.audio_url)

    @property
    def show_title(self) -> str:
        return self.show.title or self.feed.title or ""


@dataclass(slots=True)
class Pick:
    candidate: Candidate
    score: float
    picked_by: list[str]
    reasons: dict[str, str]
    blurb: str = ""


def plain(text: str | None) -> str:
    """Show notes as prose: tags dropped, entities decoded, whitespace collapsed."""
    if not text:
        return ""
    without_tags = re.sub(r"<[^>]+>", " ", text)
    return " ".join(html.unescape(without_tags).split())


def playable_length(episode: ParsedEpisode) -> bool:
    """Trailers and bulletins are not what a best-of means; unknown length passes."""
    if episode.duration_ms is None:
        return True
    return MIN_MINUTES * 60_000 <= episode.duration_ms <= MAX_MINUTES * 60_000


def language_of(show: Show, feed: ParsedFeed, episode: ParsedEpisode) -> str:
    text = f"{show.title} {feed.title or ''} {episode.title} {plain(episode.description)[:400]}"
    return detect(text, feed.language)


def build_catalogue(
    fetched: list[tuple[Show, ParsedFeed | None]],
    week: Week,
    excluded_ids: set[str],
    per_show: int = PER_SHOW,
) -> list[Candidate]:
    """Every charting show's episodes from the week, newest [per_show] per show."""
    catalogue: list[Candidate] = []
    seen: set[str] = set()
    for show, feed in fetched:
        if feed is None:
            continue
        in_week = sorted(
            (
                e for e in feed.episodes
                if week.contains(e.pub_date_ms) and playable_length(e)
            ),
            key=lambda e: e.pub_date_ms or 0,
            reverse=True,
        )
        for episode in in_week[:per_show]:
            candidate = Candidate("", show, feed, episode, language_of(show, feed, episode))
            if candidate.id in excluded_ids or candidate.id in seen:
                continue
            seen.add(candidate.id)
            catalogue.append(candidate)
    return catalogue


def locate_nomination(
    nomination: Nomination,
    week: Week,
    shows: list[tuple[Show, ParsedFeed | None]],
    lookup_shows: Callable[[Nomination], list[Show]],
    fetch: Callable[[Show], ParsedFeed | None],
) -> Candidate | None:
    """
    Finds the nominated episode in a real feed, inside the week, or returns None.

    Tries the already-fetched chart shows first, then a directory search. A show
    title alone is not trusted: "The Daily" is a close match for "The Daily
    Show", so the episode has to turn up in that feed before the show counts.
    """
    tried: set[str] = set()

    def attempt(show: Show, feed: ParsedFeed | None) -> Candidate | None:
        if not show.feed_url or show.feed_url in tried:
            return None
        tried.add(show.feed_url)
        if best_match(nomination.show, [show.title]) is None:
            return None
        feed = feed if feed is not None else fetch(show)
        if feed is None:
            return None
        in_week = [e for e in feed.episodes if week.contains(e.pub_date_ms)]
        match = best_match(nomination.episode, [e.title for e in in_week])
        if match is None:
            return None
        episode = in_week[match.index]
        return Candidate("", show, feed, episode, language_of(show, feed, episode))

    for show, feed in shows:
        found = attempt(show, feed)
        if found is not None:
            return found
    for show in lookup_shows(nomination):
        found = attempt(show, None)
        if found is not None:
            return found
    return None


def merge_nominations(
    catalogue: list[Candidate],
    nominations: list[Nomination],
    locate: Callable[[Nomination], Candidate | None],
    excluded_ids: set[str],
    suitable: Callable[[Show], bool],
) -> list[Candidate]:
    """Attaches each located nomination to its catalogue entry, adding it if new."""
    by_id = {candidate.id: candidate for candidate in catalogue}
    merged = list(catalogue)
    for nomination in nominations:
        found = locate(nomination)
        if found is None:
            LOG.info(
                "dropped %s nomination (not found in the week): %s — %s",
                nomination.agent, nomination.show, nomination.episode,
            )
            continue
        if found.id in excluded_ids:
            LOG.info("dropped (already in the previous issue): %s", found.episode.title)
            continue
        if not suitable(found.show):
            LOG.info("dropped (excluded genre): %s", found.show_title)
            continue
        existing = by_id.get(found.id)
        if existing is None:
            by_id[found.id] = existing = found
            merged.append(found)
        existing.nominations.append(nomination)
    return merged


def assign_refs(candidates: list[Candidate]) -> dict[str, Candidate]:
    counters = dict.fromkeys(FAMILIES, 0)
    refs: dict[str, Candidate] = {}
    for candidate in candidates:
        counters[candidate.family] += 1
        candidate.ref = f"{candidate.family}{counters[candidate.family]:03d}"
        refs[candidate.ref] = candidate
    return refs


# ---------------------------------------------------------------------- prompts

NOMINATE_SYSTEM = (
    "You are a well-read podcast critic compiling a weekly best-of. You search "
    "the web for what critics, editors, newsletters and listeners singled out, "
    "and you name specific, real episodes with their exact published titles. "
    "If you are not confident an episode is real, correctly titled and released "
    "inside the requested week, leave it out: every nomination is checked "
    "against the show's feed and anything that does not match is discarded."
)

JUDGE_SYSTEM = (
    "You are a podcast critic choosing a weekly best-of list from a catalogue "
    "of real episodes. You may refer to an episode ONLY by the ref it was given; "
    "never invent a ref. Judge each episode on what its notes show it contains."
)

BLURB_SYSTEM = (
    "You write the short blurbs in a podcast app's weekly digest. Each blurb "
    "says concretely what the episode is about and why it is worth the time. "
    "Use only facts found in the show notes and reviewer notes you are given: "
    "never invent a guest, a claim, a quote or a detail."
)

NOMINATION_SCHEMA = {
    "type": "object",
    "additionalProperties": False,
    "required": ["episodes"],
    "properties": {
        "episodes": {
            "type": "array",
            "items": {
                "type": "object",
                "additionalProperties": False,
                "required": ["show", "episode", "published", "why", "source"],
                "properties": {
                    "show": {"type": "string"},
                    "episode": {"type": "string"},
                    "published": {"type": "string"},
                    "why": {"type": "string"},
                    "source": {"type": "string"},
                },
            },
        }
    },
}

JUDGE_SCHEMA = {
    "type": "object",
    "additionalProperties": False,
    "required": ["picks"],
    "properties": {
        "picks": {
            "type": "array",
            "items": {
                "type": "object",
                "additionalProperties": False,
                "required": ["ref", "score", "reason"],
                "properties": {
                    "ref": {"type": "string"},
                    "score": {"type": "integer"},
                    "reason": {"type": "string"},
                },
            },
        }
    },
}

BLURB_SCHEMA = {
    "type": "object",
    "additionalProperties": False,
    "required": ["blurbs"],
    "properties": {
        "blurbs": {
            "type": "array",
            "items": {
                "type": "object",
                "additionalProperties": False,
                "required": ["ref", "blurb"],
                "properties": {
                    "ref": {"type": "string"},
                    "blurb": {"type": "string"},
                },
            },
        }
    },
}

WHERE_TO_LOOK = {
    "en": (
        "weekly podcast picks and roundup columns in newspapers and magazines, "
        "podcast industry newsletters, Apple Podcasts and Spotify editorial "
        "features, and discussion of specific episodes"
    ),
    "zh": (
        "podcast recommendation columns and newsletters, 小宇宙 editorial picks "
        "and trending lists, Apple Podcasts 台灣 editorial features, and "
        "discussion of specific episodes on 即刻, 微博, 豆瓣, Threads and PTT. "
        "Cover mainland shows (简体) and Taiwan and Hong Kong shows (繁體) alike"
    ),
}


def nomination_prompt(family: str, week: Week, count: int, listener: str) -> str:
    title_rule = (
        "Use each episode's exact title as published, in Chinese."
        if family == "zh"
        else "Use each episode's exact title as published."
    )
    return (
        f"Find the best {FAMILY_NAMES[family]}-language podcast episodes RELEASED "
        f"between Monday {week.start.isoformat()} and Sunday {week.end.isoformat()} "
        "inclusive. Best means what critics, editors and newsletters singled out "
        "that week, and episodes that genuinely got people talking: a revealing "
        "interview, a scoop, a standout story or series launch. A big show's "
        "routine instalment does not qualify on the show's fame alone.\n\n"
        f"Good places to look: {WHERE_TO_LOOK[family]}.\n\n"
        f"The listener: {listener}\n\n"
        f"Name up to {count} episodes. {title_rule} Only include an episode whose "
        "release date you have seen fall inside the week; give that date as "
        "published (YYYY-MM-DD). In why, say in one English sentence what made it "
        "stand out. In source, give the URL where it was singled out.\n\n"
        "Make at most 8 web searches in total, then answer from what you have. A "
        "shorter list delivered is worth more than a longer one never finished."
    )


def catalogue_digest(candidates: list[Candidate]) -> str:
    lines = []
    for c in candidates:
        date = datetime.datetime.fromtimestamp(
            (c.episode.pub_date_ms or 0) / 1000, datetime.timezone.utc
        ).date()
        minutes = f"{round(c.episode.duration_ms / 60_000)} min" if c.episode.duration_ms else "? min"
        parts = [c.ref, c.show_title, c.episode.title, date.isoformat(), minutes]
        for nomination in c.nominations:
            domain = urlparse(nomination.source).netloc or nomination.source[:40]
            parts.append(f"singled out ({domain}): {nomination.why}")
        notes = plain(c.episode.description)[:CATALOGUE_NOTES]
        if notes:
            parts.append(f"notes: {notes}")
        lines.append(" | ".join(part.replace("|", "/") for part in parts))
    return "\n".join(lines)


def judge_prompt(
    family: str, week: Week, candidates: list[Candidate], count: int, listener: str
) -> str:
    return (
        f"Below is every {FAMILY_NAMES[family]}-language episode in the catalogue "
        f"for the week of {week.start.isoformat()} to {week.end.isoformat()}: the "
        "episodes the charting shows released, plus ones critics or listeners "
        "singled out (marked 'singled out').\n\n"
        f"The listener: {listener}\n\n"
        f"Choose the {count} most worth this listener's time, best first. Weigh "
        "substance and craft, genuine significance (a revealing interview, a "
        "scoop, a standout story), what critics singled out, and fit for the "
        "listener. Prefer breadth: at most one episode per show. A daily news "
        "rundown or a routine chat instalment ranks low unless something notable "
        "happened in it. Reruns, replays, encores and best-of compilations are not "
        "new that week and do not qualify at all, however good the original was. "
        "Score each 1-10 and give a one-sentence English reason.\n\n"
        "<catalogue>\n"
        f"{catalogue_digest(candidates)}\n"
        "</catalogue>"
    )


def blurb_prompt(family: str, picks: list[Pick]) -> str:
    if family == "zh":
        rules = (
            "Write each blurb in Chinese, in the same script as that episode's own "
            "title and notes: Traditional characters for a Traditional-script "
            "show, Simplified for a Simplified one. Two sentences, at most about "
            "90 characters."
        )
    else:
        rules = "Write each blurb in English. Two or three sentences, at most 60 words."
    blocks = []
    for pick in picks:
        c = pick.candidate
        reviewer = " ".join(pick.reasons.values())
        singled = " ".join(n.why for n in c.nominations)
        blocks.append(
            f'<episode ref="{c.ref}">\n'
            f"Show: {c.show_title}\nTitle: {c.episode.title}\n"
            f"Reviewer notes: {reviewer} {singled}".rstrip()
            + f"\nShow notes: {plain(c.episode.description)[:BLURB_NOTES]}\n</episode>"
        )
    return (
        f"{rules} Be specific: name the guest or subject and what the listener "
        "comes away with. No hype words such as must-listen or fascinating, no "
        "emoji, and do not start with the show's name. Write one blurb for every "
        "ref below.\n\n" + "\n\n".join(blocks)
    )


# ---------------------------------------------------------- validating replies


def parse_nominations(payload: dict, agent: str, family: str) -> list[Nomination]:
    nominations = []
    for item in payload.get("episodes", []) or []:
        if not isinstance(item, dict):
            continue
        show = " ".join(str(item.get("show", "")).split())
        episode = " ".join(str(item.get("episode", "")).split())
        if not show or not episode:
            continue
        nominations.append(
            Nomination(
                agent=agent,
                family=family,
                show=show,
                episode=episode,
                why=" ".join(str(item.get("why", "")).split())[:240],
                source=str(item.get("source", "")).strip()[:500],
            )
        )
    return nominations


def parse_picks(payload: dict, refs: dict[str, Candidate]) -> dict[str, tuple[int, str]]:
    """ref -> (score, reason). Unknown refs are dropped, scores clamped to 1..10."""
    picks: dict[str, tuple[int, str]] = {}
    for item in payload.get("picks", []) or []:
        if not isinstance(item, dict):
            continue
        ref = str(item.get("ref", "")).strip()
        if ref not in refs or ref in picks:
            continue
        try:
            score = int(item.get("score", 5))
        except (TypeError, ValueError):
            score = 5
        reason = " ".join(str(item.get("reason", "")).split())[:240]
        picks[ref] = (max(1, min(10, score)), reason)
    return picks


def combine(
    candidates: list[Candidate],
    picks_by_judge: dict[str, dict[str, tuple[int, str]]],
    count: int,
) -> list[Pick]:
    """
    Blends the judges into one ranking.

    A judge's silence counts as zero, so an episode both judges chose outranks
    one only a single judge chose unless that judge was far keener. Being singled
    out on the web is a tiebreak, not a vote.
    """
    judges = [name for name, picks in picks_by_judge.items() if picks]
    if not judges:
        return []
    scored: list[Pick] = []
    for candidate in candidates:
        votes = {
            judge: picks_by_judge[judge][candidate.ref]
            for judge in judges
            if candidate.ref in picks_by_judge[judge]
        }
        if not votes:
            continue
        total = sum(score / 10 for score, _ in votes.values()) / len(judges)
        total += 0.03 * len({n.agent for n in candidate.nominations})
        scored.append(
            Pick(
                candidate=candidate,
                score=round(min(1.0, total), 4),
                picked_by=sorted(votes),
                reasons={judge: reason for judge, (_, reason) in votes.items()},
            )
        )
    scored.sort(
        key=lambda p: (p.score, len(p.picked_by), p.candidate.episode.pub_date_ms or 0),
        reverse=True,
    )
    chosen: list[Pick] = []
    shows: set[str] = set()
    for pick in scored:
        show_key = pick.candidate.show.feed_url or pick.candidate.show_title
        if show_key in shows:
            continue
        shows.add(show_key)
        chosen.append(pick)
        if len(chosen) >= count:
            break
    return chosen


def parse_blurbs(payload: dict, picks: list[Pick], family: str) -> dict[str, str]:
    """ref -> blurb, keeping only blurbs in the right language and of sane length."""
    wanted = {pick.candidate.ref for pick in picks}
    limit = 320 if family == "zh" else 600
    blurbs: dict[str, str] = {}
    for item in payload.get("blurbs", []) or []:
        if not isinstance(item, dict):
            continue
        ref = str(item.get("ref", "")).strip()
        text = " ".join(str(item.get("blurb", "")).split())
        if ref not in wanted or not text or len(text) > limit:
            continue
        chinese = _cjk_ratio(text) >= 0.5
        if chinese != (family == "zh"):
            continue
        blurbs[ref] = text
    return blurbs


# ------------------------------------------------------------------- pipeline


@dataclass(slots=True)
class WeeklyResult:
    week: Week
    picks: list[Pick]
    nominated: int
    located: int
    catalogue: int
    judges: dict[str, list[str]]
    generated_at_ms: int


def run(
    client: httpx.Client,
    profile: ProfileConfig,
    week: Week,
    *,
    countries: tuple[str, ...] = ("us", "gb", "tw", "cn"),
    agent_names: tuple[str, ...] = agents.AGENTS,
    per_language: int = 12,
    excluded_ids: set[str] | None = None,
    blurb_writer: str = "claude",
    ask: Ask = agents.ask,
    use_web: bool = True,
    reuse_nominations: bool = False,
    workers: int = 8,
    work_dir: Path | None = None,
    now_ms: int | None = None,
) -> WeeklyResult:
    excluded_ids = excluded_ids or set()
    now_ms = now_ms or int(datetime.datetime.now().timestamp() * 1000)
    harvest_profile = replace(profile, countries=countries, genres=(), search_terms=())
    suitable = lambda show: is_suitable(show, profile)  # noqa: E731

    def save(name: str, payload: object) -> None:
        if work_dir is None:
            return
        work_dir.mkdir(parents=True, exist_ok=True)
        (work_dir / name).write_text(
            json.dumps(payload, ensure_ascii=False, indent=2, default=str), "utf-8"
        )

    saved_nominations = work_dir / "nominations.json" if work_dir else None
    reuse = reuse_nominations and saved_nominations is not None and saved_nominations.exists()
    hunters = agent_names if use_web and not reuse else ()

    # Stage 1: the web hunt runs alongside the harvest; neither needs the other.
    with ThreadPoolExecutor(max_workers=len(agent_names) * len(FAMILIES) + 1) as pool:
        hunts = {
            (agent, family): pool.submit(
                _nominate, ask, agent, family, week, per_language, profile.listener
            )
            for agent in hunters
            for family in FAMILIES
        }
        shows = [s for s in harvest(client, harvest_profile) if suitable(s)]
        with ThreadPoolExecutor(max_workers=workers) as feeds:
            fetched = list(feeds.map(lambda s: fetch_feed(client, s), shows))
        nominations = [n for future in hunts.values() for n in future.result()]
    if reuse:
        # The hunt is the slow, quota-hungry stage; a rerun after a later stage
        # failed should not have to repeat it.
        raw = json.loads(saved_nominations.read_text(encoding="utf-8"))
        nominations = [Nomination(**item) for item in raw if item.get("agent") in agent_names]
        LOG.info("reusing %d saved nominations", len(nominations))
    else:
        save("nominations.json", [asdict(n) for n in nominations])

    catalogue = build_catalogue(fetched, week, excluded_ids)
    LOG.info("catalogue: %d episodes from %d shows in %s", len(catalogue), len(shows), week.id)

    def lookup_shows(nomination: Nomination) -> list[Show]:
        order = ("tw", "cn", "us") if nomination.family == "zh" else ("us", "gb")
        found: list[Show] = []
        for country in order:
            found += search(client, nomination.show, country, limit=5)
        return found

    def fetch(show: Show) -> ParsedFeed | None:
        return fetch_feed(client, show)[1]

    candidates = merge_nominations(
        catalogue,
        nominations,
        lambda n: locate_nomination(n, week, fetched, lookup_shows, fetch),
        excluded_ids,
        suitable,
    )
    located = sum(len(c.nominations) for c in candidates)
    LOG.info("located %d of %d nominations", located, len(nominations))
    refs = assign_refs(candidates)

    # Stage 3: every judge sees the same catalogue for its language.
    by_family = {f: [c for c in candidates if c.family == f] for f in FAMILIES}
    # Twice what is kept: with shorter lists the two judges overlap too little,
    # and the tail of the section is filled by picks only one of them made.
    shortlist = per_language * 2
    with ThreadPoolExecutor(max_workers=len(agent_names) * len(FAMILIES)) as pool:
        judging = {
            (agent, family): pool.submit(
                _judge, ask, agent, family, week, by_family[family], shortlist,
                profile.listener, refs,
            )
            for agent in agent_names
            for family in FAMILIES
            if by_family[family]
        }
        verdicts = {key: future.result() for key, future in judging.items()}
    save(
        "judges.json",
        {f"{agent}:{family}": picks for (agent, family), picks in verdicts.items()},
    )

    all_picks: list[Pick] = []
    judges_used: dict[str, list[str]] = {}
    for family in FAMILIES:
        per_judge = {
            agent: verdicts.get((agent, family), {}) for agent in agent_names
        }
        judges_used[family] = sorted(agent for agent, picks in per_judge.items() if picks)
        chosen = combine(by_family[family], per_judge, per_language)
        if not chosen:
            LOG.error("%s: no judge produced picks; the section will be empty", family)
            continue
        _write_blurbs(ask, family, chosen, _writers(blurb_writer, agent_names))
        kept = [pick for pick in chosen if pick.blurb]
        if len(kept) < len(chosen):
            LOG.warning("%s: %d picks dropped for want of a blurb", family, len(chosen) - len(kept))
        all_picks += kept

    save(
        "picks.json",
        [
            {
                "ref": p.candidate.ref,
                "show": p.candidate.show_title,
                "episode": p.candidate.episode.title,
                "score": p.score,
                "pickedBy": p.picked_by,
                "reasons": p.reasons,
                "blurb": p.blurb,
            }
            for p in all_picks
        ],
    )
    return WeeklyResult(
        week=week,
        picks=all_picks,
        nominated=len(nominations),
        located=located,
        catalogue=len(catalogue),
        judges=judges_used,
        generated_at_ms=now_ms,
    )


def _writers(preferred: str, agent_names: tuple[str, ...]) -> list[str]:
    ordered = [preferred] if preferred in agent_names else []
    return ordered + [name for name in agent_names if name not in ordered]


def _nominate(
    ask: Ask, agent: str, family: str, week: Week, count: int, listener: str
) -> list[Nomination]:
    try:
        payload = ask(
            agent,
            nomination_prompt(family, week, count, listener),
            system=NOMINATE_SYSTEM,
            schema=NOMINATION_SCHEMA,
            web=True,
            timeout=1500,
        )
    except Exception as error:  # noqa: BLE001 - one hunt failing is survivable
        LOG.error("%s %s hunt failed (%s); continuing without it", agent, family, error)
        return []
    nominations = parse_nominations(payload, agent, family)
    LOG.info("%s nominated %d %s episodes", agent, len(nominations), family)
    return nominations


def _judge(
    ask: Ask,
    agent: str,
    family: str,
    week: Week,
    candidates: list[Candidate],
    count: int,
    listener: str,
    refs: dict[str, Candidate],
) -> dict[str, tuple[int, str]]:
    family_refs = {ref: c for ref, c in refs.items() if c.family == family}
    try:
        payload = ask(
            agent,
            judge_prompt(family, week, candidates, count, listener),
            system=JUDGE_SYSTEM,
            schema=JUDGE_SCHEMA,
            web=False,
            timeout=900,
        )
    except Exception as error:  # noqa: BLE001 - the other judge may still answer
        LOG.error("%s could not judge %s (%s)", agent, family, error)
        return {}
    picks = parse_picks(payload, family_refs)
    LOG.info("%s judged %s: %d picks", agent, family, len(picks))
    return picks


def _write_blurbs(ask: Ask, family: str, picks: list[Pick], writers: list[str]) -> None:
    for writer in writers:
        missing = [pick for pick in picks if not pick.blurb]
        if not missing:
            return
        try:
            payload = ask(
                writer,
                blurb_prompt(family, missing),
                system=BLURB_SYSTEM,
                schema=BLURB_SCHEMA,
                web=False,
                timeout=900,
            )
        except Exception as error:  # noqa: BLE001 - fall through to the next writer
            LOG.error("%s could not write %s blurbs (%s)", writer, family, error)
            continue
        blurbs = parse_blurbs(payload, missing, family)
        for pick in missing:
            pick.blurb = blurbs.get(pick.candidate.ref, "")
        LOG.info("%s wrote %d of %d %s blurbs", writer, len(blurbs), len(missing), family)


# ------------------------------------------------------------------ publishing


def issue_pool(result: WeeklyResult, pool_id: str, label: str) -> dict:
    """The issue in the radio-pool wire format, plus the weekly-only fields."""
    ordered = sorted(result.picks, key=lambda p: p.score, reverse=True)
    entries = []
    extras = []
    for rank, pick in enumerate(ordered, start=1):
        c = pick.candidate
        ep_id = c.id
        entries.append(
            PoolEntry(
                id=ep_id,
                rank=rank,
                score=pick.score,
                language=c.language,
                why=pick.blurb,
                # Genres, not labels: the app files these as the show's categories,
                # which is what its content filters read.
                tags=useful_genres(c.show.genres),
                podcast=PoolPodcast(
                    id=podcast_id(c.show.feed_url),
                    title=c.show_title,
                    author=c.show.author or c.feed.author or "",
                    feedUrl=c.show.feed_url,
                    artworkUrl=c.feed.image_url or c.show.artwork_url,
                    description=(c.feed.description or "")[:240] or None,
                    language=c.language,
                    appleId=c.show.apple_id or None,
                ),
                episode=PoolEpisode(
                    id=ep_id,
                    title=c.episode.title,
                    audioUrl=c.episode.audio_url,
                    pubDateMs=c.episode.pub_date_ms,
                    guid=c.episode.guid,
                    # Full notes: the pool's usual 240-character stub would
                    # overwrite the real notes of an episode already in the app.
                    description=(c.episode.description or "")[:PUBLISHED_NOTES] or None,
                    audioMimeType=c.episode.audio_mime_type,
                    durationMs=c.episode.duration_ms,
                    artworkUrl=c.episode.image_url or c.feed.image_url or c.show.artwork_url,
                ),
            )
        )
        extras.append(
            {
                "pickedBy": pick.picked_by,
                "singledOut": [
                    {"by": n.agent, "why": n.why, "source": n.source} for n in c.nominations
                ],
            }
        )
    pool = PoolFile(
        profileId=pool_id,
        profileLabel=label,
        generatedAtMs=result.generated_at_ms,
        entries=entries,
        sources=sorted(
            {f"judge:{judge}" for judges in result.judges.values() for judge in judges}
            | {"verified:feed"}
        ),
    ).to_dict()
    for entry, extra in zip(pool["entries"], extras):
        entry.update(extra)
    pool["week"] = {
        "id": result.week.id,
        "start": result.week.start.isoformat(),
        "end": result.week.end.isoformat(),
    }
    return pool


def index_entry(result: WeeklyResult) -> dict:
    counts = dict.fromkeys(FAMILIES, 0)
    for pick in result.picks:
        counts[pick.candidate.family] += 1
    return {
        "id": result.week.id,
        "label": result.week.label,
        "weekStart": result.week.start.isoformat(),
        "weekEnd": result.week.end.isoformat(),
        "file": f"{result.week.id}.json",
        "generatedAtMs": result.generated_at_ms,
        "entryCount": len(result.picks),
        "counts": counts,
        # Who actually judged: a week where one model failed must not claim both.
        "pickedBy": sorted({judge for judges in result.judges.values() for judge in judges}),
    }


def updated_index(existing: dict | None, entry: dict) -> dict:
    """The index with [entry] added or replaced, newest week first."""
    issues = [i for i in (existing or {}).get("issues", []) if i.get("id") != entry["id"]]
    issues.append(entry)
    issues.sort(key=lambda i: i.get("weekStart", ""), reverse=True)
    return {"version": 1, "issues": issues}


def previous_issue_ids(weekly_dir: Path, week: Week) -> set[str]:
    """Episode ids of the latest issue before [week], so nothing runs twice."""
    index_path = weekly_dir / "index.json"
    if not index_path.exists():
        return set()
    index = json.loads(index_path.read_text(encoding="utf-8"))
    earlier = [
        issue for issue in index.get("issues", [])
        if issue.get("weekStart", "") < week.start.isoformat()
    ]
    if not earlier:
        return set()
    latest = max(earlier, key=lambda issue: issue.get("weekStart", ""))
    path = weekly_dir / latest.get("file", "")
    if not path.is_file():
        return set()
    data = json.loads(path.read_text(encoding="utf-8"))
    return {entry["id"] for entry in data.get("entries", [])}
