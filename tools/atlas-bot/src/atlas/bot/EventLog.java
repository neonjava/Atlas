/*
 * EventLog — thread-safe CSV event writer shared by all bots in one run.
 *
 * Why: benchmark numbers must come from data files, so every measurement the
 * bots make is written as one CSV row that the analysis scripts read later.
 * Columns: t_ms (since the bot process started), bot, event, a, b.
 * Done: buffered append, flush on close.
 * Not done: no rotation; one run writes one file.
 *
 * Part of Atlas. GPL-3.0.
 */
package atlas.bot;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

final class EventLog implements AutoCloseable {
    private final BufferedWriter out;
    private final long startNanos = System.nanoTime();

    EventLog(final Path file) throws IOException {
        this.out = Files.newBufferedWriter(file, StandardCharsets.UTF_8);
        this.out.write("t_ms,bot,event,a,b\n");
    }

    long nowMillis() {
        return (System.nanoTime() - this.startNanos) / 1_000_000L;
    }

    synchronized void log(final String bot, final String event, final Object a, final Object b) {
        try {
            this.out.write(nowMillis() + "," + bot + "," + event + "," + a + "," + b + "\n");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public synchronized void close() throws IOException {
        this.out.close();
    }
}
