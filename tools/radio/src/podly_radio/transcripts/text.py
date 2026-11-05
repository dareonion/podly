"""Timestamp, SRT, chapter and WebVTT operations, without external tools."""

from __future__ import annotations

import html
import re
import unicodedata
from dataclasses import dataclass
from difflib import SequenceMatcher

from lxml import html as lxml_html

STAMP = r"\d{2,}:[0-5]\d:[0-5]\d[.,]\d{3}"
CONTENT = re.compile(r"[一-鿿A-Za-z0-9]")
CHUNK_MS = 15 * 60 * 1000
# Per-paragraph alignment: a paragraph of at least this many source characters must keep
# this much similarity to Whisper's text for the lines it claims. Measured on the four
# Jokes Aside episodes (1,943 such paragraphs): one scored below 0.5 (0.21, its text had
# been put in the previous paragraph); the next lowest was 0.68.
ALIGN_MIN_CHARS = 20
ALIGN_MIN_RATIO = 0.5
# ...and keep this share of the span's characters, which catches a deleted neighbour whose
# lines the paragraph silently absorbed (similarity alone scores that 0.67). On the same
# data one paragraph fell below 0.7 (the 0.21 one above); the next lowest kept 0.76.
ALIGN_MIN_KEPT = 0.7


@dataclass(frozen=True)
class Segment:
    start_ms: int
    end_ms: int
    text: str


def timestamp(ms: int, *, milliseconds: bool = True) -> str:
    seconds, fraction = divmod(ms, 1000)
    hours, seconds = divmod(seconds, 3600)
    minutes, seconds = divmod(seconds, 60)
    result = f"{hours:02d}:{minutes:02d}:{seconds:02d}"
    return f"{result}.{fraction:03d}" if milliseconds else result


def parse_timestamp(value: str) -> int:
    if not re.fullmatch(STAMP, value):
        raise ValueError(f"Invalid millisecond timestamp: {value!r}")
    hours, minutes, seconds, ms = map(int, re.split(r"[:.,]", value))
    return ((hours * 60 + minutes) * 60 + seconds) * 1000 + ms


def parse_srt(text: str) -> list[Segment]:
    segments = []
    for block in re.split(r"\n\s*\n", text.lstrip("\ufeff").strip()):
        lines = block.splitlines()
        if lines and lines[0].strip().isdigit():
            lines.pop(0)
        if not lines:
            continue
        match = re.fullmatch(rf"({STAMP}) --> ({STAMP})", lines[0].strip())
        if not match or len(lines) < 2:
            raise ValueError(f"Invalid SRT block: {block[:100]!r}")
        start, end = map(parse_timestamp, match.groups())
        body = " ".join(line.strip() for line in lines[1:]).strip()
        if end <= start or not body:
            raise ValueError(f"Empty or reversed SRT segment at {start}")
        if segments and start < segments[-1].start_ms:
            raise ValueError("SRT starts go backwards")
        segments.append(Segment(start, end, body))
    if not segments:
        raise ValueError("No SRT segments")
    return segments


def chunk_segments(segments: list[Segment]) -> dict[int, list[Segment]]:
    chunks: dict[int, list[Segment]] = {}
    for segment in segments:
        chunks.setdefault(segment.start_ms // CHUNK_MS, []).append(segment)
    return chunks


def chunk_text(segments: list[Segment]) -> str:
    return "".join(f"[{timestamp(s.start_ms)}] {s.text}\n" for s in segments)


def check(result: dict, segments: list[Segment]) -> str | None:
    """The prototype's coverage heuristic, with strict millisecond starts and shape checks."""
    if not isinstance(result, dict):
        return "result is not an object"
    paragraphs = result.get("paragraphs")
    if not isinstance(paragraphs, list) or not paragraphs:
        return "no paragraphs"
    valid = {timestamp(s.start_ms) for s in segments}
    previous = -1
    for paragraph in paragraphs:
        if not isinstance(paragraph, dict) or not isinstance(paragraph.get("text"), str):
            return "invalid paragraph"
        if not paragraph["text"].strip():
            return "empty paragraph text"
        start = paragraph.get("start")
        if not isinstance(start, str) or start not in valid:
            return f"start {start} is not a line timestamp"
        current = parse_timestamp(start)
        if current <= previous:
            return f"start {start} is not strictly increasing"
        previous = current
    if paragraphs[0]["start"] != timestamp(segments[0].start_ms):
        return "first paragraph does not start at the chunk's first line"
    raw = sum(len(CONTENT.findall(s.text)) for s in segments)
    corrected = sum(len(CONTENT.findall(p["text"])) for p in paragraphs)
    ratio = corrected / max(raw, 1)
    if not 0.85 <= ratio <= 1.10:
        return f"kept {ratio:.0%} of the raw characters"
    if not isinstance(result.get("changes"), list) or not isinstance(result.get("notes"), str):
        return "invalid changes or notes"
    for change in result["changes"]:
        if not isinstance(change, dict) or any(
            not isinstance(change.get(key), str)
            for key in ("original", "corrected", "reason", "confidence")
        ) or change["confidence"] not in ("sure", "likely"):
            return "invalid correction"
    return _misaligned(paragraphs, segments, result["changes"])


def _content(text: str) -> str:
    return "".join(CONTENT.findall(text))


def _misaligned(paragraphs: list[dict], segments: list[Segment], changes: list[dict]) -> str | None:
    """Catches text moved between paragraphs or dropped mid-chunk, which totals miss.

    A paragraph's source is the lines from its start up to the next paragraph's start. A
    span holding a declared removal (a hallucination or recognition loop) is exempt.
    """
    removed = [_content(c["original"])[:6] for c in changes if not c["corrected"]]
    starts = [parse_timestamp(p["start"]) for p in paragraphs]
    for i, paragraph in enumerate(paragraphs):
        end = starts[i + 1] if i + 1 < len(starts) else None
        source = "".join(_content(s.text) for s in segments
                         if s.start_ms >= starts[i] and (end is None or s.start_ms < end))
        if len(source) < ALIGN_MIN_CHARS or any(r and r in source for r in removed):
            continue
        text = _content(paragraph["text"])
        ratio = SequenceMatcher(None, source, text, autojunk=False).ratio()
        if ratio < ALIGN_MIN_RATIO:
            return f"paragraph at {paragraph['start']} matches its lines at only {ratio:.0%}"
        if len(text) < ALIGN_MIN_KEPT * len(source):
            return f"paragraph at {paragraph['start']} keeps only {len(text) / len(source):.0%} of its lines"
    return None


def legacy_starts(result: dict, segments: list[Segment]) -> dict:
    """Use each matching segment once, in order, including repeated whole-second starts."""
    paragraphs = []
    cursor = 0
    for paragraph in result["paragraphs"]:
        start = paragraph["start"]
        found = next((
            i for i in range(cursor, len(segments))
            if timestamp(segments[i].start_ms, milliseconds=False) == start
        ), None)
        if found is None:
            raise ValueError(f"Legacy start {start} has no unused segment in this chunk")
        paragraphs.append({**paragraph, "start": timestamp(segments[found].start_ms)})
        cursor = found + 1
    return {**result, "paragraphs": paragraphs}


def plain_notes(notes: str) -> str:
    # Preserve HTML paragraph/line boundaries so chapter titles don't run together.
    notes = re.sub(r"(?i)<br\s*/?>|</(?:p|div|li|h[1-6])\s*>", "\n", notes)
    if not notes.strip():
        return ""
    return lxml_html.fromstring(f"<div>{notes}</div>").text_content().strip()


def chapters(notes: str) -> list[dict]:
    result = {}
    pattern = r"(?m)^\s*[\[【(（]?((?:\d{1,2}:)?\d{1,2}:\d{2})[\]】)）]?\s*[-–—|：:]?\s*(.+)$"
    for match in re.finditer(pattern, plain_notes(notes)):
        parts = list(map(int, match[1].split(":")))
        if parts[-1] >= 60 or (len(parts) == 3 and parts[-2] >= 60):
            continue
        seconds = 0
        for part in parts:
            seconds = seconds * 60 + part
        result.setdefault(seconds * 1000, match[2].strip())
    return [{"start_ms": start, "title": title} for start, title in sorted(result.items())]


def wrap_cue(text: str, limit: int = 40) -> str:
    """At most 40 CJK glyph widths (80 Latin columns), preferring punctuation or spaces.

    Unlike the two-line chineselearning renderer, long podcast paragraphs may need
    more than two lines. Wrap before escaping so entities count as their visible glyph.
    """
    remaining = " ".join(text.split())
    lines = []
    while remaining:
        width = 0
        cut = 0
        preferred = 0
        for i, char in enumerate(remaining):
            size = 0 if unicodedata.combining(char) else (
                2 if unicodedata.east_asian_width(char) in ("W", "F") else 1
            )
            if width + size > limit * 2:
                break
            width += size
            cut = i + 1
            if char in " ，、；：。！？,;:!?":
                preferred = cut
        if cut < len(remaining) and preferred >= cut // 2:
            cut = preferred
        lines.append(remaining[:cut].rstrip())
        remaining = remaining[cut:].lstrip()
    return "\n".join(lines)


def vtt_bytes(paragraphs: list[dict]) -> bytes:
    lines = ["WEBVTT", ""]
    for i, paragraph in enumerate(paragraphs, 1):
        lines += [
            str(i),
            f"{timestamp(paragraph['start_ms'])} --> {timestamp(paragraph['end_ms'])}",
            html.escape(wrap_cue(paragraph["text"]), quote=False),
            "",
        ]
    return ("\n".join(lines) + "\n").encode("utf-8")
