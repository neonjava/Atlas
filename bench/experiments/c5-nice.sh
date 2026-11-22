#!/usr/bin/env bash
# c5-nice.sh — follow-up for hole C5: extra chunk workers at low OS priority.
#
# Why: c5-workers.sh showed that more chunk workers fill a new view up to
# 2.2x faster on 4 CPUs, but global tick p99 rose from ~52 ms to ~100 ms
# because workers and the tick thread compete for CPUs. Giving the worker
# threads a higher nice value (lower priority) should let ticks win and let
# generation use only otherwise idle CPU. This tests that before any code is
# written, by renicing the worker threads from the harness.
# Configs: 2 CPUs / 2 workers and 4 CPUs / 3 workers, workers at nice 10;
#   plus the 22-bot load check on 4 CPUs with 3 workers at nice 10.
# Labels: exp-c5-t2w2-nice10, exp-c5-t4w3-nice10, exp-c5-load-w3-nice10.
#
# Usage: c5-nice.sh <server.jar> [runs]
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
jar="$1"; runs="${2:-5}"
WORKERS_KEY="config/paper-global.yml:chunk-system.worker-threads"
export ATLAS_WORKER_NICE=10
for pair in 2:2 4:3; do
    cpus="${pair%%:*}"; workers="${pair##*:}"
    ATLAS_SERVER_SET="$WORKERS_KEY=$workers" VIEW_DISTANCES="12 16" THREADS="$cpus" \
        bash "$here/../scenarios/chunks.sh" "$jar" "exp-c5-t${cpus}w${workers}-nice10" "$runs"
done
ATLAS_SERVER_SET="$WORKERS_KEY=3" HEAPS=4G GCS=g1 BOTS=22 FLYERS=2 THREADS=4 MEASURE_S=300 \
    bash "$here/../scenarios/gc.sh" "$jar" "exp-c5-load-w3-nice10" "$runs"
