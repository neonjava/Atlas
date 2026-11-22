# common.py — shared helpers for the Atlas analysis scripts.
#
# Why: every scenario reads run archives and reports medians with spread the
# same way, so the rules live in one place.
# Done: archive reading, CSV parsing, percentiles, median/min/max/IQR summaries.
# Not done: plotting (the website builds charts from the JSON written here).
import csv
import io
import json
import math
import pathlib
import statistics
import tarfile


def read_run(archive: pathlib.Path) -> dict:
    """Returns {member name: text} for one run archive."""
    out = {}
    with tarfile.open(archive, "r:gz") as tar:
        for member in tar.getmembers():
            if member.isfile():
                out[member.name] = tar.extractfile(member).read().decode("utf-8", "replace")
    return out


def rows(text: str) -> list:
    return list(csv.DictReader(io.StringIO(text)))


def percentile(values: list, q: float):
    """Nearest-rank percentile (q in [0, 1]); the same rule the server probe uses."""
    if not values:
        return None
    s = sorted(values)
    return s[max(0, min(len(s) - 1, math.ceil(q * len(s)) - 1))]


def spread(values: list) -> dict:
    """Median and spread of per-run values; never the best run alone."""
    vals = sorted(v for v in values if v is not None)
    if not vals:
        return {"n": 0}
    q = statistics.quantiles(vals, n=4, method="inclusive") if len(vals) > 1 else [vals[0]] * 3
    return {
        "n": len(vals),
        "median": statistics.median(vals),
        "min": vals[0],
        "max": vals[-1],
        "q1": q[0],
        "q3": q[2],
    }


def write_json(path: pathlib.Path, data) -> None:
    path.write_text(json.dumps(data, indent=1, sort_keys=True) + "\n", encoding="utf-8")


def write_csv(path: pathlib.Path, records: list) -> None:
    if not records:
        return
    with path.open("w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=list(records[0].keys()))
        w.writeheader()
        w.writerows(records)
