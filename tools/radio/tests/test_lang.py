from podly_radio.lang import detect


def test_english_stays_english_even_with_a_stray_character() -> None:
    assert detect("Search Engine: what is a browser?") == "en"


def test_traditional_and_simplified_are_told_apart() -> None:
    assert detect("水獺媽媽巧巧話｜聽巧慧說故事學台語") == "zh-Hant"
    assert detect("简体中文说话实现") == "zh-Hans"


def test_a_declared_tag_only_breaks_ties() -> None:
    # Text wins over a wrong declaration, which is common on Mandarin feeds.
    assert detect("水獺媽媽說故事", declared="en-us").startswith("zh")
    assert detect("Plain English title", declared="zh-tw").startswith("zh")
