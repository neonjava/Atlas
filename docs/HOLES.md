# Holes

A "hole" is a place where the server loses time. Each hole needs evidence
(numbers and a profile) before work on it starts. The list is ranked by
expected gain divided by effort, once the evidence is in.

The baseline measurements are still running, so this list only holds
**candidates to check**, not findings.

| # | Candidate | Why it is a candidate | Evidence so far | Status |
|---|---|---|---|---|
| C1 | Per-player chunk *send* rate limit (75 chunks/s default) | A view-distance-16 view is 921 chunks, so it cannot fill in less than ~12.3 s at that rate, however fast the CPU is | `player-max-chunk-send-rate: 75.0`; baseline vd16 with 8 CPUs already reaches ~61 chunks/s (921 chunks in 15.1 s), so once C5 adds workers this cap binds | to measure after C5 |
| C2 | Per-player chunk *load* rate limit (100 chunks/s default) | Same idea for chunks already on disk | `player-max-chunk-load-rate: 100.0` | to measure |
| C3 | World generation on few cores | 22 chunks/s generated with 4 server CPUs in the first smoke test | smoke test only | to profile |
| C4 | GC pauses with default flags | Target B | none yet | to measure |
| C6 | One tick thread for all regions on up to 9 CPUs | Folia's default is 1 tick thread while c/2 ≤ 4. With players spread over many regions, all regions share that thread | Profiled load run (50 spread bots + 4 flyers, 8 CPUs, G1 4 GB, JFR on): the tick thread is busy ~100% (26.8% of all samples ≈ 1 of 4.2 cores used), global tick median 99 ms (≈10 TPS), worst 15 s window 4.8 TPS. Unprofiled confirmation pending | experiment queued (part 3 of c5-workers.sh) |
| C7 | Heap fills under generation load; G1 pauses of 1.3-1.7 s | Live data stays at 3.4-3.9 GB of a 4 GB heap; G1 reports evacuation failures, pauses p99 1.48 s, 100 s paused in 6.5 min. Possibly a consequence of C6 (unloading runs in region ticks, which fall behind) | Same profiled run, `gc.log`, `jfr-gc-pauses.txt` | needs the unprofiled GC baseline; re-check after C6 |
| C5 | Too few threads by default on small CPUs | Defaults, with c = logical CPUs the JVM may use: tick threads = 1 if c/2 ≤ 4, else c/8; chunk workers = 1 if c ≤ 7, 2 if c ≤ 9, else c/4. Up to 7 CPUs, one thread generates all new terrain | Chunk baseline (median of 5 runs, time to full view after teleport): 2 CPUs vs 4 CPUs = 10.3 vs 10.5 s (vd8), 19.3 vs 19.0 s (vd12), 32.1 vs 31.4 s (vd16) — no gain from 2 more CPUs; 8 CPUs (2 workers) halves each. Logs: "1 worker threads", "1 target thread(s)" at 2 and 4 CPUs | experiment queued (`bench/experiments/c5-workers.sh`) |
