# Status

Last updated: 2026-11-23 08:44 (IST).

Every change gets one of three results: **PASSED** (clear gain, no harm →
PR merged), **FAILED** (no clear gain or it hurts something → not merged,
reason written down), **PENDING** (measurement still running).

| # | Idea | Result | Why | Next |
|---|---|---|---|---|
| C5 | More chunk worker threads on small CPUs | **FAILED on its own** | New land fills 2.3× faster (4 CPUs, view 16: 31.4 → 13.4 s), but with 22 bots MSPT went 48.7 → 90.9 ms and the worst window 12.8 → 7.2 TPS | Retest with workers at low OS priority (queued) and together with C6 |
| C6 | More region tick threads (3 instead of 1) | **PENDING** (looks good) | One-region test: global tick median 97 → 50 ms, p99 1367 → 72 ms, slow ticks 237 → 7 per minute; but the test layout put all bots in one region | True-spread rerun queued; if it passes → PR with a better default |
| C1 | Raise the 75 chunks/s per-player send limit | **PENDING** | With C5, view 16 fills at ~70 chunks/s, right at the limit | Experiment queued |
| C7 | Full GC pauses of 1-1.6 s while generating | **PENDING** | G1 starts cleaning too late (not a leak) | G1 vs G1 tuned vs ZGC vs Shenandoah queued |
| R1 | Perlin noise kernel in Rust | **PENDING** | Kernel alone: bit-identical, 2.2× faster on most inputs; whole-server gain at most ~2% | A/B running (same jar, Rust on/off); merge only if clearly faster |

Merged so far: harness, bot, probe, docs, website, baseline results
(PRs #1, #2, #5). No server change merged yet.
