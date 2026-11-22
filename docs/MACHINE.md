# The machine

All Atlas numbers so far come from this one machine. Numbers from different
machines must never be compared with each other.

## Hardware and OS

| Item | Value |
|---|---|
| CPU | Intel Core i7-13650HX (laptop chip), 14 cores / 20 threads |
| Core layout | 6 performance cores (logical CPUs 0-11, 2 threads each, max 2.6 GHz as reported) and 8 efficiency cores (logical CPUs 12-19, max 1.9 GHz) |
| L3 cache | 24 MiB |
| RAM | 23 GiB, plus 8 GiB zram swap |
| OS | Fedora Linux 44 Workstation, kernel 7.0.12 |
| Repo disk | External SSD `/dev/sda1`, 932 GB, **exFAT**, mounted `rw,nosuid,nodev` (not `noexec`) |
| Home disk | NVMe `/home`, 198 GB |
| JDK | OpenJDK 25.0.3 (Red Hat build, system package) |
| Rust | rustc 1.99.0, cargo 1.99.0 (rustup, user space) |

The machine is a laptop that is also used for normal work, and the owner's
own Minecraft client may be running during tests. Laptop chips can slow down
when hot, so every benchmark is repeated at least 5 times and reported as a
median with spread, never as a best run.

## Limits for everything Atlas runs

| Resource | Limit | How it is enforced |
|---|---|---|
| RAM | at most 50% of 23 GiB, about 11.5 GiB in total | builds run in `systemd-run --user --scope -p MemoryMax=11G`; the server runs alone with `MemoryMax` set by the harness (default 8G); bots get their own small cap. Only one server process at a time. |
| CPU | at most 18 of 20 logical CPUs (nproc - 2) | Gradle `--max-workers=18`, `CPUQuota=1800%`; benchmark processes are pinned with `taskset` (see below) and CPUs 18-19 are never used. |
| SSD free space | keep at least max(30 GB, 15% of 932 GB) = **140 GB** free | `bench/lib/guard.sh` checks before every build and benchmark and refuses to start below the limit. |
| Home free space | `~/atlas-build` at most 30 GB | same guard script. |

## CPU pinning for benchmarks

The two core types run at different speeds, so a benchmark that is free to
move between them gives noisy numbers. The harness pins:

- the **server** to logical CPUs taken in this order: 0, 2, 4, 6, 8, 10 (one
  thread per performance core), then 1, 3, 5, 7, 9, 11. "2 threads" means
  CPUs {0,2}, "4 threads" means {0,2,4,6}, "8 threads" means {0,2,4,6,8,10,1,3}.
- the **bots** to efficiency cores 12-17.
- CPUs 18-19 stay free for the desktop.

This is how "small CPU" tests (2 to 4 cores) are imitated on a big chip. It is
not a perfect copy of a real small CPU (the cache and memory are still those
of the big chip), and the docs say so where it matters.

## Filesystem notes

The SSD is exFAT. exFAT has no Unix permissions and no symlinks. With the
mount options here every file shows as executable (`fmask=0022`), so scripts
run. Git is set to `core.fileMode=false` and `core.symlinks=false` in this
clone. If a build step fails because of the filesystem, build output and
caches move to `~/atlas-build` (at most 30 GB) and this page says so.

### exFAT cluster size: builds moved to `~/atlas-build`

The exFAT SSD uses **1 MiB clusters**, so every file takes at least 1 MiB on
disk. Applying Folia's patches creates about 90,000 files (1.3 GB of real
data), which used **97 GB** of the SSD. That broke the free-space rule, so:

- The git repository (and all history) stays on the SSD at
  `/run/media/neonjava/neonjava/Atlas`.
- Builds run in **git worktrees** under `~/atlas-build` on the NVMe `/home`
  disk (ext4-class filesystem, small blocks): `~/atlas-build/baseline` holds
  the untouched Folia baseline (tag `baseline-folia-26.3`), and
  `~/atlas-build/work` holds the branch being worked on. Commits made there
  are stored in the SSD repository.
- Benchmark worlds and logs live in `~/atlas-build/runs`; only result data
  files (CSV/JSON) are committed.
- Gradle's cache stays in its normal place, `~/.gradle`.
- `/home` had 34 GB free at the start, so the guard also refuses to start when
  `/home` drops below 8 GB free, on top of the 30 GB cap for `~/atlas-build`.
