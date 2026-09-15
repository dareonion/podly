#!/usr/bin/env bash
# Builds last week's digest with Claude and Codex and publishes it: the issue
# under site/weekly/, and the newest week as site/radio/weekly.json for radio.
#
# Generates into a staging copy, not the publish worktree. A run takes the better
# part of an hour, and the worktree is shared with the daily pool refresh, which
# resets it. So the worktree is touched only for the short commit at the end,
# while holding the refresh and notable locks.
set -uo pipefail

repo="$HOME/gitrepo/podcast_player"
worktree="${PODLY_RADIO_WORKTREE:-$HOME/.cache/podly-radio-publish}"
data="$repo/tools/radio/data"
stage="$data/weekly-stage"
log="$data/weekly.log"
mkdir -p "$data"
echo "=== $(date -Is) weekly" >>"$log"

exec 9>"$data/.weekly.lock"
if ! flock -n 9; then
    echo "=== another weekly run is in flight; skipping" >>"$log"
    exit 0
fi

if [ ! -e "$worktree/.git" ]; then
    git -C "$repo" worktree add --detach "$worktree" origin/main >>"$log" 2>&1 || exit 1
fi
git -C "$worktree" fetch --quiet origin main >>"$log" 2>&1 || exit 1

# Start from what is published: the index to extend, and the previous issue,
# whose episodes this week must not repeat.
rm -rf "$stage" && mkdir -p "$stage"
if git -C "$worktree" cat-file -e origin/main:site/weekly 2>/dev/null; then
    git -C "$worktree" archive origin/main site/weekly | tar -x -C "$stage" --strip-components=1 \
        || exit 1
fi

cd "$repo/tools/radio" || exit 1
if ! uv run podly-radio weekly --site "$stage" "$@" >>"$log" 2>&1; then
    echo "=== digest failed; the published one stands" >>"$log"
    exit 1
fi

# Wait out a pool refresh or notable hunt that is mid-write in the worktree.
exec 8>"$data/.refresh.lock"
exec 7>"$data/.notable.lock"
if ! flock -w 3600 8 || ! flock -w 3600 7; then
    echo "=== the worktree stayed busy for an hour; not publishing" >>"$log"
    exit 1
fi

git -C "$worktree" fetch --quiet origin main >>"$log" 2>&1 || exit 1
git -C "$worktree" reset --hard --quiet origin/main >>"$log" 2>&1 || exit 1
mkdir -p "$worktree/site/weekly" "$worktree/site/radio"
cp "$stage"/weekly/*.json "$worktree/site/weekly/" || exit 1
if [ -f "$stage/radio/weekly.json" ]; then
    cp "$stage/radio/weekly.json" "$worktree/site/radio/weekly.json" || exit 1
fi

cd "$worktree" || exit 1
paths=(site/weekly site/radio/weekly.json)
if [ -z "$(git status --porcelain -- "${paths[@]}")" ]; then
    echo "=== nothing new; not publishing" >>"$log"
    exit 0
fi
# The issue just written, which is not always last week: extra arguments such as
# --week pass straight through to podly-radio.
week=$(basename "$(ls -t "$stage"/weekly/*-W*.json | head -1)" .json)
git add -- "${paths[@]}"
git commit -q -m "Publish weekly digest $week" >>"$log" 2>&1 || exit 1

# origin/main moves on its own (the pool timer commits to it), so one rebase is
# worth trying before giving up. An unpushed commit is pushed by the next refresh.
if git push -q origin HEAD:main >>"$log" 2>&1 \
    || { git fetch -q origin main && git rebase -q origin/main && git push -q origin HEAD:main; } >>"$log" 2>&1; then
    echo "=== published $week" >>"$log"
else
    echo "=== push failed; the commit waits in the worktree for the next refresh" >>"$log"
    exit 1
fi
