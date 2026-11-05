"""Generate private Mandarin podcast transcripts with whisper.cpp and Codex."""

from __future__ import annotations

import argparse
import os
from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import httpx

from podly_radio.agents import latest_codex_model
from podly_radio.feeds import parse_feed

from .catalogue import episode_line, fetch_feed, newest, resolve_show, select
from .pipeline import LEGACY_FEED, import_legacy, run_episode
from .storage import data_dir, log, rebuild_index, script_for


def positive(value: str) -> int:
    try:
        result = int(value)
    except ValueError as error:
        raise argparse.ArgumentTypeError("must be a positive integer") from error
    if result <= 0:
        raise argparse.ArgumentTypeError("must be a positive integer")
    return result


class _PublicHandler(SimpleHTTPRequestHandler):
    """Serves files under public/ only: no directory listings, no request log on stderr."""

    def translate_path(self, path):
        # The stdlib handler stops `..` but follows symlinks; refuse anything that resolves
        # outside public/, so a stray link can never expose work/ (audio, logs).
        # Directories are refused too: the stdlib would otherwise serve an index.html
        # inside one after this check, and nothing here ever needs a directory.
        translated = Path(super().translate_path(path)).resolve()
        public = Path(self.directory).resolve()
        if public not in translated.parents or translated.is_dir():
            return str(public / ".refused")
        return str(translated)

    def list_directory(self, path):
        self.send_error(404)

    def log_message(self, format, *args):
        pass


def serve(root: Path, host: str, port: int) -> int:
    """Loopback server for `tailscale serve --https=PORT http://127.0.0.1:PORT`.

    Serving a directory straight from `tailscale serve` needs root; proxying a loopback
    server does not, which is how the box's other apps are published too.
    """
    public = root / "public"
    public.mkdir(parents=True, exist_ok=True)
    handler = partial(_PublicHandler, directory=str(public))
    with ThreadingHTTPServer((host, port), handler) as server:
        log(f"serving {public} on http://{host}:{port}")
        server.serve_forever()
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="podly-transcripts", description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    listing = sub.add_parser("list", help="list recent episodes from a feed or Apple show")
    listing.add_argument("show")
    listing.add_argument("--limit", type=positive, default=20)
    run = sub.add_parser("run", help="download, transcribe and correct selected episodes")
    run.add_argument("show")
    run.add_argument("selectors", nargs="+")
    run.add_argument("--script", choices=("simplified", "traditional"))
    run.add_argument("--redo", action="store_true")
    run.add_argument("--concurrency", type=positive, default=3,
                     help="concurrent Codex chunks; Whisper runs one episode at a time")
    run.add_argument("--codex-model",
                     help="default: Codex's top listed model, resolved once per run")
    legacy = sub.add_parser("import-legacy",
                            help="import the four existing Jokes Aside transcripts")
    legacy.add_argument("--feed-file", type=Path,
                        help="use a saved Jokes Aside feed XML instead of fetching it")
    sub.add_parser("index", help="rebuild the serving index and derived files from transcript.json")
    serving = sub.add_parser("serve", help="serve public/ on loopback for tailscale serve to front")
    serving.add_argument("--host", default="127.0.0.1")
    serving.add_argument("--port", type=positive, default=int(os.environ.get("PORT", "8016")))
    args = parser.parse_args(argv)
    root = data_dir()
    try:
        if args.command == "serve":
            return serve(root, args.host, args.port)
        if args.command == "index":
            index = rebuild_index(root)
            log(f"index rebuilt: {len(index['entries'])} entries")
            return 0
        with httpx.Client(timeout=120, follow_redirects=True,
                          headers={"User-Agent": "Podly/1.0"}) as client:
            if args.command == "import-legacy":
                feed = (parse_feed(args.feed_file.read_bytes()) if args.feed_file
                        else fetch_feed(client, LEGACY_FEED))
                imported = import_legacy(root, feed)
                log(f"imported {len(imported)} episodes")
                return 0
            feed_url = resolve_show(client, args.show)
            feed = fetch_feed(client, feed_url)
            if args.command == "list":
                for i, episode in enumerate(newest(feed.episodes)[:args.limit], 1):
                    print(episode_line(i, episode))
                return 0
            # Resolve every selector before writing config, downloading, or starting tools.
            episodes = select(feed.episodes, args.selectors)
            script = script_for(root, feed_url, feed.language, args.script)
            model = args.codex_model or latest_codex_model()
            whisper_dir = Path(os.environ.get("WHISPER_CPP_DIR", "~/gitrepo/whisper.cpp"))
            failures = 0
            for episode in episodes:
                try:
                    run_episode(client, root, feed_url, feed, episode, script,
                                whisper_dir.expanduser().resolve(), model,
                                args.concurrency, redo=args.redo)
                except Exception as error:  # noqa: BLE001 - finish the other selected episodes
                    log(f"{episode.title} FAILED: {type(error).__name__}: {error}")
                    failures += 1
            return 1 if failures else 0
    except Exception as error:  # noqa: BLE001 - actionable CLI diagnostics without a traceback
        log(f"FAILED: {type(error).__name__}: {error}")
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
