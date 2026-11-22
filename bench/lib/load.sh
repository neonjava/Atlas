#!/usr/bin/env bash
# load.sh — one "players doing things" benchmark run, shared by the TPS, GC,
# crowd and idle scenarios.
#
# Why: those scenarios differ only in how many bots, where they stand, which
# heap/GC the server uses and how long they measure. One runner keeps the
# measurement identical across them.
# A run: fresh world -> server -> probe -> bots join -> bots are placed
# (LAYOUT) -> warm-up -> measured window (marks measure_start/measure_end,
# with CPU time) -> optional second phase (crowd -> spread) -> stop -> archive.
# Done: layouts none / spread / crowd / crowd-spread; flyers for chunk and
# allocation pressure; per-run archive.
# Not done: real player behaviour (mining, building, combat).
#
# Settings (environment): BOTS, FLYERS, LAYOUT, VIEW, THREADS, HEAP,
# GC_FLAGS, WARMUP_S, MEASURE_S, BOT_MODE (wander|idle), WANDER_SPEED,
# WANDER_RADIUS.
#
# Usage: source guard.sh server.sh load.sh; atlas_load_run <jar> <dest.tar.gz> <label>

# Bots stand above most terrain but close enough to the ground for mobs to
# spawn around them (mobs spawn 24-128 blocks from a player).
ATLAS_BOT_Y=120
# Spread layout: bots on a grid this many blocks apart, GRID_WIDTH per row.
# 512 blocks (32 chunks) keeps neighbours in separate Folia regions at view
# distance 10.
ATLAS_SPREAD_SPACING=512
ATLAS_SPREAD_GRID_WIDTH=10
# Crowd layout: everyone around one point away from spawn.
ATLAS_CROWD_X=2048
ATLAS_CROWD_Z=2048
ATLAS_CROWD_RADIUS=16
# Joins are staggered like real logins; 100 bots take 20 s.
ATLAS_JOIN_INTERVAL_MS=200
ATLAS_JOIN_TIMEOUT_S=300
# Folia moves players between regions asynchronously; give teleports time.
ATLAS_PLACE_SETTLE_S=20

# _atlas_place <run-dir> <layout> <first-index> <count> <prefix>
_atlas_place() {
    local dir="$1" layout="$2" first="$3" count="$4" prefix="$5" i x z
    for (( i = first; i < count; i++ )); do
        case "$layout" in
            spread)
                x=$(( (i % ATLAS_SPREAD_GRID_WIDTH) * ATLAS_SPREAD_SPACING ))
                z=$(( (i / ATLAS_SPREAD_GRID_WIDTH) * ATLAS_SPREAD_SPACING ))
                ;;
            crowd)
                x=$ATLAS_CROWD_X
                z=$ATLAS_CROWD_Z
                ;;
            *) return 0 ;;
        esac
        atlas_cmd "$dir" "tp $prefix$i $x $ATLAS_BOT_Y $z"
    done
    sleep "$ATLAS_PLACE_SETTLE_S"
}

atlas_load_run() {
    local jar="$1" dest="$2" label="$3" template dir layout phase1 phase2
    local bots="${BOTS:-0}" flyers="${FLYERS:-0}" vd="${VIEW:-10}" threads="${THREADS:-8}" heap="${HEAP:-4G}"
    layout="${LAYOUT:-spread}"
    atlas_guard_disk || return 1
    template="$(atlas_template "$jar")" || return 1
    dir="$(atlas_new_run "$template" "load-$(basename "$dest" .tar.gz)-$$" "$vd")" || return 1
    # shellcheck disable=SC2086  # GC_FLAGS is a list of flags on purpose
    atlas_start "$dir" "$threads" "$heap" ${GC_FLAGS:-} || return 1
    atlas_mark "$dir" server_ready
    atlas_cmd "$dir" "atlasprobe start $dir/probe.csv"
    if [[ "$layout" == crowd* ]]; then
        atlas_cmd "$dir" "atlasprobe watch $ATLAS_CROWD_X $ATLAS_CROWD_Z"
    fi
    if (( bots > 0 )); then
        atlas_bots_start "$dir" "$dir/bot.csv" --count "$bots" --flyers "$flyers" --prefix b --view "$vd" \
            --mode "${BOT_MODE:-wander}" --speed "${WANDER_SPEED:-5}" \
            --wander-radius "$([[ "$layout" == crowd* ]] && echo "$ATLAS_CROWD_RADIUS" || echo "${WANDER_RADIUS:-48}")" \
            --join-interval-ms "$ATLAS_JOIN_INTERVAL_MS" --duration-s 36000
        atlas_wait_bots "$dir/bot.csv" "$bots" "$ATLAS_JOIN_TIMEOUT_S" || true
        atlas_mark "$dir" bots_joined
    fi
    case "$layout" in
        crowd-spread) phase1=crowd; phase2=spread ;;
        *) phase1="$layout"; phase2="" ;;
    esac
    _atlas_place "$dir" "$phase1" "$flyers" "$bots" b
    sleep "${WARMUP_S:-60}"
    atlas_mark "$dir" measure_start
    sleep "${MEASURE_S:-300}"
    atlas_mark "$dir" measure_end
    if [[ -n "$phase2" ]]; then
        _atlas_place "$dir" "$phase2" "$flyers" "$bots" b
        sleep "${WARMUP_S:-60}"
        atlas_mark "$dir" measure2_start
        sleep "${MEASURE_S:-300}"
        atlas_mark "$dir" measure2_end
    fi
    (( bots > 0 )) && atlas_bots_stop
    atlas_cmd "$dir" "atlasprobe stop"
    atlas_stop "$dir"
    atlas_package_run "$dir" "$dest" "$label"
    atlas_prune_run "$dir"
}
