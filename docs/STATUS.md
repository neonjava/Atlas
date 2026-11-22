# Status

Last updated: 2026-11-23 00:28 (IST).

- **Phase 1 (baseline): in progress.** The untouched Folia 26.3 build is
  tagged `baseline-folia-26.3` and frozen as a jar. Bot client, server probe
  and harness work. The chunk-loading baseline (45 runs) is about half done;
  its results arrive in a follow-up PR.
- **First hole found (C5):** on machines with up to 7 CPUs Folia uses one chunk
  worker thread; at view distance 8, 4 CPUs fill a new view no faster than 2
  CPUs (10.5 s vs 10.3 s median). Experiment script ready
  (`bench/experiments/c5-workers.sh`).
- **Phase 2 (fixes): not started.** No speedups are claimed.
- **Website:** published from `main` by GitHub Actions.
