from __future__ import annotations

import json
import subprocess

import pytest

from podly_radio.transcripts import fixup
from podly_radio.transcripts.text import Segment, chunk_text


def test_cache_fingerprint_reuse_and_invalidation(tmp_path, monkeypatch):
    calls = []
    segments = [Segment(1100, 2000, "文字测试")]
    def run(directory, model):
        calls.append(model)
        return fixup.raw_result(segments)
    monkeypatch.setattr(fixup, "run_codex", run)
    def correct(**kwargs):
        options = {"directory": tmp_path, "segments": segments, "before": [], "show": "节目",
                   "shownotes": "嘉宾", "script": "simplified", "model": "test-model"}
        options.update(kwargs)
        return fixup.do_chunk(**options)
    assert correct()[1] is None
    assert correct()[1] is None
    assert len(calls) == 1
    assert correct(before=[Segment(1, 2, "前文")])[1] is None
    assert len(calls) == 2
    assert correct(model="other-model")[1] is None
    assert len(calls) == 3
    assert correct(script="traditional")[1] is None
    assert len(calls) == 4
    assert correct(shownotes="嘉宾改名")[1] is None
    assert len(calls) == 5
    assert correct(shownotes="嘉宾改名", redo=True)[1] is None
    assert len(calls) == 6
    (tmp_path / "ok.json").write_text("{broken")
    assert correct(shownotes="嘉宾改名")[1] is None
    assert len(calls) == 7


@pytest.mark.parametrize("position", range(7))
def test_each_fingerprint_input_matters(position):
    inputs = ["chunk", "before", "brief", "schema", "script", "model", "notes"]
    original = fixup.fingerprint(*inputs)
    inputs[position] += "changed"
    assert fixup.fingerprint(*inputs) != original


def test_failed_check_retries_once_then_raw_and_no_cache(tmp_path, monkeypatch):
    calls = []
    segments = [Segment(1234, 2000, "原始文字")]
    def run(*args):
        calls.append(1)
        return {"paragraphs": []}
    monkeypatch.setattr(fixup, "run_codex", run)
    result, problem = fixup.do_chunk(tmp_path, segments, [], "节目", "嘉宾", "simplified", "model")
    assert len(calls) == 2
    assert problem == "no paragraphs"
    assert result == fixup.raw_result(segments)
    assert not (tmp_path / "ok.json").exists()
    assert not (tmp_path / "fingerprint.json").exists()


def test_tool_failure_retries_then_succeeds(tmp_path, monkeypatch):
    segments = [Segment(1234, 2000, "原始文字")]
    calls = []
    def run(*args):
        calls.append(1)
        if len(calls) == 1:
            raise subprocess.TimeoutExpired("codex", 1800)
        return fixup.raw_result(segments)
    monkeypatch.setattr(fixup, "run_codex", run)
    result, problem = fixup.do_chunk(tmp_path, segments, [], "节目", "嘉宾", "traditional", "model")
    assert problem is None
    assert len(calls) == 2
    assert result == fixup.raw_result(segments)
    brief = (tmp_path / "brief.md").read_text()
    assert "traditional Chinese" in brief and "节目" in brief
    assert "regional vocabulary" in brief
    assert (tmp_path / "shownotes.txt").read_text() == "嘉宾"


def test_chunk_jobs_have_eight_lines_of_previous_context(tmp_path, monkeypatch):
    segments = [Segment(i * 1000, i * 1000 + 900, "字") for i in range(10)]
    segments += [Segment(900001, 901000, "下一段")]
    monkeypatch.setattr(fixup, "run_codex", lambda d, m: fixup.raw_result(
        segments[:10] if d.name == "00" else segments[10:],
    ))
    results = fixup.fixup(tmp_path, segments, "show", "notes", "simplified", "model", 3)
    assert [k for k, _, _ in results] == [0, 1]
    assert (tmp_path / "chunks/00/before.txt").read_text() == ""
    assert (tmp_path / "chunks/01/before.txt").read_text() == chunk_text(segments[2:10])
    assert "[00:15:00.001]" in (tmp_path / "chunks/01/chunk.txt").read_text()


def test_codex_command_pipes_brief_and_sets_required_flags(tmp_path, monkeypatch):
    (tmp_path / "brief.md").write_text("brief")
    def run(command, **kwargs):
        assert command[:2] == ["codex", "exec"]
        for flag in ("--skip-git-repo-check", "--ignore-rules", "--output-schema"):
            assert flag in command
        assert command[command.index("-c") + 1] == 'web_search="disabled"'
        assert command[command.index("-s") + 1] == "read-only"
        assert command[command.index("-C") + 1] == str(tmp_path)
        assert command[command.index("--model") + 1] == "test-model"
        assert kwargs["stdin"].read() == b"brief"
        assert kwargs["timeout"] == 1800
        (tmp_path / "result.json").write_text(json.dumps({"paragraphs": []}))
    monkeypatch.setattr(fixup.subprocess, "run", run)
    assert fixup.run_codex(tmp_path, "test-model") == {"paragraphs": []}
