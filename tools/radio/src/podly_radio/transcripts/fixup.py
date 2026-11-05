"""Bounded parallel Codex correction with checked, fingerprinted chunk caches."""

from __future__ import annotations

import hashlib
import json
import subprocess
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

from .storage import atomic_bytes, log, read_json, write_json
from .text import Segment, check, chunk_segments, chunk_text, timestamp

HERE = Path(__file__).parent


def fingerprint(
    chunk: str, before: str, brief: str, schema: str, script: str, model: str,
    shownotes: str = "",
) -> str:
    # JSON framing avoids ambiguous concatenations. Notes also affect corrections.
    payload = json.dumps([chunk, before, brief, schema, script, model, shownotes],
                         ensure_ascii=False, separators=(",", ":"))
    return hashlib.sha256(payload.encode()).hexdigest()


def run_codex(directory: Path, model: str) -> dict:
    out = directory / "result.json"
    out.unlink(missing_ok=True)
    command = [
        "codex", "exec", "--skip-git-repo-check", "--ignore-rules",
        "-c", 'web_search="disabled"', "-C", str(directory), "-s", "read-only",
        "--model", model, "--output-schema", str(directory / "schema.json"), "-o", str(out), "-",
    ]
    with (directory / "brief.md").open("rb") as brief, (directory / "codex.log").open("ab") as lg:
        subprocess.run(command, stdin=brief, stdout=lg, stderr=subprocess.STDOUT,
                       timeout=1800, check=True)
    text = out.read_text(encoding="utf-8").strip()
    if text.startswith("```"):
        text = text.split("\n", 1)[1].rsplit("```", 1)[0].strip()
    return json.loads(text)


def raw_result(segments: list[Segment]) -> dict:
    # Whisper can emit two segments with the same start. Keep their words together
    # so even fallback paragraphs obey the strict-start contract.
    paragraphs = []
    for segment in segments:
        start = timestamp(segment.start_ms)
        if paragraphs and paragraphs[-1]["start"] == start:
            paragraphs[-1]["text"] += " " + segment.text
        else:
            paragraphs.append({"start": start, "text": segment.text})
    return {"paragraphs": paragraphs, "changes": [], "notes": ""}


def do_chunk(
    directory: Path, segments: list[Segment], before: list[Segment], show: str,
    shownotes: str, script: str, model: str, *, redo: bool = False,
) -> tuple[dict, str | None]:
    directory.mkdir(parents=True, exist_ok=True)
    brief = (HERE / "brief.md").read_text(encoding="utf-8").format(
        show=json.dumps(show, ensure_ascii=False), script=script,
    )
    schema = (HERE / "schema.json").read_text(encoding="utf-8")
    chunk, preceding = chunk_text(segments), chunk_text(before)
    digest = fingerprint(chunk, preceding, brief, schema, script, model, shownotes)
    cache = directory / "ok.json"
    signature = directory / "fingerprint.json"
    if not redo and cache.exists() and signature.exists():
        try:
            result = read_json(cache)
            saved = read_json(signature)
            if (isinstance(saved, dict) and saved.get("sha256") == digest
                    and check(result, segments) is None):
                log(f"{directory.parent.parent.name} chunk {directory.name} ok (cached)")
                return result, None
        except (ValueError, OSError):
            pass  # A corrupt/incomplete cache is not a checked result.
    # Invalidate before starting so a failed redo cannot resurrect an older result.
    signature.unlink(missing_ok=True)
    cache.unlink(missing_ok=True)
    for name, text in {
        "chunk.txt": chunk, "before.txt": preceding, "brief.md": brief,
        "schema.json": schema, "shownotes.txt": shownotes,
    }.items():
        atomic_bytes(directory / name, text.encode())
    problem = None
    for attempt in (1, 2):
        try:
            result = run_codex(directory, model)
            problem = check(result, segments)
        except Exception as error:  # noqa: BLE001 - retry any tool failure, then preserve raw text
            problem = f"{type(error).__name__}: {error}"
        if problem is None:
            write_json(cache, result)
            write_json(signature, {"sha256": digest})
            log(f"{directory.parent.parent.name} chunk {directory.name} ok (attempt {attempt})")
            return result, None
        log(f"{directory.parent.parent.name} chunk {directory.name} "
            f"attempt {attempt} failed: {problem}")
    return raw_result(segments), problem


def fixup(
    work: Path, segments: list[Segment], show: str, shownotes: str, script: str,
    model: str, concurrency: int, *, redo: bool = False,
) -> list[tuple[int, dict, str | None]]:
    chunks = chunk_segments(segments)
    keys = sorted(chunks)

    def job(index: int) -> tuple[int, dict, str | None]:
        k = keys[index]
        result, problem = do_chunk(
            work / "chunks" / f"{k:02d}", chunks[k],
            chunks[keys[index - 1]][-8:] if index else [],
            show, shownotes, script, model, redo=redo,
        )
        return k, result, problem

    with ThreadPoolExecutor(max_workers=concurrency) as pool:
        return list(pool.map(job, range(len(keys))))
