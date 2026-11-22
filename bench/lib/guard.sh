#!/usr/bin/env bash
# guard.sh — resource guard for Atlas builds and benchmarks.
#
# Why: benchmarks and builds run unattended on a shared desktop. This script
# refuses to start work that would fill the disk, and exposes the shared
# limits (RAM, CPU sets) as variables so every script uses the same numbers.
# Done: disk checks for the repo disk and the build dir, CPU set helpers.
# Not done: nothing watches usage *during* a run; runs cap world/log size
# themselves (see bench/run-scenario.sh).
#
# Usage: source bench/lib/guard.sh; atlas_guard_disk || exit 1

# Repo disk must keep max(MIN_FREE_ABS_GB, MIN_FREE_PCT% of size) free.
ATLAS_MIN_FREE_ABS_GB="${ATLAS_MIN_FREE_ABS_GB:-30}"
ATLAS_MIN_FREE_PCT="${ATLAS_MIN_FREE_PCT:-15}"
# Build/cache directory outside the repo disk, and its size cap.
ATLAS_BUILD_DIR="${ATLAS_BUILD_DIR:-$HOME/atlas-build}"
ATLAS_BUILD_DIR_MAX_GB="${ATLAS_BUILD_DIR_MAX_GB:-30}"
# The build dir shares /home with the desktop user; never let it get close to
# full, because a full /home breaks the desktop session.
ATLAS_HOME_MIN_FREE_GB="${ATLAS_HOME_MIN_FREE_GB:-8}"
# Half of 23 GiB total RAM, rounded down; the server gets most of it.
ATLAS_TOTAL_MEM_CAP="${ATLAS_TOTAL_MEM_CAP:-11G}"
ATLAS_SERVER_MEM_CAP="${ATLAS_SERVER_MEM_CAP:-8G}"
ATLAS_BOT_MEM_CAP="${ATLAS_BOT_MEM_CAP:-2G}"
# nproc - 2 threads for builds.
ATLAS_MAX_THREADS="${ATLAS_MAX_THREADS:-$(( $(nproc) - 2 ))}"
# Server CPU order: one thread per performance core first, then their siblings.
ATLAS_SERVER_CPU_ORDER=(0 2 4 6 8 10 1 3 5 7 9 11)
# Bots live on efficiency cores, leaving the last two CPUs to the desktop.
ATLAS_BOT_CPUS="${ATLAS_BOT_CPUS:-12-17}"

atlas_repo_root() {
    git -C "$(dirname "${BASH_SOURCE[0]}")" rev-parse --show-toplevel
}

# Prints the comma-separated CPU list for N server threads.
atlas_server_cpus() {
    local n="$1"
    if (( n < 1 || n > ${#ATLAS_SERVER_CPU_ORDER[@]} )); then
        echo "atlas_server_cpus: thread count $n out of range" >&2
        return 1
    fi
    local IFS=,
    echo "${ATLAS_SERVER_CPU_ORDER[*]:0:n}"
}

atlas_guard_disk() {
    local root size_kb avail_kb need_kb pct_kb abs_kb used_kb max_kb
    root="$(atlas_repo_root)"
    read -r size_kb avail_kb < <(df -Pk "$root" | awk 'NR==2 {print $2, $4}')
    abs_kb=$(( ATLAS_MIN_FREE_ABS_GB * 1024 * 1024 ))
    pct_kb=$(( size_kb * ATLAS_MIN_FREE_PCT / 100 ))
    need_kb=$(( abs_kb > pct_kb ? abs_kb : pct_kb ))
    if (( avail_kb < need_kb )); then
        echo "guard: repo disk has $((avail_kb/1024/1024)) GB free, need $((need_kb/1024/1024)) GB" >&2
        return 1
    fi
    if [[ -d "$ATLAS_BUILD_DIR" ]]; then
        used_kb=$(du -sk "$ATLAS_BUILD_DIR" | awk '{print $1}')
        max_kb=$(( ATLAS_BUILD_DIR_MAX_GB * 1024 * 1024 ))
        if (( used_kb > max_kb )); then
            echo "guard: $ATLAS_BUILD_DIR uses $((used_kb/1024/1024)) GB, cap is $ATLAS_BUILD_DIR_MAX_GB GB" >&2
            return 1
        fi
    fi
    avail_kb=$(df -Pk "$ATLAS_BUILD_DIR" 2>/dev/null | awk 'NR==2 {print $4}')
    if [[ -n "$avail_kb" ]] && (( avail_kb < ATLAS_HOME_MIN_FREE_GB * 1024 * 1024 )); then
        echo "guard: build disk has $((avail_kb/1024/1024)) GB free, need $ATLAS_HOME_MIN_FREE_GB GB" >&2
        return 1
    fi
    return 0
}
