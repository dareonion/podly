"""Authoritative transcripts and their derived private serving files."""

from __future__ import annotations

import datetime as dt
import fcntl
import hashlib
import json
import os
import re
import sys
import tempfile
from contextlib import contextmanager
from pathlib import Path

from podly_radio.feeds import ParsedEpisode, ParsedFeed
from podly_radio.ids import episode_id

from .text import (
    Segment,
    chapters,
    chunk_segments,
    parse_timestamp,
    plain_notes,
    timestamp,
    vtt_bytes,
)


def log(message: str) -> None:
    print(f"[{dt.datetime.now().astimezone().isoformat(timespec='seconds')}] {message}",
          file=sys.stderr, flush=True)


def data_dir() -> Path:
    return Path(os.environ.get(
        "PODLY_TRANSCRIPTS_DIR", "~/.local/share/podly-transcripts",
    )).expanduser().resolve()


@contextmanager
def locked(path: Path):
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("a") as handle:
        fcntl.flock(handle, fcntl.LOCK_EX)
        try:
            yield
        finally:
            fcntl.flock(handle, fcntl.LOCK_UN)


def atomic_bytes(path: Path, content: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    name = None
    try:
        with tempfile.NamedTemporaryFile(dir=path.parent, delete=False) as handle:
            name = Path(handle.name)
            handle.write(content)
            handle.flush()
            os.fsync(handle.fileno())
        name.replace(path)
    finally:
        if name is not None:
            name.unlink(missing_ok=True)


def write_json(path: Path, value: dict) -> None:
    atomic_bytes(path, (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode())


def read_json(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def language_script(language: str | None) -> tuple[str, str | None]:
    tag = (language or "").strip().lower().replace("_", "-")
    if tag == "yue" or tag.startswith(("yue-", "zh-hk")):
        return "traditional", "feed language may be Cantonese; Whisper is configured for Mandarin"
    if tag == "zh-tw" or tag.startswith(("zh-tw-", "zh-hant")):
        return "traditional", None
    return "simplified", None


def script_for(root: Path, feed_url: str, language: str | None, override: str | None) -> str:
    inferred, warning = language_script(language)
    if warning:
        log(f"warning: {warning}")
    with locked(root / ".config.lock"):
        path = root / "config.json"
        config = read_json(path) if path.exists() else {}
        scripts = config.setdefault("scripts", {})
        if override:
            scripts[feed_url] = override
            write_json(path, config)
        script = scripts.get(feed_url, inferred)
        if script not in ("traditional", "simplified"):
            raise ValueError(f"Invalid script override for {feed_url}: {script}")
        return script


def assemble(
    feed_url: str, feed: ParsedFeed, episode: ParsedEpisode, duration_ms: int,
    script: str, model: str, segments: list[Segment], results: list[tuple[int, dict, str | None]],
) -> dict:
    chapter_list = chapters(episode.show_notes or "")
    hosts = re.search(r"(?:本期)?(?:主播|主持人)[:：]\s*([^\n]+)",
                      plain_notes(episode.show_notes or ""))
    raw_chunks = chunk_segments(segments)
    paragraphs, corrections, notes, chunks = [], [], [], []
    for k, result, problem in results:
        starts = [parse_timestamp(p["start"]) for p in result["paragraphs"]]
        chunks.append({
            "index": k, "start_ms": starts[0],
            "end_ms": raw_chunks[k][-1].start_ms, "fallback": problem,
        })
        for p, start in zip(result["paragraphs"], starts, strict=True):
            active = next((c["title"] for c in reversed(chapter_list)
                           if c["start_ms"] <= start), None)
            paragraphs.append({
                "start_ms": start, "end_ms": 0, "text": p["text"], "chapter": active,
            })
        corrections.extend({"chunk": k, **change} for change in result["changes"])
        if result["notes"]:
            notes.append({"chunk": k, "text": result["notes"]})
    for i, paragraph in enumerate(paragraphs):
        end = paragraphs[i + 1]["start_ms"] if i + 1 < len(paragraphs) else segments[-1].end_ms
        if end <= paragraph["start_ms"]:
            raise ValueError("Assembled paragraph starts must be strictly increasing")
        paragraph["end_ms"] = end
    if not paragraphs:
        raise ValueError("No paragraphs to assemble")
    return {
        "version": 1,
        "id": episode_id(episode.guid, episode.audio_url),
        "guid": episode.guid,
        "audioUrl": episode.audio_url,
        "feedUrl": feed_url,
        "title": episode.title,
        "showTitle": feed.title,
        "showDescription": feed.description,
        "showNotes": episode.show_notes,
        "hosts": hosts[1].strip() if hosts else None,
        "pubDateMs": episode.pub_date_ms,
        "date": (str(dt.datetime.fromtimestamp(episode.pub_date_ms / 1000, dt.UTC).date())
                 if episode.pub_date_ms is not None else None),
        "feedDurationMs": episode.duration_ms,
        "durationMs": duration_ms,
        "script": script,
        "language": "zh-Hant" if script == "traditional" else "zh-Hans",
        "models": {"whisper": "large-v3-turbo", "vad": "silero-v6.2.0", "codex": model},
        "paragraphs": paragraphs,
        "chapters": chapter_list,
        "corrections": corrections,
        "notes": notes,
        "chunks": chunks,
    }


def markdown(transcript: dict) -> tuple[str, str]:
    date = transcript.get("date") or "undated"
    credit = f"主播：{transcript['hosts']}" if transcript.get("hosts") else transcript["showTitle"]
    md = [
        f"# {transcript['title']}", "",
        (f"{date} · {timestamp(transcript['durationMs'], milliseconds=False)}"
        f" · {credit}"), "",
        ("Whisper large-v3-turbo transcript (whisper.cpp), corrected by Codex for homophones, "
        "names and punctuation from the text alone. No speaker labels. `[?]` marks passages "
        "Whisper garbled that could not be recovered. Raw Whisper output: `whisper.srt`; "
        "every correction: `changes.md`."), "",
    ]
    chapter_queue = list(transcript["chapters"])
    fallback_queue = [c for c in transcript["chunks"] if c["fallback"]]
    for p in transcript["paragraphs"]:
        while fallback_queue and fallback_queue[0]["start_ms"] <= p["start_ms"]:
            chunk = fallback_queue.pop(0)
            md += [(f"> {_span(chunk)}: Codex's correction failed its check "
                   f"({chunk['fallback']}); raw Whisper text below."), ""]
        while chapter_queue and chapter_queue[0]["start_ms"] <= p["start_ms"]:
            chapter = chapter_queue.pop(0)
            heading = timestamp(chapter["start_ms"], milliseconds=False)
            md += [f"## {heading} {chapter['title']}", ""]
        md += [f"[{timestamp(p['start_ms'], milliseconds=False)}] {p['text']}", ""]
    for chapter in chapter_queue:
        md += [f"## {timestamp(chapter['start_ms'], milliseconds=False)} {chapter['title']}", ""]
    changes = [f"# {transcript['title']}: corrections", ""]
    for chunk in transcript["chunks"]:
        changes += [f"## {_span(chunk)}", ""]
        if chunk["fallback"]:
            changes += [f"Not corrected: {chunk['fallback']}.", ""]
        for c in transcript["corrections"]:
            if c["chunk"] != chunk["index"]:
                continue
            if c["corrected"]:
                changes.append(f"- {c['original']} → {c['corrected']} "
                               f"({c['reason']}; {c['confidence']})")
            else:
                changes.append(f"- removed: {c['original']} ({c['reason']})")
        for note in transcript["notes"]:
            if note["chunk"] == chunk["index"]:
                changes += ["", f"Notes: {note['text']}"]
        changes.append("")
    return "\n".join(md), "\n".join(changes)


def _span(chunk: dict) -> str:
    return (f"{timestamp(chunk['start_ms'], milliseconds=False)}–"
            f"{timestamp(chunk['end_ms'], milliseconds=False)}")


def _derive(root: Path, transcript: dict) -> tuple[dict, Path]:
    ep_id = transcript["id"]
    if (not isinstance(ep_id, str) or len(ep_id) != 32
            or any(c not in "0123456789abcdef" for c in ep_id)):
        raise ValueError(f"Invalid episode id: {ep_id!r}")
    work = root / "work" / ep_id
    md, changes = markdown(transcript)
    atomic_bytes(work / "transcript.md", md.encode())
    atomic_bytes(work / "changes.md", changes.encode())
    vtt = vtt_bytes(transcript["paragraphs"])
    name = f"transcript.{hashlib.sha256(vtt).hexdigest()[:8]}.vtt"
    path = root / "public" / ep_id / name
    atomic_bytes(path, vtt)
    entry = {key: transcript[key] for key in (
        "id", "guid", "audioUrl", "feedUrl", "title", "durationMs", "language",
    )}
    entry.update(url=f"{ep_id}/{name}", type="text/vtt")
    return entry, path


def _rebuild_index(root: Path) -> dict:
    entries = []
    for path in sorted((root / "work").glob("*/transcript.json")):
        transcript = read_json(path)
        if transcript["id"] != path.parent.name:
            raise ValueError(f"Transcript id disagrees with work directory: {path}")
        entry, _vtt = _derive(root, transcript)
        entries.append(entry)
    index = {"generatedAt": dt.datetime.now(dt.UTC).isoformat(), "entries": entries}
    write_json(root / "public" / "index.json", index)
    # Superseded VTTs are kept: they are small and immutable, and a phone that read the
    # previous index still holds their URL until its next sync.
    return index


def rebuild_index(root: Path) -> dict:
    with locked(root / ".index.lock"):
        return _rebuild_index(root)


def save_transcript(root: Path, transcript: dict) -> None:
    with locked(root / ".index.lock"):
        write_json(root / "work" / transcript["id"] / "transcript.json", transcript)
        _rebuild_index(root)
    fallback = [c["index"] for c in transcript["chunks"] if c["fallback"]]
    log(f"{transcript['title']} assembled: {len(transcript['paragraphs'])} paragraphs, "
        f"raw fallback {fallback or 'none'}")
