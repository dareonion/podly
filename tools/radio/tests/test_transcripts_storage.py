from __future__ import annotations

import hashlib

import pytest

from podly_radio.feeds import parse_feed
from podly_radio.ids import episode_id
from podly_radio.transcripts.fixup import raw_result
from podly_radio.transcripts.storage import (
    assemble,
    read_json,
    rebuild_index,
    save_transcript,
    write_json,
)
from podly_radio.transcripts.text import Segment


@pytest.fixture
def transcript():
    feed = parse_feed(b'''<rss><channel><title>show</title><description>show notes</description>
        <item><title>269.title</title><guid> raw guid </guid>
        <description>00:01 chapter</description>
        <enclosure url="https://original/audio.mp3"/><pubDate>2026-10-31T00:00:00Z</pubDate>
        </item></channel></rss>''')
    segments = [Segment(10, 1000, "first"), Segment(1234, 2400, "second")]
    result = raw_result(segments)
    result["changes"] = [{"original": "frist", "corrected": "first",
                          "confidence": "likely", "reason": "typo"}]
    result["notes"] = "uncertain words"
    return assemble("https://feed", feed, feed.episodes[0], 3000, "traditional", "model",
                    segments, [(0, result, None)])


def test_assembly_exact_end_times_chapters_and_identity(transcript):
    assert transcript["id"] == episode_id("raw guid", "https://original/audio.mp3")
    assert transcript["paragraphs"][0] == {
        "start_ms": 10, "end_ms": 1234, "text": "first", "chapter": None,
    }
    assert transcript["paragraphs"][1] == {
        "start_ms": 1234, "end_ms": 2400, "text": "second", "chapter": "chapter",
    }
    assert transcript["durationMs"] == 3000  # measured audio, not last speech end
    assert transcript["language"] == "zh-Hant"
    assert transcript["corrections"][0]["chunk"] == 0


def test_index_build_hash_rewrite_cleanup_and_markdown(tmp_path, transcript):
    save_transcript(tmp_path, transcript)
    index = read_json(tmp_path / "public/index.json")
    entry = index["entries"][0]
    assert set(index) == {"generatedAt", "entries"}
    assert set(entry) == {
        "id", "guid", "audioUrl", "feedUrl", "title", "durationMs", "url", "type", "language",
    }
    assert entry["type"] == "text/vtt"
    assert entry["audioUrl"] == "https://original/audio.mp3"
    vtt = tmp_path / "public" / entry["url"]
    assert vtt.name == f"transcript.{hashlib.sha256(vtt.read_bytes()).hexdigest()[:8]}.vtt"
    work = tmp_path / "work" / transcript["id"]
    md = (work / "transcript.md").read_text()
    assert "## 00:00:01 chapter" in md
    assert "[00:00:00] first" in md
    assert "frist → first (typo; likely)" in (work / "changes.md").read_text()
    transcript["paragraphs"][0]["text"] = "changed"
    write_json(work / "transcript.json", transcript)
    rebuilt = rebuild_index(tmp_path)
    assert rebuilt["entries"][0]["url"] != entry["url"]
    # The superseded VTT stays: a phone that read the old index still holds its URL.
    assert vtt.exists()
    assert "changed" in (work / "transcript.md").read_text()
    assert len(list((tmp_path / "public").rglob("*.vtt"))) == 2
    assert not list(tmp_path.rglob("*.tmp"))


def test_fallback_visible_in_structured_and_markdown_output(tmp_path, transcript):
    transcript["chunks"][0]["fallback"] = "kept 50% of the raw characters"
    save_transcript(tmp_path, transcript)
    work = tmp_path / "work" / transcript["id"]
    assert "raw Whisper text below" in (work / "transcript.md").read_text()
    assert "Not corrected: kept 50%" in (work / "changes.md").read_text()
    assert read_json(work / "transcript.json")["chunks"][0]["fallback"]


def test_empty_and_partial_work_directories(tmp_path):
    (tmp_path / "work/unfinished").mkdir(parents=True)
    assert rebuild_index(tmp_path)["entries"] == []
    assert (tmp_path / "public/index.json").is_file()


def test_concurrent_writers_do_not_lose_index_entries(tmp_path, transcript):
    import copy
    from concurrent.futures import ThreadPoolExecutor

    other = copy.deepcopy(transcript)
    other["guid"] = "another"
    other["id"] = episode_id(other["guid"], other["audioUrl"])
    with ThreadPoolExecutor(2) as pool:
        list(pool.map(lambda t: save_transcript(tmp_path, t), [transcript, other]))
    entries = read_json(tmp_path / "public/index.json")["entries"]
    assert {e["id"] for e in entries} == {transcript["id"], other["id"]}
    assert all((tmp_path / "public" / e["url"]).is_file() for e in entries)
