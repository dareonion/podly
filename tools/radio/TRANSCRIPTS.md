# Private podcast transcripts

`podly-transcripts` uses the existing feed parser and episode IDs, whisper.cpp and
Codex to make Mandarin transcripts for selected episodes. Run from `tools/radio`:

```sh
uv run podly-transcripts list '不开玩笑' --limit 20
uv run podly-transcripts list 'https://proxy.wavpub.com/jokes-aside.xml'
uv run podly-transcripts run 'https://proxy.wavpub.com/jokes-aside.xml' 269 268
uv run podly-transcripts run 'https://example.com/feed.xml' latest:2 --script traditional
uv run podly-transcripts index
```

A show can be a feed URL, Apple Podcasts URL, or an Apple search term. Multiple
search results require choosing a feed URL. Numeric selectors match leading episode
numbers, never the printed list index. Other selectors match title substrings;
`latest:N` selects N newest episodes. Every selector is resolved before any work or
configuration changes, and overlaps are processed once, newest first.

External binaries: `ffmpeg`, `ffprobe`, `codex` on PATH, and
`$WHISPER_CPP_DIR/build/bin/whisper-cli` (default `~/gitrepo/whisper.cpp`). The Whisper
models are `models/ggml-large-v3-turbo.bin` and `models/ggml-silero-v6.2.0.bin` beneath
that directory. Whisper runs one episode at a time. `--concurrency` controls Codex
chunks (default 3); `--codex-model` overrides the top listed model from Codex's local
model cache. The resolved model is recorded and passed explicitly to every chunk.

Feed language determines simplified/traditional script; `--script` saves an override
for that feed URL in `config.json`. Hong Kong/Cantonese tags produce a warning because
this pipeline transcribes with Whisper's `zh` language. Regional vocabulary is kept.
An existing `transcript.json` skips an episode; `--redo` reruns Whisper and correction
but retains the downloaded audio. Interrupted runs can reuse successful Whisper output
and checked correction chunks. Fingerprints cover text, preceding context, brief,
schema, script, model and show notes. Failed corrections retry once, then preserve raw
Whisper text with a recorded warning. This character-count heuristic cannot prove
that a correction is accurate.

Whisper can lock into a repetition loop, most likely on long stretches of other languages:
on KK Show 384 (2026-11-05) one line ran 477 times over a 12-minute English interview, the
fix-up rightly dropped it, the kept-characters guard refused that, and the loop was
published. After every Whisper pass, 8+ identical lines in a row (runs under a minute
apart merged) are re-transcribed with no carried context (`-mc 0`, which transcribed that
interview properly) over a window padded 30 s to whole segments, and spliced into
`whisper.srt`. Anything still looping is folded into one line. The original stays in
`whisper.looped.srt` and `loop-repair.json` records the windows, so it runs once per Whisper
pass. Real speech repeated a line at most 5 times in a row in the seven episodes checked.

Data lives outside git in `$PODLY_TRANSCRIPTS_DIR`, default
`~/.local/share/podly-transcripts`:

```text
config.json                    per-feed script overrides
work/<episode_id>/
  audio.<ext>                  retained original audio
  whisper.{srt,txt,json,log}    raw transcript and log
  whisper-state.json           marker for a completed Whisper invocation
  whisper.looped.srt, loop-repair.json   pre-repair SRT and the loop windows re-transcribed
  chunks/<k>/                  chunk/before/shownotes, brief, schema, logs, checked JSON
  transcript.json              authoritative transcript
  transcript.md, changes.md    derived readable transcript and corrections
public/
  index.json
  <episode_id>/transcript.<sha256-first-8>.vtt
```

`transcript.json` version 1 has `id`, `guid`, `audioUrl`, `feedUrl`, `title`,
`showTitle`, `showDescription`, `showNotes`, optional `hosts`, `pubDateMs`, `feedDurationMs`, measured
`durationMs`, `script`, `language`, `models`, `chapters`, `paragraphs`, `corrections`,
`notes`, and `chunks`. A paragraph contains `start_ms`, `end_ms`, `text`, and `chapter`
(the chapter title, or null before the first chapter). Corrections retain the original
four fields plus `chunk`; notes contain `chunk` and `text`. Each chunk records its index,
first and last raw segment starts, and `fallback` (null or the failure reason). Paragraph
ends use the next paragraph's exact start; the last uses the SRT's final segment end,
not the audio duration. The language is `zh-Hans` or `zh-Hant` for the output script.
Legacy imports record their unrecorded Codex model as `legacy (not recorded)`.
`date` is the Markdown display date (UTC for new runs); legacy imports preserve the
publisher's calendar date from `episodes.json` without changing the feed's `pubDateMs`.

`index` derives Markdown, VTTs and the index from these JSON files. VTT text escapes
`&`, `<`, `>`, and wraps at 40 CJK glyph widths (80 Latin columns), preferring punctuation
and spaces. Long paragraphs can span more than two lines. Index writes share an `fcntl`
lock; JSON and VTT writes use temporary files and rename. A superseded VTT is kept, so a
phone still holding the old index URL can load it. Only the index and VTTs go in `public/`.
Transcripts never go in the repository's `site/`.

`serve` publishes `public/` on loopback (default `127.0.0.1:$PORT`, else 8016; see
`~/gitrepo/PORTS.md`) for the tailnet to front, without root:

```sh
PORT=8016 uv run podly-transcripts serve
tailscale serve --https=8016 http://127.0.0.1:8016   # https://claude-home.tail0dbcfd.ts.net:8016/
```

It refuses paths outside `public/`, directories and listings. Both run only while a session
keeps them running (Darren's choice, 2026-11-04); nothing is installed as a service. Podly's
Settings → Transcript server takes that URL.

To import the four existing Jokes Aside transcripts (266–269), without model calls:

```sh
uv run podly-transcripts import-legacy
# Offline verification with a saved copy of that feed:
PODLY_TRANSCRIPTS_DIR=/tmp/transcripts-check uv run podly-transcripts import-legacy \
  --feed-file /path/to/saved-jokes-aside-feed.xml
```

The importer reads `~/gitrepo/transcribe/out/jokes-aside` and its `work/<n>/<k>`
results, copies the audio from `~/gitrepo/transcribe/audio/jokes-aside`, and measures
it with ffprobe. Whole-second paragraph starts map to the first unused SRT segment
in that chunk with the same floored second, in order. Legacy evidence is preserved in
`work/<id>/legacy/<k>/`; it is not treated as a fingerprinted correction cache. Feed/SRT
end differences above 2% are reported without failing the import.

Progress and failures are timestamped on stderr. Tests mock HTTP and external tools:

```sh
uv run ruff check .
uv run pytest -q
```
