from podly_radio.build import useful_genres


def test_keeps_every_genre_not_just_the_first_three():
    # The app filters on these; a show's fourth genre disqualifies it as surely
    # as its first, so truncating could publish a pick that should be excluded.
    genres = ("Society & Culture", "Podcasts", "Documentary", "True Crime")
    assert useful_genres(genres) == ["Society & Culture", "Documentary", "True Crime"]


def test_drops_the_noise_genre_in_any_language():
    assert useful_genres(("兒童故事", "播客", "兒童與家庭")) == ["兒童故事", "兒童與家庭"]
    assert useful_genres(("Podcast",)) == []
