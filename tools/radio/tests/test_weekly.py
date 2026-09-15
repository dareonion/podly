import datetime
import json
from pathlib import Path

from podly_radio import agents
from podly_radio.feeds import ParsedEpisode, ParsedFeed
from podly_radio.ids import episode_id
from podly_radio.sources import Show
from podly_radio.weekly import (
    Candidate,
    Nomination,
    Pick,
    Week,
    assign_refs,
    blurb_prompt,
    build_catalogue,
    combine,
    index_entry,
    issue_pool,
    judge_prompt,
    locate_nomination,
    merge_nominations,
    parse_blurbs,
    parse_picks,
    parse_week,
    previous_issue_ids,
    previous_week,
    updated_index,
    WeeklyResult,
)

WEEK = Week(datetime.date(2026, 9, 7))


def ms(year, month, day, hour=12):
    moment = datetime.datetime(year, month, day, hour, tzinfo=datetime.timezone.utc)
    return int(moment.timestamp() * 1000)


def episode(title, pub_ms, minutes=40, notes="notes"):
    return ParsedEpisode(
        guid=f"guid-{title}",
        title=title,
        description=notes,
        audio_url=f"https://cdn.example/{title}.mp3",
        audio_mime_type="audio/mpeg",
        pub_date_ms=pub_ms,
        duration_ms=minutes * 60_000 if minutes is not None else None,
        image_url=None,
    )


def show(title, feed_url=None, genres=("Society & Culture",)):
    return Show(
        apple_id=title,
        title=title,
        feed_url=feed_url or f"https://feeds.example/{title}",
        genres=genres,
    )


def feed(title, *episodes, language="en"):
    return ParsedFeed(title, "Author", "About", None, language, tuple(episodes))


def candidate(title, family="en", show_title=None, nominations=()):
    s = show(show_title or f"Show of {title}")
    e = episode(title, ms(2026, 9, 9))
    c = Candidate("", s, feed(s.title, e), e, "zh-Hans" if family == "zh" else "en")
    c.nominations.extend(nominations)
    return c


# ------------------------------------------------------------------------ week


def test_the_previous_week_is_the_last_complete_monday_to_sunday() -> None:
    assert previous_week(datetime.date(2026, 9, 14)) == WEEK  # a Monday
    assert previous_week(datetime.date(2026, 9, 20)) == WEEK  # the Sunday after
    assert WEEK.end == datetime.date(2026, 9, 13)
    assert WEEK.id == "2026-W37"
    assert parse_week("2026-W37") == WEEK
    assert WEEK.pool_id == "weekly-2026-w37"


def test_labels_read_naturally_across_months() -> None:
    assert WEEK.label == "Sep 7–13, 2026"
    assert Week(datetime.date(2026, 8, 31)).label == "Aug 31 – Sep 6, 2026"


def test_the_window_forgives_time_zones_by_half_a_day() -> None:
    assert WEEK.contains(ms(2026, 9, 6, 13))  # Sunday evening US, a day early in UTC
    assert WEEK.contains(ms(2026, 9, 14, 11))  # Sunday night in California
    assert not WEEK.contains(ms(2026, 9, 14, 13))
    assert not WEEK.contains(ms(2026, 9, 6, 11))
    assert not WEEK.contains(None)


# ------------------------------------------------------------------- catalogue


def test_the_catalogue_keeps_the_week_and_skips_trailers_and_repeats() -> None:
    daily = show("Daily")
    episodes = [episode(f"d{day}", ms(2026, 9, day)) for day in range(7, 14)]
    trailer = episode("trailer", ms(2026, 9, 10), minutes=2)
    old = episode("old", ms(2026, 8, 20))
    repeat = episode("repeat", ms(2026, 9, 8))
    fetched = [
        (daily, feed("Daily", *episodes)),
        (show("Other"), feed("Other", trailer, old, repeat)),
        (show("Broken"), None),
    ]
    catalogue = build_catalogue(
        fetched, WEEK, excluded_ids={episode_id(repeat.guid, repeat.audio_url)}, per_show=3
    )
    assert [c.episode.title for c in catalogue] == ["d13", "d12", "d11"]


def test_a_nomination_needs_the_episode_in_the_feed_inside_the_week() -> None:
    right = episode("The Big Interview", ms(2026, 9, 10))
    stale = episode("Last Month's Scoop", ms(2026, 8, 12))
    daily_show = show("The Daily Show")
    the_daily = show("The Daily")
    fetched = [(daily_show, feed("The Daily Show", episode("Headlines", ms(2026, 9, 9))))]
    feeds = {the_daily.feed_url: feed("The Daily", right, stale)}

    def locate(n):
        return locate_nomination(
            n, WEEK, fetched, lambda _: [the_daily], lambda s: feeds.get(s.feed_url)
        )

    # "The Daily Show" is a close title match, but the episode is not in its feed,
    # so the search result is tried and wins.
    found = locate(Nomination("claude", "en", "The Daily", "The Big Interview", "why", "u"))
    assert found is not None and found.show is the_daily

    assert locate(Nomination("codex", "en", "The Daily", "Last Month's Scoop", "", "")) is None
    assert locate(Nomination("codex", "en", "Nonexistent Show", "Anything", "", "")) is None


def test_nominations_merge_onto_catalogue_entries_and_unsuitable_shows_are_dropped() -> None:
    known = candidate("Known")
    fresh = candidate("Fresh")
    crime = candidate("Murder", show_title="True Crime Hour")
    located = {"Known": known, "Fresh": fresh, "Murder": crime}
    nominations = [
        Nomination("claude", "en", "s", "Known", "a", "u"),
        Nomination("codex", "en", "s", "Known", "b", "u"),
        Nomination("codex", "en", "s", "Fresh", "c", "u"),
        Nomination("codex", "en", "s", "Murder", "d", "u"),
        Nomination("codex", "en", "s", "Missing", "e", "u"),
    ]
    merged = merge_nominations(
        [known],
        nominations,
        lambda n: located.get(n.episode),
        excluded_ids=set(),
        suitable=lambda s: "Crime" not in s.title,
    )
    assert [c.episode.title for c in merged] == ["Known", "Fresh"]
    assert [n.agent for n in merged[0].nominations] == ["claude", "codex"]


# --------------------------------------------------------------------- judging


def test_refs_are_per_language_and_invented_refs_are_ignored() -> None:
    items = [candidate("a"), candidate("b", "zh"), candidate("c")]
    refs = assign_refs(items)
    assert list(refs) == ["en001", "zh001", "en002"]
    en_refs = {k: v for k, v in refs.items() if k.startswith("en")}
    picks = parse_picks(
        {"picks": [
            {"ref": "en001", "score": 14, "reason": " great  "},
            {"ref": "zh001", "score": 9, "reason": "wrong language list"},
            {"ref": "en999", "score": 10, "reason": "invented"},
            {"ref": "en002", "score": "x", "reason": ""},
        ]},
        en_refs,
    )
    assert picks == {"en001": (10, "great"), "en002": (5, "")}


def test_agreement_outranks_one_keen_judge_and_one_episode_per_show() -> None:
    both = candidate("both")
    keen = candidate("keen")
    sibling = candidate("sibling")
    sibling.show = both.show
    web = candidate("web", nominations=[Nomination("codex", "en", "s", "web", "", "")])
    assign_refs([both, keen, sibling, web])
    ranked = combine(
        [both, keen, sibling, web],
        {
            "claude": {both.ref: (7, "solid"), keen.ref: (10, "superb"), sibling.ref: (6, "")},
            "codex": {both.ref: (7, "agreed"), web.ref: (4, "ok"), sibling.ref: (6, "")},
        },
        count=10,
    )
    # 0.7 for both judges at 7 beats 0.5 for one judge at 10; the sibling (0.6)
    # shares a show with "both" and gives way to it.
    assert [p.candidate.episode.title for p in ranked] == ["both", "keen", "web"]
    assert ranked[0].picked_by == ["claude", "codex"]
    assert ranked[0].reasons == {"claude": "solid", "codex": "agreed"}
    assert ranked[2].score == 0.23


def test_judges_are_told_reruns_do_not_count() -> None:
    prompt = judge_prompt("en", WEEK, [candidate("a")], 24, "A listener.")
    assert "Reruns" in prompt and "do not qualify" in prompt
    assert "at most one episode per show" in prompt


def test_a_missing_judge_does_not_halve_every_score() -> None:
    a = candidate("a")
    assign_refs([a])
    ranked = combine([a], {"claude": {a.ref: (8, "")}, "codex": {}}, count=5)
    assert ranked[0].score == 0.8


def test_blurbs_must_be_in_the_episodes_own_language() -> None:
    en = candidate("english")
    zh = candidate("中文節目", "zh")
    assign_refs([en, zh])
    en_pick, zh_pick = Pick(en, 1, [], {}), Pick(zh, 1, [], {})
    assert parse_blurbs(
        {"blurbs": [
            {"ref": en.ref, "blurb": "A reporter retraces a flood."},
            {"ref": "en999", "blurb": "invented"},
        ]},
        [en_pick],
        "en",
    ) == {en.ref: "A reporter retraces a flood."}
    assert parse_blurbs(
        {"blurbs": [{"ref": zh.ref, "blurb": "An English blurb for a Chinese show."}]},
        [zh_pick],
        "zh",
    ) == {}
    assert parse_blurbs(
        {"blurbs": [{"ref": zh.ref, "blurb": "主持人和作家聊城市變遷，談到老街的記憶。"}]},
        [zh_pick],
        "zh",
    ) == {zh.ref: "主持人和作家聊城市變遷，談到老街的記憶。"}
    assert "Traditional characters" in blurb_prompt("zh", [zh_pick])


# ------------------------------------------------------------------ publishing


def result_with(*picks):
    return WeeklyResult(WEEK, list(picks), 3, 2, 40, {"en": ["claude", "codex"]}, 1_757_000_000_000)


def test_the_issue_is_a_valid_radio_pool_with_blurbs_as_reasons() -> None:
    nominated = candidate("scoop", nominations=[Nomination("codex", "en", "s", "scoop", "praised", "https://x.example/a")])
    zh = candidate("訪談", "zh")
    assign_refs([nominated, zh])
    pool = issue_pool(
        result_with(
            Pick(zh, 0.7, ["claude"], {}, "一場訪談。"),
            Pick(nominated, 0.9, ["claude", "codex"], {}, "A scoop."),
        ),
        WEEK.pool_id,
        WEEK.label,
    )
    assert pool["profileId"] == "weekly-2026-w37"
    assert [e["rank"] for e in pool["entries"]] == [1, 2]
    first = pool["entries"][0]
    assert first["why"] == "A scoop."
    assert first["id"] == first["episode"]["id"]
    assert first["tags"] == ["Society & Culture"]
    assert first["pickedBy"] == ["claude", "codex"]
    assert first["singledOut"][0]["source"] == "https://x.example/a"
    assert pool["week"] == {"id": "2026-W37", "start": "2026-09-07", "end": "2026-09-13"}
    assert pool["sources"] == ["judge:claude", "judge:codex", "verified:feed"]
    json.dumps(pool)


def test_the_index_replaces_a_rebuilt_week_and_sorts_newest_first(tmp_path) -> None:
    a = candidate("a")
    assign_refs([a])
    entry = index_entry(result_with(Pick(a, 0.5, ["claude"], {}, "b")))
    assert entry["counts"] == {"en": 1, "zh": 0}
    assert entry["pickedBy"] == ["claude", "codex"]
    older = {"id": "2026-W36", "weekStart": "2026-08-31", "file": "2026-W36.json"}
    stale = {"id": "2026-W37", "weekStart": "2026-09-07", "entryCount": 99}
    index = updated_index({"issues": [older, stale]}, entry)
    assert [i["id"] for i in index["issues"]] == ["2026-W37", "2026-W36"]
    assert index["issues"][0]["entryCount"] == 1

    (tmp_path / "index.json").write_text(json.dumps(index))
    (tmp_path / "2026-W36.json").write_text(json.dumps({"entries": [{"id": "old-ep"}]}))
    assert previous_issue_ids(tmp_path, WEEK) == {"old-ep"}
    assert previous_issue_ids(tmp_path, Week(datetime.date(2026, 8, 31))) == set()


# ---------------------------------------------------------------------- agents


def test_neither_agent_can_reach_a_metered_api(tmp_path, monkeypatch) -> None:
    monkeypatch.setenv("ANTHROPIC_API_KEY", "sk-ant")
    monkeypatch.setenv("OPENAI_API_KEY", "sk-openai")
    monkeypatch.setenv("CODEX_API_KEY", "sk-codex")
    env = agents._codex_env()
    assert not any(k.startswith(("ANTHROPIC_", "OPENAI_")) or k == "CODEX_API_KEY" for k in env)

    auth = tmp_path / "auth.json"
    auth.write_text(json.dumps({"auth_mode": "apikey"}))
    assert not agents.codex_uses_subscription(auth)
    auth.write_text(json.dumps({"auth_mode": "chatgpt"}))
    assert agents.codex_uses_subscription(auth)
    assert not agents.codex_uses_subscription(tmp_path / "missing.json")


def test_commands_search_only_when_asked() -> None:
    schema = {"type": "object"}
    with_web = agents.claude_command("claude", "opus", "sys", schema, web=True)
    assert "WebSearch,WebFetch" in with_web and "--bare" not in with_web
    without = agents.claude_command("claude", "opus", "sys", schema, web=False)
    assert without[without.index("--tools") + 1] == ""
    work = Path("/w")
    codex = agents.codex_command("codex", "m", "high", work, work / "s", work / "l", web=True)
    assert "tools.web_search=true" in codex and codex[-1] == "-"
    offline = agents.codex_command("codex", "m", "high", work, work / "s", work / "l", web=False)
    assert "tools.web_search=true" not in offline
