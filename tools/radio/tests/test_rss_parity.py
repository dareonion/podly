"""The Python half of the feed contract; RssParityFixtureTest.kt is the other."""

from __future__ import annotations

import json
from pathlib import Path

from podly_radio.feeds import parse_feed
from podly_radio.ids import episode_id

REPO = Path(__file__).resolve().parents[3]
FIXTURE = REPO / "app/src/test/resources/parity-feed.xml"
EXPECTED = REPO / "app/src/test/resources/parity-feed.expected.json"


def test_matches_the_shared_fixture() -> None:
    expected = json.loads(EXPECTED.read_text(encoding="utf-8"))
    parsed = parse_feed(FIXTURE.read_bytes())

    # The item with no enclosure is not an episode.
    assert len(parsed.episodes) == len(expected)
    for episode, want in zip(parsed.episodes, expected, strict=True):
        assert episode.title == want["title"]
        assert episode.guid == want["guid"]
        assert episode.audio_url == want["audioUrl"]
        assert episode.duration_ms == want["durationMs"]
        assert episode.pub_date_ms == want["pubDateMs"]
        assert episode_id(episode.guid, episode.audio_url) == want["id"]


def test_a_relative_guid_is_not_resolved() -> None:
    parsed = parse_feed(FIXTURE.read_bytes())
    relative = next(e for e in parsed.episodes if e.title == "Relative guid")
    # feedparser would turn this into an absolute URL and change the hash.
    assert relative.guid == "/episodes/3"


def test_an_untyped_enclosure_still_counts_and_an_image_one_does_not() -> None:
    parsed = parse_feed(FIXTURE.read_bytes())
    untyped = next(e for e in parsed.episodes if e.title == "Untyped enclosure")
    assert untyped.audio_url.endswith("untyped.mp3")
    two = next(e for e in parsed.episodes if e.title == "Two enclosures")
    assert two.audio_url.endswith("second.mp3")


def test_the_channel_language_is_read() -> None:
    xml = b"""<rss><channel><language>zh-tw</language>
        <title>t</title></channel></rss>"""
    assert parse_feed(xml).language == "zh-tw"
