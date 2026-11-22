#!/usr/bin/env python3
# load.py — summarises the load scenarios (tps, gc, crowd, idle).
#
# Why: turns probe, bot, safepoint and mark files into the per-run numbers the
# website shows. Only the measured window(s) between the marks count; joins,
# placement and warm-up are excluded.
# Per window:
#   tick_p50_ms / tick_p99_ms / tick_max_ms  global-region tick interval
#   slow_ticks_per_min                       global ticks later than SLOW_TICK_MS
#   region_tick_p99_ms / region_tick_max_ms  watched region (crowd only)
#   mspt_mean    mean of per-region mean MSPT (regions with players)
#   mspt_p99     worst per-region 15 s p99 MSPT
#   tps_min_window  lowest per-region 15 s TPS
#   stw_pause_max_ms / stw_pause_total_ms_per_min  all stop-the-world
#                safepoint pauses (mostly GC) from safepoint.log
#   cpu_cores_used  server CPU time / wall time
#   net_kib_per_player_s  bytes the bots received per player per second
# Crowd runs have two windows: "<config>-crowd" and "<config>-spread".
# Done: all of the above; runs.csv and summary.json per label.
# Not done: per-region breakdown tables (in the raw probe.csv if needed).
#
# Usage: load.py <results/<scenario>/<label>>
import pathlib
import re
import statistics
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import common  # noqa: E402

SLOW_TICK_MS = 100.0
US_PER_MS = 1000.0
MS_PER_S = 1000.0
NS_PER_MS = 1.0e6
S_PER_MIN = 60.0
BYTES_PER_KIB = 1024.0
SAFEPOINT_RE = re.compile(r"^\[(\d+\.\d+)s\].*Safepoint \"(\w+)\".*Total: (\d+) ns")
WINDOWS = [("measure_start", "measure_end", ""), ("measure2_start", "measure2_end", "-spread")]


def epoch_of(rows_: list, kind_col: str, kind: str, value_col: str):
    for r in rows_:
        if r.get(kind_col) == kind:
            return float(r[value_col])
    return None


def window_metrics(files: dict, start: dict, end: dict) -> dict:
    t0, t1 = float(start["epoch_ms"]), float(end["epoch_ms"])
    minutes = (t1 - t0) / MS_PER_S / S_PER_MIN
    m = {}
    probe = common.rows(files.get("probe.csv", "t_ms,kind,region\n"))
    p_epoch = epoch_of(probe, "kind", "epoch", "v1")
    if p_epoch is not None:
        def inwin(r):
            t = p_epoch + float(r["t_ms"])
            return t0 <= t <= t1
        g = [float(r["v1"]) / US_PER_MS for r in probe if r["kind"] == "gtick" and inwin(r)]
        m["tick_p50_ms"] = common.percentile(g, 0.50)
        m["tick_p99_ms"] = common.percentile(g, 0.99)
        m["tick_max_ms"] = max(g) if g else None
        m["slow_ticks_per_min"] = sum(1 for v in g if v > SLOW_TICK_MS) / minutes if g else None
        w = [float(r["v1"]) / US_PER_MS for r in probe if r["kind"] == "tick" and inwin(r)]
        m["region_tick_p99_ms"] = common.percentile(w, 0.99)
        m["region_tick_max_ms"] = max(w) if w else None
        rep = [r for r in probe if r["kind"] == "report" and r["region"] != "global" and inwin(r) and int(r["v8"]) > 0]
        m["mspt_mean"] = statistics.fmean(float(r["v1"]) for r in rep) if rep else None
        m["mspt_p99"] = max(float(r["v4"]) for r in rep) if rep else None
        m["tps_min_window"] = min(float(r["v6"]) for r in rep) if rep else None
    jvm_start = float(start["jvm_start_epoch_ms"]) if start.get("jvm_start_epoch_ms") else None
    pauses = []
    if jvm_start is not None:
        for line in files.get("safepoint.log", "").splitlines():
            sm = SAFEPOINT_RE.match(line)
            if sm and t0 <= jvm_start + float(sm.group(1)) * MS_PER_S <= t1:
                pauses.append(int(sm.group(3)) / NS_PER_MS)
    m["stw_pause_max_ms"] = max(pauses) if pauses else 0.0
    m["stw_pause_total_ms_per_min"] = sum(pauses) / minutes if minutes > 0 else None
    if start.get("cpu_ticks") and end.get("cpu_ticks"):
        cpu_s = (float(end["cpu_ticks"]) - float(start["cpu_ticks"])) / float(start["clk_tck"])
        m["cpu_cores_used"] = cpu_s / ((t1 - t0) / MS_PER_S)
    bot = common.rows(files.get("bot.csv", "t_ms,bot,event,a,b\n"))
    b_epoch = epoch_of(bot, "event", "epoch", "a")
    if b_epoch is not None:
        rates = [r for r in bot if r["event"] == "rate" and t0 <= b_epoch + float(r["t_ms"]) <= t1]
        players = len({r["bot"] for r in rates})
        if players:
            total = sum(int(r["b"]) for r in rates)
            m["net_kib_per_player_s"] = total / BYTES_PER_KIB / players / ((t1 - t0) / MS_PER_S)
        m["players"] = players
    return m


def main() -> None:
    root = pathlib.Path(sys.argv[1])
    records = []
    by_cfg = {}
    for cfg_dir in sorted(p for p in root.iterdir() if p.is_dir()):
        for archive in sorted(cfg_dir.glob("run*.tar.gz")):
            files = common.read_run(archive)
            marks = {r["name"]: r for r in common.rows(files.get("marks.csv", "name\n"))}
            for s_name, e_name, suffix in WINDOWS:
                if s_name not in marks or e_name not in marks:
                    continue
                cfg = cfg_dir.name + (suffix or ("-crowd" if "measure2_start" in marks else ""))
                m = {"config": cfg, "run": archive.name.split(".")[0], **window_metrics(files, marks[s_name], marks[e_name])}
                records.append(m)
                by_cfg.setdefault(cfg, []).append(m)
    keys = [k for k in records[0] if k not in ("config", "run")] if records else []
    summary = {cfg: {k: common.spread([r.get(k) for r in rs]) for k in keys} for cfg, rs in by_cfg.items()}
    common.write_csv(root / "runs.csv", records)
    common.write_json(root / "summary.json", summary)


if __name__ == "__main__":
    main()
