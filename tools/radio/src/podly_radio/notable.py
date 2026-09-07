"""Finds episodes worth singling out: award winners, critics' picks, and hits.

Two kinds of notable, one bar. "Acclaimed" is an award or a critic's verdict;
"popular" is an episode a lot of people actually heard and talked about — a
breakout interview, a viral instalment, the one everyone quoted that month.
They are different signals and neither implies the other, so both are hunted
and each entry says which it is.

Claude may nominate anything it can find, but nothing reaches the app until it
has been located in a real feed with real audio. A nomination that cannot be
verified is dropped, not published with a shrug.

That inversion is what makes search-led acclaim safe: the model supplies the
judgement and the citation, the code supplies the proof.
"""

from __future__ import annotations

import datetime
import json
import logging
import subprocess
from dataclasses import dataclass

import httpx

from .curate import _child_env, _extract_json, resolve_claude
from .feeds import ParsedEpisode, parse_feed
from .match import best_match
from .sources import search

LOG = logging.getLogger(__name__)

SYSTEM_PROMPT = (
    "You are an expert on podcasts, both their awards and their audiences. You "
    "name specific, real episodes of two kinds. ACCLAIMED: won or was nominated "
    "for a major award (Peabody, Ambies, Pulitzer Prize for Audio Reporting, "
    "duPont-Columbia, British Podcast Awards, Third Coast, 金鐘獎), or appeared "
    "on a prominent critic's best-of list. POPULAR: a lot of people actually "
    "heard it — a breakout interview, an instalment that went viral, the "
    "episode everyone was quoting, a record-breaking guest. Popularity and "
    "acclaim are different things and neither implies the other. Use each "
    "episode's exact published title. If you are not confident an episode is "
    "real and correctly titled, leave it out — a short accurate list is worth "
    "far more than a long one. Reply with ONLY a JSON object."
)

RESPONSE_SHAPE = (
    'Reply as {"episodes":[{"show":"exact podcast title",'
    '"episode":"exact episode title","kind":"acclaimed or popular",'
    '"accolade":"one short sentence naming the award, the list and its year, or '
    'what made it a hit","language":"en or zh"}]}'
)


@dataclass(frozen=True, slots=True)
class Nomination:
    show: str
    episode: str
    accolade: str
    language: str = "en"
    kind: str = "acclaimed"


@dataclass(slots=True)
class VerifiedNotable:
    nomination: Nomination
    feed_url: str
    show_title: str
    show_author: str
    show_artwork: str | None
    episode: ParsedEpisode
    score: float


def ask_for_nominations(
    count: int,
    model: str = "claude-opus-4-8",
    timeout: int = 900,
    allow_search: bool = True,
    recent_year: int | None = None,
    recent_share: float = 0.7,
    popular_share: float = 0.5,
) -> list[Nomination]:
    claude = resolve_claude()
    if claude is None:
        raise RuntimeError("the claude CLI is not installed")
    recent_year = recent_year or (datetime.date.today().year - 1)
    recent = max(1, int(count * recent_share))
    popular = max(1, int(count * popular_share))
    prompt = (
        f"List {count} podcast episodes worth singling out. "
        f"About {popular} of them should be POPULAR rather than acclaimed — "
        "episodes that were widely heard and widely discussed, whether or not "
        "any jury noticed them. The remainder should be ACCLAIMED. "
        f"At least {recent} of the {count} MUST be from {recent_year} or later "
        "— this year's and last year's award cycles, recent nominees, current "
        "best-of lists, and the episodes people were actually talking about "
        "recently. Search for them rather than relying on memory. "
        "Cover both English and Mandarin-language podcasts. "
        "Strongly prefer episodes still present in the show's RSS feed: an "
        "episode that has rolled out of the feed cannot be played and will be "
        "discarded. "
        + RESPONSE_SHAPE
    )
    command = [
        claude,
        "-p",
        "--model", model,
        "--system-prompt", SYSTEM_PROMPT,
        "--output-format", "json",
    ]
    if allow_search:
        # Awards move faster than a training cutoff, so this one hunt gets the
        # web. Everything it returns is verified against a feed afterwards.
        command += ["--allowed-tools", "WebSearch", "--max-turns", "8"]
    else:
        command += ["--tools", "", "--max-turns", "1"]

    LOG.info("asking for up to %d notable episodes (search=%s)", count, allow_search)
    result = subprocess.run(
        command, input=prompt, capture_output=True, text=True,
        timeout=timeout, env=_child_env(),
    )
    if result.returncode != 0:
        # The CLI sometimes exits non-zero with an empty stderr and the detail in
        # stdout, so log both or the next failure is undiagnosable.
        raise RuntimeError(
            f"claude exited {result.returncode}: "
            f"stderr={result.stderr[:300]!r} stdout={result.stdout[:300]!r}"
        )
    envelope = json.loads(result.stdout)
    if envelope.get("is_error"):
        raise RuntimeError(f"claude reported an error: {envelope.get('result')}")
    payload = _extract_json(envelope.get("result", ""))

    nominations = []
    for item in payload.get("episodes", []):
        show = str(item.get("show", "")).strip()
        episode = str(item.get("episode", "")).strip()
        accolade = " ".join(str(item.get("accolade", "")).split())[:200]
        kind = str(item.get("kind", "acclaimed")).strip().lower()
        if kind not in ("acclaimed", "popular"):
            kind = "acclaimed"
        if show and episode and accolade:
            nominations.append(
                Nomination(
                    show, episode, accolade, str(item.get("language", "en")), kind
                )
            )
    return nominations


def verify(
    client: httpx.Client,
    nominations: list[Nomination],
    countries: tuple[str, ...] = ("us", "tw"),
) -> list[VerifiedNotable]:
    """Resolves each nomination to a real episode, dropping what cannot be found."""
    verified: list[VerifiedNotable] = []
    feed_cache: dict[str, tuple] = {}

    for nomination in nominations:
        show = _resolve_show(client, nomination, countries)
        if show is None:
            LOG.info("dropped (no such show): %s", nomination.show)
            continue
        if show.feed_url not in feed_cache:
            feed_cache[show.feed_url] = _fetch(client, show.feed_url)
        feed = feed_cache[show.feed_url]
        if feed is None or not feed.episodes:
            LOG.info("dropped (feed unreadable): %s", nomination.show)
            continue
        titles = [episode.title for episode in feed.episodes]
        match = best_match(nomination.episode, titles)
        if match is None:
            LOG.info("dropped (episode not in feed): %s — %s", nomination.show, nomination.episode)
            continue
        episode = feed.episodes[match.index]
        if not episode.audio_url or not episode.pub_date_ms:
            LOG.info("dropped (no playable audio): %s", nomination.episode)
            continue
        verified.append(
            VerifiedNotable(
                nomination=nomination,
                feed_url=show.feed_url,
                show_title=show.title or feed.title or nomination.show,
                show_author=show.author or feed.author or "",
                show_artwork=show.artwork_url or feed.image_url,
                episode=episode,
                score=round(0.9 * match.score, 4),
            )
        )
    LOG.info("verified %d of %d nominations", len(verified), len(nominations))
    return verified


def _resolve_show(client: httpx.Client, nomination: Nomination, countries):
    order = ("tw", "us") if nomination.language.startswith("zh") else countries
    for country in order:
        results = search(client, nomination.show, country, limit=5)
        for candidate in results:
            if not candidate.feed_url:
                continue
            if best_match(nomination.show, [candidate.title]) is not None:
                return candidate
    return None


def _fetch(client: httpx.Client, feed_url: str):
    try:
        response = client.get(feed_url, timeout=25)
        response.raise_for_status()
        return parse_feed(response.content)
    except (httpx.HTTPError, ValueError) as error:
        LOG.warning("feed failed %s: %s", feed_url, error)
        return None
