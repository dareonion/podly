# Manual recommendations via ChatGPT / Claude chat

The CI generator needs paid API credits; this is the free alternative using a chat
subscription's built-in web search. Run the prompt below in the ChatGPT or Claude
app (make sure web search is available), copy the JSON it returns, then in Podly:
**Settings → Paste picks JSON → Import**. Picks are matched against each show's
feed, and picks that have rolled off a short feed are recovered through the
Taddy/PodcastIndex keys configured in Settings.

Tips:

- Swap the window in the first line: "the past 2 weeks" / "the past month" /
  "the past 3 months".
- Chatbots can keep searching — if an area looks thin or the list comes back
  short, reply "find 3 more <area> episodes and re-emit the full JSON" before
  importing.
- The list order becomes the playlist order, so the strongest picks land at the
  top. If the bot grouped by area instead, reply "re-order the whole list by
  overall merit, best first, and re-emit the full JSON".
- Skim the list before importing: every entry should be a real, dated episode of
  a real show. Ask the bot to remove anything that looks invented.

## Prompt

Paragraphs are single long lines (no hard wraps) so the block pastes cleanly into a chat box.

```text
You are an expert podcast critic and curator. Use today's actual date.

Find the most worthwhile individual podcast episodes released in the past 2 weeks. Search the web area by area — run at least one focused search for standout recent episodes in each of these nine areas, and take the 3 strongest from each:

1. narrative and investigative storytelling
2. science, technology, and health
3. culture, society, and history
4. interviews and conversations
5. business, economics, and money
6. comedy and casual chat shows
7. sports and games
8. arts and entertainment (music, film, TV, and books)
9. news and politics

Rules:
- Episodes outside the news-and-politics area must not be about politics or current political news, whoever the host or guest is.
- At most 2 episodes from the same podcast across the whole list.
- The final list must contain at least 20 episodes. If an area's search comes up thin, search that area again with different wording before giving up on it.
- Only include an episode when your search results show its real, specific published title. Never invent titles or placeholders, and never list a whole show or limited series as if it were one episode.
- Prefer episodes that were widely discussed, critically praised, deeply reported, exceptionally useful, unusually moving, or genuinely fun to listen to.
- Order the final list by overall merit, not by area: put the episodes you are most confident are worth a listener's time — the most popular, most widely discussed, or most acclaimed — at the top, down to the weakest at the end. Do not group the list by area.

When you are done, reply with ONLY a JSON code block (no prose before or after) in exactly this shape:

{
  "name": "Picks · <today's date>",
  "picks": [
    {"pick": {"podcastTitle": "...", "episodeTitle": "...", "author": "... or null", "reason": "one sentence on why it's worth listening", "publishedApprox": "YYYY-MM-DD or null"}}
  ]
}

Use each episode's exact published title (not a paraphrase), and set publishedApprox to its release date whenever you can determine it.
```

## Format notes

The JSON is the app's `PicksImportFile` shape (`data/PicksImporter.kt`). Unknown
fields are ignored, `author`/`publishedApprox` may be null, and podcast metadata
(feed URLs, artwork) is resolved by the app at import time — the chatbot only
needs titles, reasons, and dates. `publishedApprox` matters: the matcher uses it
to pick between similarly-titled episodes, and shows whose feeds use undated
boilerplate titles ("...full episode, 7/3/26") match mostly on date + description.
