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
    legacy_starts,
    parse_srt,
    parse_timestamp,
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
