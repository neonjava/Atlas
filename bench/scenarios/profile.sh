#!/usr/bin/env bash
# profile.sh — one profiled run per scenario, to find where time goes.
#
# Why: timing runs and profiling runs are kept apart, because the profiler
# (Java Flight Recorder) itself costs a little time. These runs only answer
# "which code is busy", never "how fast is it".
# Runs: chunks (view distance 12, 4 threads), gc load (50 bots, 4 G1 heap),
# crowd (100 bots). Each with ATLAS_JFR=1; the archive holds jfr-*.txt tables.
# Done: the three profiles.
# Not done: allocation profiling beyond JFR's sampled allocation events.
#
# Usage: profile.sh <server.jar> <label>
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
jar="$1"; label="$2"
export ATLAS_JFR=1
VIEW_DISTANCES=12 THREADS=4 bash "$here/chunks.sh" "$jar" "$label-jfr" 1
HEAPS=4G GCS=g1 MEASURE_S=300 bash "$here/gc.sh" "$jar" "$label-jfr" 1
COUNTS=100 MEASURE_S=180 bash "$here/crowd.sh" "$jar" "$label-jfr" 1
