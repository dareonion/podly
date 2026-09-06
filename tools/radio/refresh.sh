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

# Detached, not on `main`: git refuses to check out a branch that is already
# checked out in the working repo, and this must never contend for it anyway.
if [ ! -e "$worktree/.git" ]; then
    echo "=== creating publish worktree at $worktree" >>"$log"
    git -C "$repo" worktree add --detach "$worktree" origin/main >>"$log" 2>&1 || exit 1
fi

git -C "$worktree" fetch --quiet origin main >>"$log" 2>&1 || exit 1
ahead=$(git -C "$worktree" rev-list --count origin/main..HEAD 2>/dev/null || echo 0)
if [ "$ahead" -gt 0 ]; then
    # A previous run committed but could not push; send it up before rebuilding
    # so the reset below cannot discard it.
    echo "=== $ahead unpushed commit(s) from an earlier run" >>"$log"
    git -C "$worktree" push -q origin HEAD:main >>"$log" 2>&1 \
        || { echo "=== push still failing; leaving the commit in place" >>"$log"; exit 1; }
    git -C "$worktree" fetch --quiet origin main >>"$log" 2>&1
fi
git -C "$worktree" reset --hard --quiet origin/main >>"$log" 2>&1 || exit 1

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
if git push -q origin HEAD:main >>"$log" 2>&1; then
    echo "=== published" >>"$log"
else
    echo "=== push failed; the commit waits in the worktree for the next run" >>"$log"
    exit 1
fi
