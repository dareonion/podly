"""Matching a named episode to a real one in a feed.

This is the verification step that makes search-led acclaim safe: a model may
name an award-winning episode, but nothing is published unless that episode is
actually found in a real feed with real audio.

Deliberately unlike the app's RecentEpisodeMatcher, which normalises with
`[^a-z0-9 ]` and therefore reduces every Chinese title to an empty string. CJK
is kept, and matching falls back to character overlap for scripts that do not
use spaces.
"""

from __future__ import annotations

import re
import unicodedata
from dataclasses import dataclass

# Keep letters, digits and CJK; drop punctuation, which varies wildly between
# an award citation and a feed's own title.
_DROP = re.compile(r"[^\w㐀-䶿一-鿿]+", re.UNICODE)
# "EP.133", "#2549 -", "第12集", "Episode 4:" — feeds prefix these, citations don't.
_EPISODE_NUMBER = re.compile(
    r"^\s*(ep|episode|第)?\s*[.#]?\s*\d+\s*(集|話|回|期)?[\s.:：、\-–—]*",
    re.IGNORECASE,
)


def normalize(title: str) -> str:
    folded = unicodedata.normalize("NFKC", title).casefold()
    return _DROP.sub(" ", folded).strip()


def _strip_leading_number(title: str) -> str:
    """Feeds prefix episode numbers, award citations rarely do."""
    return _EPISODE_NUMBER.sub("", title, count=1).strip()


def _tokens(title: str) -> set[str]:
    normalized = normalize(title)
    words = {w for w in normalized.split() if len(w) > 2}
    # Character bigrams for scripts without spaces, where word tokens are useless.
    cjk = [c for c in normalized if "㐀" <= c <= "鿿"]
    bigrams = {cjk[i] + cjk[i + 1] for i in range(len(cjk) - 1)}
    return words | bigrams


def similarity(wanted: str, candidate: str) -> float:
    """0..1, where 1 means the same title once punctuation is set aside."""
    a, b = normalize(wanted), normalize(candidate)
    if not a or not b:
        return 0.0
    if a == b:
        return 1.0
    a2, b2 = normalize(_strip_leading_number(wanted)), normalize(_strip_leading_number(candidate))
    if a2 and a2 == b2:
        return 0.98
    compact_a, compact_b = a2.replace(" ", "") or a.replace(" ", ""), b2.replace(" ", "") or b.replace(" ", "")
    if compact_a and (compact_a in compact_b or compact_b in compact_a):
        shorter, longer = sorted((len(compact_a), len(compact_b)))
        # Full containment is strong evidence on its own; the ratio only tempers
        # it. Short fragments are excluded so "bonus" cannot match everything.
        if shorter >= 6 and shorter / longer >= 0.3:
            return 0.75 + 0.25 * (shorter / longer)
    tokens_a, tokens_b = _tokens(wanted), _tokens(candidate)
    if not tokens_a or not tokens_b:
        return 0.0
    # Dice rather than "share of the smaller set": with the latter a one-word
    # citation scores a perfect overlap against any title that contains it, so
    # "Bonus" would match every bonus episode ever published.
    overlap = 2 * len(tokens_a & tokens_b) / (len(tokens_a) + len(tokens_b))
    return 0.8 * overlap


@dataclass(frozen=True, slots=True)
class Match:
    index: int
    score: float


ACCEPT = 0.62


def best_match(wanted: str, candidates: list[str]) -> Match | None:
    """The best candidate above the acceptance bar, or None to drop the claim."""
    best: Match | None = None
    for index, candidate in enumerate(candidates):
        score = similarity(wanted, candidate)
        if best is None or score > best.score:
            best = Match(index=index, score=score)
    if best is None or best.score < ACCEPT:
        return None
    return best
