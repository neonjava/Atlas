#!/usr/bin/env python3
# chunks.py — summarises scenario A runs: chunks.sh (teleports) and fly.sh.
#
# Why: turns raw bot/probe events into the per-run and per-config numbers
# shown on the website, so no number is typed by hand.
# chunks.sh, per run: median fill50/fill90/fill100 over the measured teleports
# (the first teleport is a warm-up), fills that timed out, the server's mean
# chunk generation rate, and global-region tick interval p99 / max from the
# first measured teleport on (does faster generation cost tick time?). The
# first seconds after start are excluded: they hold JIT warm-up and metaspace
# collections, with tick gaps of 150-460 ms in every configuration.
# Clock alignment uses the epoch rows; archives made before epoch rows existed
# fall back to treating both clocks as equal (the probe starts at most about a
# second before the bot, which only moves the window edge).
# fly.sh, per run: median and worst view coverage after the first FLY_SKIP_S
# seconds (join and climb), and server position corrections (non-zero means
# the bot was held back and the run is suspect).
# The early chunk baseline archives also hold a bot-fly.csv from a flyer that
# was stuck at spawn height (see docs/JOURNAL.md); it is ignored on purpose.
# Done: per-run CSV (runs.csv) and per-config summary (summary.json).
# Not done: statistical tests; the docs compare medians and spreads.
#
# Usage: chunks.py <results/chunks/<label>>
import pathlib
import statistics
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import common  # noqa: E402

WARMUP_TELEPORTS = 1
TICK_PERCENTILE = 0.99
US_PER_MS = 1000.0
# Skip the join and the climb to flying height (about 5 s at 30 blocks/s).
FLY_SKIP_S = 30
MS_PER_S = 1000.0


def teleport_metrics(files: dict) -> dict:
    bot = common.rows(files["bot.csv"])
    teleports = []
    current = None
    for r in bot:
        if r["bot"] != "walker0":
            continue
        if r["event"] == "teleport":
            current = {"t": int(r["t_ms"])}
            teleports.append(current)
        elif current is not None and r["event"] in ("fill50", "fill90", "fill100"):
            current[r["event"]] = int(r["a"]) / MS_PER_S
            current["chunks"] = int(r["b"])
    measured = teleports[1 + WARMUP_TELEPORTS:]  # [0] is the join itself
    bot_epoch = next((float(r["a"]) for r in bot if r["event"] == "epoch"), None)
    m = {"teleports": len(measured)}
    for ev in ("fill50", "fill90", "fill100"):
        vals = [t[ev] for t in measured if ev in t]
        m[ev + "_s"] = statistics.median(vals) if vals else None
    m["timeouts"] = sum(1 for t in measured if "fill100" not in t)
    m["view_chunks"] = measured[0].get("chunks") if measured else None
    m["server_gen_rate_mean"] = gen_rate(files)
    probe = common.rows(files.get("probe.csv", "t_ms,kind,region,v1\n"))
    probe_epoch = next((float(r["v1"]) for r in probe if r["kind"] == "epoch"), None)
    offset = (bot_epoch - probe_epoch) if bot_epoch is not None and probe_epoch is not None else 0.0
    window_start = measured[0]["t"] + offset if measured else float("inf")
    ticks = [float(r["v1"]) / US_PER_MS for r in probe if r["kind"] == "gtick" and float(r["t_ms"]) >= window_start]
    m["tick_p99_ms"] = common.percentile(ticks, TICK_PERCENTILE)
    m["tick_max_ms"] = max(ticks) if ticks else None
    return m


def fly_metrics(files: dict) -> dict:
    fly = common.rows(files["bot-fly.csv"])
    joined = next((int(r["t_ms"]) for r in fly if r["event"] == "joined"), None)

    def after_skip(r):
        return joined is not None and int(r["t_ms"]) - joined >= FLY_SKIP_S * MS_PER_S

    cov = [int(r["a"]) / 10.0 for r in fly if r["event"] == "view_permille" and after_skip(r)]
    return {
        "fly_view_pct_median": statistics.median(cov) if cov else None,
        "fly_view_pct_min": min(cov) if cov else None,
        "fly_corrections": sum(int(r["a"]) for r in fly if r["event"] == "corrections"),
        "server_gen_rate_mean": gen_rate(files),
    }


def gen_rate(files: dict):
    probe = common.rows(files.get("probe.csv", "t_ms,kind,region,v1\n"))
    gen = [float(r["v1"]) for r in probe if r["kind"] == "chunk_rate"]
    return statistics.fmean(gen) if gen else None


def main() -> None:
    root = pathlib.Path(sys.argv[1])
    scenario = root.resolve().parent.name
    metrics = fly_metrics if scenario == "fly" else teleport_metrics
    records = []
    summary = {}
    for cfg_dir in sorted(p for p in root.iterdir() if p.is_dir()):
        per_run = []
        for archive in sorted(cfg_dir.glob("run*.tar.gz")):
            m = metrics(common.read_run(archive))
            m = {"config": cfg_dir.name, "run": archive.name.split(".")[0], **m}
            records.append(m)
            per_run.append(m)
        keys = [k for k in per_run[0] if k not in ("config", "run")] if per_run else []
        summary[cfg_dir.name] = {key: common.spread([r[key] for r in per_run]) for key in keys}
    common.write_csv(root / "runs.csv", records)
    common.write_json(root / "summary.json", summary)


if __name__ == "__main__":
    main()
