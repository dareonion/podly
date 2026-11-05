"""Download, transcribe, correct, assemble; legacy import never invokes either model."""

from __future__ import annotations

import datetime as dt
import hashlib
import math
import re
import shutil
import subprocess
from pathlib import Path
from urllib.parse import urlparse

import httpx

from podly_radio.feeds import ParsedEpisode, ParsedFeed
from podly_radio.ids import episode_id

from .catalogue import leading_number, select
from .fixup import fixup
from .storage import (
    assemble,
    atomic_bytes,
    locked,
    log,
    read_json,
    save_transcript,
    write_json,
)
from .text import (
    Segment,
    check,
    chunk_segments,
    collapse_loops,
    find_loops,
    legacy_starts,
    loop_window,
    parse_srt,
    srt_text,
)

LEGACY_FEED = "https://proxy.wavpub.com/jokes-aside.xml"
LEGACY_OUT = Path.home() / "gitrepo/transcribe/out/jokes-aside"
LEGACY_AUDIO = Path.home() / "gitrepo/transcribe/audio/jokes-aside"


def measure_duration(audio: Path) -> int:
    result = subprocess.run(
        ["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of",
         "default=noprint_wrappers=1:nokey=1", str(audio)],
        stdin=subprocess.DEVNULL, capture_output=True, text=True, check=True, timeout=120,
    )
    seconds = float(result.stdout.strip())
    if not math.isfinite(seconds) or seconds <= 0:
        raise ValueError(f"ffprobe returned invalid duration for {audio}")
    return round(seconds * 1000)


def download(client: httpx.Client, url: str, work: Path) -> Path:
    existing = sorted(
        p for p in work.glob("audio.*") if p.name not in ("audio.part", "audio.16k.wav")
    )
    if existing:
        return existing[0]
    ext = Path(urlparse(url).path).suffix.lower()
    if not re.fullmatch(r"\.[a-z0-9]{1,5}", ext):
        ext = ".audio"
    target = work / f"audio{ext}"
    partial = work / "audio.part"
    try:
        with client.stream("GET", url, headers={"User-Agent": "Podly/1.0"},
                           follow_redirects=True) as response:
            response.raise_for_status()
            with partial.open("wb") as handle:
                for block in response.iter_bytes():
                    handle.write(block)
        if not partial.stat().st_size:
            raise ValueError("Downloaded empty audio")
        partial.replace(target)
    finally:
        partial.unlink(missing_ok=True)
    return target


def whisper_prompt(show: str, script: str) -> str:
    if script == "traditional":
        return f"以下是國語播客《{show}》的對話。"
    return f"以下是普通话播客《{show}》的对话。"


def transcribe(audio: Path, work: Path, whisper_dir: Path, show: str, script: str) -> None:
    wav = work / "audio.16k.wav"
    try:
        with (work / "whisper.log").open("wb") as lg:
            subprocess.run(
                ["ffmpeg", "-nostdin", "-v", "error", "-y", "-i", str(audio),
                 "-ar", "16000", "-ac", "1", "-c:a", "pcm_s16le", str(wav)],
                stdin=subprocess.DEVNULL, stdout=lg, stderr=subprocess.STDOUT, check=True,
            )
            subprocess.run(
                [str(whisper_dir / "build/bin/whisper-cli"),
                 "-m", str(whisper_dir / "models/ggml-large-v3-turbo.bin"), "-f", str(wav),
                 "-l", "zh", "--vad", "-vm", str(whisper_dir / "models/ggml-silero-v6.2.0.bin"),
                 "-t", "4", "-bs", "5", "-mc", "112", "--prompt", whisper_prompt(show, script),
                 "--carry-initial-prompt", "-osrt", "-otxt", "-oj", "-of", str(work / "whisper")],
                stdin=subprocess.DEVNULL, stdout=lg, stderr=subprocess.STDOUT, check=True,
            )
    finally:
        wav.unlink(missing_ok=True)


LOOP_REPAIR_VERSION = 1


def transcribe_span(
    audio: Path, work: Path, whisper_dir: Path, show: str, script: str, start_ms: int, end_ms: int,
) -> list[Segment]:
    """Whisper on one stretch with no carried context, which breaks a repetition loop."""
    # Not named audio.*: download() and the readers take the first audio.* as the episode.
    wav, out = work / "loop.16k.wav", work / "loop"
    try:
        with (work / "whisper.log").open("ab") as lg:
            subprocess.run(
                ["ffmpeg", "-nostdin", "-v", "error", "-y", "-ss", f"{start_ms / 1000:.3f}",
                 "-t", f"{(end_ms - start_ms) / 1000:.3f}", "-i", str(audio),
                 "-ar", "16000", "-ac", "1", "-c:a", "pcm_s16le", str(wav)],
                stdin=subprocess.DEVNULL, stdout=lg, stderr=subprocess.STDOUT, check=True,
            )
            subprocess.run(
                [str(whisper_dir / "build/bin/whisper-cli"),
                 "-m", str(whisper_dir / "models/ggml-large-v3-turbo.bin"), "-f", str(wav),
                 "-l", "zh", "--vad", "-vm", str(whisper_dir / "models/ggml-silero-v6.2.0.bin"),
                 "-t", "4", "-bs", "5", "-mc", "0", "--prompt", whisper_prompt(show, script),
                 "-osrt", "-of", str(out)],
                stdin=subprocess.DEVNULL, stdout=lg, stderr=subprocess.STDOUT, check=True,
            )
        # whisper.cpp can split a multi-byte character across tokens (seen on this clip).
        text = out.with_suffix(".srt").read_bytes().decode("utf-8", errors="replace")
        text = text.replace("\ufffd", "")
        # Cue by cue: one malformed cue must not throw away the rest of the window.
        found, dropped = [], 0
        for block in re.split(r"\n\s*\n", text.strip()):
            try:
                found.extend(parse_srt(block))
            except ValueError:
                dropped += 1
        if dropped:
            log(f"loop re-transcription {start_ms // 1000}-{end_ms // 1000}s: "
                f"dropped {dropped} malformed cue(s)")
        found.sort(key=lambda s: s.start_ms)
        return [Segment(s.start_ms + start_ms, min(s.end_ms + start_ms, end_ms), s.text)
                for s in found if s.start_ms + start_ms < end_ms]
    finally:
        wav.unlink(missing_ok=True)
        out.with_suffix(".srt").unlink(missing_ok=True)


def repair_loops(
    audio: Path, work: Path, whisper_dir: Path, show: str, script: str, title: str,
) -> None:
    """Re-transcribe each Whisper repetition loop and splice it into whisper.srt (once).

    The record (with the repaired SRT's hash) is written before the SRT is replaced, so a
    crash in between is seen on resume as a mismatch and the repair is simply redone.
    """
    state_path = work / "loop-repair.json"
    srt = work / "whisper.srt"
    current = srt.read_bytes()
    if state_path.exists():
        state = read_json(state_path)
        if (state.get("version") == LOOP_REPAIR_VERSION
                and state.get("srtSha256") == hashlib.sha256(current).hexdigest()):
            return
    segments = parse_srt(current.decode("utf-8"))
    spans = find_loops(segments)
    # Windows come from the original transcript and are merged before any splice, so one
    # window's expansion can never leave another pointing at text already replaced.
    windows: list[dict] = []
    for span in spans:
        first, last, start_ms, end_ms = loop_window(segments, span)
        window = {"loops": [list(span)], "first": first, "last": last, "audio": [start_ms, end_ms]}
        while windows and (window["first"] < windows[-1]["last"]
                           or window["audio"][0] < windows[-1]["audio"][1]):
            prior = windows.pop()
            window = {"loops": prior["loops"] + window["loops"],
                      "first": min(prior["first"], window["first"]),
                      "last": max(prior["last"], window["last"]),
                      "audio": [min(prior["audio"][0], window["audio"][0]),
                                max(prior["audio"][1], window["audio"][1])]}
        windows.append(window)
    repairs = []
    # Last window first, so earlier indexes stay valid while splicing.
    for window in reversed(windows):
        first, last, (start_ms, end_ms) = window["first"], window["last"], window["audio"]
        fresh = transcribe_span(audio, work, whisper_dir, show, script, start_ms, end_ms)
        fresh_looped = bool(find_loops(fresh))
        if not fresh:
            # Nothing usable came back: keep the window's own speech, loop folded to one line.
            fresh = collapse_loops(segments[first:last])
        repairs.append({"loopsMs": window["loops"], "audioMs": [start_ms, end_ms],
                        "segmentsBefore": last - first, "segmentsAfter": len(fresh),
                        "freshLooped": fresh_looped})
        log(f"{title} Whisper loop {start_ms // 1000}-{end_ms // 1000}s re-transcribed: "
            f"{last - first} -> {len(fresh)} segments")
        segments = segments[:first] + fresh + segments[last:]
    # A run can also form across a splice boundary, or survive in the fresh text.
    collapsed = bool(spans) and bool(find_loops(segments))
    if collapsed:
        segments = collapse_loops(segments)
        log(f"{title} Whisper loop still present after re-transcription; folded to one line")
    text = srt_text(segments).encode() if spans else current
    if spans:
        parse_srt(text.decode())  # starts still ordered, nothing empty
        looped = work / "whisper.looped.srt"
        if not looped.exists():
            atomic_bytes(looped, current)
    write_json(state_path, {"version": LOOP_REPAIR_VERSION, "repairs": repairs[::-1],
                            "collapsedAfterSplice": collapsed,
                            "srtSha256": hashlib.sha256(text).hexdigest()})
    if spans:
        atomic_bytes(srt, text)


def run_episode(
    client: httpx.Client, root: Path, feed_url: str, feed: ParsedFeed, episode: ParsedEpisode,
    script: str, whisper_dir: Path, model: str, concurrency: int, *, redo: bool = False,
) -> None:
    ep_id = episode_id(episode.guid, episode.audio_url)
    work = root / "work" / ep_id
    with locked(work / ".lock"):
        if (work / "transcript.json").exists() and not redo:
            log(f"{episode.title} skipped (transcript.json exists)")
            return
        log(f"{episode.title} start ({ep_id})")
        if redo:
            # Discard even chunks a previous run produced but this run no longer has.
            for path in (work / "chunks").glob("*/fingerprint.json"):
                path.unlink()
            for path in (work / "chunks").glob("*/ok.json"):
                path.unlink()
        audio = download(client, episode.audio_url, work)
        duration = measure_duration(audio)
        show = feed.title or feed_url
        # The marker is committed only after Whisper succeeds, so partial SRTs from
        # an interrupted invocation never count as completed transcription.
        marker = work / "whisper-state.json"
        state = {"script": script, "prompt": whisper_prompt(show, script),
                 "whisperDir": str(whisper_dir), "model": "large-v3-turbo"}
        reusable = False
        if marker.exists() and (work / "whisper.srt").exists() and not redo:
            reusable = read_json(marker) == state
        if not reusable:
            marker.unlink(missing_ok=True)
            # A fresh whisper.srt needs its own loop check; the old evidence no longer applies.
            (work / "loop-repair.json").unlink(missing_ok=True)
            (work / "whisper.looped.srt").unlink(missing_ok=True)
            transcribe(audio, work, whisper_dir, show, script)
            parse_srt((work / "whisper.srt").read_text(encoding="utf-8"))
            write_json(marker, state)
        log(f"{episode.title} whisper done{' (cached)' if reusable else ''}")
        repair_loops(audio, work, whisper_dir, show, script, episode.title)
        segments = parse_srt((work / "whisper.srt").read_text(encoding="utf-8"))
        notes = (f"Show: {show}\n\nShow description:\n{feed.description or ''}\n\n"
                 f"Episode: {episode.title}\n\nEpisode notes:\n{episode.show_notes or ''}\n")
        results = fixup(work, segments, show, notes, script, model, concurrency, redo=redo)
        transcript = assemble(feed_url, feed, episode, duration, script, model, segments, results)
        save_transcript(root, transcript)


def import_legacy(
    root: Path, feed: ParsedFeed, *, out: Path = LEGACY_OUT, audio_dir: Path = LEGACY_AUDIO,
) -> list[dict]:
    episodes = select(feed.episodes, ["266", "267", "268", "269"])
    metadata_path = out / "episodes.json"
    metadata = read_json(metadata_path) if metadata_path.exists() else {}
    imported = []
    for episode in episodes:
        number = str(leading_number(episode.title))
        if not episode.guid:
            raise ValueError(f"Legacy episode {number} has no parsed feed GUID")
        ep_id = episode_id(episode.guid, episode.audio_url)
        log(f"{number} start legacy import: guid={episode.guid!r}, id={ep_id}")
        segments = parse_srt((out / f"{number}.whisper.srt").read_text(encoding="utf-8"))
        duration = measure_duration(audio_dir / f"{number}.m4a")
        last_end = segments[-1].end_ms
        if episode.duration_ms and abs(last_end - episode.duration_ms) / episode.duration_ms > 0.02:
            log(f"{number} duration mismatch: feed={episode.duration_ms}ms, "
                f"SRT last end={last_end}ms, measured={duration}ms (>2%)")
        else:
            log(f"{number} duration: feed={episode.duration_ms}ms, "
                f"SRT last end={last_end}ms, measured={duration}ms")
        results = []
        for k, lines in chunk_segments(segments).items():
            cached = read_json(out / "work" / number / f"{k:02d}" / "ok.json")
            result = legacy_starts(cached, lines)
            if problem := check(result, lines):
                raise ValueError(f"Legacy {number} chunk {k} failed: {problem}")
            results.append((k, result, None))
            log(f"{number} chunk {k} ok (legacy)")
        transcript = assemble(LEGACY_FEED, feed, episode, duration, "simplified",
                              "legacy (not recorded)", segments, results)
        if legacy_date := metadata.get(number, {}).get("date"):
            # Tonight's Markdown used the publisher's calendar date; UTC can be
            # the day before. Keep that presentation without altering feed identity/time.
            transcript["date"] = dt.date.fromisoformat(legacy_date).isoformat()
        work = root / "work" / ep_id
        with locked(work / ".lock"):
            shutil.copyfile(audio_dir / f"{number}.m4a", work / "audio.m4a")
            shutil.copyfile(out / f"{number}.whisper.srt", work / "whisper.srt")
            # Preserve evidence without making an unfingerprinted legacy result reusable
            # by a future correction run with a different brief or script.
            for k, result, _ in results:
                source = out / "work" / number / f"{k:02d}"
                dest = work / "legacy" / f"{k:02d}"
                dest.mkdir(parents=True, exist_ok=True)
                for name in ("chunk.txt", "before.txt", "ok.json"):
                    shutil.copyfile(source / name, dest / name)
                write_json(dest / "mapped.json", result)
            atomic_bytes(work / "shownotes.txt", (episode.show_notes or "").encode())
            save_transcript(root, transcript)
        imported.append(transcript)
    return imported
