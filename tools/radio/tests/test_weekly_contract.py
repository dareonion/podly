"""The generator half of the weekly contract; WeeklyContractTest.kt is the app half."""

from __future__ import annotations

import datetime
import json
from pathlib import Path

from podly_radio.ids import episode_id, podcast_id
from podly_radio.weekly import Week

RESOURCES = Path(__file__).resolve().parents[3] / "app/src/test/resources"


def test_the_committed_issue_is_shaped_as_the_app_expects() -> None:
    issue = json.loads((RESOURCES / "weekly-issue.json").read_text(encoding="utf-8"))
    week = Week(datetime.date.fromisoformat(issue["week"]["start"]))
    assert issue["profileId"] == week.pool_id
    assert issue["version"] == 1
    for entry in issue["entries"]:
        for key in ("id", "rank", "score", "podcast", "episode", "language", "why", "tags"):
            assert key in entry, key
        for key in ("pickedBy", "singledOut"):
            assert key in entry, key
        assert entry["episode"]["id"] == episode_id(
            entry["episode"].get("guid"), entry["episode"]["audioUrl"]
        )
        assert entry["podcast"]["id"] == podcast_id(entry["podcast"]["feedUrl"])
        assert entry["id"] == entry["episode"]["id"]


def test_the_committed_index_names_its_files() -> None:
    index = json.loads((RESOURCES / "weekly-index.json").read_text(encoding="utf-8"))
    for issue in index["issues"]:
        assert issue["file"] == f"{issue['id']}.json"
        for key in ("label", "weekStart", "weekEnd", "generatedAtMs", "counts", "pickedBy"):
            assert key in issue, key
