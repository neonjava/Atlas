# Journal

Times are the machine clock (IST, UTC+5:30), format `YYYY-MM-DD HH:MM`.

- 2026-11-22 22:50 — Checked GitHub: neonjava/Atlas is already a GitHub fork of
  PaperMC/Folia, identical to upstream `ver/26.3.x`, with no `main` branch.
  Cloned it to the SSD, added `upstream` (push disabled), created `main` from
  `ver/26.3.x`, made it the default branch, enabled Issues, added topics, and
  enabled GitHub Pages (build type: GitHub Actions). The description was
  already set.
- 2026-11-22 22:55 — Confirmed from the repo: `gradle.properties` says
  `mcVersion=26.3`, and `build.gradle.kts` sets the Java toolchain to 25. The
  system JDK is OpenJDK 25.0.3, so no extra JDK is needed.
- 2026-11-22 22:58 — Detected the machine (see MACHINE.md). The SSD is exFAT;
  trying the build on it first.
- 2026-11-22 23:00 — Bot tool: mineflayer (MIT) is the usual choice, but its
  data package (minecraft-data 3.119.0) only goes up to Minecraft 26.1, so it
  cannot join a 26.3 server. A translation proxy (ViaVersion/ViaProxy) would
  add its own work in the middle of what we measure. Decision: write a small
  bot client in `tools/atlas-bot` that reads packet IDs from the server's own
  generated `packets.json` report, so it follows the server version without
  hand-copied tables.
- 2026-11-22 23:20 — First `applyAllPatches` on the SSD worked, but the disk
  guard then blocked the build: free space went from 180 GB to 83 GB. Cause:
  exFAT with 1 MiB clusters; 90k generated files used 97 GB for 1.3 GB of data.
  Deleted the generated (git-ignored) files, back to 180 GB free. Builds now
  run in git worktrees under `~/atlas-build` (see MACHINE.md). Tagged the
  untouched upstream commit `eca40f9` as `baseline-folia-26.3`.
- 2026-11-22 23:25 — The owner asked for every change to be committed and
  pushed to Atlas, with PRs opened on neonjava/Atlas for them to review and
  merge. So I do not merge PRs myself: I open them against Atlas `main`, keep
  CI green, and leave the merge to the owner. Work that depends on an unmerged
  PR is stacked on that PR's branch and says so in its description.
- 2026-11-22 23:25 — Folia's jar task in this version is `createPaperclipJar`
  (not `createMojmapPaperclipJar`); README fixed.
- 2026-11-22 23:00 — Built the unmodified baseline jar (`createPaperclipJar`,
  2 min 21 s) and froze a copy as `~/atlas-build/jars/baseline.jar`
  (sha256 `c6d24832…`).
- 2026-11-22 23:05 — Bot client works against 26.3: a single bot joined in
  4.3 s and, at view distance 8 on 4 pinned CPUs, its 257-chunk view was full
  12.2 s after joining a fresh world; a console `/tp` to new land was full
  after 11.7 s (about 22 generated chunks per second). These are smoke-test
  numbers, not the baseline.
- 2026-11-22 23:10 — Found while building the harness: in this Folia build a
  console command sent the moment the server prints "Done" can fail with a
  NullPointerException (the console command source has no level yet), and the
  `/stop` is lost. The harness now waits 5 s after "Done". Not reported
  upstream (Atlas only works in its own repository).
- 2026-11-22 23:15 — Found while writing the probe: in the timing library
  used by Folia (`ca.spottedleaf.common.time.TickData`, leafpile 1.3.2), the
  "worst 5%" and "worst 1%" fields of a tick report hold each other's values
  (the arguments are passed in a different order than the record declares).
  Folia's own health output inherits this. The Atlas probe computes p50, p95,
  p99 and max itself from the raw samples, so Atlas numbers do not depend on it.
- 2026-11-22 23:20 — Started the full chunk-loading baseline: view distance
  8 / 12 / 16 × 2 / 4 / 8 server threads × 5 runs, 6 teleports per run (first
  is warm-up) plus 90 s of flight.
- 2026-11-22 23:27 — Wrote the website generator (`site/build.py`): pages in
  Markdown, numbers and charts only from `bench/results/**/summary.json`, the
  Story from this journal and Updates from git history. Background drawn with
  CSS gradients and a seeded SVG star layer.
- 2026-11-22 23:30 — Read how Folia sizes its pools (c = CPUs the JVM may
  use): tick threads = 1 if c/2 ≤ 4, else c/8; chunk workers = 1 if c ≤ 7,
  2 if c ≤ 9, else c/4. The 2- and 4-CPU runs both log "1 worker threads"
  and "1 target thread(s)"; a direct test shows the CPU count here comes from
  `availableProcessors()`, which respects `taskset`. So on machines with up
  to 7 CPUs one thread generates all new terrain. Added as candidate C5; an
  experiment with more workers comes after the baseline.
- 2026-11-22 23:33 — Caught a bad metric before trusting it: in the chunk
  baseline the flying bot reported a 100% full view while receiving zero new
  chunks after spawn. It was flying at spawn height, ran into a hill, and the
  server silently held it in place every tick. Fixes: flyers now climb to
  y=300 before flying; the bot logs server position corrections each second,
  so a stuck bot shows up in the data; flying moved into its own scenario
  (`fly.sh`). The teleport metrics of the chunk baseline are not affected;
  its flight data is ignored by the analysis and not shown anywhere.
- 2026-11-22 23:33 — Lesson: bash reads a running script from disk as it
  goes, so a running scenario script is now only ever replaced atomically
  (write a new file, then rename), never edited in place.
- 2026-11-23 00:07 — First baseline evidence for C5 (view distance 8,
  5 runs each, median time to a full view after a teleport): 2 CPUs 10.3 s,
  4 CPUs 10.5 s, 8 CPUs 5.6 s. Doubling CPUs from 2 to 4 gives nothing,
  because both use one chunk worker; 8 CPUs (two workers) nearly halves the
  time. Generation follows the worker count, not the CPU count. C5 becomes
  the first hole to work on after the baseline finishes.
- 2026-11-23 00:38 — The owner asked for PRs to be merged when CI is green
  and the numbers support them, and for Rust to be used where a hole calls for
  it (never unused code). Merged PR #1 (harness, bot, probe, docs, website) with
  a merge commit after CI passed. The first Pages deploy was rejected because
  the github-pages environment only allowed the old default branch
  `ver/26.3.x`; added `main` to its allowed branches and redeployed. The site
  is live at https://neonjava.github.io/Atlas/.
- 2026-11-23 02:39 — Chunk baseline finished (45 runs). Median time to a
  full view after a teleport to new land: vd8 10.3 / 10.5 / 5.6 s, vd12
  19.3 / 19.0 / 9.5 s, vd16 32.1 / 31.4 / 15.1 s for 2 / 4 / 8 CPUs. Runs
  agree within about ±5%. The last run's script hung after finishing (an
  in-place edit made earlier in the night shifted bash's read position, so it
  re-ran a stray console write into a closed FIFO); all 45 archives are
  complete. Started the queue: profiling runs, then the C5 experiment.
- 2026-11-23 03:02 — Profiles done (JFR, not used for timing). Chunk
  generation (vd12, 4 CPUs): workers 89% of samples; self time is spread thin
  (aquifer 8.3%, surface rules ~10%, Perlin kernels ~4.6%). Load run (50
  spread bots + 4 flyers, 8 CPUs, 4 GB G1): the single default tick thread is
  saturated (≈10 TPS) and the heap stays almost full of live data, with G1
  pauses of 1.3-1.7 s. New candidates C6 (tick threads) and C7 (heap/GC).
- 2026-11-23 03:02 — Rust candidate R1 (issue #4): ported the Perlin
  volume kernel to Rust. 2,000 random volumes are bit-identical to the
  server's Java kernel; low-frequency volumes are 2.2x faster, high-frequency
  equal. FFM costs ~210 ns per call, JNI ~300 ns, so FFM is used. Because the
  kernel is only ~5% of generation CPU the end-to-end gain may be too small
  to see; an A/B on the same jar (native on/off) is queued and decides.
- 2026-11-23 05:48 — C5 experiment, chunk part (5 runs each, median time to
  a full view; baseline in brackets). Control at 4 CPUs matches the baseline
  (18.9 vs 19.0 s at vd12), so no drift. 2 CPUs + 2 workers: vd12 15.1 s
  (19.3), vd16 20.0 s (32.1). 4 CPUs + 3 workers: vd12 8.8 s (19.0), vd16
  13.4 s (31.4) — 2.2-2.3x faster; 4 workers no better. 8 CPUs + 6 workers:
  vd12 7.9 s (9.5), vd16 13.1 s (15.1). Cost: global tick p99 rose from ~52 ms
  to ~72 ms (2 CPUs) and ~95-100 ms (4 CPUs) during generation, because workers
  compete with the tick thread. At vd16 the fills now run at ~70 chunks/s,
  right at the 75/s per-player send limit (hole C1). Queued: workers at
  nice 10 (does the tick thread recover?) and a send-limit experiment.
- 2026-11-23 05:48 — Unprofiled load check (22 bots incl. 2 flyers, 4 CPUs,
  4 GB G1, default threads): MSPT mean 48.7 ms, worst 15 s window 12.8 TPS,
  and **Full GCs of 0.98-1.21 s**. Live data after a Full GC is 1.5-2.1 GB, so
  it is not a leak: G1's background marking starts too late for chunk
  generation's allocation bursts. Queued a GC comparison (G1, G1 with
  Aikar's flags, ZGC, Shenandoah) for hole C7.
