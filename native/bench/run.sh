#!/usr/bin/env bash
# run.sh — builds the Rust library with the JNI benchmark entry and runs
# NoiseKernelBench against the server classes of a benchmark template.
#
# Why: one command reproduces the kernel parity check and timing table.
# Usage: run.sh <server-template-dir> <out.csv> [cpu list for taskset]
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
template="$1"; out="$2"; cpus="${3:-0}"
target="${CARGO_TARGET_DIR:-$HOME/atlas-build/cargo-target-bench}"
(cd "$here/../atlas-native" && CARGO_TARGET_DIR="$target" cargo build --release --features jni-bench --quiet)
classpath="$(find "$template/versions" "$template/libraries" -name '*.jar' | paste -sd:)"
taskset -c "$cpus" java -Xmx2g --enable-native-access=ALL-UNNAMED -cp "$classpath" \
    "$here/NoiseKernelBench.java" "$target/release/libatlas_native.so" | tee "$out"
