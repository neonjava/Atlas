#!/usr/bin/env bash
# crowd.sh — scenario C: one crowded region, then the same players spread out.
#
# Why: target C is "one region with many players keeps its TPS". In Folia one
# region is ticked by one thread, so a crowd cannot use more cores; the
# spread phase shows the same load split over many regions.
# Phase 1: all bots wander within 16 blocks of one point (probe watches it).
# Phase 2: the same bots move to the 512-block grid.
# Done: 50 and 100 bots (override with COUNTS), RUNS runs each.
# Not done: entity crowds (mob farms); bots are players only.
#
# Usage: crowd.sh <server.jar> <label> [runs]
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$here/../lib/guard.sh"; source "$here/../lib/server.sh"; source "$here/../lib/load.sh"
jar="$1"; label="$2"; runs="${3:-5}"
COUNTS=(${COUNTS:-50 100})
export LAYOUT=crowd-spread THREADS="${THREADS:-8}" HEAP="${HEAP:-4G}" VIEW="${VIEW:-10}"
export WARMUP_S="${WARMUP_S:-60}" MEASURE_S="${MEASURE_S:-300}"
atlas_build_bot
for n in "${COUNTS[@]}"; do
    out="$ATLAS_BENCH_DIR/results/crowd/$label/bots$n"; mkdir -p "$out"
    for run in $(seq 1 "$runs"); do
        dest="$out/run$run.tar.gz"; [[ -f "$dest" ]] && continue
        echo "[$(date +%H:%M:%S)] crowd $label bots$n run $run"
        BOTS="$n" atlas_load_run "$jar" "$dest" "$label/bots$n/run$run"
    done
done
