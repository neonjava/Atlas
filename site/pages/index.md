# Atlas: making a Minecraft server faster, in the open

**Atlas** is an experimental copy ("fork") of [Folia](https://github.com/PaperMC/Folia),
a Minecraft server that splits the world into **regions** (groups of nearby
chunks) and runs them at the same time on different CPU cores.

Atlas tries to fix three things, and shows every measurement along the way:

1. **New chunks should load faster**, even on small computers with 2 to 4
   cores, so that the world appears smoothly around a player.
2. **TPS should stay at 20.** TPS means *ticks per second*: the server
   updates the world 20 times a second. When Java stops to clean up memory
   (a *garbage collection pause*), ticks can be late.
3. **One crowded region should keep up** when many players stand together.

Some fixes may use **Rust** (a fast programming language) for heavy,
self-contained work, but only where a measurement shows Rust really wins.
Other fixes are normal Java changes or settings.

## Where things stand

The first step is an honest **baseline**: measuring unmodified Folia on one
machine, with fake players ("bots"), many times. No speedup is claimed until
there are before-and-after numbers from the same machine and settings.

### Latest baseline numbers: chunk loading

How long a player waits after a teleport until every chunk they should see
has arrived (view distance 12, server limited to 4 CPU threads):

[[cards chunks baseline vd12-t4 fill100_s fill90_s fly_view_pct_median]]

More on the [Baseline](baseline.html) and [Benchmarks](benchmarks.html) pages,
what went wrong on [Failed Experiments](failed.html), and every step in
[The Story](story.html).
