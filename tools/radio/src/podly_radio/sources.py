"""Keyless catalogue sources.

Everything here works without an API key, which is the point: the pool has to
refresh on a timer without touching a paid API.

* Apple's marketing-tools chart gives overall popularity per storefront.
* The older per-genre chart is the only keyless way to ask for "top Mandarin
  kids' shows"; it is undocumented and long-deprecated, so every caller must
  cope with it returning nothing.
* iTunes lookup resolves Apple ids to feed URLs in one batched call, and
  carries the content advisory rating the toddler filter needs.
"""

from __future__ import annotations

import logging
from dataclasses import dataclass, field

import httpx

LOG = logging.getLogger(__name__)

CHART_URL = "https://rss.marketingtools.apple.com/api/v2/{cc}/podcasts/top/{limit}/podcasts.json"
GENRE_CHART_URL = "https://itunes.apple.com/{cc}/rss/toppodcasts/limit={limit}/genre={genre}/json"
LOOKUP_URL = "https://itunes.apple.com/lookup"
SEARCH_URL = "https://itunes.apple.com/search"

# Apple podcast genres worth naming.
GENRE_KIDS_FAMILY = 1305
GENRE_EDUCATION_FOR_KIDS = 1519
GENRE_STORIES_FOR_KIDS = 1520
KID_GENRES = (GENRE_KIDS_FAMILY, GENRE_EDUCATION_FOR_KIDS, GENRE_STORIES_FOR_KIDS)


@dataclass(slots=True)
class Show:
    """A candidate show, before any feed has been fetched."""

    apple_id: str
    title: str
    author: str | None = None
    feed_url: str | None = None
    artwork_url: str | None = None
    genres: tuple[str, ...] = ()
    genre_ids: tuple[int, ...] = ()
    advisory: str | None = None
    country: str | None = None
    """Best (lowest) chart position seen, and the chart it came from."""
    chart_rank: int | None = None
    chart_label: str | None = None
    sources: set[str] = field(default_factory=set)


def _get_json(client: httpx.Client, url: str, **params: object) -> dict | None:
    try:
        response = client.get(url, params=params or None)
        response.raise_for_status()
        return response.json()
    except (httpx.HTTPError, ValueError) as error:
        LOG.warning("source failed: %s (%s)", url, error)
        return None


def top_chart(client: httpx.Client, country: str, limit: int = 100) -> list[Show]:
    """Apple's overall popularity chart for one storefront."""
    payload = _get_json(client, CHART_URL.format(cc=country, limit=limit))
    results = (payload or {}).get("feed", {}).get("results", [])
    shows = []
    for rank, row in enumerate(results, start=1):
        shows.append(
            Show(
                apple_id=str(row.get("id")),
                title=row.get("name", ""),
                author=row.get("artistName"),
                artwork_url=row.get("artworkUrl100"),
                country=country,
                chart_rank=rank,
                chart_label=f"Apple {country.upper()} #{rank}",
                sources={f"apple-charts:{country}"},
            )
        )
    return shows


def genre_chart(
    client: httpx.Client, country: str, genre: int, limit: int = 100
) -> list[Show]:
    """
    Popularity *within* a genre. Undocumented and deprecated, and the only
    keyless way to get e.g. the Taiwanese kids' chart — so treat an empty
    result as normal and let the caller fall back.
    """
    payload = _get_json(
        client, GENRE_CHART_URL.format(cc=country, limit=limit, genre=genre)
    )
    entries = (payload or {}).get("feed", {}).get("entry", []) or []
    if isinstance(entries, dict):
        entries = [entries]
    shows = []
    for rank, entry in enumerate(entries, start=1):
        attributes = entry.get("id", {}).get("attributes", {})
        apple_id = attributes.get("im:id")
        if not apple_id:
            continue
        shows.append(
            Show(
                apple_id=str(apple_id),
                title=entry.get("im:name", {}).get("label", ""),
                author=entry.get("im:artist", {}).get("label"),
                country=country,
                genre_ids=(genre,),
                chart_rank=rank,
                chart_label=f"Apple {country.upper()} genre {genre} #{rank}",
                sources={f"itunes-genre:{country}/{genre}"},
            )
        )
    return shows


def search(
    client: httpx.Client, term: str, country: str, genre: int | None = None, limit: int = 25
) -> list[Show]:
    params: dict[str, object] = {
        "term": term,
        "country": country,
        "media": "podcast",
        "limit": limit,
    }
    if genre is not None:
        params["genreId"] = genre
    payload = _get_json(client, SEARCH_URL, **params)
    return [_show_from_lookup(row, country) for row in (payload or {}).get("results", [])]


def lookup(client: httpx.Client, apple_ids: list[str], country: str) -> dict[str, Show]:
    """
    Resolves ids to feed URLs, genres and advisory ratings.

    Batched: Apple takes a comma-separated id list, so a whole chart costs one
    request. Chunked because very long URLs get rejected.
    """
    resolved: dict[str, Show] = {}
    for start in range(0, len(apple_ids), 100):
        chunk = apple_ids[start : start + 100]
        payload = _get_json(
            client, LOOKUP_URL, id=",".join(chunk), entity="podcast", country=country
        )
        for row in (payload or {}).get("results", []):
            show = _show_from_lookup(row, country)
            if show.apple_id:
                resolved[show.apple_id] = show
    return resolved


def _show_from_lookup(row: dict, country: str) -> Show:
    genre_ids = tuple(
        int(value) for value in row.get("genreIds", []) if str(value).isdigit()
    )
    return Show(
        apple_id=str(row.get("collectionId") or row.get("trackId") or ""),
        title=row.get("collectionName", ""),
        author=row.get("artistName"),
        feed_url=row.get("feedUrl"),
        artwork_url=row.get("artworkUrl600") or row.get("artworkUrl100"),
        genres=tuple(row.get("genres", [])),
        genre_ids=genre_ids,
        advisory=row.get("contentAdvisoryRating"),
        country=country,
    )
