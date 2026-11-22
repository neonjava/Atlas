#!/usr/bin/env python3
# chunks.py — summarises scenario A (chunk loading) runs.
#
# Why: turns raw bot/probe events into the per-run and per-config numbers
# shown on the website, so no number is typed by hand.
# Per run: median fill50/fill90/fill100 over the measured teleports (the
# first teleport is a warm-up), the fly bot's median and minimum view
# coverage after its first FLY_SKIP_S seconds, the number of fills that timed
# out, and the server's mean chunk generation rate.
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
# The flyer starts at spawn, where chunks already exist; skip that part.
FLY_SKIP_S = 30
MS_PER_S = 1000.0


def run_metrics(files: dict) -> dict:
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
    m = {"teleports": len(measured)}
    for ev in ("fill50", "fill90", "fill100"):
        vals = [t[ev] for t in measured if ev in t]
        m[ev + "_s"] = statistics.median(vals) if vals else None
    m["timeouts"] = sum(1 for t in measured if "fill100" not in t)
    m["view_chunks"] = measured[0].get("chunks") if measured else None
    fly = common.rows(files.get("bot-fly.csv", "t_ms,bot,event,a,b\n"))
    joined = next((int(r["t_ms"]) for r in fly if r["event"] == "joined"), None)
    cov = [int(r["a"]) / 10.0 for r in fly
           if r["event"] == "view_permille" and joined is not None and int(r["t_ms"]) - joined >= FLY_SKIP_S * MS_PER_S]
    m["fly_view_pct_median"] = statistics.median(cov) if cov else None
    m["fly_view_pct_min"] = min(cov) if cov else None
    probe = common.rows(files.get("probe.csv", "t_ms,kind,region,v1\n"))
    gen = [float(r["v1"]) for r in probe if r["kind"] == "chunk_rate"]
    m["server_gen_rate_mean"] = statistics.fmean(gen) if gen else None
    return m


def main() -> None:
    root = pathlib.Path(sys.argv[1])
    records = []
    summary = {}
    for cfg_dir in sorted(p for p in root.iterdir() if p.is_dir()):
        per_run = []
        for archive in sorted(cfg_dir.glob("run*.tar.gz")):
            m = run_metrics(common.read_run(archive))
            m = {"config": cfg_dir.name, "run": archive.name.split(".")[0], **m}
            records.append(m)
            per_run.append(m)
        summary[cfg_dir.name] = {
            key: common.spread([r[key] for r in per_run])
            for key in ("fill50_s", "fill90_s", "fill100_s", "fly_view_pct_median", "fly_view_pct_min", "server_gen_rate_mean")
        }
        summary[cfg_dir.name]["timeouts_total"] = sum(r["timeouts"] for r in per_run)
    common.write_csv(root / "runs.csv", records)
    common.write_json(root / "summary.json", summary)


if __name__ == "__main__":
    main()
