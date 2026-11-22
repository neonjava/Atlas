# How to run the benchmarks

Everything here is a script; nothing needs typing into a game. All numbers on
the website come from the files these scripts write.

## What you need

- Linux, JDK 25, Python 3 with PyYAML, `systemd-run --user` and `taskset`
  (both standard on Fedora).
- About 15 GB free on a normal Linux filesystem for builds and test worlds
  (not exFAT: see [MACHINE.md](MACHINE.md)).
- A server jar: build Atlas (see the README) or use the frozen baseline jar.

## The pieces

| Piece | What it does |
|---|---|
| `tools/atlas-bot/` | The bot client: fake players that join, move and measure how fast chunks arrive. No dependencies. |
| `tools/atlas-probe/` | A small server plugin that writes the time of every tick and Folia's own per-region reports to a CSV file. |
| `bench/lib/guard.sh` | Stops a run if disk space or the build folder would cross the limits. |
| `bench/lib/server.sh` | Starts one server with fixed settings, pinned CPUs and a memory cap; sends console commands; stops it; packs results. |
| `bench/lib/load.sh` | One "players doing things" run, used by the load scenarios. |
| `bench/scenarios/*.sh` | The scenarios (below). |
| `bench/analyze/*.py` | Turns run archives into `runs.csv` and `summary.json` (median and spread per configuration). |
| `site/build.py` | Builds the website from those files. |

## Scenarios

| Script | Question | Default matrix |
|---|---|---|
| `chunks.sh` | How fast do *new* chunks reach a player after a teleport? | view distance 8 / 12 / 16 × server threads 2 / 4 / 8 |
| `fly.sh` | Is the view full while flying over new land at 30 blocks/s? | same matrix |
| `tps.sh` | How steady are ticks with spread-out players over a long time? | 10 / 50 / 100 bots, 30 minutes measured |
| `gc.sh` | How long are garbage-collection pauses, and do ticks drop? | heap 2 / 4 / 6 GB × G1 / ZGC / Shenandoah |
| `crowd.sh` | Does one crowded region keep up? Then the same players spread out. | 50 / 100 bots |
| `idle.sh` | What does an empty server cost, and what does each standing player cost on the network? | 0 / 10 bots |

Each configuration runs **5 times** by default (third argument). Every run
starts from a fresh world with the same seed (`12345`). Every run has a
warm-up that is not counted. Reports show the **median** of the runs and the
spread (lowest and highest run, and the middle half), never the best run.

```bash
# example: chunk loading for one jar, labelled "baseline", 5 runs per config
bench/scenarios/chunks.sh ~/atlas-build/jars/baseline.jar baseline 5
python3 bench/analyze/chunks.py bench/results/chunks/baseline
python3 site/build.py   # writes site/_build/
```

Scripts skip runs whose archive already exists, so a stopped benchmark can be
started again and continues where it stopped.

## Settings that differ from a default Folia server

Only what the bots need to join and move is changed (`bench/lib/configure.py`):

| Setting | Value | Why |
|---|---|---|
| `online-mode` | false | bots have no Mojang accounts |
| `white-list`, `enforce-secure-profile` | false | same |
| `gamemode` / `force-gamemode` | creative / true | bots fly and never take damage |
| `allow-flight` | true | bots fly |
| `spawn-protection` | 0 | not needed |
| `max-players` | 500 | room for 100+ bots |
| `level-seed` | 12345 | same terrain every run |
| `view-distance` | per scenario | the thing being tested |
| `spigot.yml` `moved-too-quickly-multiplier` | 100 | bots fly faster than the default speed check allows |

All performance settings (thread counts, chunk send and load rates, GC)
stay at their defaults unless a scenario sets them on purpose, and that is
recorded in each run's `meta.json`.

## CPU pinning and memory caps

See [MACHINE.md](MACHINE.md). The server runs on chosen performance cores
(`taskset`), the bots on efficiency cores, and both under `systemd-run`
memory caps. "2 threads" means the server can only use 2 logical CPUs, which
imitates a small machine. Folia sizes its thread pools from the CPUs it can
see, so this also shrinks its default pools, as on a real small machine.

## What each file in a run archive holds

`bench/results/<scenario>/<label>/<config>/runN.tar.gz` contains:

- `meta.json`: jar checksum, CPUs, JVM flags, view distance, seed, JDK, kernel.
- `marks.csv`: wall-clock marks for the measured window, server CPU time.
- `bot*.csv`: bot events. Columns `t_ms,bot,event,a,b`. Events: `epoch`
  (wall clock of t=0), `joined` (ms to join), `teleport` (x, z),
  `fill50`/`fill90`/`fill100` (ms after teleport, chunks in view),
  `view_permille` (share of the view present, ‰), `rate` (chunks and bytes
  received in the last second), `rtt_us` (round trip through the server).
- `probe.csv`: server events. Columns `t_ms,kind,region,v1..v10`. Kinds:
  `epoch`, `gtick` / `tick` (microseconds since the previous global /
  watched-region tick), `report` (every 15 s per region: MSPT mean, p50, p95,
  p99, max in ms, TPS, utilisation, players, chunks, entities), `mspt_raw`
  (sorted MSPT samples of the watched region), `chunk_rate` (server chunk
  generations and loads per second).
- `gc.log`, `safepoint.log`: JVM logs (size-capped).
- `server.log`: the server console.
