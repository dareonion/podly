"""Identifiers, which must match the app byte for byte.

The app derives every id as ``stableId`` in
``app/src/main/java/com/podly/data/db/Entities.kt``: SHA-256 of the UTF-8 bytes,
hex, truncated to 32 characters. Podcasts hash their feed URL; episodes hash
``guid ?: audioUrl``.

If these ever diverge, a pool entry inserts a *second* row for an episode the
app already has, and the duplicate is invisible until someone notices the same
episode twice. ``tests/test_ids.py`` and ``StableIdVectorsTest.kt`` both assert
against the same checked-in vectors to stop that happening quietly.
"""

from __future__ import annotations

import hashlib


def stable_id(raw: str) -> str:
    return hashlib.sha256(raw.encode("utf-8")).hexdigest()[:32]


def podcast_id(feed_url: str) -> str:
    return stable_id(feed_url)


def episode_id(guid: str | None, audio_url: str) -> str:
    """Mirrors ``stableId(guid ?: audioUrl)`` — a blank guid falls back to the URL."""
    return stable_id(guid if guid else audio_url)
