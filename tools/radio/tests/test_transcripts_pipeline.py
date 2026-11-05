from __future__ import annotations

import shutil
import subprocess
from dataclasses import replace
from pathlib import Path
from types import SimpleNamespace

import httpx
import pytest

from podly_radio.feeds import parse_feed
from podly_radio.ids import episode_id
from podly_radio.transcripts import pipeline
from podly_radio.transcripts.fixup import raw_result
from podly_radio.transcripts.storage import read_json, write_json

FIXTURES = Path(__file__).parent / "fixtures/transcripts"


def test_download_follows_redirect_keeps_original_identity_and_reuses_audio(tmp_path):
    requests = []
    def serve(request):
        requests.append(request)
        assert request.headers["User-Agent"] == "Podly/1.0"
        if request.url.path == "/original.mp3":
            return httpx.Response(302, headers={"location": "https://cdn/final.mp3"})
        return httpx.Response(200, content=b"audio")
    with httpx.Client(transport=httpx.MockTransport(serve)) as client:
        path = pipeline.download(client, "https://feed/original.mp3", tmp_path)
        assert path == tmp_path / "audio.mp3"
        assert path.read_bytes() == b"audio"
        assert pipeline.download(client, "https://feed/original.mp3", tmp_path) == path
    assert len(requests) == 2
    assert not (tmp_path / "audio.part").exists()


def test_download_failure_removes_partial_and_ignores_orphan_wav(tmp_path):
    (tmp_path / "audio.16k.wav").write_bytes(b"orphan")
    with (
        httpx.Client(transport=httpx.MockTransport(lambda r: httpx.Response(403))) as client,
        pytest.raises(httpx.HTTPStatusError),
    ):
        pipeline.download(client, "https://feed/audio.mp3", tmp_path)
    assert not (tmp_path / "audio.part").exists()
    assert not (tmp_path / "audio.mp3").exists()


@pytest.mark.parametrize("failure", [False, True])
def test_whisper_invocation_flags_and_wav_cleanup(tmp_path, monkeypatch, failure):
    calls = []
    def run(command, **kwargs):
        calls.append(command)
        assert kwargs["stdin"] is subprocess.DEVNULL
        if command[0] == "ffmpeg":
            assert "-nostdin" in command
            assert command[command.index("-ar") + 1] == "16000"
            assert command[command.index("-ac") + 1] == "1"
            Path(command[-1]).write_bytes(b"wav")
        elif failure:
            raise subprocess.CalledProcessError(1, command)
    monkeypatch.setattr(pipeline.subprocess, "run", run)
    if failure:
        with pytest.raises(subprocess.CalledProcessError):
            pipeline.transcribe(tmp_path / "audio.mp3", tmp_path, Path("/whisper"),
                                "节目", "traditional")
    else:
        pipeline.transcribe(tmp_path / "audio.mp3", tmp_path, Path("/whisper"),
                            "节目", "traditional")
    assert not (tmp_path / "audio.16k.wav").exists()
    command = calls[1]
    assert command[0] == "/whisper/build/bin/whisper-cli"
    for flag, value in {"-l": "zh", "-t": "4", "-bs": "5", "-mc": "112"}.items():
        assert command[command.index(flag) + 1] == value
    for flag in ("--vad", "--carry-initial-prompt", "-osrt", "-otxt", "-oj"):
        assert flag in command
    assert command[command.index("--prompt") + 1] == "以下是國語播客《节目》的對話。"
    assert pipeline.whisper_prompt("节目", "simplified") == "以下是普通话播客《节目》的对话。"


def test_measure_duration_uses_ffprobe_with_devnull(tmp_path, monkeypatch):
    def run(command, **kwargs):
        assert command[0] == "ffprobe" and command[-1] == str(tmp_path / "audio")
        assert kwargs["stdin"] is subprocess.DEVNULL
        return SimpleNamespace(stdout="12.345\n")
    monkeypatch.setattr(pipeline.subprocess, "run", run)
    assert pipeline.measure_duration(tmp_path / "audio") == 12345


def test_pipeline_skip_and_redo_keep_audio_and_original_id(tmp_path, monkeypatch):
    feed = parse_feed(b'''<rss><channel><title>show</title>
        <description>show description</description>
        <item><title>269.ep</title><description>episode notes</description>
        <enclosure url="https://feed/original.mp3"/></item></channel></rss>''')
    episode = feed.episodes[0]
    transcriptions = []
    corrections = []
    def transcribe(audio, work, *args):
        transcriptions.append(audio)
        (work / "whisper.srt").write_text("1\n00:00:00,010 --> 00:00:02,000\n原始文字\n")
    def fixup(work, segments, show, notes, script, model, concurrency, **kwargs):
        assert "show description" in notes and "episode notes" in notes
        corrections.append(kwargs["redo"])
        return [(0, raw_result(segments), None)]
    monkeypatch.setattr(pipeline, "transcribe", transcribe)
    monkeypatch.setattr(pipeline, "fixup", fixup)
    monkeypatch.setattr(pipeline, "measure_duration", lambda _: 2001)
    requests = []
    def serve(request):
        requests.append(request)
        return httpx.Response(200, content=b"audio")
    with httpx.Client(transport=httpx.MockTransport(serve)) as client:
        args = (client, tmp_path, "https://feed", feed, episode,
                "simplified", Path("/whisper"), "model", 3)
        pipeline.run_episode(*args)
        pipeline.run_episode(*args)  # no work if transcript exists
        pipeline.run_episode(*args, redo=True)
    assert len(requests) == 1
    assert len(transcriptions) == 2
    assert corrections == [False, True]
    ep_id = episode_id(None, episode.audio_url)
    transcript = read_json(tmp_path / "work" / ep_id / "transcript.json")
    assert transcript["id"] == ep_id
    assert transcript["audioUrl"] == episode.audio_url
    assert transcript["durationMs"] == 2001


def test_import_legacy_checks_durations_and_never_calls_models(tmp_path, monkeypatch, capsys):
    feed = parse_feed(b'''<rss><channel><title>show</title><item><title>269.ep</title>
        <guid>guid</guid><enclosure url="https://audio"/></item></channel></rss>''')
    episodes = tuple(replace(feed.episodes[0], guid=f"guid-{n}", title=f"{n}.ep",
                             pub_date_ms=n, duration_ms=1000000) for n in range(266, 270))
    feed = replace(feed, episodes=episodes)
    out, audio, root = tmp_path / "old", tmp_path / "audio", tmp_path / "new"
    out.mkdir()
    write_json(out / "episodes.json", {str(n): {"date": "2026-10-31"}
                                      for n in range(266, 270)})
    audio.mkdir()
    for n in range(266, 270):
        shutil.copyfile(FIXTURES / "excerpt.srt", out / f"{n}.whisper.srt")
        chunk = out / "work" / str(n) / "00"
        chunk.mkdir(parents=True)
        shutil.copyfile(FIXTURES / "legacy-excerpt.json", chunk / "ok.json")
        (chunk / "chunk.txt").write_text("original chunk")
        (chunk / "before.txt").write_text("")
        (audio / f"{n}.m4a").write_bytes(b"audio")
    monkeypatch.setattr(pipeline, "measure_duration", lambda _: 96000)
    monkeypatch.setattr(pipeline, "fixup", lambda *a, **kw: pytest.fail("no model calls"))
    monkeypatch.setattr(pipeline, "transcribe", lambda *a: pytest.fail("no whisper"))
    transcripts = pipeline.import_legacy(root, feed, out=out, audio_dir=audio)
    assert len(transcripts) == 4
    assert all(t["date"] == "2026-10-31" for t in transcripts)
    assert [t["title"] for t in transcripts] == [f"{n}.ep" for n in range(269, 265, -1)]
    assert all(t["paragraphs"][0]["start_ms"] == 10340 for t in transcripts)
    assert capsys.readouterr().err.count("duration mismatch") == 4
    assert len(read_json(root / "public/index.json")["entries"]) == 4


def test_failed_whisper_marker_is_not_reused(tmp_path, monkeypatch):
    feed = parse_feed(b'''<rss><channel><title>x</title><item><title>ep</title>
        <enclosure url="https://audio"/></item></channel></rss>''')
    episode = feed.episodes[0]
    work = tmp_path / "work" / episode_id(episode.guid, episode.audio_url)
    work.mkdir(parents=True)
    (work / "audio.mp3").write_bytes(b"audio")
    (work / "whisper.srt").write_text("partial")
    write_json(work / "whisper-state.json", {"different": True})
    write_json(work / "loop-repair.json", {"version": 1, "repairs": []})
    (work / "whisper.looped.srt").write_text("old evidence")
    monkeypatch.setattr(pipeline, "measure_duration", lambda _: 2000)
    def fail(*args):
        raise RuntimeError("failed")
    monkeypatch.setattr(pipeline, "transcribe", fail)
    with httpx.Client() as client, pytest.raises(RuntimeError):
        pipeline.run_episode(client, tmp_path, "https://feed", feed, episode,
                             "simplified", Path("/whisper"), "model", 3)
    assert not (work / "whisper-state.json").exists()
    # A new Whisper pass invalidates the loop check made on the old SRT.
    assert not (work / "loop-repair.json").exists()
    assert not (work / "whisper.looped.srt").exists()
    assert not (work / "transcript.json").exists()


def _srt(*rows):
    from podly_radio.transcripts.text import Segment, srt_text
    return srt_text([Segment(a, b, t) for a, b, t in rows])


def _loop_rows():
    rows = [(k * 5000, k * 5000 + 4000, f"正常{k}") for k in range(20)]
    rows += [(100000 + k * 2000, 101000 + k * 2000, "對,我們知道她的經歷") for k in range(30)]
    rows += [(200000 + k * 5000, 204000 + k * 5000, f"后来{k}") for k in range(20)]
    return rows


def test_repair_loops_splices_fresh_text_once_and_keeps_evidence(tmp_path, monkeypatch):
    from podly_radio.transcripts.text import Segment, parse_srt
    (tmp_path / "whisper.srt").write_text(_srt(*_loop_rows()), encoding="utf-8")
    original = (tmp_path / "whisper.srt").read_bytes()
    calls = []

    def fake_span(audio, work, whisper_dir, show, script, start_ms, end_ms):
        calls.append((start_ms, end_ms))
        return [Segment(start_ms + 1000, start_ms + 3000, "I actually got some question"),
                Segment(end_ms - 3000, end_ms - 1000, "that's crazy")]

    monkeypatch.setattr(pipeline, "transcribe_span", fake_span)
    pipeline.repair_loops(Path("a.mp3"), tmp_path, Path("/w"), "show", "traditional", "ep")
    # Loop 100-159 s; padded window 70-189 s, so 正常14-19 go and the silence is included.
    assert calls == [(70000, 189000)]
    texts = [s.text for s in parse_srt((tmp_path / "whisper.srt").read_text(encoding="utf-8"))]
    assert texts == [f"正常{k}" for k in range(14)] + [
        "I actually got some question", "that's crazy"] + [f"后来{k}" for k in range(20)]
    assert (tmp_path / "whisper.looped.srt").read_bytes() == original
    state = read_json(tmp_path / "loop-repair.json")
    assert state["repairs"] == [{"loopsMs": [[100000, 159000]], "audioMs": [70000, 189000],
                                 "segmentsBefore": 36, "segmentsAfter": 2, "freshLooped": False}]
    assert state["collapsedAfterSplice"] is False
    pipeline.repair_loops(Path("a.mp3"), tmp_path, Path("/w"), "show", "traditional", "ep")
    assert len(calls) == 1  # recorded, not repeated


def test_repair_loops_redoes_after_a_crash_before_the_srt_was_replaced(tmp_path, monkeypatch):
    from podly_radio.transcripts.text import Segment
    (tmp_path / "whisper.srt").write_text(_srt(*_loop_rows()), encoding="utf-8")
    # The record was written but the SRT never replaced: its hash names the repaired text.
    write_json(tmp_path / "loop-repair.json", {"version": 1, "repairs": [], "srtSha256": "0" * 64})
    calls = []
    monkeypatch.setattr(pipeline, "transcribe_span",
                        lambda *a: calls.append(a[-2:]) or [Segment(a[-2], a[-2] + 500, "fresh")])
    pipeline.repair_loops(Path("a.mp3"), tmp_path, Path("/w"), "show", "traditional", "ep")
    assert calls == [(70000, 189000)]
    assert "對,我們知道她的經歷" not in (tmp_path / "whisper.srt").read_text(encoding="utf-8")


def test_repair_loops_keeps_window_speech_when_the_rerun_finds_nothing(tmp_path, monkeypatch):
    from podly_radio.transcripts.text import parse_srt
    (tmp_path / "whisper.srt").write_text(_srt(*_loop_rows()), encoding="utf-8")
    monkeypatch.setattr(pipeline, "transcribe_span", lambda *a: [])
    pipeline.repair_loops(Path("a.mp3"), tmp_path, Path("/w"), "show", "traditional", "ep")
    texts = [s.text for s in parse_srt((tmp_path / "whisper.srt").read_text(encoding="utf-8"))]
    assert texts.count("對,我們知道她的經歷") == 1
    assert [t for t in texts if t.startswith("正常")] == [f"正常{k}" for k in range(20)]


def test_repair_loops_folds_a_run_formed_across_the_splice(tmp_path, monkeypatch):
    from podly_radio.transcripts.text import Segment, parse_srt
    rows = [(k * 2000, k * 2000 + 1000, "循环") for k in range(10)]
    rows += [(100000 + k * 2000, 101000 + k * 2000, "重复") for k in range(7)]
    (tmp_path / "whisper.srt").write_text(_srt(*rows), encoding="utf-8")
    # The rerun ends with 7 copies of the line the kept text starts with: 14 in a row.
    monkeypatch.setattr(pipeline, "transcribe_span", lambda *a: [
        Segment(50000 + k * 2000, 51000 + k * 2000, "重复") for k in range(7)])
    pipeline.repair_loops(Path("a.mp3"), tmp_path, Path("/w"), "show", "traditional", "ep")
    texts = [s.text for s in parse_srt((tmp_path / "whisper.srt").read_text(encoding="utf-8"))]
    assert texts == ["重复"]
    assert read_json(tmp_path / "loop-repair.json")["collapsedAfterSplice"] is True


def test_repair_loops_without_loops_only_records_the_check(tmp_path, monkeypatch):
    (tmp_path / "whisper.srt").write_text(_srt((0, 1000, "甲"), (2000, 3000, "乙")), encoding="utf-8")
    before = (tmp_path / "whisper.srt").read_bytes()
    monkeypatch.setattr(pipeline, "transcribe_span", lambda *a: pytest.fail("no loop"))
    pipeline.repair_loops(Path("a.mp3"), tmp_path, Path("/w"), "show", "simplified", "ep")
    state = read_json(tmp_path / "loop-repair.json")
    assert state["repairs"] == [] and state["collapsedAfterSplice"] is False
    assert (tmp_path / "whisper.srt").read_bytes() == before
    assert not (tmp_path / "whisper.looped.srt").exists()


def test_transcribe_span_offsets_clips_and_survives_bad_bytes_and_cues(tmp_path, monkeypatch):
    def fake_run(args, **kwargs):
        if args[0] != "ffmpeg":
            srt = ("1\n00:00:01,000 --> 00:00:02,000\nyeah \xe4\xb8 ok\n\n"
                   "2\n00:00:05,000 --> 00:00:04,000\nreversed\n\n"
                   "3\n00:00:06,000 --> 00:00:07,000\n\xe4\xb8\n\n"
                   "4\n00:00:09,000 --> 00:00:12,000\nend\n").encode("latin-1")
            Path(args[args.index("-of") + 1] + ".srt").write_bytes(srt)
            assert "-mc" in args and args[args.index("-mc") + 1] == "0"
            assert "--carry-initial-prompt" not in args
        return SimpleNamespace(returncode=0)

    monkeypatch.setattr(pipeline.subprocess, "run", fake_run)
    out = pipeline.transcribe_span(Path("a.mp3"), tmp_path, Path("/w"), "show", "traditional",
                                   60000, 70000)
    # The broken bytes vanish, the reversed and emptied cues are dropped, the rest stays.
    assert [(s.start_ms, s.end_ms, s.text) for s in out] == [(61000, 62000, "yeah  ok"),
                                                              (69000, 70000, "end")]
    assert not (tmp_path / "loop.srt").exists()


def test_repair_loops_merges_windows_that_one_expansion_joins(tmp_path, monkeypatch):
    from podly_radio.transcripts.text import Segment, parse_srt
    # A 90-180 s cue overlaps two separate loops; both windows must become one.
    rows = [(0, 1000, "开头"), (90000, 180000, "长句")]
    rows += [(100000 + k * 1500, 101000 + k * 1500, "环一") for k in range(10)]
    rows += [(180000 + k * 1500, 181000 + k * 1500, "环二") for k in range(10)]
    rows += [(300000, 301000, "结尾")]
    (tmp_path / "whisper.srt").write_text(_srt(*rows), encoding="utf-8")
    calls = []

    def fake_span(audio, work, whisper_dir, show, script, start_ms, end_ms):
        calls.append((start_ms, end_ms))
        return [Segment(start_ms + 500, start_ms + 1500, "新的")]

    monkeypatch.setattr(pipeline, "transcribe_span", fake_span)
    pipeline.repair_loops(Path("a.mp3"), tmp_path, Path("/w"), "show", "traditional", "ep")
    # Loop one pads back to 70 s; loop two pads on to 224.5 s; one window covers both.
    assert calls == [(70000, 224500)]
    texts = [s.text for s in parse_srt((tmp_path / "whisper.srt").read_text(encoding="utf-8"))]
    assert texts == ["开头", "新的", "结尾"]
    assert read_json(tmp_path / "loop-repair.json")["repairs"][0]["loopsMs"] == [
        [100000, 114500], [180000, 194500]]
