#!/usr/bin/env bash
# idle.sh — scenario E: what the server costs with nobody online, and with a
# few still players (network and packet work per player).
#
# Why: idle CPU use matters on small machines, and per-player network bytes
# show how much each player costs before they do anything.
# Configs: 0 bots, and 10 idle bots standing still at spawn.
# Done: RUNS runs each of MEASURE_S seconds.
# Not done: packet-level profiling (see the profiling runs instead).
#
# Usage: idle.sh <server.jar> <label> [runs]
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$here/../lib/guard.sh"; source "$here/../lib/server.sh"; source "$here/../lib/load.sh"
jar="$1"; label="$2"; runs="${3:-5}"
COUNTS=(${COUNTS:-0 10})
export LAYOUT=none THREADS="${THREADS:-8}" HEAP="${HEAP:-4G}" VIEW="${VIEW:-10}"
export WARMUP_S="${WARMUP_S:-60}" MEASURE_S="${MEASURE_S:-300}" BOT_MODE=idle
atlas_build_bot
for n in "${COUNTS[@]}"; do
    out="$ATLAS_BENCH_DIR/results/idle/$label/bots$n"; mkdir -p "$out"
    for run in $(seq 1 "$runs"); do
        dest="$out/run$run.tar.gz"; [[ -f "$dest" ]] && continue
        echo "[$(date +%H:%M:%S)] idle $label bots$n run $run"
        BOTS="$n" atlas_load_run "$jar" "$dest" "$label/bots$n/run$run"
    done
done
