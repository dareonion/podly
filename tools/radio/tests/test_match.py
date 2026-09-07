from podly_radio.match import ACCEPT, best_match, normalize, similarity


def test_chinese_titles_survive_normalisation() -> None:
    # The app's own matcher reduces this to "" and can never match it.
    assert normalize("EP.133 急凍剋星的極光之旅（上集）") != ""
    assert similarity("急凍剋星的極光之旅", "EP.133 急凍剋星的極光之旅（上集）") >= ACCEPT


def test_exact_and_punctuation_only_differences_match() -> None:
    assert similarity("The Killing of the Gaza Chef", "The Killing of the Gaza Chef") == 1.0
    assert similarity("Poll Me Twice, Shame On Me", "Poll Me Twice — Shame on Me") >= ACCEPT


def test_a_feed_episode_number_prefix_does_not_block_a_match() -> None:
    assert similarity("Jared Diamond", "#2549 - Jared Diamond") >= ACCEPT
    assert similarity("諸葛白菜之三十六計", "第12集 諸葛白菜之三十六計") >= ACCEPT


def test_unrelated_titles_are_rejected() -> None:
    assert similarity("Classical Music Is in Crisis", "Orcas ramming sunfish") < ACCEPT
    assert best_match("Some Award Winner", ["Totally different", "Also unrelated"]) is None


def test_best_match_picks_the_closest_candidate() -> None:
    match = best_match(
        "The Haditha killings",
        ["Season 3 trailer", "In the Dark: The Haditha killings", "Bonus episode"],
    )
    assert match is not None and match.index == 1


def test_a_one_word_citation_does_not_match_everything() -> None:
    # "Bonus" must not match every bonus episode ever published.
    assert similarity("Bonus", "Bonus episode about everything else entirely") < ACCEPT
    assert similarity("Trailer", "Trailer for our new investigative series") < ACCEPT


def test_a_genuine_paraphrase_still_matches() -> None:
    assert (
        similarity(
            "Poll Me Twice, Shame On Me",
            "Pod Save America: Poll Me Twice, Shame On Me",
        )
        >= ACCEPT
    )
