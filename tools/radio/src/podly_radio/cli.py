"""podly-radio — build and publish Podly's radio candidate pools."""

from __future__ import annotations

import argparse
import json
import logging
import sys
import time
from pathlib import Path

import httpx

from . import build as build_module
from .config import load
from .curate import curate
from .notable import ask_for_nominations, verify
from .ids import episode_id, podcast_id
from .schema import PoolEntry, PoolEpisode, PoolFile, PoolPodcast
from .harvest import harvest, is_suitable, roster_for
from .schema import PoolValidationError, validate

LOG = logging.getLogger("podly_radio")
USER_AGENT = "Podly-Radio/1.0 (+https://github.com/dareonion/podly)"


def _client() -> httpx.Client:
    return httpx.Client(
        timeout=25,
        follow_redirects=True,
        headers={"User-Agent": USER_AGENT},
    )


def cmd_build(args: argparse.Namespace) -> int:
    config = load(Path(args.config))
    out_dir = Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)
    profiles = [config.profile(args.profile)] if args.profile else list(config.profiles)

    roster_dir = Path(args.config).parent / "roster"
    failures = 0
    for profile in profiles:
        curated, reasons = _load_roster(roster_dir / f"{profile.id}.json")
        with _client() as client:
            shows = [s for s in harvest(client, profile) if is_suitable(s, profile)]
            roster = roster_for(shows, profile)
            if curated:
                # A curated roster is an ordering, not a filter: charts still
                # supply the long tail underneath it.
                roster.sort(key=lambda s: (s.apple_id not in curated, s.chart_rank or 999))
            pool = build_module.build_pool(client, profile, roster, curated, reasons)
        try:
            validate(pool, profile.min_entries, profile.min_shows)
        except PoolValidationError as error:
            LOG.error("not publishing %s: %s", profile.id, error)
            failures += 1
            continue
        target = out_dir / f"{profile.id}.json"
        _write_atomic(target, pool.to_dict())
        LOG.info(
            "%s: %d entries across %d shows -> %s",
            profile.id,
            len(pool.entries),
            len({e.podcast.id for e in pool.entries}),
            target,
        )
    # The daily build rewrites each pool from scratch, so the boosts a notable
    # run applied would be lost unless they are re-applied from the published
    # notable list every time.
    _reapply_notable(out_dir)
    _write_index(out_dir, profiles)
    # Only a total failure is worth a non-zero exit; one thin profile must not
    # take the whole run red.
    return 1 if failures == len(profiles) else 0


def cmd_curate(args: argparse.Namespace) -> int:
    """Refreshes the roster with one Claude Code call per profile."""
    config = load(Path(args.config))
    roster_dir = Path(args.config).parent / "roster"
    roster_dir.mkdir(parents=True, exist_ok=True)
    profiles = [config.profile(args.profile)] if args.profile else list(config.profiles)

    failures = 0
    for profile in profiles:
        with _client() as client:
            shows = [s for s in harvest(client, profile) if is_suitable(s, profile)]
        shows = roster_for(shows, profile)[: args.catalogue]
        if not shows:
            LOG.error("%s: nothing harvested, keeping the old roster", profile.id)
            failures += 1
            continue
        try:
            roster = curate(profile, shows, profile.listener, model=args.model)
        except Exception as error:  # noqa: BLE001 - any failure keeps the old roster
            LOG.error("%s: curation failed (%s); keeping the old roster", profile.id, error)
            failures += 1
            continue
        target = roster_dir / f"{profile.id}.json"
        _write_atomic_list(
            target,
            [
                {"appleId": entry.apple_id, "weight": entry.weight, "why": entry.why}
                for entry in roster
            ],
        )
        LOG.info("%s: roster of %d shows -> %s", profile.id, len(roster), target)
    return 1 if failures == len(profiles) else 0


def _write_atomic_list(path: Path, payload: list) -> None:
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", "utf-8")
    tmp.replace(path)


def cmd_notable(args: argparse.Namespace) -> int:
    """Hunts for award-winning and acclaimed episodes, and verifies every one."""
    out_dir = Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)
    try:
        nominations = ask_for_nominations(
            args.count, model=args.model, recent_year=args.since
        )
    except Exception as error:  # noqa: BLE001 - keep the published list on any failure
        LOG.error("nomination failed (%s); keeping the published notable list", error)
        return 1
    with _client() as client:
        verified = verify(client, nominations)
    if not verified:
        LOG.error("nothing verified; keeping the published notable list")
        return 1

    now_ms = int(time.time() * 1000)
    entries = []
    for rank, item in enumerate(
        sorted(verified, key=lambda v: v.score, reverse=True), start=1
    ):
        episode = item.episode
        pod_id = podcast_id(item.feed_url)
        ep_id = episode_id(episode.guid, episode.audio_url)
        entries.append(
            PoolEntry(
                id=ep_id,
                rank=rank,
                score=item.score,
                language=item.nomination.language,
                why=item.nomination.accolade,
                tags=[item.nomination.kind],
                podcast=PoolPodcast(
                    id=pod_id,
                    title=item.show_title,
                    author=item.show_author,
                    feedUrl=item.feed_url,
                    artworkUrl=item.show_artwork,
                    language=item.nomination.language,
                ),
                episode=PoolEpisode(
                    id=ep_id,
                    title=episode.title,
                    audioUrl=episode.audio_url,
                    pubDateMs=episode.pub_date_ms,
                    guid=episode.guid,
                    description=(episode.description or "")[:240] or None,
                    audioMimeType=episode.audio_mime_type,
                    durationMs=episode.duration_ms,
                    artworkUrl=episode.image_url or item.show_artwork,
                ),
            )
        )
    pool = PoolFile(
        profileId="notable",
        profileLabel="Notable",
        generatedAtMs=now_ms,
        entries=entries,
        sources=["claude:awards", "verified:feed"],
    )
    _write_atomic(out_dir / "notable.json", pool.to_dict())
    LOG.info("notable: %d verified of %d nominated", len(entries), len(nominations))
    kinds = {item.episode and entry.id: item.nomination.kind for entry, item in zip(entries, sorted(verified, key=lambda v: v.score, reverse=True))}
    _boost_pools(out_dir, {entry.id: entry.why or "" for entry in entries}, kinds)
    return 0


def _reapply_notable(out_dir: Path) -> None:
    notable = out_dir / "notable.json"
    if not notable.exists():
        return
    data = json.loads(notable.read_text(encoding="utf-8"))
    entries = data.get("entries", [])
    accolades = {entry["id"]: entry.get("why", "") for entry in entries}
    kinds = {
        entry["id"]: (entry.get("tags") or ["acclaimed"])[0] for entry in entries
    }
    if accolades:
        _boost_pools(out_dir, accolades, kinds)


def _boost_pools(
    out_dir: Path, accolades: dict[str, str], kinds: dict[str, str] | None = None
) -> None:
    """
    Marks acclaimed episodes inside the ordinary pools.

    An award winner the user would have heard anyway should not need a separate
    trip: it rides in the normal rotation with its citation as the reason.
    """
    for path in sorted(out_dir.glob("*.json")):
        if path.name in ("index.json", "notable.json"):
            continue
        data = json.loads(path.read_text(encoding="utf-8"))
        touched = 0
        for entry in data.get("entries", []):
            accolade = accolades.get(entry.get("id"))
            if not accolade:
                continue
            entry["score"] = min(1.0, float(entry.get("score", 0)) + 0.5)
            entry["why"] = accolade
            tags = set(entry.get("tags", [])) | {(kinds or {}).get(entry.get("id"), "acclaimed")}
            entry["tags"] = sorted(tags)
            touched += 1
        if touched:
            _write_atomic(path, data)
            LOG.info("boosted %d acclaimed entries in %s", touched, path.name)


def _load_roster(path: Path) -> tuple[dict[str, float], dict[str, str]]:
    if not path.exists():
        return {}, {}
    raw = json.loads(path.read_text(encoding="utf-8"))
    weights = {entry["appleId"]: float(entry.get("weight", 0.5)) for entry in raw}
    reasons = {
        entry["appleId"]: entry["why"] for entry in raw if entry.get("why")
    }
    return weights, reasons


def _write_atomic(path: Path, payload: dict) -> None:
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text(json.dumps(payload, ensure_ascii=False, separators=(",", ":")), "utf-8")
    tmp.replace(path)


def _write_index(out_dir: Path, profiles: list) -> None:
    entries = []
    for profile in profiles:
        path = out_dir / f"{profile.id}.json"
        if not path.exists():
            continue
        data = json.loads(path.read_text(encoding="utf-8"))
        entries.append(
            {
                "id": profile.id,
                "label": profile.label,
                "file": f"{profile.id}.json",
                "schemaVersion": data.get("version", 1),
                "entryCount": len(data.get("entries", [])),
                "generatedAtMs": data.get("generatedAtMs", 0),
            }
        )
    if entries:
        _write_atomic(out_dir / "index.json", {"version": 1, "profiles": entries})


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="podly-radio", description=__doc__)
    parser.add_argument("--config", default="radio.yaml")
    parser.add_argument("-v", "--verbose", action="store_true")
    sub = parser.add_subparsers(dest="command", required=True)

    build_cmd = sub.add_parser("build", help="build pool JSON from charts and feeds")
    build_cmd.add_argument("--out", default="../../site/radio")
    build_cmd.add_argument("--profile", help="only this profile")
    build_cmd.set_defaults(func=cmd_build)

    curate_cmd = sub.add_parser("curate", help="refresh the roster with Claude Code")
    curate_cmd.add_argument("--profile", help="only this profile")
    curate_cmd.add_argument("--model", default="claude-opus-4-8")
    curate_cmd.add_argument(
        "--catalogue", type=int, default=120, help="shows offered to the model"
    )
    curate_cmd.set_defaults(func=cmd_curate)

    notable_cmd = sub.add_parser(
        "notable", help="find and verify award-winning and acclaimed episodes"
    )
    notable_cmd.add_argument("--out", default="../../site/radio")
    notable_cmd.add_argument("--count", type=int, default=30)
    notable_cmd.add_argument("--model", default="claude-opus-4-8")
    notable_cmd.add_argument(
        "--since", type=int, default=None,
        help="year that counts as recent (default: last year)",
    )
    notable_cmd.set_defaults(func=cmd_notable)

    args = parser.parse_args(argv)
    logging.basicConfig(
        level=logging.DEBUG if args.verbose else logging.INFO,
        format="%(levelname)s %(message)s",
    )
    logging.getLogger("httpx").setLevel(logging.WARNING)
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())
