# Recommendation generator

A standalone Kotlin/JVM module that generates Podly's **recent-episode** and **acclaimed**
recommendation lists with Claude and writes them as static JSON. A scheduled GitHub Action
(`.github/workflows/recommendations.yml`) runs it and publishes the output to GitHub Pages; the app
fetches the files instead of calling Claude on-device.

It depends only on a JDK (no Android SDK), so CI is lightweight.

## Run locally

```sh
export ANTHROPIC_API_KEY=sk-ant-...
OUTPUT_DIR=/tmp/site ./gradlew :generator:run
ls /tmp/site   # recent-2weeks.json recent-month.json recent-3months.json acclaimed.json
```

`OUTPUT_DIR` defaults to `site`. The recent-episode call uses Claude's web search (it looks up
episodes newer than the training cutoff), so each run takes a few minutes and costs API credits.

## Output files

Published at `https://dareonion.github.io/podly/<file>`:

| File                  | Contents                                  |
|-----------------------|-------------------------------------------|
| `recent-2weeks.json`  | Best individual episodes, past 2 weeks    |
| `recent-month.json`   | Best individual episodes, past month      |
| `recent-3months.json` | Best individual episodes, past 3 months   |
| `acclaimed.json`      | Award winners / best-of picks, ~12 months |

### Contract

The files deserialize directly into the app's `CachedRecentEpisodes` / `CachedAcclaimed`
(`app/.../data/AiPicksCache.kt`). The classes in `Payloads.kt` mirror them field-for-field — keep
them in sync; the app's `RemoteRecsContractTest` fails if they drift.

```jsonc
// recent-*.json
{
  "version": 1,
  "generatedAtMs": 1750924800000,   // when this run produced the file
  "window": "MONTH",                // TWO_WEEKS | MONTH | THREE_MONTHS
  "coverageStart": "2026-05-26",    // time span the picks cover
  "coverageEnd": "2026-06-26",
  "picks": [
    {
      "pick": { "podcastTitle": "...", "episodeTitle": "...", "author": "...",
                "reason": "...", "publishedApprox": "2026-06-20" },
      "podcastId": "ab12…", "podcastTitle": "...", "podcastAuthor": "...",
      "feedUrl": "https://…", "artworkUrl": "https://…", "podcastDescription": "News"
    }
  ]
}
```

`acclaimed.json` uses the same envelope minus `window`/`coverageStart`/`coverageEnd`, plus
`"coverageLabel": "the last 12 months"`; its `pick` objects have `episodeTitle?` + `accolade`.

### Resilience

If a payload fails to generate, the generator re-fetches the currently-published file and re-emits it
so a deploy never drops a list. If even that's unavailable (first run), it writes a minimal empty
payload. The process exits non-zero only when **every** payload failed, so a total outage leaves the
previous GitHub Pages deploy intact.

## One-time setup

1. Add an Actions secret **`ANTHROPIC_API_KEY`** (repo → Settings → Secrets and variables → Actions).
2. Enable Pages: repo → Settings → Pages → **Source: GitHub Actions**.
3. The repo must be **public** so the app can fetch the files unauthenticated.
4. Run the workflow once via **Actions → Generate recommendations → Run workflow** to publish the first set.
