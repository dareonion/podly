from podly_radio.config import ProfileConfig
from podly_radio.curate import _child_env, _extract_json, _validate, build_digest
from podly_radio.sources import Show

PROFILE = ProfileConfig(
    id="you", label="You", languages=("en",), countries=("us",),
    target_entries=10, min_entries=1, min_shows=1, max_per_show=2,
    sweet_spot_ms=(0, 1), hard_ms=(0, 10**9), roster_size=12,
)
SHOWS = [Show(apple_id=f"id{i}", title=f"Show {i}", chart_rank=i) for i in range(1, 13)]
REFS = {f"s{i:03d}": show for i, show in enumerate(SHOWS, start=1)}


def test_the_digest_offers_refs_not_free_text() -> None:
    digest = build_digest(PROFILE, SHOWS, "A listener.")
    assert "s001 | Show 1" in digest
    assert "<catalogue>" in digest


def test_invented_refs_are_dropped() -> None:
    payload = {"roster": [
        {"ref": "s001", "weight": 0.9, "why": "good"},
        {"ref": "s999", "weight": 1.0, "why": "hallucinated"},
        *[{"ref": f"s{i:03d}", "weight": 0.5, "why": "x"} for i in range(2, 8)],
    ]}
    roster = _validate(payload, REFS, PROFILE)
    assert [entry.apple_id for entry in roster] == [f"id{i}" for i in range(1, 8)]


def test_a_thin_reply_fails_so_the_previous_roster_survives() -> None:
    try:
        _validate({"roster": [{"ref": "s001"}]}, REFS, PROFILE)
    except RuntimeError as error:
        assert "keeping the old roster" in str(error)
    else:  # pragma: no cover
        raise AssertionError("a thin roster must not be accepted")


def test_weights_are_clamped_and_reasons_trimmed() -> None:
    payload = {"roster": [
        {"ref": f"s{i:03d}", "weight": 9 if i == 1 else 0.4, "why": "a\n b  c" if i == 1 else "y"}
        for i in range(1, 8)
    ]}
    roster = _validate(payload, REFS, PROFILE)
    assert roster[0].weight == 1.0
    assert roster[0].why == "a b c"


def test_the_child_environment_cannot_reach_a_metered_api(monkeypatch) -> None:
    monkeypatch.setenv("ANTHROPIC_API_KEY", "sk-should-not-survive")
    monkeypatch.setenv("CLAUDE_CODE_SOMETHING", "1")
    env = _child_env()
    assert "ANTHROPIC_API_KEY" not in env
    assert "CLAUDE_CODE_SOMETHING" not in env


def test_json_is_extracted_from_fenced_prose() -> None:
    assert _extract_json('Sure!\n```json\n{"roster": []}\n```')["roster"] == []


def test_the_notable_prompt_demands_recent_acclaim() -> None:
    """The first hunt returned 2014-2015 classics because nothing asked for recency."""
    import datetime
    from podly_radio import notable

    captured = {}

    def fake_run(command, **kwargs):
        captured["prompt"] = kwargs["input"]
        raise RuntimeError("stop here")

    original = notable.subprocess.run
    notable.subprocess.run = fake_run
    try:
        notable.ask_for_nominations(30)
    except Exception:
        pass
    finally:
        notable.subprocess.run = original

    prompt = captured.get("prompt", "")
    assert str(datetime.date.today().year - 1) in prompt
    assert "MUST be from" in prompt
    # Rolled-off episodes cannot be played, so the model is told not to spend
    # nominations on them.
    assert "rolled out of the feed" in prompt


def test_the_hunt_is_split_per_kind_so_one_failure_is_survivable() -> None:
    """A single long ask hit the turn budget mid-search and returned nothing."""
    from podly_radio import notable

    calls = []

    def fake_ask(kind, count, *args, **kwargs):
        calls.append(kind)
        if kind == "popular":
            raise RuntimeError("simulated failure")
        return [notable.Nomination("Show", "Ep", "Won something", "en", kind)]

    original = notable._ask_one_kind
    notable._ask_one_kind = fake_ask
    try:
        result = notable.ask_for_nominations(10)
    finally:
        notable._ask_one_kind = original

    assert calls == ["popular", "acclaimed"]
    # The acclaimed half still lands even though the popular half blew up.
    assert [n.kind for n in result] == ["acclaimed"]


def test_the_prompt_bounds_searching_and_forbids_ending_on_a_tool_call() -> None:
    from podly_radio import notable

    captured = {}

    def fake_run(command, **kwargs):
        captured["prompt"] = kwargs["input"]
        captured["command"] = command
        raise RuntimeError("stop here")

    original = notable.subprocess.run
    notable.subprocess.run = fake_run
    try:
        notable._ask_one_kind("popular", 8, "m", 60, True, 2025, 0.7)
    except Exception:
        pass
    finally:
        notable.subprocess.run = original

    assert "at most 5 web searches" in captured["prompt"]
    assert "do not end your turn" in captured["prompt"]
    # Too small a turn budget is what left it mid-search with nothing to show.
    assert "30" in captured["command"]
