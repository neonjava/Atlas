#!/usr/bin/env bash
# chunks.sh — scenario A: how fast new chunks reach a player.
#
# Why: target A of Atlas is faster new-chunk loading, also on small CPUs.
# What one run does, on a fresh world with a fixed seed:
#   1. one bot joins at spawn and waits until its view is full (warm-up);
#   2. it is teleported TELEPORTS times to far, never-generated places; the
#      bot logs when 50%, 90% and 100% of the chunks a vanilla client would
#      render have arrived (fill50/fill90/fill100);
#   3. a second bot flies in a straight line through new terrain at
#      FLY_SPEED blocks/s for FLY_SECONDS; it logs each second which share of
#      its view is present (view_permille).
# Done: all of the above, for each view distance x server thread count.
# Not done: elytra physics (bots fly in creative mode at a fixed speed).
#
# Usage: chunks.sh <server.jar> <label> [runs] ; results go to
#        bench/results/chunks/<label>/vd<V>-t<T>/run<N>.tar.gz
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$here/../lib/guard.sh"
source "$here/../lib/server.sh"

jar="$1"
label="$2"
runs="${3:-5}"
VIEW_DISTANCES=(${VIEW_DISTANCES:-8 12 16})
THREADS=(${THREADS:-2 4 8})
HEAP="${HEAP:-4G}"
# The first teleport is a warm-up (JIT, caches); analysis uses the rest.
TELEPORTS="${TELEPORTS:-6}"
# Teleport targets are this far apart (blocks) on the +X axis, far beyond any
# view distance, so each target is fresh terrain. Same targets every run.
TELEPORT_SPACING="${TELEPORT_SPACING:-8000}"
TELEPORT_Y=200
FILL_TIMEOUT_S="${FILL_TIMEOUT_S:-240}"
FLY_SPEED="${FLY_SPEED:-30}"
FLY_SECONDS="${FLY_SECONDS:-90}"
# The bot process outlives the scripted steps; it is stopped explicitly.
BOT_MAX_SECONDS=36000

# wait_event <csv> <event> <after-line> <timeout-s>: waits for a bot event
# that appears after line number <after-line>.
wait_event() {
    local csv="$1" event="$2" after="$3" timeout="$4" waited=0
    while (( waited < timeout )); do
        if tail -n +"$((after + 1))" "$csv" 2>/dev/null | grep -q ",$event,"; then
            return 0
        fi
        sleep 1
        waited=$((waited + 1))
    done
    return 1
}

atlas_build_bot
template="$(atlas_template "$jar")"
out_root="$ATLAS_BENCH_DIR/results/chunks/$label"
mkdir -p "$out_root"

for vd in "${VIEW_DISTANCES[@]}"; do
    for t in "${THREADS[@]}"; do
        cfg="vd$vd-t$t"
        mkdir -p "$out_root/$cfg"
        for run in $(seq 1 "$runs"); do
            dest="$out_root/$cfg/run$run.tar.gz"
            [[ -f "$dest" ]] && continue
            atlas_guard_disk
            echo "[$(date +%H:%M:%S)] chunks $label $cfg run $run"
            dir="$(atlas_new_run "$template" "chunks-$label-$cfg-$run" "$vd")"
            atlas_start "$dir" "$t" "$HEAP"
            atlas_cmd "$dir" "atlasprobe start $dir/probe.csv"
            atlas_bots_start "$dir" "$dir/bot.csv" --count 1 --prefix walker --view "$vd" --mode idle --duration-s "$BOT_MAX_SECONDS"
            wait_event "$dir/bot.csv" fill100 0 "$FILL_TIMEOUT_S" || echo "  warm-up fill timed out"
            for i in $(seq 1 "$TELEPORTS"); do
                mark="$(wc -l < "$dir/bot.csv")"
                atlas_cmd "$dir" "tp walker0 $((i * TELEPORT_SPACING)) $TELEPORT_Y 0"
                wait_event "$dir/bot.csv" fill100 "$mark" "$FILL_TIMEOUT_S" || echo "  teleport $i fill timed out"
            done
            atlas_bots_stop
            atlas_bots "$dir" "$dir/bot-fly.csv" --count 1 --prefix flyer --view "$vd" --mode fly --speed "$FLY_SPEED" --duration-s "$FLY_SECONDS"
            atlas_cmd "$dir" "atlasprobe stop"
            atlas_stop "$dir"
            atlas_package_run "$dir" "$dest" "$label/$cfg/run$run"
            atlas_prune_run "$dir"
        done
    done
done
