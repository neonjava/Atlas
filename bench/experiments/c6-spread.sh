#!/usr/bin/env bash
# c6-spread.sh — hole C6 again, with players that really are in separate regions.
#
# Why: the first tick-thread runs used a 512-block grid, but Folia merges
# regions whose 16x16-chunk sections are within 2 sections of each other, so
# all 50 bots ended up in ONE region. That run still showed the global region
# waiting behind it with 1 tick thread. This repeats the comparison with the
# grid at 1536 blocks (set in bench/lib/load.sh), so regions stay separate and
# more tick threads can run them in parallel.
# Configs: 46 wandering + 4 flying bots, 8 CPUs, G1 4 GB; default (1 tick
# thread) vs threaded-regions.threads=3.
# Labels: exp-c6b-ticks-default, exp-c6b-ticks-3.
#
# Usage: c6-spread.sh <server.jar> [runs]
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
jar="$1"; runs="${2:-5}"
TICKS_KEY="config/paper-global.yml:threaded-regions.threads"
for variant in default 3; do
    set_arg=""
    [[ "$variant" != default ]] && set_arg="$TICKS_KEY=$variant"
    ATLAS_SERVER_SET="$set_arg" HEAPS=4G GCS=g1 BOTS=50 FLYERS=4 THREADS=8 MEASURE_S=300 \
        bash "$here/../scenarios/gc.sh" "$jar" "exp-c6b-ticks-$variant" "$runs"
done
