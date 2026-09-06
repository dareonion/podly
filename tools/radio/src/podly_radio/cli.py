"""podly-radio — build and publish Podly's radio candidate pools."""

from __future__ import annotations

import argparse
import json
import logging
import sys
from pathlib import Path

import httpx

from . import build as build_module
from .config import load
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
    _write_index(out_dir, profiles)
    # Only a total failure is worth a non-zero exit; one thin profile must not
    # take the whole run red.
    return 1 if failures == len(profiles) else 0


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

    args = parser.parse_args(argv)
    logging.basicConfig(
        level=logging.DEBUG if args.verbose else logging.INFO,
        format="%(levelname)s %(message)s",
    )
    logging.getLogger("httpx").setLevel(logging.WARNING)
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())
