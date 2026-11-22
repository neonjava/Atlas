#!/usr/bin/env python3
# build.py — builds the Atlas "documentary" website into site/_build.
#
# Why: every number on the site must come from the result data files in the
# repo, and the story and updates must come from the journal and git history,
# so pages are generated instead of written by hand.
# Inputs: site/pages/**/*.md (prose), docs/*.md (included where a page asks),
# bench/results/<scenario>/<label>/summary.json (numbers), docs/JOURNAL.md
# (the story timeline), git log (updates).
# Shortcodes, each alone on a line in a page:
#   [[include docs/FILE.md]]               the file, without its first heading
#   [[table SCENARIO LABEL METRIC...]]     per-config median (min-max) table
#   [[chart SCENARIO METRIC LABEL,LABEL]]  SVG chart: median, IQR box, min-max
#   [[cards SCENARIO LABEL CONFIG METRIC...]]  big-number cards for the home page
#   [[timeline]]  [[updates]]  [[holes]]
# Done: all of the above, navigation, holes index with status tags.
# Not done: client-side interactivity (the site needs no JavaScript).
#
# Usage: build.py [--out DIR]
import argparse
import html
import json
import pathlib
import re
import shutil
import subprocess

import markdown

ROOT = pathlib.Path(__file__).resolve().parent.parent
SITE = ROOT / "site"
RESULTS = ROOT / "bench" / "results"
REPO_URL = "https://github.com/neonjava/Atlas"
NAV = [
    ("index", "Home"),
    ("story", "The Story"),
    ("machine", "The Machine"),
    ("baseline", "Baseline"),
    ("holes", "Holes"),
    ("benchmarks", "Benchmarks"),
    ("rust", "Rust"),
    ("failed", "Failed Experiments"),
    ("how-to-run", "How To Run"),
    ("glossary", "Glossary"),
    ("updates", "Updates"),
]
# Units and readable names for metrics that appear in summary.json files.
METRICS = {
    "fill50_s": ("Time to 50% of view", "s"),
    "fill90_s": ("Time to 90% of view", "s"),
    "fill100_s": ("Time to full view", "s"),
    "fly_view_pct_median": ("View present while flying (median)", "%"),
    "fly_view_pct_min": ("View present while flying (worst second)", "%"),
    "server_gen_rate_mean": ("Server chunk generation rate", "chunks/s"),
    "fly_corrections": ("Server position corrections while flying", "count"),
    "tick_p50_ms": ("Tick interval, median", "ms"),
    "tick_p99_ms": ("Tick interval, 99th percentile", "ms"),
    "tick_max_ms": ("Longest tick interval", "ms"),
    "slow_ticks_per_min": ("Ticks over 100 ms per minute", "/min"),
    "mspt_mean": ("Work per tick (MSPT), mean", "ms"),
    "mspt_p99": ("Work per tick (MSPT), 99th percentile", "ms"),
    "tps_min_window": ("Lowest 15 s TPS", "TPS"),
    "stw_pause_max_ms": ("Longest stop-the-world pause (mostly GC)", "ms"),
    "stw_pause_total_ms_per_min": ("Stop-the-world pause time per minute", "ms/min"),
    "region_tick_p99_ms": ("Crowd region tick interval, 99th percentile", "ms"),
    "region_tick_max_ms": ("Crowd region longest tick interval", "ms"),
    "cpu_cores_used": ("CPU used", "cores"),
    "net_kib_per_player_s": ("Network per player", "KiB/s"),
}
SERIES_COLORS = ["#3fd6c6", "#a77bf3", "#f5c26b", "#63e6a1", "#ff8a8a"]
CHART_W = 760
CHART_H = 300
PAD_L = 62
PAD_R = 16
PAD_T = 18
PAD_B = 54
Y_TICKS = 5
BOX_W = 14
SHORTCODE = re.compile(r"^\[\[(\w[\w-]*)(?:\s+(.*?))?\]\]\s*$", re.M)


def esc(s) -> str:
    return html.escape(str(s), quote=True)


def fmt(v) -> str:
    if v is None:
        return "–"
    if isinstance(v, float):
        return f"{v:.3g}" if abs(v) < 100 else f"{v:.0f}"
    return str(v)


def load_summary(scenario: str, label: str) -> dict:
    p = RESULTS / scenario / label / "summary.json"
    return json.loads(p.read_text(encoding="utf-8")) if p.exists() else {}


def metric_name(metric: str) -> tuple:
    return METRICS.get(metric, (metric, ""))


def sc_include(arg: str) -> str:
    text = (ROOT / arg).read_text(encoding="utf-8")
    return re.sub(r"\A#\s.*\n", "", text)


def sc_table(arg: str) -> str:
    scenario, label, *metrics = arg.split()
    data = load_summary(scenario, label)
    if not data:
        return f"\n*No `{scenario}` results for `{label}` yet.*\n"
    head = "".join(f'<th class="num">{esc(metric_name(m)[0])} ({esc(metric_name(m)[1])})</th>' for m in metrics)
    body = []
    for cfg, vals in data.items():
        cells = []
        for m in metrics:
            s = vals.get(m, {})
            if not s or not s.get("n"):
                cells.append('<td class="num">–</td>')
                continue
            cells.append(f'<td class="num"><strong>{fmt(s["median"])}</strong><br>'
                         f'<small>{fmt(s["min"])}–{fmt(s["max"])}, n={s["n"]}</small></td>')
        body.append(f"<tr><th>{esc(cfg)}</th>{''.join(cells)}</tr>")
    return (f'\n<div class="table-scroll"><table><thead><tr><th>Config</th>{head}</tr></thead>'
            f'<tbody>{"".join(body)}</tbody></table></div>\n'
            f'<p><small>Bold: median of the runs. Below: lowest–highest run and number of runs. '
            f'Source: <code>bench/results/{esc(scenario)}/{esc(label)}/summary.json</code>.</small></p>\n')


def nice_max(v: float) -> float:
    if v <= 0:
        return 1.0
    exp = 10 ** (len(str(int(v))) - 1)
    for step in (1, 2, 2.5, 5, 10):
        if v <= step * exp:
            return step * exp
    return 10 * exp


def sc_chart(arg: str) -> str:
    scenario, metric, labels = arg.split()
    labels = labels.split(",")
    series = [(lab, load_summary(scenario, lab)) for lab in labels]
    series = [(lab, d) for lab, d in series if d]
    if not series:
        return f"\n*No `{scenario}` results yet for this chart.*\n"
    configs = []
    for _, d in series:
        for cfg in d:
            if cfg not in configs:
                configs.append(cfg)
    top = nice_max(max((d.get(c, {}).get(metric, {}).get("max") or 0) for _, d in series for c in configs))
    plot_w = CHART_W - PAD_L - PAD_R
    plot_h = CHART_H - PAD_T - PAD_B
    group_w = plot_w / max(1, len(configs))

    def y(v):
        return PAD_T + plot_h - (v / top) * plot_h

    out = [f'<svg viewBox="0 0 {CHART_W} {CHART_H}" role="img" aria-label="{esc(metric_name(metric)[0])}">']
    for i in range(Y_TICKS + 1):
        v = top * i / Y_TICKS
        out.append(f'<line x1="{PAD_L}" x2="{CHART_W - PAD_R}" y1="{y(v):.1f}" y2="{y(v):.1f}" stroke="#22303e"/>')
        out.append(f'<text x="{PAD_L - 8}" y="{y(v) + 4:.1f}" fill="#b8c4cc" font-size="12" text-anchor="end">{fmt(v)}</text>')
    out.append(f'<text x="14" y="{PAD_T + plot_h / 2:.0f}" fill="#b8c4cc" font-size="12" '
               f'transform="rotate(-90 14 {PAD_T + plot_h / 2:.0f})" text-anchor="middle">{esc(metric_name(metric)[1])}</text>')
    for gi, cfg in enumerate(configs):
        cx0 = PAD_L + gi * group_w
        out.append(f'<text x="{cx0 + group_w / 2:.1f}" y="{CHART_H - PAD_B + 18}" fill="#eef3f6" font-size="12" text-anchor="middle">{esc(cfg)}</text>')
        for si, (lab, d) in enumerate(series):
            s = d.get(cfg, {}).get(metric)
            if not s or not s.get("n"):
                continue
            x = cx0 + group_w * (si + 1) / (len(series) + 1)
            color = SERIES_COLORS[si % len(SERIES_COLORS)]
            out.append(f'<line x1="{x:.1f}" x2="{x:.1f}" y1="{y(s["min"]):.1f}" y2="{y(s["max"]):.1f}" stroke="{color}" stroke-width="1.5"/>')
            out.append(f'<rect x="{x - BOX_W / 2:.1f}" y="{y(s["q3"]):.1f}" width="{BOX_W}" '
                       f'height="{max(1.0, y(s["q1"]) - y(s["q3"])):.1f}" fill="{color}" fill-opacity="0.35" stroke="{color}"/>')
            out.append(f'<circle cx="{x:.1f}" cy="{y(s["median"]):.1f}" r="4.5" fill="{color}">'
                       f'<title>{esc(lab)} {esc(cfg)}: median {fmt(s["median"])} (min {fmt(s["min"])}, max {fmt(s["max"])}, n={s["n"]})</title></circle>')
    lx = PAD_L
    for si, (lab, _) in enumerate(series):
        color = SERIES_COLORS[si % len(SERIES_COLORS)]
        out.append(f'<circle cx="{lx + 6}" cy="{CHART_H - 14}" r="5" fill="{color}"/>'
                   f'<text x="{lx + 16}" y="{CHART_H - 10}" fill="#eef3f6" font-size="12">{esc(lab)}</text>')
        lx += 22 + 8 * len(lab)
    out.append("</svg>")
    caption = (f"{esc(metric_name(metric)[0])}. Dot: median of runs; box: middle half of runs; "
               f"line: lowest to highest run. Data: <code>bench/results/{esc(scenario)}/*/summary.json</code>.")
    return f'\n<figure class="chart">{"".join(out)}<figcaption>{caption}</figcaption></figure>\n'


def sc_cards(arg: str) -> str:
    scenario, label, cfg, *metrics = arg.split()
    data = load_summary(scenario, label).get(cfg, {})
    cards = []
    for m in metrics:
        s = data.get(m)
        if not s or not s.get("n"):
            continue
        name, unit = metric_name(m)
        cards.append(f'<div class="card"><div class="big">{fmt(s["median"])} {esc(unit)}</div>'
                     f'<div class="label">{esc(name)} — {esc(label)}, {esc(cfg)}, median of {s["n"]} runs '
                     f'(range {fmt(s["min"])}–{fmt(s["max"])})</div></div>')
    if not cards:
        return f"\n*No `{scenario}` numbers for `{label}` yet.*\n"
    return f'\n<div class="cards wide">{"".join(cards)}</div>\n'


def sc_timeline(_: str) -> str:
    text = (ROOT / "docs" / "JOURNAL.md").read_text(encoding="utf-8")
    entries = re.findall(r"^- (\d{4}-\d{2}-\d{2} \d{2}:\d{2}) — (.*?)(?=^- \d{4}-|\Z)", text, re.M | re.S)
    items = [f'<li><time>{esc(ts)}</time>{markdown.markdown(" ".join(body.split()))}</li>' for ts, body in entries]
    return f'\n<ol class="timeline wide">{"".join(items)}</ol>\n'


def sc_updates(_: str) -> str:
    try:
        log = subprocess.run(
            ["git", "-C", str(ROOT), "log", "--date=short", "--pretty=format:%h%x09%ad%x09%P%x09%s"],
            capture_output=True, text=True, check=True).stdout
    except (subprocess.CalledProcessError, FileNotFoundError):
        return "\n*Git history is not available in this build.*\n"
    base = subprocess.run(["git", "-C", str(ROOT), "rev-parse", "baseline-folia-26.3"],
                          capture_output=True, text=True).stdout.strip()
    rows = []
    for line in log.splitlines():
        sha, date, parents, subject = line.split("\t", 3)
        if base and sha and base.startswith(sha):
            break
        kind = "merge" if " " in parents.strip() else "commit"
        rows.append(f'<tr><td>{esc(date)}</td><td><a href="{REPO_URL}/commit/{esc(sha)}"><code>{esc(sha)}</code></a></td>'
                    f'<td>{esc(kind)}</td><td>{esc(subject)}</td></tr>')
    return ('\n<div class="table-scroll"><table><thead><tr><th>Date</th><th>Commit</th><th>Kind</th><th>Change</th></tr></thead>'
            f'<tbody>{"".join(rows)}</tbody></table></div>\n'
            '<p><small>Every Atlas commit since the untouched Folia baseline, newest first, read from git history.</small></p>\n')


def hole_pages() -> list:
    pages = []
    for p in sorted((SITE / "pages" / "holes").glob("*.md")):
        text = p.read_text(encoding="utf-8")
        title = re.search(r"^#\s+(.*)$", text, re.M).group(1)
        status = re.search(r"^Status:\s*(\w+)", text, re.M | re.I)
        pages.append((p.stem, title, status.group(1).lower() if status else "open"))
    return pages


def sc_holes(_: str) -> str:
    items = [f'<li><a href="holes/{esc(slug)}.html">{esc(title)}</a> <span class="tag {esc(status)}">{esc(status)}</span></li>'
             for slug, title, status in hole_pages()]
    return f'\n<ul>{"".join(items) or "<li>No hole pages yet.</li>"}</ul>\n'


SHORTCODES = {
    "include": sc_include, "table": sc_table, "chart": sc_chart, "cards": sc_cards,
    "timeline": sc_timeline, "updates": sc_updates, "holes": sc_holes,
}


def render_markdown(text: str) -> str:
    blocks = {}

    def replace(m):
        key = f"ATLASBLOCK{len(blocks)}X"
        produced = SHORTCODES[m.group(1)](m.group(2) or "")
        if m.group(1) == "include":
            return produced
        blocks[key] = produced
        return f"\n{key}\n"

    text = SHORTCODE.sub(replace, text)
    text = SHORTCODE.sub(replace, text)  # shortcodes inside included files
    body = markdown.markdown(text, extensions=["tables", "fenced_code", "toc"])
    for key, produced in blocks.items():
        body = body.replace(f"<p>{key}</p>", produced).replace(key, produced)
    return body


def page(title: str, body: str, current: str, depth: int) -> str:
    up = "../" * depth
    links = []
    for slug, name in NAV:
        marker = ' aria-current="page"' if slug == current else ""
        links.append(f'<a href="{up}{slug}.html"{marker}>{esc(name)}</a>')
    nav = "".join(links)
    return f"""<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>{esc(title)} · Atlas</title>
<meta name="description" content="Atlas: an experimental Folia fork, measured in the open.">
<link rel="stylesheet" href="{up}style.css">
</head>
<body>
<div class="stars-far" aria-hidden="true"></div>
<div class="wrap">
<header class="site"><a class="brand" href="{up}index.html">ATLAS</a><nav class="main" aria-label="Main">{nav}</nav></header>
<main class="panel">
{body}
</main>
<footer class="site">Atlas is an experimental fork of Folia (GPL-3.0). Source: <a href="{REPO_URL}">{REPO_URL}</a>.<br>Not affiliated with Mojang or Microsoft.</footer>
</div>
</body>
</html>
"""


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", type=pathlib.Path, default=SITE / "_build")
    out = ap.parse_args().out
    if out.exists():
        shutil.rmtree(out)
    (out / "holes").mkdir(parents=True)
    for f in (SITE / "static").iterdir():
        shutil.copy(f, out / f.name)
    for slug, name in NAV:
        src = SITE / "pages" / f"{slug}.md"
        body = render_markdown(src.read_text(encoding="utf-8"))
        (out / f"{slug}.html").write_text(page(name, body, slug, 0), encoding="utf-8")
    for slug, title, _ in hole_pages():
        body = render_markdown((SITE / "pages" / "holes" / f"{slug}.md").read_text(encoding="utf-8"))
        (out / "holes" / f"{slug}.html").write_text(page(title, body, "holes", 1), encoding="utf-8")


if __name__ == "__main__":
    main()
