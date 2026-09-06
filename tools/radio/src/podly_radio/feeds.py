"""Reads podcast feeds the same way the app does.

This mirrors ``app/src/main/java/com/podly/network/RssParser.kt`` deliberately
and literally, because the ids the generator publishes are
``stableId(guid ?: audioUrl)``. Any disagreement about which enclosure counts,
or about the exact text of a guid, produces a duplicate episode row in the app.

Two rules are easy to get wrong and are the reason this is hand-rolled rather
than ``feedparser``:

* ``feedparser`` normalises ``entry.id`` — it resolves a relative guid against
  the feed URL and applies ``isPermaLink`` semantics. That silently changes the
  string being hashed. Here the guid is the raw element text, stripped.
* The enclosure is the first one whose ``type`` starts with ``audio`` *or is
  absent entirely*, not simply the first enclosure.

``tests/test_rss_parity.py`` and ``RssParityFixtureTest.kt`` both assert against
the same checked-in fixture.
"""

from __future__ import annotations

import datetime
from dataclasses import dataclass
from email.utils import parsedate_to_datetime

from lxml import etree


@dataclass(frozen=True, slots=True)
class ParsedEpisode:
    guid: str | None
    title: str
    description: str | None
    audio_url: str
    audio_mime_type: str | None
    pub_date_ms: int | None
    duration_ms: int | None
    image_url: str | None


@dataclass(frozen=True, slots=True)
class ParsedFeed:
    title: str | None
    author: str | None
    description: str | None
    image_url: str | None
    language: str | None
    episodes: tuple[ParsedEpisode, ...]


def _tag(element: etree._Element) -> str:
    """The tag as the app sees it: namespace processing off, so ``itunes:duration``."""
    raw = element.tag
    if not isinstance(raw, str):
        return ""
    local = raw.rsplit("}", 1)[-1]
    prefix = element.prefix
    return f"{prefix}:{local}".lower() if prefix else local.lower()


def _text(element: etree._Element) -> str | None:
    """Element text, stripped, empty becomes None — the app's ``nextTextSafe``."""
    text = "".join(element.itertext())
    text = text.strip()
    return text or None


def parse_duration(text: str | None) -> int | None:
    """Accepts ``HH:MM:SS``, ``MM:SS`` and bare seconds, like the app."""
    if not text or not text.strip():
        return None
    parts = text.strip().split(":")
    try:
        if len(parts) == 1:
            seconds = int(float(parts[0]))
        elif len(parts) == 2:
            seconds = int(parts[0]) * 60 + int(parts[1])
        elif len(parts) == 3:
            seconds = int(parts[0]) * 3600 + int(parts[1]) * 60 + int(parts[2])
        else:
            return None
    except ValueError:
        return None
    return seconds * 1000


def parse_pub_date(text: str | None) -> int | None:
    """
    RFC-822 dates, plus the bare ISO form the app also accepts.

    Anything unparseable returns None and the caller drops the episode. Never
    substitute "now": a fabricated date would poison the app's chronological
    sorts, and the app itself stores 0 for an undated item.
    """
    if not text or not text.strip():
        return None
    raw = text.strip()
    try:
        return int(parsedate_to_datetime(raw).timestamp() * 1000)
    except (TypeError, ValueError):
        pass
    for pattern in ("%Y-%m-%dT%H:%M:%SZ", "%Y-%m-%dT%H:%M:%S%z"):
        try:
            parsed = datetime.datetime.strptime(raw, pattern)
        except ValueError:
            continue
        if parsed.tzinfo is None:
            parsed = parsed.replace(tzinfo=datetime.timezone.utc)
        return int(parsed.timestamp() * 1000)
    return None


def parse_feed(xml: bytes) -> ParsedFeed:
    parser = etree.XMLParser(recover=True, huge_tree=True, resolve_entities=False)
    root = etree.fromstring(xml, parser=parser)
    if root is None:
        return ParsedFeed(None, None, None, None, None, ())

    channel_title = channel_author = channel_description = None
    channel_image = channel_language = None
    episodes: list[ParsedEpisode] = []

    for element in root.iter():
        tag = _tag(element)
        if tag == "item":
            episode = _parse_item(element)
            if episode is not None:
                episodes.append(episode)
        elif tag == "title" and channel_title is None:
            channel_title = _text(element)
        elif tag == "itunes:author":
            channel_author = _text(element)
        elif tag == "description" and channel_description is None:
            channel_description = _text(element)
        elif tag == "language" and channel_language is None:
            channel_language = _text(element)
        elif tag == "itunes:image":
            channel_image = element.get("href") or channel_image

    return ParsedFeed(
        title=channel_title,
        author=channel_author,
        description=channel_description,
        image_url=channel_image,
        language=channel_language,
        episodes=tuple(episodes),
    )


def _parse_item(item: etree._Element) -> ParsedEpisode | None:
    title = guid = description = None
    audio_url = audio_type = pub_date = duration = image = None

    for element in item.iter():
        tag = _tag(element)
        if tag == "title":
            title = _text(element)
        elif tag == "guid":
            guid = _text(element)
        elif tag in ("description", "itunes:summary", "content:encoded"):
            if description is None:
                description = _text(element)
        elif tag == "enclosure":
            url = element.get("url")
            kind = element.get("type") or ""
            # First audio-or-untyped enclosure wins, not simply the first one.
            if audio_url is None and url and (kind.startswith("audio") or kind == ""):
                audio_url = url
                audio_type = kind or None
        elif tag == "pubdate":
            pub_date = _text(element)
        elif tag == "itunes:duration":
            duration = _text(element)
        elif tag == "itunes:image":
            image = element.get("href") or image

    # The app only emits an episode when it has both audio and a title.
    if audio_url is None or title is None:
        return None
    return ParsedEpisode(
        guid=guid,
        title=title,
        description=description,
        audio_url=audio_url,
        audio_mime_type=audio_type,
        pub_date_ms=parse_pub_date(pub_date),
        duration_ms=parse_duration(duration),
        image_url=image,
    )
