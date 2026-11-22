# Baseline

The **baseline** is unmodified Folia for Minecraft 26.3, built from upstream
commit `eca40f9` (git tag `baseline-folia-26.3`) with Java 25. Every later
change is measured against it, on the same machine with the same settings.

## Chunk loading

A bot is teleported six times to places where no world exists yet, so the
server must *generate* the terrain (the first teleport is a warm-up and is not
counted). The bot records when half, 90% and all of the chunks it should see
have arrived. A second bot flies in a straight line through new land at
30 blocks per second and records each second how much of its view is there.

A *chunk* is a 16 × 16 column of the world. *View distance* is how many chunks
around the player the server sends, in each direction.

[[table chunks baseline fill50_s fill90_s fill100_s fly_view_pct_median fly_view_pct_min server_gen_rate_mean]]

[[chart chunks fill100_s baseline]]

[[chart chunks fly_view_pct_median baseline]]

## Other scenarios

TPS, garbage collection, crowds and idle cost are measured after the chunk
baseline. Their tables appear here when the runs are done.

[[table gc baseline tick_p99_ms tick_max_ms gc_pause_max_ms gc_pause_total_ms_per_min]]

[[table crowd baseline mspt_mean mspt_p99 tick_p99_ms]]
