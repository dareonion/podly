"""The Python half of the id contract; StableIdVectorsTest.kt is the other."""

from __future__ import annotations

import json
from pathlib import Path

from podly_radio.ids import episode_id, podcast_id, stable_id

VECTORS = (
    Path(__file__).resolve().parents[3]
    / "app/src/test/resources/stable-id-vectors.json"
)


def test_matches_the_shared_vectors() -> None:
    vectors = json.loads(VECTORS.read_text(encoding="utf-8"))
    assert vectors, "vectors fixture should not be empty"
    for vector in vectors:
        assert stable_id(vector["raw"]) == vector["id"], vector["raw"]


def test_ids_are_32_hex_chars() -> None:
    value = podcast_id("https://example.com/feed.xml")
    assert len(value) == 32
    assert set(value) <= set("0123456789abcdef")


def test_episode_falls_back_to_the_audio_url_without_a_guid() -> None:
    audio = "https://cdn.example/ep1.mp3"
    assert episode_id(None, audio) == stable_id(audio)
    assert episode_id("", audio) == stable_id(audio)
    assert episode_id("guid-1", audio) == stable_id("guid-1")
