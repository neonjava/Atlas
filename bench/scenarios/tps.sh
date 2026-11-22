#!/usr/bin/env bash
# tps.sh — scenario B1: TPS and MSPT over a long run with spread-out players.
#
# Why: targets B and C need to know how steady ticks are under normal load.
# Bots wander on a 512-block grid (separate Folia regions), at view distance
# 10, for MEASURE_S seconds after a WARMUP_S warm-up.
# Done: 10 / 50 / 100 bots (override with COUNTS), RUNS runs each.
# Not done: player actions beyond moving.
#
# Usage: tps.sh <server.jar> <label> [runs]
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$here/../lib/guard.sh"; source "$here/../lib/server.sh"; source "$here/../lib/load.sh"
jar="$1"; label="$2"; runs="${3:-5}"
COUNTS=(${COUNTS:-10 50 100})
export LAYOUT=spread THREADS="${THREADS:-8}" HEAP="${HEAP:-4G}" VIEW="${VIEW:-10}"
export WARMUP_S="${WARMUP_S:-120}" MEASURE_S="${MEASURE_S:-1800}"
atlas_build_bot
for n in "${COUNTS[@]}"; do
    out="$ATLAS_BENCH_DIR/results/tps/$label/bots$n"; mkdir -p "$out"
    for run in $(seq 1 "$runs"); do
        dest="$out/run$run.tar.gz"; [[ -f "$dest" ]] && continue
        echo "[$(date +%H:%M:%S)] tps $label bots$n run $run"
        BOTS="$n" atlas_load_run "$jar" "$dest" "$label/bots$n/run$run"
    done
done
