Atlas
=====

Atlas is an **experimental fork of [Folia](https://github.com/PaperMC/Folia)**,
the Minecraft server that splits the world into regions and ticks them in
parallel. Atlas tries to make Folia faster in three places:

1. **New chunks load faster**, also on small CPUs (2 to 4 cores), so render
   distance stays smooth.
2. **TPS stays at 20**, without big drops when Java cleans up memory
   (garbage collection pauses).
3. **One crowded region keeps its TPS** when many players stand together.

Some fixes may use Rust (a fast, memory-safe language) for self-contained heavy
work, but only where a measurement shows that Rust wins. Other fixes are plain
Java changes or JVM settings. Every claim comes with before/after numbers from
the same machine and settings, and failed ideas are documented too.

**Documentation site:** <https://neonjava.github.io/Atlas/>

## Current status

Early work. Phase 1 (honest baseline measurements of unmodified Folia) is in
progress. There are **no proven speedups yet**. See
[`docs/STATUS.md`](docs/STATUS.md) for the current state,
[`docs/HOLES.md`](docs/HOLES.md) for the list of slow spots being worked on, and
[`docs/JOURNAL.md`](docs/JOURNAL.md) for a dated log of everything done.

Atlas tracks Folia's `ver/26.3.x` branch (Minecraft 26.3, Java 25).

## Building

You need **JDK 25** and Git. Rust is only needed if you build the native
library (see `native/` once it exists).

```bash
git clone https://github.com/neonjava/Atlas.git
cd Atlas
./gradlew applyAllPatches
./gradlew createPaperclipJar
```

The server jar is written to `folia-server/build/libs/`.

Do not build on an exFAT or FAT drive: the build creates about 90,000 small
files, and with exFAT's large clusters that used close to 100 GB of disk here.
Build on a Linux filesystem (ext4, btrfs, xfs).

## Running

```bash
java -Xms4G -Xmx4G -jar folia-server/build/libs/folia-paperclip-*.jar --nogui
```

Accept the EULA in `eula.txt` on first start. Folia (and so Atlas) only loads
plugins marked `folia-supported: true`. Read Folia's own notes on plugin
compatibility and thread settings in [`FOLIA.md`](FOLIA.md).

## Benchmarks

The benchmark harness lives in [`bench/`](bench/) and the bot client in
[`tools/atlas-bot/`](tools/atlas-bot/). How to run them:
[`docs/BENCHMARKS.md`](docs/BENCHMARKS.md).

## Licence

Atlas keeps Folia's licence. Folia's patches are under the licence in
[`PATCHES-LICENSE`](PATCHES-LICENSE); the server as a whole is GPL-3.0. New code
in Atlas is GPL-3.0.

Not affiliated with Mojang or Microsoft.
