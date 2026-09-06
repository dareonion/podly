"""Language detection, because no source reports it reliably.

Feeds do carry ``<language>``, but plenty of Mandarin shows declare ``en-us``,
so the text itself is the primary signal and the declared tag is the tiebreak.
"""

from __future__ import annotations

# Characters used (near-)exclusively by one script, for the Hant/Hans split.
_TRADITIONAL = set("繁體臺灣說話實現覺當為熱點國語聲會兒學開來過發")
_SIMPLIFIED = set("简体台湾说话实现觉当为热点国语声会儿学开来过发")


def _cjk_ratio(text: str) -> float:
    if not text:
        return 0.0
    cjk = sum(1 for ch in text if "一" <= ch <= "鿿")
    letters = sum(1 for ch in text if ch.isalpha() or "一" <= ch <= "鿿")
    return cjk / letters if letters else 0.0


def detect(text: str, declared: str | None = None) -> str:
    """Returns a BCP-47 primary subtag: ``zh-Hant``, ``zh-Hans`` or ``en``."""
    ratio = _cjk_ratio(text)
    if ratio < 0.2:
        if declared and declared.lower().startswith("zh"):
            return _chinese_script(text, declared)
        return "en"
    return _chinese_script(text, declared)


def _chinese_script(text: str, declared: str | None) -> str:
    traditional = sum(1 for ch in text if ch in _TRADITIONAL)
    simplified = sum(1 for ch in text if ch in _SIMPLIFIED)
    if traditional > simplified:
        return "zh-Hant"
    if simplified > traditional:
        return "zh-Hans"
    lowered = (declared or "").lower()
    if lowered.startswith(("zh-cn", "zh-hans", "zh_cn")):
        return "zh-Hans"
    # Taiwan and Hong Kong are where most of this catalogue comes from.
    return "zh-Hant"
