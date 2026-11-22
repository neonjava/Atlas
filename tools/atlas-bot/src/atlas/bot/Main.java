/*
 * Main — command line entry for the Atlas benchmark bot swarm.
 *
 * Why: benchmark scripts start N bots with one command and get one CSV file.
 * Done: option parsing, staggered joins, fixed run duration, clean shutdown.
 * Not done: no interactive control; the harness steers bots through server
 * console commands (for example /tp) instead.
 *
 * Usage: java -cp <classes> atlas.bot.Main --ids <packet-ids.properties>
 *        --events <out.csv> [--host 127.0.0.1] [--port 25565] [--count 1]
 *        [--prefix bot] [--mode idle|fly|wander] [--speed 20] [--view 12]
 *        [--wander-radius 32] [--join-interval-ms 200] [--duration-s 60]
 *        [--chunks-per-tick 64] [--seed 1] [--flyers 0]
 *
 * --flyers K makes bots 0..K-1 fly outward (fresh terrain) whatever --mode is.
 *
 * Part of Atlas. GPL-3.0.
 */
package atlas.bot;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class Main {
    /** Default tick scheduler threads: bots only build small packets on it. */
    private static final int TICKER_THREADS = 2;
    /** The vanilla client caps desired chunks per tick at 64 (PlayerChunkSender clamps to it). */
    private static final float MAX_CHUNKS_PER_TICK = 64.0F;

    static final class Options {
        Path ids;
        Path events;
        String host = "127.0.0.1";
        int port = 25565;
        int count = 1;
        String prefix = "bot";
        Bot.Mode mode = Bot.Mode.IDLE;
        double speedBlocksPerSecond = 20.0;
        int viewDistance = 12;
        double wanderRadius = 32.0;
        long joinIntervalMs = 200;
        long durationSeconds = 60;
        float chunksPerTick = MAX_CHUNKS_PER_TICK;
        long seed = 1;
        int flyers;
    }

    private Main() {
    }

    public static void main(final String[] args) throws Exception {
        Options opt = parse(args);
        PacketIds ids = new PacketIds(opt.ids);
        ScheduledExecutorService ticker = Executors.newScheduledThreadPool(TICKER_THREADS, Thread.ofPlatform().daemon().factory());
        List<Thread> threads = new ArrayList<>();
        try (EventLog log = new EventLog(opt.events)) {
            log.log("main", "start", ids.versionName, opt.count);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(opt.durationSeconds);
            for (int i = 0; i < opt.count && System.nanoTime() < deadline; i++) {
                threads.add(Thread.ofVirtual().start(new Bot(opt, i, ids, log, ticker)));
                Thread.sleep(opt.joinIntervalMs);
            }
            long remainingMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
            if (remainingMs > 0) {
                Thread.sleep(remainingMs);
            }
            log.log("main", "stop", opt.durationSeconds, threads.size());
        }
        ticker.shutdownNow();
        System.exit(0);
    }

    private static Options parse(final String[] args) {
        Options o = new Options();
        for (int i = 0; i < args.length; i += 2) {
            if (i + 1 >= args.length) {
                throw new IllegalArgumentException("missing value for " + args[i]);
            }
            String v = args[i + 1];
            switch (args[i]) {
                case "--ids" -> o.ids = Path.of(v);
                case "--events" -> o.events = Path.of(v);
                case "--host" -> o.host = v;
                case "--port" -> o.port = Integer.parseInt(v);
                case "--count" -> o.count = Integer.parseInt(v);
                case "--prefix" -> o.prefix = v;
                case "--mode" -> o.mode = Bot.Mode.valueOf(v.toUpperCase());
                case "--speed" -> o.speedBlocksPerSecond = Double.parseDouble(v);
                case "--view" -> o.viewDistance = Integer.parseInt(v);
                case "--wander-radius" -> o.wanderRadius = Double.parseDouble(v);
                case "--join-interval-ms" -> o.joinIntervalMs = Long.parseLong(v);
                case "--duration-s" -> o.durationSeconds = Long.parseLong(v);
                case "--chunks-per-tick" -> o.chunksPerTick = Float.parseFloat(v);
                case "--seed" -> o.seed = Long.parseLong(v);
                case "--flyers" -> o.flyers = Integer.parseInt(v);
                default -> throw new IllegalArgumentException("unknown option " + args[i]);
            }
        }
        if (o.ids == null || o.events == null) {
            throw new IllegalArgumentException("--ids and --events are required");
        }
        return o;
    }
}
