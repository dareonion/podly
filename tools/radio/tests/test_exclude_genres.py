from podly_radio.config import ProfileConfig
from podly_radio.harvest import is_suitable
from podly_radio.sources import Show


def profile(**kwargs) -> ProfileConfig:
    base = dict(
        id="you",
        label="You",
        languages=("en",),
        countries=("us",),
        target_entries=10,
        min_entries=1,
        min_shows=1,
        max_per_show=3,
        sweet_spot_ms=(0, 0),
        hard_ms=(0, 0),
        roster_size=10,
    )
    base.update(kwargs)
    return ProfileConfig(**base)


def show(*genre_ids: int) -> Show:
    return Show(
        apple_id="1",
        title="A show",
        feed_url="https://example.com/feed",
        genre_ids=tuple(genre_ids),
    )


def test_excluded_genre_drops_the_show():
    you = profile(exclude_genres=(1488,))
    assert not is_suitable(show(1488), you)
    assert is_suitable(show(1301), you)


def test_exclusion_wins_over_a_wholesome_second_genre():
    # True Crime shows are routinely also filed under Society & Culture.
    you = profile(exclude_genres=(1488,))
    assert not is_suitable(show(1324, 1488), you)


def test_no_exclusions_configured_keeps_everything():
    assert is_suitable(show(1488), profile())


def test_an_exempt_genre_rescues_the_show():
    # "Good Inside with Dr. Becky" is Kids & Family + Parenting: a show for
    # parents, filed where children's programming lives.
    you = profile(exclude_genres=(1305,), exempt_genres=(1521,))
    assert is_suitable(show(1305, 1521), you)
    assert not is_suitable(show(1305, 1520), you)
