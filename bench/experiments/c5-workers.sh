#!/usr/bin/env bash
# c5-workers.sh — experiment for hole C5: more chunk worker threads on small CPUs.
#
# Why: by default Folia runs one chunk worker on machines with up to 7 CPUs;
# the baseline shows 4 CPUs generate no faster than 2. This tests setting
# chunk-system.worker-threads explicitly, and checks the cost: workers share
# CPUs with the tick thread, so ticks could get slower under load.
# Part 1 (speed): chunks.sh at view distance 12 and 16 for
#   2 CPUs: 2 workers;  4 CPUs: 3 and 4 workers;  8 CPUs: 6 workers;
#   plus a control (4 CPUs, default workers) run in the same session, so
#   drift since the baseline (heat, background load) would show up.
# Part 2 (side effect): 20 wandering bots + 2 flyers on 4 CPUs, default
#   workers vs 3 workers, tick intervals and MSPT (load.sh).
# Labels: exp-c5-control-t4, exp-c5-t<cpus>w<workers>, exp-c5-load-default,
# exp-c5-load-w3.
# Done: both parts, RUNS runs per config.
# Not done: choosing a new default (decided from these numbers in HOLES.md).
#
# Usage: c5-workers.sh <server.jar> [runs]
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
jar="$1"; runs="${2:-5}"
WORKERS_KEY="config/paper-global.yml:chunk-system.worker-threads"
VIEW_DISTANCES="12 16" THREADS=4 bash "$here/../scenarios/chunks.sh" "$jar" "exp-c5-control-t4" "$runs"
for pair in 2:2 4:3 4:4 8:6; do
    cpus="${pair%%:*}"; workers="${pair##*:}"
    ATLAS_SERVER_SET="$WORKERS_KEY=$workers" VIEW_DISTANCES="12 16" THREADS="$cpus" \
        bash "$here/../scenarios/chunks.sh" "$jar" "exp-c5-t${cpus}w${workers}" "$runs"
done
for variant in default 3; do
    set_arg=""
    [[ "$variant" != default ]] && set_arg="$WORKERS_KEY=$variant"
    ATLAS_SERVER_SET="$set_arg" HEAPS=4G GCS=g1 BOTS=22 FLYERS=2 THREADS=4 MEASURE_S=300 \
        bash "$here/../scenarios/gc.sh" "$jar" "exp-c5-load-$([[ $variant == default ]] && echo default || echo w$variant)" "$runs"
done
