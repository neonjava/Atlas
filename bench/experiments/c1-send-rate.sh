#!/usr/bin/env bash
# c1-send-rate.sh — experiment for hole C1: the per-player chunk send limit.
#
# Why: Paper limits chunk sending to 75 chunks per second per player
# (chunk-loading-basic.player-max-chunk-send-rate). With 3 chunk workers on
# 4 CPUs (hole C5) a view-distance-16 view fills at ~69 chunks/s, right at
# that limit. This raises the limit to see whether it is now what players wait
# for. Same worker setting as the C5 follow-up (3 workers at nice 10) so only
# the send limit differs from exp-c5-t4w3-nice10.
# Configs: 4 CPUs, view distance 12 and 16, send rate 150 chunks/s.
# Label: exp-c1-t4w3-nice10-send150.
#
# Usage: c1-send-rate.sh <server.jar> [runs]
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
jar="$1"; runs="${2:-5}"
export ATLAS_WORKER_NICE=10
ATLAS_SERVER_SET="config/paper-global.yml:chunk-system.worker-threads=3 config/paper-global.yml:chunk-loading-basic.player-max-chunk-send-rate=150.0" \
    VIEW_DISTANCES="12 16" THREADS=4 \
    bash "$here/../scenarios/chunks.sh" "$jar" "exp-c1-t4w3-nice10-send150" "$runs"
