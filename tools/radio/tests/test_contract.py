"""The generator half of the wire contract; RadioPoolContractTest.kt is the app half."""

from __future__ import annotations

import json
from pathlib import Path

from podly_radio.ids import episode_id, podcast_id

FIXTURE = (
    Path(__file__).resolve().parents[3]
    / "app/src/test/resources/radio-pool-toddler.json"
)


def test_the_committed_fixture_is_shaped_as_the_app_expects() -> None:
    pool = json.loads(FIXTURE.read_text(encoding="utf-8"))
    assert pool["version"] == 1
    assert pool["profileId"]
    assert pool["entries"]
    for entry in pool["entries"]:
        for key in ("id", "rank", "score", "podcast", "episode", "language"):
            assert key in entry, key
        for key in ("id", "title", "author", "feedUrl"):
            assert key in entry["podcast"], key
        for key in ("id", "title", "audioUrl", "pubDateMs"):
            assert key in entry["episode"], key


def test_fixture_ids_are_derived_the_documented_way() -> None:
    pool = json.loads(FIXTURE.read_text(encoding="utf-8"))
    for entry in pool["entries"]:
        assert entry["episode"]["id"] == episode_id(
            entry["episode"].get("guid"), entry["episode"]["audioUrl"]
        )
        assert entry["podcast"]["id"] == podcast_id(entry["podcast"]["feedUrl"])
        assert entry["id"] == entry["episode"]["id"]
