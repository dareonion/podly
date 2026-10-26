"""Runs Claude Code and Codex headless, on the subscriptions rather than API keys.

Both CLIs are asked for JSON against a schema. Neither is ever allowed to reach
a metered API: the Claude child loses every ANTHROPIC_* variable (see
``curate._child_env``), the Codex child loses every OPENAI_* one, and Codex
refuses to run at all unless its stored login is a ChatGPT one.

Each call runs in a throwaway directory with read-only tools, so the only thing
either model can do is search the web (when asked to) and answer.
"""

from __future__ import annotations

import json
import logging
import math
import os
import shutil
import subprocess
import tempfile
from pathlib import Path

from .curate import LOCKED_DOWN, _child_env, _extract_json, resolve_claude

LOG = logging.getLogger(__name__)

AGENTS = ("claude", "codex")

# Every call answers from the prompt alone, so Codex gets no shell: one would let a planted
# instruction read anything on disk (its read-only sandbox reads everywhere) into an answer
# that is published. Nor any of ~/.codex's plugins, apps, hooks or memories.
CODEX_DISABLED = (
    "shell_tool",
    "unified_exec",
    "apps",
    "plugins",
    "hooks",
    "memories",
    "multi_agent",
    "multi_agent_v2",
    "browser_use",
    "browser_use_external",
    "computer_use",
    "image_generation",
    "view_image",
    "skill_search",
    "goals",
)
# Codex's current models call every tool, web search included, from code mode
# (`tool_mode = "code_mode_only"` in its model list), so only a call without the web
# can lose it.
CODEX_DISABLED_OFFLINE = ("code_mode", "code_mode_host")

# Used only when Codex's own model list cannot be read.
CODEX_FALLBACK_MODEL = "gpt-6-astra"

CODEX_CANDIDATES = (
    Path.home() / ".local" / "bin" / "codex",
    Path("/usr/local/bin/codex"),
    Path("/usr/bin/codex"),
)


class AgentError(RuntimeError):
    """One agent's call failed; the caller decides whether the run survives it."""


def resolve_codex() -> str | None:
    found = shutil.which("codex")
    if found:
        return found
    for candidate in CODEX_CANDIDATES:
        if candidate.exists():
            return str(candidate)
    return None


def latest_codex_model(codex_home: Path | None = None) -> str:
    """
    Codex's own top-ranked model, so a new release is used without editing this.

    `codex` has no model-list command, but it caches the list it fetches in
    `models_cache.json`, ranked by `priority` (1 is the top of its picker).
    Hidden models and malformed entries are skipped. The same rule as
    aicombine's, which learned it after a pinned id outlived its model.
    """
    home = codex_home or Path(os.environ.get("CODEX_HOME") or Path.home() / ".codex")
    cache = home / "models_cache.json"
    try:
        models = json.loads(cache.read_text(encoding="utf-8"))["models"]
        listed = [
            m
            for m in models
            if isinstance(m, dict)
            and isinstance(m.get("slug"), str)
            and m["slug"]
            and _usable_priority(m.get("priority"))
            and m.get("visibility") == "list"
        ]
        return min(listed, key=lambda m: m["priority"])["slug"]
    except (OSError, ValueError, KeyError, TypeError) as error:
        LOG.warning(
            "could not read codex's model list at %s (%r); using %s",
            cache, error, CODEX_FALLBACK_MODEL,
        )
        return CODEX_FALLBACK_MODEL


def _usable_priority(value: object) -> bool:
    # bool is an int to isinstance, and json.loads accepts NaN and Infinity.
    return isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value)


def codex_uses_subscription(auth_file: Path | None = None) -> bool:
    """True only when Codex is logged in with ChatGPT rather than an API key."""
    path = auth_file or Path.home() / ".codex" / "auth.json"
    try:
        return json.loads(path.read_text(encoding="utf-8")).get("auth_mode") == "chatgpt"
    except (OSError, ValueError):
        return False


def _codex_env() -> dict[str, str]:
    env = _child_env()
    for key in list(env):
        if key.startswith("OPENAI_") or key == "CODEX_API_KEY":
            env.pop(key, None)
    return env


def claude_command(
    claude: str, model: str, system: str, schema: dict, web: bool
) -> list[str]:
    command = [
        claude,
        "-p",
        "--model", model,
        "--system-prompt", system,
        "--output-format", "json",
        "--json-schema", json.dumps(schema),
    ]
    if web:
        # Enabled and allow-listed both: an enabled tool that is not allowed is
        # denied in print mode, and an allowed one that is not enabled is absent.
        command += [
            "--tools", "WebSearch,WebFetch",
            "--allowed-tools", "WebSearch,WebFetch",
            # Generous: the prompt limits searching. Too few turns ends the run
            # mid-search with no answer at all (notable.py learned this).
            "--max-turns", "40",
        ]
    else:
        # Structured output is delivered through a tool call of its own, so even
        # a no-tools answer needs a second turn.
        command += ["--tools", "", "--max-turns", "3"]
    return command + list(LOCKED_DOWN)


def codex_command(
    codex: str,
    model: str,
    effort: str,
    workdir: Path,
    schema_path: Path,
    last_path: Path,
    web: bool,
) -> list[str]:
    command = [
        codex,
        "exec",
        "--ephemeral",
        "--skip-git-repo-check",
        # An allow rule in ~/.codex/rules runs its command outside the sandbox unprompted.
        "--ignore-rules",
        "--ignore-user-config",
        "-C", str(workdir),
        "-s", "read-only",
        "-m", model,
        # Pinned: otherwise it is Codex's default.
        "-c", f"model_reasoning_effort={effort}",
        # `--search` (which `codex exec` rejects) sets this key to "live". The
        # `tools.web_search=true` used before is ignored as of 0.163.0, which left
        # every call, web or not, on the default: cached.
        "-c", f'web_search="{"cached" if web else "disabled"}"',
        # The model's own list can turn sub-agents on whatever the features say.
        "-c", "agents.enabled=false",
        "--output-schema", str(schema_path),
        "-o", str(last_path),
    ]
    for feature in CODEX_DISABLED + (() if web else CODEX_DISABLED_OFFLINE):
        command += ["--disable", feature]
    command.append("-")
    return command


def ask(
    agent: str,
    prompt: str,
    *,
    system: str,
    schema: dict,
    web: bool,
    timeout: int,
    claude_model: str = "opus",
    codex_model: str | None = None,
    codex_effort: str = "high",
) -> dict:
    """One structured answer from [agent], or AgentError."""
    with tempfile.TemporaryDirectory(prefix=f"podly-{agent}-") as tmp:
        workdir = Path(tmp)
        if agent == "claude":
            return _ask_claude(prompt, system, schema, web, timeout, claude_model, workdir)
        if agent == "codex":
            return _ask_codex(
                prompt, system, schema, web, timeout,
                codex_model or latest_codex_model(), codex_effort, workdir,
            )
    raise AgentError(f"unknown agent {agent!r}")


def _ask_claude(prompt, system, schema, web, timeout, model, workdir: Path) -> dict:
    claude = resolve_claude()
    if claude is None:
        raise AgentError("the claude CLI is not installed")
    result = _run(
        claude_command(claude, model, system, schema, web),
        prompt, timeout, _child_env(), workdir, "claude",
    )
    try:
        envelope = json.loads(result.stdout)
    except ValueError as error:
        raise AgentError(f"claude printed no JSON envelope: {result.stdout[:300]!r}") from error
    if envelope.get("is_error"):
        raise AgentError(f"claude reported an error: {str(envelope.get('result'))[:300]}")
    structured = envelope.get("structured_output")
    if isinstance(structured, dict):
        return structured
    try:
        return _extract_json(str(envelope.get("result", "")))
    except ValueError as error:
        raise AgentError(f"claude answered without JSON: {error}") from error


def _ask_codex(prompt, system, schema, web, timeout, model, effort, workdir: Path) -> dict:
    codex = resolve_codex()
    if codex is None:
        raise AgentError("the codex CLI is not installed")
    if not codex_uses_subscription():
        raise AgentError("codex is not logged in with ChatGPT; refusing a metered call")
    schema_path = workdir / "schema.json"
    schema_path.write_text(json.dumps(schema), encoding="utf-8")
    last_path = workdir / "last.json"
    # Codex has no system-prompt flag for exec; the brief leads the prompt instead.
    _run(
        codex_command(codex, model, effort, workdir, schema_path, last_path, web),
        f"{system}\n\n{prompt}", timeout, _codex_env(), workdir, "codex",
    )
    try:
        return _extract_json(last_path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as error:
        raise AgentError(f"codex left no JSON answer: {error}") from error


def _run(command, prompt, timeout, env, cwd: Path, name: str) -> subprocess.CompletedProcess:
    try:
        result = subprocess.run(
            command, input=prompt, capture_output=True, text=True,
            timeout=timeout, env=env, cwd=cwd, check=False,
        )
    except subprocess.TimeoutExpired as error:
        raise AgentError(f"{name} timed out after {timeout}s") from error
    if result.returncode != 0:
        # Either stream can hold the detail, so log both.
        raise AgentError(
            f"{name} exited {result.returncode}: "
            f"stderr={result.stderr[-300:]!r} stdout={result.stdout[-300:]!r}"
        )
    return result
