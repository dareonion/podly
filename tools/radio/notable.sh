#!/usr/bin/env bash
# Hunts for notable episodes and publishes the result, same discipline as
# refresh.sh: build into the publish worktree, commit only what changed, push.
set -uo pipefail

repo="$HOME/gitrepo/podcast_player"
worktree="${PODLY_RADIO_WORKTREE:-$HOME/.cache/podly-radio-publish}"
log="$repo/tools/radio/data/notable.log"
mkdir -p "$(dirname "$log")"
echo "=== $(date -Is) notable" >>"$log"

exec 9>"$repo/tools/radio/data/.notable.lock"
if ! flock -n 9; then
    echo "=== another notable run is in flight; skipping" >>"$log"
    exit 0
fi

if [ ! -e "$worktree/.git" ]; then
    git -C "$repo" worktree add --detach "$worktree" origin/main >>"$log" 2>&1 || exit 1
fi
git -C "$worktree" fetch --quiet origin main >>"$log" 2>&1 || exit 1
git -C "$worktree" reset --hard --quiet origin/main >>"$log" 2>&1 || exit 1

cd "$repo/tools/radio" || exit 1
if ! uv run podly-radio notable --out "$worktree/site/radio" >>"$log" 2>&1; then
    echo "=== hunt failed; the published notable list stands" >>"$log"
    exit 1
fi

cd "$worktree" || exit 1
if git diff --quiet -- site/radio; then
    echo "=== nothing new; not publishing" >>"$log"
    exit 0
fi
git add -- site/radio
git commit -q -m "Refresh notable episodes $(date +%Y-%m-%d)" >>"$log" 2>&1 || exit 1
git push -q origin HEAD:main >>"$log" 2>&1 && echo "=== published" >>"$log"
