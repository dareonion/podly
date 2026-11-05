"""Resolve shows with the shared Apple catalogue and select feed episodes."""

from __future__ import annotations

import datetime as dt
import re
from urllib.parse import urlparse

import httpx

from podly_radio import sources
from podly_radio.feeds import ParsedEpisode, ParsedFeed, parse_feed
from podly_radio.ids import episode_id

from .text import timestamp


class SelectionError(ValueError):
    pass


def resolve_show(client: httpx.Client, show: str) -> str:
    parsed = urlparse(show)
    if parsed.scheme in ("http", "https"):
        if parsed.hostname in ("podcasts.apple.com", "itunes.apple.com"):
            match = re.search(r"/id(\d+)", parsed.path)
            if not match:
                raise SelectionError("Apple Podcasts URL has no /id<number>")
            country = parsed.path.strip("/").split("/")[0]
            if not re.fullmatch(r"[a-zA-Z]{2}", country):
                country = "us"
            result = sources.lookup(client, [match[1]], country.lower()).get(match[1])
            if result is None or not result.feed_url:
                raise SelectionError(f"Apple has no feed for id {match[1]}")
            return result.feed_url
        return show
    matches = sources.search(client, show, "us")
    if len(matches) != 1:
        candidates = "\n".join(f"  {s.title}\t{s.feed_url or '(no feed URL)'}" for s in matches)
        raise SelectionError(f"Show {show!r} matched {len(matches)} shows; use a feed URL.\n"
                             f"{candidates or '  No candidates.'}")
    if not matches[0].feed_url:
        raise SelectionError(f"Apple has no feed for {matches[0].title}")
    return matches[0].feed_url


def fetch_feed(client: httpx.Client, feed_url: str) -> ParsedFeed:
    response = client.get(feed_url)
    response.raise_for_status()
    feed = parse_feed(response.content)
    if not feed.episodes:
        raise ValueError(f"No playable episodes in {feed_url}")
    return feed


def newest(episodes) -> list[ParsedEpisode]:
    return sorted(episodes, key=lambda e: e.pub_date_ms or 0, reverse=True)


def episode_line(index: int, episode: ParsedEpisode) -> str:
    date = (str(dt.datetime.fromtimestamp(episode.pub_date_ms / 1000, dt.UTC).date())
            if episode.pub_date_ms is not None else "undated")
    duration = (timestamp(episode.duration_ms, milliseconds=False)
                if episode.duration_ms is not None else "unknown")
    return (f"{index}\t{date}\t{duration}\t{episode.title}\t"
            f"{episode_id(episode.guid, episode.audio_url)}")


def leading_number(title: str) -> int | None:
    match = re.match(r"^\s*(?:EP\s*|#\s*|第\s*)?(\d+)(?!\d)", title, re.IGNORECASE)
    return int(match[1]) if match else None


def select(episodes, selectors: list[str]) -> list[ParsedEpisode]:
    ordered = newest(episodes)
    selected = set()
    errors = []
    for selector in selectors:
        if selector.startswith("latest:"):
            count = selector.removeprefix("latest:")
            if not count.isascii() or not count.isdigit() or not 0 < int(count) <= len(ordered):
                errors.append(f"{selector!r}: count must be between 1 and {len(ordered)}")
                continue
            matches = ordered[:int(count)]
        else:
            if selector.isascii() and selector.isdigit():
                matches = [e for e in ordered if leading_number(e.title) == int(selector)]
            else:
                matches = [e for e in ordered if selector.strip()
                           and selector.casefold() in e.title.casefold()]
            if len(matches) != 1:
                candidates = matches or ordered
                listing = "\n".join(episode_line(i, e) for i, e in enumerate(candidates, 1))
                errors.append(
                    f"{selector!r}: matched {len(matches)} episodes. Candidates:\n{listing}"
                )
                continue
        selected.update(episode_id(e.guid, e.audio_url) for e in matches)
    if errors:
        raise SelectionError("\n".join(errors))
    return [e for e in ordered if episode_id(e.guid, e.audio_url) in selected]
