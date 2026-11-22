#!/usr/bin/env bash
# server.sh — start, drive and stop one benchmark server.
#
# Why: every scenario needs the same steps (fresh world, fixed settings,
# pinned CPUs, memory cap, console access, clean stop). Keeping them here
# means all scenarios measure the server the same way.
# Done: per-jar template (first start unpacks libraries and writes default
# configs), fresh run dirs, console via FIFO, readiness wait, probe plugin,
# GC and safepoint logs with size caps, clean stop with a hard-kill fallback
# that only touches the process this script started.
# Not done: one server at a time only (by design, see docs/MACHINE.md).
#
# Usage: source bench/lib/guard.sh; source bench/lib/server.sh

ATLAS_BENCH_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ATLAS_RUNS_DIR="${ATLAS_RUNS_DIR:-$ATLAS_BUILD_DIR/runs}"
ATLAS_TEMPLATES_DIR="${ATLAS_TEMPLATES_DIR:-$ATLAS_BUILD_DIR/templates}"
ATLAS_PORT="${ATLAS_PORT:-25565}"
# Fixed world seed so every run generates the same terrain.
ATLAS_SEED="${ATLAS_SEED:-12345}"
# A cold start (fresh world, spawn chunks) takes ~12 s here; allow plenty.
ATLAS_READY_TIMEOUT_S="${ATLAS_READY_TIMEOUT_S:-300}"
ATLAS_STOP_TIMEOUT_S="${ATLAS_STOP_TIMEOUT_S:-120}"
# GC/safepoint logs rotate at this size so a long run cannot fill the disk.
ATLAS_LOG_FILE_SIZE="${ATLAS_LOG_FILE_SIZE:-50m}"
ATLAS_LOG_FILE_COUNT="${ATLAS_LOG_FILE_COUNT:-4}"
# Console commands sent right after "Done" can hit a NullPointerException in
# Folia (the console source has no level yet), so wait a little first.
ATLAS_SETTLE_S="${ATLAS_SETTLE_S:-5}"
ATLAS_TEMPLATE_THREADS=4
ATLAS_TEMPLATE_HEAP=2G

# atlas_template <jar> -> prints template dir (creates it once per jar hash).
atlas_template() {
    local jar="$1" hash dir
    hash="$(sha256sum "$jar" | cut -c1-16)"
    dir="$ATLAS_TEMPLATES_DIR/$hash"
    if [[ ! -f "$dir/.ready" ]]; then
        rm -rf "$dir"
        mkdir -p "$dir"
        cp "$jar" "$dir/server.jar"
        echo "eula=true" > "$dir/eula.txt"
        # One full start/stop writes every default config file (--initSettings
        # only writes server.properties); the world it creates is deleted.
        atlas_start "$dir" "$ATLAS_TEMPLATE_THREADS" "$ATLAS_TEMPLATE_HEAP" || return 1
        atlas_stop "$dir"
        rm -rf "$dir/world" "$dir/world_nether" "$dir/world_the_end" "$dir/logs" "$dir"/*.log "$dir"/*.log.* "$dir/cpus.txt" "$dir/jvm-flags.txt" "$dir"/*.pid "$dir/console.fifo"
        python3 -I "$ATLAS_BENCH_DIR/lib/configure.py" "$dir" --seed "$ATLAS_SEED" --port "$ATLAS_PORT"
        touch "$dir/.ready"
    fi
    echo "$dir"
}

# atlas_new_run <template> <run-name> <view-distance> -> prints run dir.
atlas_new_run() {
    local template="$1" name="$2" vd="$3" dir
    dir="$ATLAS_RUNS_DIR/$name"
    rm -rf "$dir"
    mkdir -p "$dir"
    # Hard links: libraries are read-only at runtime and this saves ~100 MB per run.
    cp -al "$template/libraries" "$template/versions" "$dir/"
    cp "$template/server.jar" "$template/eula.txt" "$dir/"
    cp -r "$template/config" "$dir/"
    cp "$template"/*.yml "$template"/*.json "$template/server.properties" "$dir/" 2>/dev/null || true
    python3 -I "$ATLAS_BENCH_DIR/lib/configure.py" "$dir" --view-distance "$vd"
    mkdir -p "$dir/plugins"
    bash "$ATLAS_BENCH_DIR/../tools/atlas-probe/build.sh" "$dir" "$dir/plugins/atlas-probe.jar"
    echo "$dir"
}

# atlas_start <run-dir> <server-threads> <heap> [extra JVM flags...]
atlas_start() {
    local dir="$1" threads="$2" heap="$3"
    shift 3
    local cpus
    cpus="$(atlas_server_cpus "$threads")" || return 1
    if ss -ltn "sport = :$ATLAS_PORT" | grep -q LISTEN; then
        echo "start: port $ATLAS_PORT is already in use" >&2
        return 1
    fi
    rm -f "$dir/console.fifo"
    mkfifo "$dir/console.fifo"
    # Keeps the FIFO open so the server's stdin does not see end-of-file.
    sleep infinity > "$dir/console.fifo" 2>/dev/null &
    echo $! > "$dir/fifo-holder.pid"
    echo "$cpus" > "$dir/cpus.txt"
    printf '%s\n' "-Xms$heap" "-Xmx$heap" "$@" > "$dir/jvm-flags.txt"
    (
        cd "$dir" || exit 1
        exec systemd-run --user --scope --quiet --unit="atlas-server-$$-$RANDOM" \
            -p MemoryMax="$ATLAS_SERVER_MEM_CAP" -p MemorySwapMax=0 \
            taskset -c "$cpus" java "-Datlas.run=$dir" "-Xms$heap" "-Xmx$heap" \
            "-Xlog:gc:file=gc.log:uptime,level,tags:filecount=$ATLAS_LOG_FILE_COUNT,filesize=$ATLAS_LOG_FILE_SIZE" \
            "-Xlog:safepoint:file=safepoint.log:uptime,level,tags:filecount=$ATLAS_LOG_FILE_COUNT,filesize=$ATLAS_LOG_FILE_SIZE" \
            "$@" -jar server.jar --nogui < console.fifo > server.log 2>&1
    ) > /dev/null 2>&1 &
    echo $! > "$dir/launcher.pid"
    atlas_wait_ready "$dir"
}

atlas_server_pid() {
    pgrep -f -- "-Datlas.run=$1 " | head -1
}

atlas_wait_ready() {
    local dir="$1" waited=0
    while (( waited < ATLAS_READY_TIMEOUT_S )); do
        if grep -q 'Done (' "$dir/server.log" 2>/dev/null; then
            sleep "$ATLAS_SETTLE_S"
            return 0
        fi
        if ! kill -0 "$(cat "$dir/launcher.pid")" 2>/dev/null; then
            echo "start: server exited early, see $dir/server.log" >&2
            return 1
        fi
        sleep 1
        waited=$((waited + 1))
    done
    echo "start: server not ready after ${ATLAS_READY_TIMEOUT_S}s" >&2
    return 1
}

# atlas_cmd <run-dir> <console command...>
atlas_cmd() {
    local dir="$1"
    shift
    echo "$*" > "$dir/console.fifo"
}

# atlas_wait_log <run-dir> <regex> <timeout-s> — waits for a server.log line.
atlas_wait_log() {
    local dir="$1" pattern="$2" timeout="$3" waited=0
    while (( waited < timeout )); do
        grep -qE "$pattern" "$dir/server.log" && return 0
        sleep 1
        waited=$((waited + 1))
    done
    return 1
}

atlas_stop() {
    local dir="$1" pid waited=0
    pid="$(atlas_server_pid "$dir")"
    if [[ -n "$pid" ]]; then
        atlas_cmd "$dir" "stop"
        while kill -0 "$pid" 2>/dev/null && (( waited < ATLAS_STOP_TIMEOUT_S )); do
            sleep 1
            waited=$((waited + 1))
        done
        if kill -0 "$pid" 2>/dev/null; then
            echo "stop: server did not stop in ${ATLAS_STOP_TIMEOUT_S}s, killing pid $pid" >&2
            kill -9 "$pid"
        fi
    fi
    if [[ -f "$dir/fifo-holder.pid" ]]; then
        kill "$(cat "$dir/fifo-holder.pid")" 2>/dev/null || true
        rm -f "$dir/fifo-holder.pid"
    fi
    wait 2>/dev/null || true
}

# atlas_bots <run-dir> <csv-out> [bot options...] — runs bots in the foreground.
atlas_bots() {
    _atlas_bot_command "$@"
    "${ATLAS_BOT_CMD[@]}"
}

# atlas_bots_start: same, in the background; sets ATLAS_BOT_PID. systemd-run
# --scope and taskset both exec, so the PID is the bot JVM itself.
atlas_bots_start() {
    _atlas_bot_command "$@"
    "${ATLAS_BOT_CMD[@]}" &
    ATLAS_BOT_PID=$!
}

atlas_bots_stop() {
    kill "$ATLAS_BOT_PID" 2>/dev/null || true
    wait "$ATLAS_BOT_PID" 2>/dev/null || true
}

_atlas_bot_command() {
    local out="$2"
    shift 2
    ATLAS_BOT_CMD=(systemd-run --user --scope --quiet -p MemoryMax="$ATLAS_BOT_MEM_CAP"
        taskset -c "$ATLAS_BOT_CPUS" java -Xmx1g -cp "$ATLAS_BOT_CLASSES" atlas.bot.Main
        --ids "$ATLAS_BENCH_DIR/../tools/atlas-bot/packet-ids-26.3.properties"
        --port "$ATLAS_PORT" --events "$out" "$@")
}

# Compiles the bot once per shell into the build dir.
atlas_build_bot() {
    ATLAS_BOT_CLASSES="$ATLAS_BUILD_DIR/bot-classes"
    rm -rf "$ATLAS_BOT_CLASSES"
    javac -d "$ATLAS_BOT_CLASSES" $(find "$ATLAS_BENCH_DIR/../tools/atlas-bot/src" -name '*.java')
}

# Deletes the bulky parts of a finished run (worlds, unpacked jars, logs);
# result files (CSV, GC and safepoint logs, server.log) stay.
atlas_prune_run() {
    local dir="$1"
    rm -rf "$dir/world" "$dir/world_nether" "$dir/world_the_end" "$dir/libraries" "$dir/versions" "$dir/cache" "$dir/logs"
}

# atlas_package_run <run-dir> <dest.tar.gz> <label> — saves one run's results.
# One archive per run keeps the file count low (the repo disk is exFAT with
# 1 MiB clusters, see docs/MACHINE.md).
atlas_package_run() {
    local dir="$1" dest="$2" label="$3"
    python3 -I - "$dir" "$label" > "$dir/meta.json" <<'PY'
import json, os, pathlib, platform, subprocess, sys
d = pathlib.Path(sys.argv[1])
def read(name):
    p = d / name
    return p.read_text().strip() if p.exists() else None
props = {}
for line in (d / "server.properties").read_text().splitlines():
    if "=" in line and not line.startswith("#"):
        k, v = line.split("=", 1)
        props[k] = v
print(json.dumps({
    "label": sys.argv[2],
    "jar_sha256": subprocess.run(["sha256sum", str(d / "server.jar")], capture_output=True, text=True).stdout.split()[0],
    "cpus": read("cpus.txt"),
    "jvm_flags": (read("jvm-flags.txt") or "").splitlines(),
    "view_distance": props.get("view-distance"),
    "simulation_distance": props.get("simulation-distance"),
    "seed": props.get("level-seed"),
    "java": subprocess.run(["java", "-version"], capture_output=True, text=True).stderr.splitlines()[0],
    "kernel": platform.release(),
}, indent=1))
PY
    (
        cd "$dir" || exit 1
        shopt -s nullglob
        tar -czf "$dest" meta.json bot*.csv probe*.csv gc.log* safepoint.log* server.log
    )
}
