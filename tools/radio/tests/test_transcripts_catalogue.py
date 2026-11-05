from __future__ import annotations

from dataclasses import replace

import httpx
import pytest

from podly_radio.feeds import ParsedEpisode, parse_feed
from podly_radio.sources import Show
from podly_radio.transcripts import catalogue, cli
from podly_radio.transcripts.catalogue import SelectionError, leading_number, resolve_show, select
from podly_radio.transcripts.storage import language_script, read_json, script_for


def episode(title, date=1):
    return ParsedEpisode(title, title, None, "https://audio/" + title, None, date, 1000, None)


@pytest.mark.parametrize("title", [
    "269.救救我", "EP269 救救我", "ep 269 救救我", "#269", "第269期",
])
def test_leading_numbers(title):
    assert leading_number(title) == 269
    assert select([episode(title)], ["269"])[0].title == title


def test_select_all_before_work_and_newest_first_deduplicated():
    episodes = [episode("266.旧", 1), episode("269.新", 3), episode("268.中", 2)]
    assert [e.title for e in select(episodes, ["266", "latest:2", "269"])] == [
        "269.新", "268.中", "266.旧",
    ]
    with pytest.raises(SelectionError, match="Candidates"):
        select(episodes, ["269", "missing"])
    with pytest.raises(SelectionError, match="2690"):
        select([episode("2690 不匹配")], ["269"])


def test_ambiguous_selector_lists_candidates():
    with pytest.raises(SelectionError) as error:
        select([episode("269.甲"), episode("EP269 乙")], ["269"])
    assert "269.甲" in str(error.value) and "EP269 乙" in str(error.value)
    with pytest.raises(SelectionError, match="matched 2"):
        select([episode("一 中文"), episode("二 中文")], ["中文"])


@pytest.mark.parametrize("selector", ["latest:0", "latest:-1", "latest:3", "latest:x", ""])
def test_invalid_selectors(selector):
    with pytest.raises(SelectionError):
        select([episode("甲")], [selector])


def test_resolve_apple_feed_and_ambiguous_name(monkeypatch):
    calls = []
    def lookup(client, ids, country):
        calls.append((ids, country))
        return {"123": Show("123", "show", feed_url="https://feed")}
    monkeypatch.setattr(catalogue.sources, "lookup", lookup)
    monkeypatch.setattr(catalogue.sources, "search", lambda *a: [
        Show("1", "one", feed_url="https://one"), Show("2", "two", feed_url="https://two"),
    ])
    with httpx.Client() as client:
        assert resolve_show(client, "https://podcasts.apple.com/tw/podcast/name/id123") == "https://feed"
        assert calls == [(["123"], "tw")]
        assert resolve_show(client, "https://feed.test/id123") == "https://feed.test/id123"
        with pytest.raises(SelectionError) as error:
            resolve_show(client, "show")
        assert "https://one" in str(error.value) and "https://two" in str(error.value)


@pytest.mark.parametrize("language,script,warn", [
    ("ZH_tw", "traditional", False), ("zh-Hant-CN", "traditional", False),
    ("zh-HK", "traditional", True), ("yue_Hant", "traditional", True),
    ("yue", "traditional", True), ("zh-CN", "simplified", False),
    (None, "simplified", False), ("en", "simplified", False), ("zh", "simplified", False),
])
def test_language_script(language, script, warn):
    actual, warning = language_script(language)
    assert actual == script
    assert bool(warning) == warn


def test_script_override_persists_by_feed(tmp_path):
    assert script_for(tmp_path, "feed", "zh", "traditional") == "traditional"
    assert script_for(tmp_path, "feed", "zh", None) == "traditional"
    assert script_for(tmp_path, "another", "zh", None) == "simplified"
    assert read_json(tmp_path / "config.json") == {"scripts": {"feed": "traditional"}}


@pytest.mark.parametrize("fields,expected", [
    (("<itunes:summary>summary</itunes:summary><description>description</description>"
     "<content:encoded>full</content:encoded>"), "full"),
    ("<content:encoded>full</content:encoded><description>description</description>", "full"),
    ("<itunes:summary>summary</itunes:summary><description>description</description>",
     "description"),
    ("<content:encoded> </content:encoded><itunes:summary>summary</itunes:summary>", "summary"),
])
def test_full_notes_precedence_preserves_radio_description(fields, expected):
    xml = ('<rss xmlns:itunes="urn:itunes" xmlns:content="urn:content"><channel>'
           '<title>show</title><language>zh-TW</language><item><title>episode</title>'
           f'{fields}<enclosure url="audio"/></item></channel></rss>').encode()
    result = parse_feed(xml)
    e = result.episodes[0]
    assert e.show_notes == expected
    assert e.description == ("summary" if fields.startswith("<itunes:") else expected)
    assert result.language == "zh-TW"


def test_cli_invalid_selector_has_no_side_effects(tmp_path, monkeypatch, capsys):
    root = tmp_path / "absent"
    monkeypatch.setenv("PODLY_TRANSCRIPTS_DIR", str(root))
    feed = parse_feed(b"<rss><channel><title>x</title></channel></rss>")
    monkeypatch.setattr(cli, "fetch_feed", lambda *a: replace(feed, episodes=(episode("269.x"),)))
    monkeypatch.setattr(cli, "run_episode", lambda *a, **k: pytest.fail("must not run"))
    assert cli.main(["run", "https://feed", "269", "missing", "--script", "traditional"]) == 1
    assert not root.exists()
    assert "269.x" in capsys.readouterr().err
