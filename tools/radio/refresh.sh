#!/usr/bin/env bash
# Rebuilds the radio pools and publishes them by committing to main.
#
# Publishing happens from a dedicated worktree, never Darren's checkout: he
# works in this repo daily, and a 04:15 commit onto whatever branch he happens
# to have checked out is a hazard. Meant for the systemd user timer; safe to run
# by hand.
set -uo pipefail

repo="$HOME/gitrepo/podcast_player"
worktree="${PODLY_RADIO_WORKTREE:-$HOME/.cache/podly-radio-publish}"
log="$repo/tools/radio/data/refresh.log"
mkdir -p "$(dirname "$log")"

echo "=== $(date -Is) refresh" >>"$log"

# One run at a time: a hand-run must not race the timer.
exec 9>"$repo/tools/radio/data/.refresh.lock"
if ! flock -n 9; then
    echo "=== another refresh is running; skipping" >>"$log"
    exit 0
fi

if [ ! -d "$worktree/.git" ] && [ ! -f "$worktree/.git" ]; then
    echo "=== creating publish worktree at $worktree" >>"$log"
    git -C "$repo" worktree add "$worktree" main >>"$log" 2>&1 || exit 1
fi

# Fast-forward only: never publish on top of a diverged tree.
git -C "$worktree" fetch --quiet origin main >>"$log" 2>&1
git -C "$worktree" checkout --quiet main >>"$log" 2>&1
if ! git -C "$worktree" merge --ff-only origin/main >>"$log" 2>&1; then
    echo "=== publish worktree has diverged from origin/main; not publishing" >>"$log"
    exit 1
fi

cd "$repo/tools/radio" || exit 1
if ! uv run podly-radio build --out "$worktree/site/radio" >>"$log" 2>&1; then
    echo "=== every profile failed to build; leaving the published pools alone" >>"$log"
    exit 1
fi

cd "$worktree" || exit 1
if git diff --quiet -- site/radio; then
    echo "=== pools unchanged; nothing to publish" >>"$log"
    exit 0
fi

git add -- site/radio
git commit -q -m "Refresh radio pools $(date +%Y-%m-%d)" >>"$log" 2>&1 || exit 1

# Push whatever is unpushed, not just this run's commit: a refresh made while
# the network was down should still go up next time.
ahead=$(git rev-list --count '@{u}..HEAD' 2>/dev/null || echo 0)
if [ "$ahead" -gt 0 ]; then
    if git push -q >>"$log" 2>&1; then
        echo "=== pushed $ahead commit(s)" >>"$log"
    else
        echo "=== push failed; the commit is waiting locally" >>"$log"
        exit 1
    fi
fi
