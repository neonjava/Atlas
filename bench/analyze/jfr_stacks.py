#!/usr/bin/env python3
# jfr_stacks.py — summarises CPU samples from a JFR recording.
#
# Why: `jfr view hot-methods` only shows the method at the top of each stack.
# To see *which work* costs time (generation, lighting, saving, networking),
# Atlas also needs inclusive time per method and time per thread group.
# Output: three tables — samples per thread group (thread names with digits
# removed), top methods by inclusive share (method anywhere on the stack,
# counted once per sample), top methods by self share (top frame).
# Done: streaming parse of `jfr print` text, so large recordings fit in memory.
# Not done: flame graphs.
#
# Usage: jfr_stacks.py <recording.jfr> [top-n]
import collections
import re
import subprocess
import sys

STACK_DEPTH = 96
DEFAULT_TOP = 60
THREAD_RE = re.compile(r'sampledThread = "(.*?)"')
FRAME_RE = re.compile(r"^\s+([\w$.<>]+)\(.*?\)\s+line:")


def main() -> None:
    path = sys.argv[1]
    top = int(sys.argv[2]) if len(sys.argv) > 2 else DEFAULT_TOP
    proc = subprocess.Popen(
        ["jfr", "print", "--events", "jdk.ExecutionSample", "--stack-depth", str(STACK_DEPTH), path],
        stdout=subprocess.PIPE, text=True, bufsize=1 << 20)
    threads = collections.Counter()
    inclusive = collections.Counter()
    self_time = collections.Counter()
    samples = 0
    thread = None
    frames = []
    in_stack = False
    for line in proc.stdout:
        if line.startswith("jdk.ExecutionSample"):
            thread, frames, in_stack = None, [], False
            continue
        m = THREAD_RE.search(line)
        if m:
            thread = re.sub(r"\d+", "#", m.group(1))
            continue
        if "stackTrace = [" in line:
            in_stack = True
            continue
        if in_stack:
            fm = FRAME_RE.match(line)
            if fm:
                frames.append(fm.group(1))
            elif line.strip() == "]":
                in_stack = False
                samples += 1
                threads[thread] += 1
                if frames:
                    self_time[frames[0]] += 1
                for f in set(frames):
                    inclusive[f] += 1
    proc.wait()
    if samples == 0:
        print("no execution samples")
        return

    def table(title, counter):
        print(f"\n== {title} ({samples} samples) ==")
        for name, n in counter.most_common(top):
            print(f"{100.0 * n / samples:6.2f}%  {n:8d}  {name}")

    table("Samples by thread group", threads)
    table("Inclusive (method anywhere on stack)", inclusive)
    table("Self (top frame)", self_time)


if __name__ == "__main__":
    main()
