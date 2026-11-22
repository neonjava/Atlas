#!/usr/bin/env bash
# r1-native-noise.sh — end-to-end experiment for hole R1 (Rust Perlin kernel).
#
# Why: the microbenchmark shows the kernel itself is up to 2.2x faster, but
# it is only ~5% of generation CPU. This measures what a player sees.
# A/B on the SAME jar: "java" runs with -Datlas.native=false, "native" runs
# with the Rust library; nothing else differs. Runs alternate (java 1,
# native 1, java 2, ...) so slow drift (heat, background load) hits both.
# Configs: view distance 12 and 16 at 4 CPUs (the small-CPU target; 4 CPUs
# rather than 2 so the result is not hidden by the worker-thread limit alone).
# Labels: exp-r1-java, exp-r1-native.
# Done: both arms, RUNS runs each; check that each native run loaded the library.
# Not done: deciding (see HOLES.md and the PR).
#
# Usage: r1-native-noise.sh <atlas.jar> <libatlas_native.so> [runs]
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
jar="$1"; lib="$2"; runs="${3:-5}"
for run in $(seq 1 "$runs"); do
    JVM_FLAGS="-Datlas.native=false" VIEW_DISTANCES="12 16" THREADS="4" \
        bash "$here/../scenarios/chunks.sh" "$jar" exp-r1-java "$run"
    JVM_FLAGS="-Datlas.native.path=$lib" VIEW_DISTANCES="12 16" THREADS="4" \
        bash "$here/../scenarios/chunks.sh" "$jar" exp-r1-native "$run"
done
results="$here/../results/chunks"
for archive in "$results"/exp-r1-native/*/run*.tar.gz; do
    tar -xzOf "$archive" server.log | grep -q "Atlas native library loaded" \
        || { echo "native library NOT loaded in $archive" >&2; exit 1; }
done
for archive in "$results"/exp-r1-java/*/run*.tar.gz; do
    tar -xzOf "$archive" server.log | grep -q "Atlas native code disabled" \
        || { echo "java arm did not disable native code in $archive" >&2; exit 1; }
done
echo "r1: all runs used the intended code path"
