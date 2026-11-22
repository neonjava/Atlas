#!/usr/bin/env bash
# build-jar.sh — builds a server jar (and the Rust library) for one git ref.
#
# Why: experiments compare jars built from exact commits. The build runs in a
# git worktree under ~/atlas-build (the repo disk is exFAT; see
# docs/MACHINE.md) and is queued like a benchmark so it never overlaps one.
# Output: ~/atlas-build/jars/<name>.jar and, when native/atlas-native exists
# at that ref, ~/atlas-build/jars/<name>-libatlas_native.so.
# Done: worktree update, patch apply, paperclip jar, cargo release build.
# Not done: other OSes (CI builds those).
#
# Usage: build-jar.sh <git-ref> <name>
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$here/lib/guard.sh"
ref="$1"; name="$2"
repo="$(cd "$here/.." && pwd)"
work="$ATLAS_BUILD_DIR/work"
jars="$ATLAS_BUILD_DIR/jars"
atlas_guard_disk
mkdir -p "$jars"
if [[ ! -d "$work" ]]; then
    git -C "$repo" worktree add --detach "$work" "$ref"
else
    git -C "$work" checkout --detach --force "$ref"
fi
cd "$work"
systemd-run --user --scope --quiet -p MemoryMax="$ATLAS_TOTAL_MEM_CAP" -p CPUQuota="$((ATLAS_MAX_THREADS * 100))%" \
    ./gradlew applyAllPatches createPaperclipJar --max-workers="$ATLAS_MAX_THREADS" --no-daemon -q
cp folia-server/build/libs/folia-paperclip-*.jar "$jars/$name.jar"
if [[ -d native/atlas-native ]]; then
    (cd native/atlas-native && CARGO_TARGET_DIR="$ATLAS_BUILD_DIR/cargo-target" cargo build --release --quiet)
    cp "$ATLAS_BUILD_DIR/cargo-target/release/libatlas_native.so" "$jars/$name-libatlas_native.so"
fi
echo "built $jars/$name.jar from $(git rev-parse --short HEAD)"
