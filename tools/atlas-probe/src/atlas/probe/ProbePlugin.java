/*
 * ProbePlugin — server-side measurement plugin for Atlas benchmarks.
 *
 * Why: Folia's /tps shows 15-second averages, which hide single slow ticks
 * (for example a GC pause). The probe writes every tick interval of a watched
 * region and of the global region, plus Folia's own per-region tick reports,
 * to a CSV file that the analysis scripts read.
 * Done: per-tick intervals (watched region, global region); every 15 s for
 * every region: MSPT average/median/p95/p99/max, TPS, utilisation,
 * players, chunks, entities; raw MSPT samples for the watched region; server
 * chunk generate and load rates.
 * Not done: no in-game UI; only one watched location at a time.
 *
 * CSV columns: t_ms,kind,region,v1..v10 (meaning depends on kind; see
 * bench/README.md). Controlled from the console:
 *   atlasprobe start <csv-path>   atlasprobe watch <x> <z>   atlasprobe stop
 *
 * It reads Folia internals (TickRegionScheduler, ThreadedRegionizer), so it is
 * compiled against the server jar it measures.
 *
 * Part of Atlas. GPL-3.0.
 */
package atlas.probe;

import ca.spottedleaf.common.time.TickData;
import ca.spottedleaf.moonrise.patches.chunk_system.scheduling.task.ChunkFullTask;
import io.papermc.paper.threadedregions.RegionizedServer;
import io.papermc.paper.threadedregions.ThreadedRegionizer;
import io.papermc.paper.threadedregions.TickRegionScheduler;
import io.papermc.paper.threadedregions.TickRegions;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import net.minecraft.server.level.ServerLevel;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.plugin.java.JavaPlugin;

public final class ProbePlugin extends JavaPlugin {
    /** Folia keeps 15-second tick reports; dumping at the same period gives back-to-back windows. */
    private static final long REPORT_PERIOD_NANOS = TimeUnit.SECONDS.toNanos(15);
    private static final long REPORT_PERIOD_TICKS = 300L;
    private static final double NANOS_PER_MS = 1.0E6;
    private static final long NANOS_PER_US = 1_000L;
    private static final int BLOCKS_PER_CHUNK_SHIFT = 4;
    private static final double MEDIAN = 0.50;
    private static final double P95 = 0.95;
    private static final double P99 = 0.99;
    private static final double MAX = 1.0;
    private static final String HEADER = "t_ms,kind,region,v1,v2,v3,v4,v5,v6,v7,v8,v9,v10\n";

    private final Object writeLock = new Object();
    private BufferedWriter out;
    private long startNanos;
    private ScheduledTask globalTick;
    private ScheduledTask globalReport;
    private ScheduledTask watchTask;

    @Override
    public boolean onCommand(final CommandSender sender, final Command command, final String label, final String[] args) {
        if (args.length == 2 && args[0].equals("start")) {
            start(Path.of(args[1]));
        } else if (args.length == 3 && args[0].equals("watch")) {
            watch(Bukkit.getWorlds().getFirst(), Integer.parseInt(args[1]), Integer.parseInt(args[2]));
        } else if (args.length == 1 && args[0].equals("stop")) {
            stop();
        } else {
            return false;
        }
        sender.sendMessage("atlasprobe " + String.join(" ", args) + ": ok");
        return true;
    }

    @Override
    public void onDisable() {
        stop();
    }

    private void start(final Path file) {
        stop();
        try {
            synchronized (this.writeLock) {
                this.out = Files.newBufferedWriter(file, StandardCharsets.UTF_8);
                this.out.write(HEADER);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        this.startNanos = System.nanoTime();
        long[] last = {System.nanoTime()};
        this.globalTick = Bukkit.getGlobalRegionScheduler().runAtFixedRate(this, task -> {
            long now = System.nanoTime();
            row(now, "gtick", "global", (now - last[0]) / NANOS_PER_US);
            last[0] = now;
        }, 1L, 1L);
        this.globalReport = Bukkit.getGlobalRegionScheduler().runAtFixedRate(this, task -> reportAll(), REPORT_PERIOD_TICKS, REPORT_PERIOD_TICKS);
    }

    private void watch(final World world, final int blockX, final int blockZ) {
        if (this.watchTask != null) {
            this.watchTask.cancel();
        }
        long[] last = {System.nanoTime(), System.nanoTime()};
        String label = blockX + ":" + blockZ;
        this.watchTask = Bukkit.getRegionScheduler().runAtFixedRate(this, world,
            blockX >> BLOCKS_PER_CHUNK_SHIFT, blockZ >> BLOCKS_PER_CHUNK_SHIFT, task -> {
                long now = System.nanoTime();
                row(now, "tick", label, (now - last[0]) / NANOS_PER_US);
                last[0] = now;
                if (now - last[1] >= REPORT_PERIOD_NANOS) {
                    last[1] = now;
                    dumpWatchedRaw(now, label);
                }
            }, 1L, 1L);
    }

    private void dumpWatchedRaw(final long now, final String label) {
        ThreadedRegionizer.ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> region =
            TickRegionScheduler.getCurrentRegion();
        if (region == null) {
            return;
        }
        TickData.TickReportData report = region.getData().getRegionSchedulingHandle().getTickReport15s(now);
        if (report == null) {
            return;
        }
        // rawData() is sorted, so these rows describe the distribution, not tick order.
        for (long nanos : report.timePerTickData().rawData()) {
            row(now, "mspt_raw", label, nanos / NANOS_PER_MS);
        }
    }

    private void reportAll() {
        long now = System.nanoTime();
        List<ThreadedRegionizer.ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData>> regions = new ArrayList<>();
        for (World w : Bukkit.getWorlds()) {
            ServerLevel level = ((CraftWorld) w).getHandle();
            level.regioniser.computeForAllRegions(regions::add);
        }
        for (var region : regions) {
            TickData.TickReportData report = region.getData().getRegionSchedulingHandle().getTickReport15s(now);
            if (report == null) {
                continue;
            }
            var center = region.getCenterChunk();
            var stats = region.getData().getRegionStats();
            String id = region.regioniser.world.dimension().identifier().getPath() + "@"
                + (center == null ? "?" : center.x() + ":" + center.z());
            reportRow(now, id, report, stats.getPlayerCount(), stats.getChunkCount(), stats.getEntityCount());
        }
        TickData.TickReportData global = RegionizedServer.getGlobalTickData().getTickReport15s(now);
        if (global != null) {
            reportRow(now, "global", global, 0, 0, 0);
        }
        row(now, "chunk_rate", "server", ChunkFullTask.genRate(now), ChunkFullTask.loadRate(now));
    }

    private void reportRow(final long now, final String id, final TickData.TickReportData r, final int players, final int chunks, final int entities) {
        // Percentiles come from the sorted raw samples. The library's
        // segment5PercentWorst()/segment1PercentWorst() accessors hold each
        // other's data (argument order mismatch in TickData 1.3.2), so they are
        // not used.
        TickData.SegmentedAverage mspt = r.timePerTickData();
        long[] sorted = mspt.rawData();
        row(now, "report", id,
            mspt.segmentAll().average() / NANOS_PER_MS,
            percentile(sorted, MEDIAN) / NANOS_PER_MS,
            percentile(sorted, P95) / NANOS_PER_MS,
            percentile(sorted, P99) / NANOS_PER_MS,
            percentile(sorted, MAX) / NANOS_PER_MS,
            r.tpsData().segmentAll().average(),
            r.utilisation(),
            players, chunks, entities);
    }

    /** Nearest-rank percentile of an ascending array; q in [0, 1]. */
    private static long percentile(final long[] sorted, final double q) {
        if (sorted.length == 0) {
            return 0L;
        }
        int rank = (int) Math.ceil(q * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, rank))];
    }

    private void row(final long now, final String kind, final String region, final Object... values) {
        StringBuilder sb = new StringBuilder(64);
        sb.append((now - this.startNanos) / (long) NANOS_PER_MS).append(',').append(kind).append(',').append(region);
        for (Object v : values) {
            sb.append(',').append(v);
        }
        sb.append('\n');
        synchronized (this.writeLock) {
            if (this.out == null) {
                return;
            }
            try {
                this.out.write(sb.toString());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    private void stop() {
        for (ScheduledTask t : new ScheduledTask[] {this.globalTick, this.globalReport, this.watchTask}) {
            if (t != null) {
                t.cancel();
            }
        }
        this.globalTick = null;
        this.globalReport = null;
        this.watchTask = null;
        synchronized (this.writeLock) {
            if (this.out != null) {
                try {
                    this.out.close();
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                this.out = null;
            }
        }
    }
}
