/*
 * Bot — one fake player for Atlas benchmarks: joins an offline-mode server,
 * answers what the server needs (keep-alive, teleports, chunk batches,
 * configuration), moves in a chosen pattern and measures chunk arrival.
 *
 * Why: no existing bot library supports Minecraft 26.3 (see docs/JOURNAL.md),
 * and benchmarks need many players without real clients.
 * Done: login, configuration, play; idle / fly / wander movement; metrics for
 * time-to-fill the view after a teleport, view coverage while flying, chunk
 * and byte rates, and round-trip time through the server tick (ping packet).
 * Not done: no physics (bots fly in creative mode), no chat, no entities, no
 * block or inventory actions, no encryption (offline mode only).
 *
 * Part of Atlas. GPL-3.0.
 */
package atlas.bot;

import atlas.bot.PacketIds.Phase;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

final class Bot implements Runnable {
    enum Mode { IDLE, FLY, WANDER }

    /** Client ticks run at the vanilla rate of 20 per second. */
    static final long TICK_MS = 50L;
    /** Vanilla clients send their position at least once per second even when still. */
    private static final int IDLE_POSITION_EVERY_TICKS = 20;
    /** Metrics (rates, view coverage, RTT) are logged once per second. */
    private static final int REPORT_EVERY_TICKS = 20;
    /** Handshake intent value for "login". */
    private static final int INTENT_LOGIN = 2;
    /** Bit flags in the teleport packet's "relative" set (net.minecraft.world.entity.Relative). */
    private static final int REL_X = 1;
    private static final int REL_Y = 1 << 1;
    private static final int REL_Z = 1 << 2;
    private static final int REL_Y_ROT = 1 << 3;
    private static final int REL_X_ROT = 1 << 4;
    /** Abilities packet flag for "flying". */
    private static final int ABILITY_FLYING = 2;
    /** Move packet flags: bit 0 on ground, bit 1 horizontal collision; bots are always airborne. */
    private static final int MOVE_FLAGS_AIRBORNE = 0;
    /** Client "main hand" value for right-handed (vanilla default). */
    private static final int MAIN_HAND_RIGHT = 1;
    /** All skin parts shown, like a default vanilla client. */
    private static final int SKIN_PARTS_ALL = 0x7F;
    /** Chat visibility "full" and particle status "all", vanilla defaults. */
    private static final int CHAT_FULL = 0;
    private static final int PARTICLES_ALL = 0;
    /** Fill thresholds reported after a teleport (fraction of the visible chunks). */
    private static final double[] FILL_THRESHOLDS = {0.5, 0.9, 1.0};
    private static final String[] FILL_EVENTS = {"fill50", "fill90", "fill100"};
    /** Wander mode picks a new random heading this often (ticks). */
    private static final int WANDER_TURN_EVERY_TICKS = 40;
    private static final double BLOCKS_PER_CHUNK = 16.0;

    private final Main.Options opt;
    private final String name;
    private final int index;
    private final PacketIds ids;
    private final EventLog log;
    private final ScheduledExecutorService ticker;
    private final Random random;
    private final Mode mode;

    private Connection conn;
    private volatile Phase phase = Phase.LOGIN;
    private volatile boolean spawned;
    private boolean sentLoaded;
    private ScheduledFuture<?> tickTask;
    private long connectStartMs;

    // Position state; written by the reader thread (teleports), read by the ticker.
    private final Object posLock = new Object();
    private double x;
    private double y;
    private double z;
    private float yaw;
    private float pitch;
    private double anchorX;
    private double anchorZ;
    private double heading;
    private int tickCount;

    // Chunk state, guarded by chunkLock.
    private final Object chunkLock = new Object();
    private final Set<Long> loadedChunks = new HashSet<>();
    private int serverViewDistance = -1;
    private Set<Long> fillTarget;
    private long fillStartMs;
    private int fillNext;
    private int chunksThisSecond;
    private long bytesThisSecond;

    Bot(final Main.Options opt, final int index, final PacketIds ids, final EventLog log, final ScheduledExecutorService ticker) {
        this.opt = opt;
        this.index = index;
        this.name = opt.prefix + index;
        this.ids = ids;
        this.log = log;
        this.ticker = ticker;
        this.random = new Random(opt.seed + index);
        // The first `flyers` bots fly; the rest use the main mode.
        this.mode = index < opt.flyers ? Mode.FLY : opt.mode;
        // Spread fly headings evenly with the golden angle so flying bots do not share chunks.
        this.heading = this.mode == Mode.FLY ? index * Math.PI * (3.0 - Math.sqrt(5.0)) : 0.0;
    }

    @Override
    public void run() {
        this.connectStartMs = this.log.nowMillis();
        try (Connection c = new Connection(this.opt.host, this.opt.port)) {
            this.conn = c;
            login();
            Connection.Packet p;
            while ((p = c.read()) != null) {
                handle(p);
            }
            this.log.log(this.name, "closed", "server", 0);
        } catch (IOException | RuntimeException e) {
            this.log.log(this.name, "error", sanitize(e.toString()), 0);
        } finally {
            if (this.tickTask != null) {
                this.tickTask.cancel(false);
            }
        }
    }

    private static String sanitize(final String s) {
        return s.replace(',', ';').replace('\n', ' ');
    }

    private void login() throws IOException {
        this.conn.send(this.ids.serverbound(Phase.HANDSHAKE, "intention"), new Wire.Out()
            .varInt(this.ids.protocolVersion).string(this.opt.host).u16(this.opt.port).varInt(INTENT_LOGIN));
        UUID offlineId = UUID.nameUUIDFromBytes(("OfflinePlayer:" + this.name).getBytes(StandardCharsets.UTF_8));
        this.conn.send(this.ids.serverbound(Phase.LOGIN, "hello"), new Wire.Out().string(this.name).uuid(offlineId));
        this.conn.flush();
    }

    private void send(final String packet, final Wire.Out body) throws IOException {
        this.conn.send(this.ids.serverbound(this.phase, packet), body);
    }

    private void handle(final Connection.Packet p) throws IOException {
        String packet = this.ids.clientboundName(this.phase, p.id());
        if (packet == null) {
            return;
        }
        ByteBuffer in = p.body();
        switch (this.phase) {
            case LOGIN -> handleLogin(packet, in);
            case CONFIGURATION -> handleConfiguration(packet, in);
            case PLAY -> handlePlay(packet, in, p.wireBytes());
            case HANDSHAKE -> throw new IllegalStateException("no clientbound handshake packets exist");
        }
        this.conn.flush();
    }

    private void handleLogin(final String packet, final ByteBuffer in) throws IOException {
        switch (packet) {
            case "login_compression" -> this.conn.enableCompression(Wire.readVarInt(in));
            case "custom_query" -> send("custom_query_answer", new Wire.Out().varInt(Wire.readVarInt(in)).bool(false));
            case "cookie_request" -> send("cookie_response", new Wire.Out().string(Wire.readString(in)).bool(false));
            case "hello" -> throw new IOException("server is in online mode; bots need online-mode=false");
            case "login_disconnect" -> throw new IOException("login refused");
            case "login_finished" -> {
                send("login_acknowledged", new Wire.Out());
                this.phase = Phase.CONFIGURATION;
                send("client_information", clientInformation());
            }
            default -> { }
        }
    }

    private Wire.Out clientInformation() {
        return new Wire.Out()
            .string("en_us")
            .u8(this.opt.viewDistance)
            .varInt(CHAT_FULL)
            .bool(true)
            .u8(SKIN_PARTS_ALL)
            .varInt(MAIN_HAND_RIGHT)
            .bool(false)
            .bool(true)
            .varInt(PARTICLES_ALL);
    }

    private void handleConfiguration(final String packet, final ByteBuffer in) throws IOException {
        switch (packet) {
            case "keep_alive" -> send("keep_alive", new Wire.Out().i64(in.getLong()));
            case "ping" -> send("pong", new Wire.Out().i32(in.getInt()));
            case "cookie_request" -> send("cookie_response", new Wire.Out().string(Wire.readString(in)).bool(false));
            case "code_of_conduct" -> send("accept_code_of_conduct", new Wire.Out());
            case "select_known_packs" -> {
                // Echo the server's packs back: the bot "has" them, so registry data is sent without bodies.
                int n = Wire.readVarInt(in);
                Wire.Out reply = new Wire.Out().varInt(n);
                for (int i = 0; i < n; i++) {
                    reply.string(Wire.readString(in)).string(Wire.readString(in)).string(Wire.readString(in));
                }
                send("select_known_packs", reply);
            }
            case "finish_configuration" -> {
                send("finish_configuration", new Wire.Out());
                this.phase = Phase.PLAY;
            }
            case "disconnect" -> throw new IOException("kicked during configuration");
            default -> { }
        }
    }

    private void handlePlay(final String packet, final ByteBuffer in, final int wireBytes) throws IOException {
        switch (packet) {
            case "keep_alive" -> send("keep_alive", new Wire.Out().i64(in.getLong()));
            case "ping" -> send("pong", new Wire.Out().i32(in.getInt()));
            case "cookie_request" -> send("cookie_response", new Wire.Out().string(Wire.readString(in)).bool(false));
            case "login" -> readLogin(in);
            case "player_position" -> handleTeleport(in);
            case "level_chunk_with_light" -> onChunk(ChunkKey.of(in.getInt(), in.getInt()), wireBytes);
            case "forget_level_chunk" -> {
                long packed = in.getLong();
                synchronized (this.chunkLock) {
                    this.loadedChunks.remove(ChunkKey.of((int) packed, (int) (packed >>> 32)));
                }
            }
            case "chunk_batch_finished" -> send("chunk_batch_received", new Wire.Out().f32(this.opt.chunksPerTick));
            case "pong_response" -> {
                long sentNanos = in.getLong();
                this.log.log(this.name, "rtt_us", (System.nanoTime() - sentNanos) / 1_000L, 0);
            }
            case "start_configuration" -> {
                send("configuration_acknowledged", new Wire.Out());
                this.phase = Phase.CONFIGURATION;
            }
            case "disconnect" -> throw new IOException("kicked during play");
            default -> { }
        }
    }

    private void readLogin(final ByteBuffer in) {
        in.getInt(); // entity id
        in.get(); // hardcore
        int dimensions = Wire.readVarInt(in);
        for (int i = 0; i < dimensions; i++) {
            Wire.readString(in);
        }
        Wire.readVarInt(in); // max players
        synchronized (this.chunkLock) {
            this.serverViewDistance = Wire.readVarInt(in);
        }
    }

    private void handleTeleport(final ByteBuffer in) throws IOException {
        int id = Wire.readVarInt(in);
        double px = in.getDouble();
        double py = in.getDouble();
        double pz = in.getDouble();
        in.getDouble(); // delta x/y/z: bots have no momentum
        in.getDouble();
        in.getDouble();
        float pyaw = in.getFloat();
        float ppitch = in.getFloat();
        int relative = in.getInt();
        double nx;
        double nz;
        synchronized (this.posLock) {
            double oldX = this.x;
            double oldZ = this.z;
            this.x = (relative & REL_X) != 0 ? this.x + px : px;
            this.y = (relative & REL_Y) != 0 ? this.y + py : py;
            this.z = (relative & REL_Z) != 0 ? this.z + pz : pz;
            this.yaw = (relative & REL_Y_ROT) != 0 ? this.yaw + pyaw : pyaw;
            this.pitch = (relative & REL_X_ROT) != 0 ? this.pitch + ppitch : ppitch;
            nx = this.x;
            nz = this.z;
            boolean farJump = Math.hypot(nx - oldX, nz - oldZ) > BLOCKS_PER_CHUNK * this.opt.viewDistance * 2;
            if (!this.spawned || farJump) {
                this.anchorX = nx;
                this.anchorZ = nz;
                startFill(chunkOf(nx), chunkOf(nz));
                this.log.log(this.name, "teleport", (long) nx, (long) nz);
            }
            send("accept_teleportation", new Wire.Out().varInt(id).f64(this.x).f64(this.y).f64(this.z).f32(this.yaw).f32(this.pitch));
        }
        if (!this.sentLoaded) {
            this.sentLoaded = true;
            send("player_loaded", new Wire.Out());
            if (this.mode != Mode.IDLE) {
                send("player_abilities", new Wire.Out().u8(ABILITY_FLYING));
            }
            this.spawned = true;
            this.log.log(this.name, "joined", this.log.nowMillis() - this.connectStartMs, 0);
            this.tickTask = this.ticker.scheduleAtFixedRate(this::tickSafely, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS);
        }
    }

    private static int chunkOf(final double block) {
        return (int) Math.floor(block / BLOCKS_PER_CHUNK);
    }

    private int effectiveViewDistance() {
        return this.serverViewDistance < 0 ? this.opt.viewDistance : Math.min(this.serverViewDistance, this.opt.viewDistance);
    }

    /** Chunks a vanilla client renders around (cx, cz): see ChunkTrackingView.isWithinDistance. */
    private Set<Long> visibleChunks(final int cx, final int cz) {
        int vd = effectiveViewDistance();
        Set<Long> set = new HashSet<>();
        for (int dx = -vd; dx <= vd; dx++) {
            for (int dz = -vd; dz <= vd; dz++) {
                long ex = Math.max(0, Math.abs(dx) - 1);
                long ez = Math.max(0, Math.abs(dz) - 1);
                if (ex * ex + ez * ez < (long) vd * vd) {
                    set.add(ChunkKey.of(cx + dx, cz + dz));
                }
            }
        }
        return set;
    }

    private void startFill(final int cx, final int cz) {
        synchronized (this.chunkLock) {
            this.fillTarget = visibleChunks(cx, cz);
            this.fillStartMs = this.log.nowMillis();
            this.fillNext = 0;
            checkFill();
        }
    }

    private void onChunk(final long key, final int wireBytes) {
        synchronized (this.chunkLock) {
            this.loadedChunks.add(key);
            this.chunksThisSecond++;
            this.bytesThisSecond += wireBytes;
            checkFill();
        }
    }

    /** Caller holds chunkLock. */
    private void checkFill() {
        if (this.fillTarget == null || this.fillNext >= FILL_THRESHOLDS.length) {
            return;
        }
        int have = 0;
        for (long k : this.fillTarget) {
            if (this.loadedChunks.contains(k)) {
                have++;
            }
        }
        double fraction = (double) have / this.fillTarget.size();
        while (this.fillNext < FILL_THRESHOLDS.length && fraction >= FILL_THRESHOLDS[this.fillNext]) {
            this.log.log(this.name, FILL_EVENTS[this.fillNext], this.log.nowMillis() - this.fillStartMs, this.fillTarget.size());
            this.fillNext++;
        }
    }

    private void tickSafely() {
        try {
            tick();
            this.conn.flush();
        } catch (IOException | RuntimeException e) {
            this.log.log(this.name, "error", sanitize("tick: " + e), 0);
            this.conn.close();
        }
    }

    private void tick() throws IOException {
        if (this.phase != Phase.PLAY) {
            return;
        }
        int t = ++this.tickCount;
        boolean moved = move(t);
        if (moved || t % IDLE_POSITION_EVERY_TICKS == 0) {
            synchronized (this.posLock) {
                send("move_player_pos", new Wire.Out().f64(this.x).f64(this.y).f64(this.z).u8(MOVE_FLAGS_AIRBORNE));
            }
        }
        send("client_tick_end", new Wire.Out());
        if (t % REPORT_EVERY_TICKS == 0) {
            report();
        }
    }

    /** Advances the position for this tick; returns whether it changed. */
    private boolean move(final int t) {
        double step = this.opt.speedBlocksPerSecond * TICK_MS / 1000.0;
        synchronized (this.posLock) {
            switch (this.mode) {
                case IDLE -> {
                    return false;
                }
                case FLY -> {
                    this.x += Math.cos(this.heading) * step;
                    this.z += Math.sin(this.heading) * step;
                }
                case WANDER -> {
                    double dist = Math.hypot(this.x - this.anchorX, this.z - this.anchorZ);
                    if (dist > this.opt.wanderRadius) {
                        this.heading = Math.atan2(this.anchorZ - this.z, this.anchorX - this.x);
                    } else if (t % WANDER_TURN_EVERY_TICKS == 0) {
                        this.heading = this.random.nextDouble() * 2.0 * Math.PI;
                    }
                    this.x += Math.cos(this.heading) * step;
                    this.z += Math.sin(this.heading) * step;
                }
            }
            return true;
        }
    }

    private void report() throws IOException {
        int chunks;
        long bytes;
        int cx;
        int cz;
        synchronized (this.posLock) {
            cx = chunkOf(this.x);
            cz = chunkOf(this.z);
        }
        synchronized (this.chunkLock) {
            chunks = this.chunksThisSecond;
            bytes = this.bytesThisSecond;
            this.chunksThisSecond = 0;
            this.bytesThisSecond = 0;
            if (this.mode == Mode.FLY) {
                Set<Long> visible = visibleChunks(cx, cz);
                int have = 0;
                for (long k : visible) {
                    if (this.loadedChunks.contains(k)) {
                        have++;
                    }
                }
                this.log.log(this.name, "view_permille", have * 1000L / visible.size(), visible.size());
            }
        }
        this.log.log(this.name, "rate", chunks, bytes);
        send("ping_request", new Wire.Out().i64(System.nanoTime()));
    }

    /** Packs chunk coordinates the same way the server does (x low, z high). */
    static final class ChunkKey {
        private static final long LOW_32_BITS = 0xFFFFFFFFL;

        private ChunkKey() {
        }

        static long of(final int cx, final int cz) {
            return (cx & LOW_32_BITS) | ((cz & LOW_32_BITS) << 32);
        }
    }
}
