#!/usr/bin/env bash
# c7-gc.sh — experiment for hole C7: Full GC pauses under generation load.
#
# Why: with 22 bots (2 flying into new land) on 4 CPUs and a 4 GB heap, G1
# falls back to Full GCs of ~1-1.2 s: background marking starts too late for
# the allocation bursts of chunk generation (live data after a Full GC is only
# 1.5-2.1 GB, so it is not a leak). This compares collectors and settings on
# exactly that load, with default Folia thread settings.
# Configs: G1 default, G1 with Aikar's flags, generational ZGC, Shenandoah.
# Label: exp-c7-gc (config dirs <gc>-4G).
#
# Usage: c7-gc.sh <server.jar> [runs]
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
jar="$1"; runs="${2:-5}"
HEAPS=4G GCS="g1 g1aikar zgc shenandoah" BOTS=22 FLYERS=2 THREADS=4 MEASURE_S=300 \
    bash "$here/../scenarios/gc.sh" "$jar" "exp-c7-gc" "$runs"
