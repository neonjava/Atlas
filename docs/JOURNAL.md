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
- 2026-11-22 23:35 — Read how Folia sizes its pools: chunk workers are
  `cores/2`, cut to 1 when that is ≤ 3 (so 1 worker on a 2-, 4- or 6-CPU
  machine) and tick threads are `cores/2`. The CPU count comes from a native
  call that respects `taskset`: the 2-CPU runs log "1 worker threads" and
  "1 target thread(s)". So on small machines one thread generates all new
  terrain. Added as candidate C5; an experiment with more workers comes after
  the baseline.
