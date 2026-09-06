"""Frozen config loaded from radio.yaml, matching the box's other jobs."""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path

import yaml


@dataclass(frozen=True, slots=True)
class ProfileConfig:
    id: str
    label: str
    languages: tuple[str, ...]
    countries: tuple[str, ...]
    target_entries: int
    min_entries: int
    min_shows: int
    max_per_show: int
    sweet_spot_ms: tuple[int, int]
    hard_ms: tuple[int, int]
    roster_size: int
    genres: tuple[int, ...] = ()
    search_terms: tuple[str, ...] = ()
    require_clean: bool = False


@dataclass(frozen=True, slots=True)
class Config:
    profiles: tuple[ProfileConfig, ...] = field(default_factory=tuple)

    def profile(self, profile_id: str) -> ProfileConfig:
        for profile in self.profiles:
            if profile.id == profile_id:
                return profile
        raise KeyError(profile_id)


def load(path: Path) -> Config:
    raw = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
    profiles = []
    for entry in raw.get("profiles", []):
        sweet = entry.get("sweet_spot_minutes", [20, 75])
        hard = entry.get("hard_minutes", [1, 240])
        profiles.append(
            ProfileConfig(
                id=entry["id"],
                label=entry["label"],
                languages=tuple(entry.get("languages", ())),
                countries=tuple(entry.get("countries", ("us",))),
                target_entries=int(entry.get("target_entries", 150)),
                min_entries=int(entry.get("min_entries", 40)),
                min_shows=int(entry.get("min_shows", 10)),
                max_per_show=int(entry.get("max_per_show", 3)),
                sweet_spot_ms=(int(sweet[0]) * 60_000, int(sweet[1]) * 60_000),
                hard_ms=(int(hard[0]) * 60_000, int(hard[1]) * 60_000),
                roster_size=int(entry.get("roster_size", 40)),
                genres=tuple(int(g) for g in entry.get("genres", ())),
                search_terms=tuple(entry.get("search_terms", ())),
                require_clean=bool(entry.get("require_clean", False)),
            )
        )
    return Config(profiles=tuple(profiles))
