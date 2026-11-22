#!/usr/bin/env bash
# queue.sh — runs benchmark commands one after another from a queue file.
#
# Why: only one server may run at a time, and the machine should not sit idle
# between jobs. Lines can be appended to the queue while it runs.
# Each line is one shell command run from the repo root. Finished lines are
# moved to <queue>.done with their exit code and times; a failed command is
# logged and the queue moves on.
# Done: sequential execution, append-while-running, stop file.
# Not done: priorities; order is the file order.
#
# Usage: queue.sh <queue-file>     (touch <queue-file>.stop to stop after the current job)
set -uo pipefail
queue="$1"
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
touch "$queue" "$queue.done"
while [[ ! -f "$queue.stop" ]]; do
    line="$(head -n 1 "$queue")"
    if [[ -z "$line" ]]; then
        if [[ -s "$queue" ]]; then
            sed -i '1d' "$queue"  # skip a blank line
        else
            sleep 30  # an empty queue waits for new work instead of exiting
        fi
        continue
    fi
    sed -i '1d' "$queue"
    start="$(date '+%F %T')"
    (cd "$root" && bash -c "$line")
    code=$?
    echo "$start | $(date '+%F %T') | exit $code | $line" >> "$queue.done"
done
rm -f "$queue.stop"
