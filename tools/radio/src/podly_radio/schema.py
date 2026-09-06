"""The published wire format.

Entries are *fully resolved*: each carries everything the app needs to insert a
podcast row and an episode row with no network call of its own. That is the
whole point of the pipeline — pressing play in the app must not depend on a
live feed fetch and a fuzzy title match, which is what the older picks path
does and why it drops Chinese picks entirely.

``docs/radio-pool.schema.json`` is the normative definition; this module and
the app's Kotlin classes both have to satisfy it.
"""

from __future__ import annotations

from dataclasses import asdict, dataclass, field

SCHEMA_VERSION = 1


@dataclass(slots=True)
class PoolPodcast:
    id: str
    title: str
    author: str
    feedUrl: str
    artworkUrl: str | None = None
    description: str | None = None
    language: str | None = None
    appleId: str | None = None


@dataclass(slots=True)
class PoolEpisode:
    id: str
    title: str
    audioUrl: str
    pubDateMs: int
    guid: str | None = None
    description: str | None = None
    audioMimeType: str | None = None
    durationMs: int | None = None
    artworkUrl: str | None = None


@dataclass(slots=True)
class PoolEntry:
    id: str
    rank: int
    score: float
    podcast: PoolPodcast
    episode: PoolEpisode
    language: str | None = None
    why: str | None = None
    tags: list[str] = field(default_factory=list)


@dataclass(slots=True)
class PoolFile:
    profileId: str
    profileLabel: str
    generatedAtMs: int
    entries: list[PoolEntry]
    version: int = SCHEMA_VERSION
    sources: list[str] = field(default_factory=list)

    def to_dict(self) -> dict:
        return asdict(self)


class PoolValidationError(Exception):
    """Raised when a built pool is not fit to publish."""


def validate(pool: PoolFile, min_entries: int, min_shows: int) -> None:
    """
    Refuses to publish a pool that would make radio worse than not refreshing.

    A thin or broken pool is worse than a stale one: the app would replace
    working candidates with nothing.
    """
    if pool.version != SCHEMA_VERSION:
        raise PoolValidationError(f"unexpected version {pool.version}")
    if len(pool.entries) < min_entries:
        raise PoolValidationError(
            f"{pool.profileId}: {len(pool.entries)} entries, need {min_entries}"
        )
    shows = {entry.podcast.id for entry in pool.entries}
    if len(shows) < min_shows:
        raise PoolValidationError(
            f"{pool.profileId}: {len(shows)} shows, need {min_shows}"
        )
    seen: set[str] = set()
    for entry in pool.entries:
        if not entry.episode.audioUrl:
            raise PoolValidationError(f"{entry.id}: no audio url")
        if not entry.episode.pubDateMs:
            raise PoolValidationError(f"{entry.id}: no publication date")
        if entry.id in seen:
            raise PoolValidationError(f"{entry.id}: duplicate entry")
        seen.add(entry.id)
