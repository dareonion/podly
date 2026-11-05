from __future__ import annotations

import copy
import json
from pathlib import Path

import pytest

from podly_radio.transcripts.fixup import raw_result
from podly_radio.transcripts.text import (
    Segment,
    chapters,
    check,
    chunk_segments,
    chunk_text,
    collapse_loops,
    find_loops,
    legacy_starts,
    loop_window,
    parse_srt,
    parse_timestamp,
    srt_text,
    timestamp,
    vtt_bytes,
    wrap_cue,
)

FIXTURES = Path(__file__).parent / "fixtures/transcripts"


def test_srt_and_legacy_excerpt():
    # Invented text with a real episode's timings and correction shapes: this repo is
    # public, and real transcripts are never published.
    segments = parse_srt((FIXTURES / "excerpt.srt").read_text())
    assert segments[0].start_ms == 10340
    assert segments[0].end_ms == 30860
    assert segments[-1].start_ms == 62960
    cached = json.loads((FIXTURES / "legacy-excerpt.json").read_text())
    mapped = legacy_starts(cached, segments)
    assert [p["start"] for p in mapped["paragraphs"]] == [
        "00:00:10.340", "00:00:31.240", "00:01:02.960",
    ]
    assert cached["paragraphs"][0]["start"] == "00:00:10"
    assert check(mapped, segments) is None


def test_srt_multiline_crlf_bom_and_end_times():
    segments = parse_srt("\ufeff1\r\n01:02:03,004 --> 01:02:04,005\r\n甲\r\n乙\r\n")
    assert segments == [Segment(3723004, 3724005, "甲 乙")]
    assert timestamp(3723004) == "01:02:03.004"
    assert parse_timestamp("01:02:03.004") == 3723004


@pytest.mark.parametrize("srt", [
    "", "bad", "1\n00:00:01,000 --> 00:00:00,900\ntext", "1\n00:00:01,000 --> 00:00:02,000",
    "1\n00:00:70,000 --> 00:00:72,000\ntext",
])
def test_srt_rejects_bad_input(srt):
    with pytest.raises(ValueError):
        parse_srt(srt)


def test_chunk_boundaries_keep_milliseconds_and_sparse_keys():
    segments = [Segment(899999, 900100, "甲"), Segment(900000, 901000, "乙"),
                Segment(2700001, 2700100, "丙")]
    assert list(chunk_segments(segments)) == [0, 1, 3]
    assert chunk_text(segments[:1]) == "[00:14:59.999] 甲\n"


@pytest.fixture
def checked():
    segments = [Segment(1010, 1100, "甲乙丙丁"), Segment(1230, 1500, "戊己庚辛")]
    return segments, raw_result(segments)


def test_check_valid_and_unknown_start(checked):
    segments, result = checked
    assert check(result, segments) is None
    result["paragraphs"][1]["start"] = "00:00:01.000"
    assert "not a line timestamp" in check(result, segments)


@pytest.mark.parametrize("order", [(0, 0), (1, 0)])
def test_check_non_increasing(checked, order):
    segments, result = checked
    original = copy.deepcopy(result["paragraphs"])
    result["paragraphs"] = [original[i] for i in order]
    assert "strictly increasing" in check(result, segments)


def test_check_first_start_and_character_coverage(checked):
    segments, result = checked
    result["paragraphs"] = result["paragraphs"][1:]
    assert "first paragraph" in check(result, segments)
    result = raw_result(segments)
    result["paragraphs"][0]["text"] = "甲"
    assert "kept" in check(result, segments)
    result["paragraphs"][0]["text"] = "甲" * 30
    assert "kept" in check(result, segments)


@pytest.mark.parametrize("count,valid", [(84, False), (85, True), (110, True), (111, False)])
def test_check_ratio_boundaries(count, valid):
    segments = [Segment(0, 1000, "字" * 100)]
    result = raw_result(segments)
    result["paragraphs"][0]["text"] = "字" * count
    assert (check(result, segments) is None) == valid


def test_legacy_same_second_uses_each_segment_once_in_order():
    segments = [Segment(1100, 1200, "甲"), Segment(1400, 1500, "乙"), Segment(2010, 2200, "丙")]
    result = {"paragraphs": [{"start": "00:00:01", "text": t} for t in ("甲", "乙")]}
    mapped = legacy_starts(result, segments)
    assert [p["start"] for p in mapped["paragraphs"]] == ["00:00:01.100", "00:00:01.400"]
    result["paragraphs"].append({"start": "00:00:01", "text": "extra"})
    with pytest.raises(ValueError, match="no unused segment"):
        legacy_starts(result, segments)


def test_fallback_merges_duplicate_starts_without_losing_text():
    result = raw_result([Segment(0, 100, "甲"), Segment(0, 110, "乙")])
    assert result["paragraphs"] == [{"start": "00:00:00.000", "text": "甲 乙"}]


def test_chapters_from_html_and_plain_notes():
    assert chapters("<p>03:11 第一章</p><p>01:17:15 第二章<br>02:26:01 救回状态！</p>") == [
        {"start_ms": 191000, "title": "第一章"},
        {"start_ms": 4635000, "title": "第二章"},
        {"start_ms": 8761000, "title": "救回状态！"},
    ]
    assert chapters("介绍\n[00:01] - 开场\n00:70 无效") == [{"start_ms": 1000, "title": "开场"}]


def test_vtt_escaping_wrapping_and_exact_times():
    body = "<&> " + "中文" * 60
    rendered = vtt_bytes([{"start_ms": 10340, "end_ms": 62960, "text": body}]).decode()
    assert rendered.startswith("WEBVTT\n\n1\n00:00:10.340 --> 00:01:02.960\n")
    assert "&lt;&amp;&gt;" in rendered
    assert "<&>" not in rendered
    assert rendered.endswith("\n\n")
    wrapped = wrap_cue("中" * 101)
    assert list(map(len, wrapped.splitlines())) == [40, 40, 21]
    assert "".join(wrapped.splitlines()) == "中" * 101
    assert max(map(len, wrap_cue("a" * 170).splitlines())) == 80
    assert "\n\n" not in wrap_cue("甲\n\n乙")


def test_check_rejects_empty_cue_even_when_overall_character_count_passes():
    segments = [Segment(0, 1000, "字" * 99), Segment(1000, 2000, "字")]
    result = raw_result(segments)
    result["paragraphs"][1]["text"] = " "
    assert check(result, segments) == "empty paragraph text"


def _two_paragraphs(first: str, second: str) -> tuple[dict, list[Segment]]:
    segments = [Segment(0, 1000, first), Segment(1000, 2000, second)]
    return raw_result(segments), segments


def test_check_rejects_text_swapped_between_paragraphs():
    first, second = "甲乙丙丁戊己庚辛壬癸" * 3, "子丑寅卯辰巳午未申酉" * 3
    result, segments = _two_paragraphs(first, second)
    assert check(result, segments) is None
    result["paragraphs"][0]["text"], result["paragraphs"][1]["text"] = second, first
    assert "matches its lines" in check(result, segments)


def test_check_rejects_a_paragraph_whose_lines_were_dropped():
    lines = ["甲乙丙丁戊己庚辛壬癸" * 3 for _ in range(10)]
    segments = [Segment(i * 1000, i * 1000 + 900, text) for i, text in enumerate(lines)]
    result = raw_result(segments)
    # Total length still passes (the middle text is moved, not lost overall) but one
    # paragraph no longer matches the lines it claims.
    result["paragraphs"][4]["text"] = "子丑"
    result["paragraphs"][5]["text"] = lines[5] + lines[4][:28]
    assert "matches its lines" in check(result, segments)


def test_check_alignment_exempts_short_paragraphs():
    result, segments = _two_paragraphs("甲乙丙丁戊己庚辛壬癸" * 20, "对对对对对对对对")
    result["paragraphs"][1]["text"] = "不是不是不是不是"  # 8 source characters: too short to judge
    assert check(result, segments) is None


def test_check_alignment_exempts_a_span_with_a_declared_removal():
    loop = "请不吝点赞订阅转发打赏支持明镜与点点栏目"
    result, segments = _two_paragraphs("甲乙丙丁戊己庚辛壬癸" * 50, "你好" * 5 + loop * 2)
    result["paragraphs"][1]["text"] = "你好" * 5
    assert "matches its lines" in check(result, segments)
    result["changes"] = [{"original": loop, "corrected": "", "reason": "幻觉", "confidence": "sure"}]
    assert check(result, segments) is None


def test_check_rejects_a_deleted_paragraph_absorbed_by_its_neighbour():
    lines = ["甲乙丙丁戊己庚辛壬癸" * 3 for _ in range(10)]
    segments = [Segment(i * 1000, i * 1000 + 900, text) for i, text in enumerate(lines)]
    result = raw_result(segments)
    del result["paragraphs"][4]  # overall coverage stays at 90%
    assert "keeps only" in check(result, segments)


def _run(start_s, text, n, step_s=2):
    return [Segment((start_s + k * step_s) * 1000, (start_s + k * step_s + 1) * 1000, text)
            for k in range(n)]


def test_find_loops_needs_eight_in_a_row_and_ignores_punctuation():
    real = _run(0, "太可爱了。", 5) + _run(20, "没见着", 4)
    assert find_loops(real) == []
    looped = _run(0, "前面", 1) + _run(10, "對,我們知道她的經歷", 4) + _run(18, "對，我們知道她的經歷。", 4)
    assert find_loops(looped) == [(10000, 25000)]


def test_find_loops_joins_runs_a_minute_apart_but_not_further():
    near = _run(0, "甲乙", 8) + _run(20, "插话", 1) + _run(70, "甲乙", 8)
    assert find_loops(near) == [(0, 85000)]
    far = _run(0, "甲乙", 8) + _run(200, "甲乙丙", 8)
    assert find_loops(far) == [(0, 15000), (200000, 215000)]


def test_loop_window_pads_into_neighbours_and_silence():
    segments = _run(0, "前", 20, step_s=5) + _run(100, "环", 10) + _run(130, "后", 20, step_s=5)
    first, last, start, end = loop_window(segments, (100000, 119000))
    # Padded span 70-149 s: 前14 (70-71 s) through 后3 (145-146 s) are replaced; the audio
    # runs from the padding's start to just before 后4 (150 s) or the padding's end.
    assert segments[first] == Segment(70000, 71000, "前") and segments[first - 1].end_ms == 66000
    assert segments[last - 1] == Segment(145000, 146000, "后") and segments[last].start_ms == 150000
    assert (start, end) == (70000, 149000)
    # A silent gap after the loop is still re-transcribed up to the padding.
    gap = _run(100, "环", 10) + _run(200, "后", 3)
    assert loop_window(gap, (100000, 119000))[2:] == (70000, 149000)


def test_loop_window_takes_cues_overlapping_its_edges():
    segments = [Segment(0, 69500, "长前"), *_run(100, "环", 9),
                Segment(140000, 160000, "跨界"), Segment(150000, 170000, "重叠"),
                Segment(175000, 176000, "后")]
    first, last, start, end = loop_window(segments, (100000, 117000))
    # 长前 ends at 69.5 s, before the padded span opens at 70 s, so it stays; 重叠 starts
    # after the span closes (146 s) but overlaps 跨界, so it goes too.
    assert segments[first].text == "环" and segments[last - 1].text == "重叠"
    assert (start, end) == (70000, 170000)
    straddle = [Segment(60000, 80000, "跨前"), *_run(100, "环", 9), Segment(200000, 201000, "后")]
    first, last, start, end = loop_window(straddle, (100000, 117000))
    assert straddle[first].text == "跨前" and start == 60000


def test_collapse_loops_folds_a_run_that_survives_retranscription():
    fresh = _run(0, "yeah", 3) + _run(10, "同一句", 9) + _run(40, "after", 1)
    out = collapse_loops(fresh)
    assert [s.text for s in out] == ["yeah"] * 3 + ["同一句", "after"]
    assert out[3] == Segment(10000, 27000, "同一句")


def test_srt_text_round_trips():
    segments = [Segment(1000, 2500, "甲"), Segment(3723004, 3724005, "Open Mic 乙")]
    assert parse_srt(srt_text(segments)) == segments


def test_loop_window_takes_any_earlier_cue_reaching_in():
    segments = [Segment(20000, 65000, "长"), Segment(40000, 50000, "短"),
                Segment(60000, 80000, "接"), *_run(100, "环", 8), Segment(300000, 301000, "后")]
    first, last, start, end = loop_window(segments, (100000, 115000))
    # 接 starts the window at 60 s, and 长 (20-65 s) reaches into it, so 长 and 短 go too.
    assert (first, last, start, end) == (0, 11, 20000, 145000)
