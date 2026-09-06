from podly_radio.select import Candidate, duration_fit, interleave, show_weight

NOW = 1_760_000_000_000


def candidate(show: str, weight: float, index: int = 0) -> Candidate:
    return Candidate(
        show_id=show,
        show_weight=weight,
        episode_index=index,
        pub_date_ms=NOW,
        duration_ms=20 * 60_000,
        language="en",
    )


def test_chart_position_and_curation_both_move_the_weight() -> None:
    top = show_weight(chart_rank=1, chart_size=100, curated=0.9)
    bottom = show_weight(chart_rank=100, chart_size=100, curated=0.9)
    uncurated = show_weight(chart_rank=1, chart_size=100, curated=None)
    assert top > bottom
    assert top > uncurated
    # Nothing off the charts is disqualified by that alone.
    assert show_weight(chart_rank=None, chart_size=0, curated=0.9) > 0


def test_duration_fit_prefers_the_sweet_spot() -> None:
    low, high = 20 * 60_000, 75 * 60_000
    assert duration_fit(40 * 60_000, low, high) == 1.0
    assert duration_fit(3 * 60_000, low, high) < 0.5
    assert duration_fit(4 * 60 * 60_000, low, high) < 0.5
    # Unknown duration is neutral rather than disqualifying.
    assert duration_fit(None, low, high) == 0.5


def test_interleaving_spreads_shows_and_caps_each_one() -> None:
    ranked = (
        [candidate("a", 0.9, i) for i in range(5)]
        + [candidate("b", 0.8, i) for i in range(5)]
        + [candidate("c", 0.7, i) for i in range(5)]
    )
    picked = interleave(ranked, max_per_show=2, limit=6)
    assert [c.show_id for c in picked] == ["a", "b", "c", "a", "b", "c"]
    assert len(picked) == 6


def test_interleaving_stops_when_the_material_runs_out() -> None:
    picked = interleave([candidate("a", 0.9)], max_per_show=3, limit=10)
    assert len(picked) == 1


def test_roster_balances_across_storefronts() -> None:
    """The 'you' pool came out 100% English before this: one chart filled it."""
    from podly_radio.config import ProfileConfig
    from podly_radio.harvest import roster_for
    from podly_radio.sources import Show

    profile = ProfileConfig(
        id="you", label="You", languages=("en", "zh-Hant"), countries=("us", "tw"),
        target_entries=10, min_entries=1, min_shows=1, max_per_show=2,
        sweet_spot_ms=(0, 1), hard_ms=(0, 10**9), roster_size=6,
    )
    shows = [Show(apple_id=f"us{i}", title=f"us{i}", country="us", chart_rank=i) for i in range(1, 20)]
    shows += [Show(apple_id=f"tw{i}", title=f"tw{i}", country="tw", chart_rank=i) for i in range(1, 20)]
    roster = roster_for(shows, profile)
    assert len(roster) == 6
    assert sum(1 for s in roster if s.country == "tw") == 3
    assert sum(1 for s in roster if s.country == "us") == 3
