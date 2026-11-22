#!/usr/bin/env bash
# gc.sh — scenario B2: garbage-collection pauses and the tick drops they cause.
#
# Why: target B is "no big TPS drops when the heap fills". Load: 46 wandering
# bots plus 4 bots flying into new terrain (chunk generation allocates a lot),
# so the heap fills and the collector must work during the measured window.
# Matrix: heaps 2G / 4G / 6G x collectors G1 / ZGC / Shenandoah (override with
# HEAPS and GCS). 6G is the largest heap that fits the memory cap with bots.
# Done: matrix runs, RUNS each.
# Not done: tuning flags per collector (that is a later experiment).
#
# Usage: gc.sh <server.jar> <label> [runs]
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$here/../lib/guard.sh"; source "$here/../lib/server.sh"; source "$here/../lib/load.sh"
jar="$1"; label="$2"; runs="${3:-5}"
HEAPS=(${HEAPS:-2G 4G 6G})
GCS=(${GCS:-g1 zgc shenandoah})
declare -A GC_FLAG=([g1]="-XX:+UseG1GC" [zgc]="-XX:+UseZGC" [shenandoah]="-XX:+UseShenandoahGC")
export LAYOUT=spread BOTS="${BOTS:-50}" FLYERS="${FLYERS:-4}" THREADS="${THREADS:-8}" VIEW="${VIEW:-10}"
export WARMUP_S="${WARMUP_S:-60}" MEASURE_S="${MEASURE_S:-480}"
# The server cap must hold the largest heap plus JVM overhead (metaspace,
# code cache, thread stacks, direct buffers for networking).
export ATLAS_SERVER_MEM_CAP="${ATLAS_SERVER_MEM_CAP:-9G}"
atlas_build_bot
for heap in "${HEAPS[@]}"; do
    for gc in "${GCS[@]}"; do
        cfg="$gc-$heap"; out="$ATLAS_BENCH_DIR/results/gc/$label/$cfg"; mkdir -p "$out"
        for run in $(seq 1 "$runs"); do
            dest="$out/run$run.tar.gz"; [[ -f "$dest" ]] && continue
            echo "[$(date +%H:%M:%S)] gc $label $cfg run $run"
            HEAP="$heap" GC_FLAGS="${GC_FLAG[$gc]}" atlas_load_run "$jar" "$dest" "$label/$cfg/run$run"
        done
    done
done
