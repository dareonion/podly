"""Collects candidate shows for a profile, without judgement."""

from __future__ import annotations

import logging

import httpx

from .config import ProfileConfig
from .sources import Show, genre_chart, lookup, search, top_chart

LOG = logging.getLogger(__name__)


def harvest(client: httpx.Client, profile: ProfileConfig) -> list[Show]:
    """Chart + genre-chart + search, merged by Apple id, best rank kept."""
    found: dict[str, Show] = {}

    def merge(shows: list[Show]) -> None:
        for show in shows:
            if not show.apple_id:
                continue
            existing = found.get(show.apple_id)
            if existing is None:
                found[show.apple_id] = show
                continue
            existing.sources |= show.sources
            existing.genre_ids = tuple(set(existing.genre_ids) | set(show.genre_ids))
            if show.chart_rank is not None and (
                existing.chart_rank is None or show.chart_rank < existing.chart_rank
            ):
                existing.chart_rank = show.chart_rank
                existing.chart_label = show.chart_label

    for country in profile.countries:
        merge(top_chart(client, country))
        for genre in profile.genres:
            merge(genre_chart(client, country, genre))
        for term in profile.search_terms:
            merge(search(client, term, country, genre=profile.genres[0] if profile.genres else None))

    # One batched lookup per storefront fills in feed URLs and advisory ratings.
    for country in profile.countries:
        ids = [s.apple_id for s in found.values() if s.feed_url is None]
        if not ids:
            break
        for apple_id, resolved in lookup(client, ids, country).items():
            show = found.get(apple_id)
            if show is None or resolved.feed_url is None:
                continue
            show.feed_url = resolved.feed_url
            show.artwork_url = show.artwork_url or resolved.artwork_url
            show.author = show.author or resolved.author
            show.genres = resolved.genres or show.genres
            show.genre_ids = tuple(set(show.genre_ids) | set(resolved.genre_ids))
            show.advisory = resolved.advisory

    shows = [s for s in found.values() if s.feed_url]
    LOG.info("harvested %d shows with feeds for %s", len(shows), profile.id)
    return shows


def roster_for(shows: list[Show], profile: ProfileConfig) -> list[Show]:
    """
    Trims the harvest to a roster, round-robining across storefronts.

    Taking the first N by chart rank would fill the whole roster from whichever
    country was fetched first — the "you" profile came out 100% English that
    way, even though it asks for English *and* Chinese. Interleaving keeps every
    configured storefront represented.
    """
    by_country: dict[str, list[Show]] = {}
    for show in shows:
        by_country.setdefault(show.country or "", []).append(show)
    for group in by_country.values():
        group.sort(key=lambda s: s.chart_rank or 10_000)

    order = [by_country[c] for c in profile.countries if c in by_country]
    order += [group for country, group in by_country.items() if country not in profile.countries]

    roster: list[Show] = []
    index = 0
    while len(roster) < profile.roster_size:
        added = False
        for group in order:
            if index < len(group):
                roster.append(group[index])
                added = True
                if len(roster) >= profile.roster_size:
                    break
        if not added:
            break
        index += 1
    return roster


def is_suitable(show: Show, profile: ProfileConfig) -> bool:
    """Profile-level gates that do not need the feed."""
    if profile.require_clean and (show.advisory or "").lower() == "explicit":
        return False
    if profile.genres and show.genre_ids:
        if not set(profile.genres) & set(show.genre_ids):
            return False
    # An excluded genre wins over an included one: a show filed under both
    # "Society & Culture" and "True Crime" is still true crime. An exempt genre
    # then wins over the exclusion — Apple files parenting shows for adults under
    # Kids & Family, and those are wanted.
    if profile.exclude_genres and set(profile.exclude_genres) & set(show.genre_ids):
        if not (set(profile.exempt_genres) & set(show.genre_ids)):
            return False
    return True
