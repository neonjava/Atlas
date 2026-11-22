#!/usr/bin/env bash
# fly.sh — scenario A2: is the view full while a player flies through new land?
#
# Why: target A is "render distance stays smooth". A bot climbs to y=300
# (above all terrain), then flies in a straight line at FLY_SPEED blocks/s
# for FLY_SECONDS over never-generated land, logging each second the share of
# its view that is present (view_permille) and any server corrections.
# Done: each view distance x server thread count, RUNS runs.
# Not done: elytra physics (bots fly in creative mode at a fixed speed).
#
# Usage: fly.sh <server.jar> <label> [runs]
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$here/../lib/guard.sh"
source "$here/../lib/server.sh"
jar="$1"; label="$2"; runs="${3:-5}"
VIEW_DISTANCES=(${VIEW_DISTANCES:-8 12 16})
THREADS=(${THREADS:-2 4 8})
HEAP="${HEAP:-4G}"
JVM_FLAGS="${JVM_FLAGS:-}"
# Elytra flight with rockets is roughly 30 blocks/s.
FLY_SPEED="${FLY_SPEED:-30}"
FLY_SECONDS="${FLY_SECONDS:-150}"
atlas_build_bot
template="$(atlas_template "$jar")"
for vd in "${VIEW_DISTANCES[@]}"; do
    for t in "${THREADS[@]}"; do
        cfg="vd$vd-t$t"; out="$ATLAS_BENCH_DIR/results/fly/$label/$cfg"; mkdir -p "$out"
        for run in $(seq 1 "$runs"); do
            dest="$out/run$run.tar.gz"; [[ -f "$dest" ]] && continue
            atlas_guard_disk
            echo "[$(date +%H:%M:%S)] fly $label $cfg run $run"
            dir="$(atlas_new_run "$template" "fly-$label-$cfg-$run" "$vd")"
            # shellcheck disable=SC2086  # JVM_FLAGS is a list of flags on purpose
            atlas_start "$dir" "$t" "$HEAP" ${JVM_FLAGS:-}
            atlas_cmd "$dir" "atlasprobe start $dir/probe.csv"
            atlas_bots "$dir" "$dir/bot-fly.csv" --count 1 --prefix flyer --view "$vd" --mode fly \
                --speed "$FLY_SPEED" --duration-s "$FLY_SECONDS"
            atlas_cmd "$dir" "atlasprobe stop"
            atlas_stop "$dir"
            atlas_package_run "$dir" "$dest" "$label/$cfg/run$run"
            atlas_prune_run "$dir"
        done
    done
done
