"""Turns a roster of shows into a pool of fully-resolved, playable episodes."""

from __future__ import annotations

import logging
import time
from concurrent.futures import ThreadPoolExecutor

import httpx

from .config import ProfileConfig
from .feeds import ParsedFeed, parse_feed
from .ids import episode_id, podcast_id
from .lang import detect
from .schema import PoolEntry, PoolEpisode, PoolFile, PoolPodcast
from .select import Candidate, episode_score, interleave, show_weight
from .sources import Show

LOG = logging.getLogger(__name__)
MAX_DESCRIPTION = 240


# Every show carries it, in whatever language, and it says nothing.
_NOISE_GENRES = frozenset({"podcasts", "podcast", "播客"})


def useful_genres(genres: tuple[str, ...] | list[str]) -> list[str]:
    """A show's genres, in full.

    Not truncated: the app stores these as the signal its content filters read,
    and a show's fourth genre is as disqualifying as its first.
    """
    return [g for g in genres if g.strip().lower() not in _NOISE_GENRES]


def fetch_feed(client: httpx.Client, show: Show) -> tuple[Show, ParsedFeed | None]:
    try:
        response = client.get(show.feed_url, timeout=25)
        response.raise_for_status()
        return show, parse_feed(response.content)
    except (httpx.HTTPError, ValueError) as error:
        LOG.warning("feed failed for %s: %s", show.title, error)
        return show, None


def build_pool(
    client: httpx.Client,
    profile: ProfileConfig,
    shows: list[Show],
    curated: dict[str, float] | None = None,
    reasons: dict[str, str] | None = None,
    now_ms: int | None = None,
    workers: int = 8,
) -> PoolFile:
    now_ms = now_ms or int(time.time() * 1000)
    curated = curated or {}
    reasons = reasons or {}
    roster = shows[: profile.roster_size] if len(shows) > profile.roster_size else shows

    with ThreadPoolExecutor(max_workers=workers) as pool:
        fetched = list(pool.map(lambda s: fetch_feed(client, s), roster))

    candidates: list[Candidate] = []
    episodes_by_key: dict[tuple[str, int], tuple[Show, ParsedFeed, int]] = {}
    for show, feed in fetched:
        if feed is None or not feed.episodes:
            continue
        weight = show_weight(show.chart_rank, len(roster), curated.get(show.apple_id))
        language = detect(f"{show.title} {feed.title or ''}", feed.language)
        if profile.languages and not any(
            language.startswith(want.split("-")[0]) for want in profile.languages
        ):
            continue
        for index, episode in enumerate(feed.episodes):
            # No date means no entry: inventing one would poison the app's
            # chronological sorting, and the app itself stores 0 for undated.
            if not episode.pub_date_ms:
                continue
            duration = episode.duration_ms
            if duration is not None and not (
                profile.hard_ms[0] <= duration <= profile.hard_ms[1]
            ):
                continue
            key = (show.apple_id, index)
            episodes_by_key[key] = (show, feed, index)
            candidates.append(
                Candidate(
                    show_id=show.apple_id,
                    show_weight=weight,
                    episode_index=index,
                    pub_date_ms=episode.pub_date_ms,
                    duration_ms=duration,
                    language=language,
                )
            )

    ranked = sorted(
        candidates,
        key=lambda c: episode_score(c, now_ms, *profile.sweet_spot_ms),
        reverse=True,
    )
    chosen = interleave(ranked, profile.max_per_show, profile.target_entries)

    entries: list[PoolEntry] = []
    for rank, candidate in enumerate(chosen, start=1):
        show, feed, index = episodes_by_key[(candidate.show_id, candidate.episode_index)]
        episode = feed.episodes[index]
        pod_id = podcast_id(show.feed_url)
        ep_id = episode_id(episode.guid, episode.audio_url)
        entries.append(
            PoolEntry(
                id=ep_id,
                rank=rank,
                score=round(episode_score(candidate, now_ms, *profile.sweet_spot_ms), 4),
                language=candidate.language,
                why=reasons.get(show.apple_id) or show.chart_label,
                tags=list(show.genres[:3]),
                podcast=PoolPodcast(
                    id=pod_id,
                    title=show.title or feed.title or "",
                    author=show.author or feed.author or "",
                    feedUrl=show.feed_url,
                    artworkUrl=show.artwork_url or feed.image_url,
                    description=(feed.description or "")[:MAX_DESCRIPTION] or None,
                    language=candidate.language,
                    appleId=show.apple_id,
                ),
                episode=PoolEpisode(
                    id=ep_id,
                    title=episode.title,
                    audioUrl=episode.audio_url,
                    pubDateMs=episode.pub_date_ms,
                    guid=episode.guid,
                    description=(episode.description or "")[:MAX_DESCRIPTION] or None,
                    audioMimeType=episode.audio_mime_type,
                    durationMs=episode.duration_ms,
                    artworkUrl=episode.image_url or show.artwork_url,
                ),
            )
        )

    sources = sorted({source for show in roster for source in show.sources})
    return PoolFile(
        profileId=profile.id,
        profileLabel=profile.label,
        generatedAtMs=now_ms,
        entries=entries,
        sources=sources,
    )
