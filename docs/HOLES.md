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
| C5 | Too few chunk worker threads on small CPUs | Moonrise picks `workers = cores/2`, then 1 if that is ≤ 3, else 2 if ≤ 4, else half again. Folia picks `tick threads = cores/2`. With one busy region, extra tick threads idle while one worker generates all new terrain | Server log with 2 CPUs: "Paper is using 1 worker threads, 1 I/O threads" and "EDF Folia scheduler with initial 1 target thread(s)"; formula in `MoonriseCommon.java:43-47` and `TickRegions.java:46-47` | to measure: rerun chunks with more workers |
