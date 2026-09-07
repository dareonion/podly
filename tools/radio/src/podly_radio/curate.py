"""Asks Claude Code which shows are worth someone's time.

Runs through the `claude` CLI, not the Anthropic API: the CLI is authenticated
via OAuth in ~/.claude/.credentials.json, so this draws on the subscription
rather than metered credits. That distinction is the whole reason the existing
CI generator's weekly cron is switched off, so it is enforced rather than
assumed — `_child_env` strips every ANTHROPIC_* variable, which makes a metered
call impossible even if a key is present in the environment.

The model only ever *selects from a supplied catalogue*, by reference. It is
never asked to name a show or an episode from memory, so it cannot invent one.
"""

from __future__ import annotations

import json
import logging
import os
import re
import shutil
import subprocess
from dataclasses import dataclass
from pathlib import Path

from .config import ProfileConfig
from .sources import Show

LOG = logging.getLogger(__name__)

SYSTEM_PROMPT = (
    "You are an expert podcast critic and curator. You will be given a catalogue "
    "of real shows with live chart positions. Choose the ones worth a particular "
    "listener's time. You may reference a show ONLY by the ref it was given — "
    "never invent a ref, a show or an episode. Reply with ONLY a JSON object."
)

# `claude` lives in ~/.local/bin, which a systemd user service's PATH omits;
# shutil.which alone has silently returned None there before.
CLAUDE_CANDIDATES = (
    Path.home() / ".local" / "bin" / "claude",
    Path("/usr/local/bin/claude"),
    Path("/usr/bin/claude"),
)


@dataclass(frozen=True, slots=True)
class RosterEntry:
    apple_id: str
    weight: float
    why: str


def resolve_claude() -> str | None:
    found = shutil.which("claude")
    if found:
        return found
    for candidate in CLAUDE_CANDIDATES:
        if candidate.exists():
            return str(candidate)
    return None


def _child_env() -> dict[str, str]:
    """Everything that could route this to a metered API is removed."""
    env = dict(os.environ)
    for key in list(env):
        if key.startswith("ANTHROPIC_") or key.startswith("CLAUDE_CODE_"):
            env.pop(key, None)
    env.setdefault("PATH", f"{Path.home()}/.local/bin:/usr/local/bin:/usr/bin:/bin")
    return env


def _extract_json(text: str) -> dict:
    """Tolerates code fences and stray prose around the object."""
    start = text.find("{")
    end = text.rfind("}")
    if start == -1 or end <= start:
        raise ValueError("no JSON object in the reply")
    return json.loads(text[start : end + 1])


def build_digest(profile: ProfileConfig, shows: list[Show], listener: str) -> str:
    lines = [
        f'<profile id="{profile.id}" label="{profile.label}">',
        listener.strip(),
        f"Target roster: {profile.roster_size} shows. "
        f"Languages: {', '.join(profile.languages)}.",
        "</profile>",
        "<catalogue>",
    ]
    for index, show in enumerate(shows, start=1):
        ref = f"s{index:03d}"
        genres = "/".join(show.genres[:2]) or "-"
        rank = f"#{show.chart_rank}" if show.chart_rank else "-"
        lines.append(
            f"{ref} | {show.title} | {show.author or '-'} | {genres} | "
            f"chart {rank} | {show.advisory or '-'}"
        )
    lines.append("</catalogue>")
    lines.append(
        'Reply as {"roster":[{"ref":"s001","weight":0.0-1.0,'
        '"why":"one sentence"}]}'
    )
    return "\n".join(lines)


def curate(
    profile: ProfileConfig,
    shows: list[Show],
    listener: str,
    model: str = "claude-opus-4-8",
    timeout: int = 600,
) -> list[RosterEntry]:
    """Returns a validated roster, or raises so the caller keeps the old one."""
    claude = resolve_claude()
    if claude is None:
        raise RuntimeError("the claude CLI is not installed")

    refs = {f"s{index:03d}": show for index, show in enumerate(shows, start=1)}
    digest = build_digest(profile, shows, listener)
    command = [
        claude,
        "-p",
        "--model", model,
        "--system-prompt", SYSTEM_PROMPT,
        "--output-format", "json",
        # No tools: the digest already carries live chart data, so a tool loop
        # would only add latency and burn more of the subscription's quota.
        "--tools", "",
        "--max-turns", "1",
    ]
    LOG.info("curating %s over %d shows via %s", profile.id, len(shows), claude)
    result = subprocess.run(
        command,
        input=digest,
        capture_output=True,
        text=True,
        timeout=timeout,
        env=_child_env(),
    )
    if result.returncode != 0:
        raise RuntimeError(
            f"claude exited {result.returncode}: "
            f"stderr={result.stderr[:300]!r} stdout={result.stdout[:300]!r}"
        )

    envelope = json.loads(result.stdout)
    if envelope.get("is_error"):
        raise RuntimeError(f"claude reported an error: {envelope.get('result')}")
    payload = _extract_json(envelope.get("result", ""))
    return _validate(payload, refs, profile)


def _validate(
    payload: dict, refs: dict[str, Show], profile: ProfileConfig
) -> list[RosterEntry]:
    roster: list[RosterEntry] = []
    seen: set[str] = set()
    for item in payload.get("roster", []):
        ref = str(item.get("ref", "")).strip()
        show = refs.get(ref)
        # Anything not in the supplied catalogue is dropped rather than trusted.
        if show is None or show.apple_id in seen:
            continue
        seen.add(show.apple_id)
        why = re.sub(r"\s+", " ", str(item.get("why", ""))).strip()[:200]
        weight = max(0.0, min(1.0, float(item.get("weight", 0.5) or 0.5)))
        roster.append(RosterEntry(apple_id=show.apple_id, weight=weight, why=why))
    minimum = max(5, profile.roster_size // 3)
    if len(roster) < minimum:
        raise RuntimeError(
            f"only {len(roster)} valid roster entries, need {minimum} — keeping the old roster"
        )
    return roster
