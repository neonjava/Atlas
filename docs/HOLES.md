# Holes

A "hole" is a place where the server loses time. Each hole needs evidence
(numbers and a profile) before work on it starts. The list is ranked by
expected gain divided by effort, once the evidence is in.

The baseline measurements are still running, so this list only holds
**candidates to check**, not findings.

| # | Candidate | Why it is a candidate | Evidence so far | Status |
|---|---|---|---|---|
| C1 | Per-player chunk *send* rate limit (75 chunks/s default) | Filling a view-distance-16 view (~800 chunks) cannot take less than ~11 s at that rate, however fast the CPU is | Paper's `GlobalConfiguration`, `player-max-chunk-send-rate: 75.0` | to measure |
| C2 | Per-player chunk *load* rate limit (100 chunks/s default) | Same idea for chunks already on disk | `player-max-chunk-load-rate: 100.0` | to measure |
| C3 | World generation on few cores | 22 chunks/s generated with 4 server CPUs in the first smoke test | smoke test only | to profile |
| C4 | GC pauses with default flags | Target B | none yet | to measure |
| C5 | Too few threads by default on small CPUs | Defaults, with c = logical CPUs the JVM may use: tick threads = 1 if c/2 ≤ 4, else c/8; chunk workers = 1 if c ≤ 7, 2 if c ≤ 9, else c/4. So up to 7 CPUs Folia runs 1 tick thread and 1 chunk worker; one thread generates all new terrain while other cores idle | Server logs: 2 and 4 pinned CPUs both print "1 worker threads" and "1 target thread(s)"; `OSNuma` here falls back to `availableProcessors()` (checked: `OSNuma$NoOp`); formulas in `TickRegions.getTickThreads` and `MoonriseCommon.adjustWorkerThreads` | to measure: rerun chunks with more workers |
