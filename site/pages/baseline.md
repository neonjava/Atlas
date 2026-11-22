# Baseline

The **baseline** is unmodified Folia for Minecraft 26.3, built from upstream
commit `eca40f9` (git tag `baseline-folia-26.3`) with Java 25. Every later
change is measured against it, on the same machine with the same settings.

## Chunk loading

A bot is teleported six times to places where no world exists yet, so the
server must *generate* the terrain (the first teleport is a warm-up and is not
counted). The bot records when half, 90% and all of the chunks it should see
have arrived.

A *chunk* is a 16 × 16 column of the world. *View distance* is how many chunks
around the player the server sends, in each direction.

[[table chunks baseline fill50_s fill90_s fill100_s server_gen_rate_mean]]

[[chart chunks fill100_s baseline]]

## Flying through new land

A bot climbs above all terrain and flies in a straight line at 30 blocks per
second (about elytra speed) over land that does not exist yet, and records
each second how much of its view is there.

[[table fly baseline fly_view_pct_median fly_view_pct_min fly_corrections server_gen_rate_mean]]

[[chart fly fly_view_pct_median baseline]]

## Other scenarios

TPS, garbage collection, crowds and idle cost are measured after the chunk
baseline. Their tables appear here when the runs are done.

[[table gc baseline tick_p99_ms tick_max_ms stw_pause_max_ms stw_pause_total_ms_per_min]]

[[table crowd baseline mspt_mean mspt_p99 tick_p99_ms]]
