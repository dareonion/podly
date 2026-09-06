"""Scoring and diversity — the part that decides what actually lands in a pool.

Pure functions over already-fetched data, so it is testable without network.
"""

from __future__ import annotations

import math
from dataclasses import dataclass

DAY_MS = 86_400_000


@dataclass(slots=True)
class Candidate:
    show_id: str
    show_weight: float
    episode_index: int
    pub_date_ms: int
    duration_ms: int | None
    language: str | None


def show_weight(chart_rank: int | None, chart_size: int, curated: float | None) -> float:
    """
    Combines chart position with the curator's judgement.

    Charts measure popularity, the curator judges acclaim; neither alone is
    what "worth someone's time" means, so both feed in.
    """
    chart = 0.0
    if chart_rank is not None and chart_size > 1:
        chart = 1.0 - 0.7 * ((chart_rank - 1) / (chart_size - 1))
    acclaim = 0.5 if curated is None else max(0.0, min(1.0, curated))
    return 0.6 * acclaim + 0.4 * chart


def recency_score(pub_date_ms: int, now_ms: int) -> float:
    age_days = max(0, now_ms - pub_date_ms) / DAY_MS
    return math.exp(-age_days / 45.0)


def duration_fit(duration_ms: int | None, low_ms: int, high_ms: int) -> float:
    """1.0 inside the sweet spot, tapering outside it; unknown is neutral."""
    if duration_ms is None:
        return 0.5
    if low_ms <= duration_ms <= high_ms:
        return 1.0
    if duration_ms < low_ms:
        return max(0.0, duration_ms / low_ms) if low_ms else 0.0
    return max(0.0, high_ms / duration_ms)


def episode_score(candidate: Candidate, now_ms: int, low_ms: int, high_ms: int) -> float:
    return (
        0.55 * candidate.show_weight
        + 0.30 * recency_score(candidate.pub_date_ms, now_ms)
        + 0.15 * duration_fit(candidate.duration_ms, low_ms, high_ms)
    )


def interleave(
    ranked: list[Candidate], max_per_show: int, limit: int
) -> list[Candidate]:
    """
    Round-robins across shows so the pool does not open with six episodes of
    one podcast. The same idea as the generator's existing mergeAreaPicks:
    variety belongs in code, not in a prompt.
    """
    by_show: dict[str, list[Candidate]] = {}
    for candidate in ranked:
        by_show.setdefault(candidate.show_id, []).append(candidate)
    for items in by_show.values():
        del items[max_per_show:]

    order = sorted(
        by_show.values(),
        key=lambda items: items[0].show_weight,
        reverse=True,
    )
    out: list[Candidate] = []
    round_index = 0
    while len(out) < limit:
        added = False
        for items in order:
            if round_index < len(items):
                out.append(items[round_index])
                added = True
                if len(out) >= limit:
                    break
        if not added:
            break
        round_index += 1
    return out
