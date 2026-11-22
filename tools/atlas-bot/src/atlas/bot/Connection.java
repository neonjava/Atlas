/*
 * Connection — one TCP connection speaking the Minecraft packet framing
 * (length prefix, optional zlib compression) for the Atlas benchmark bot.
 *
 * Why: the bot needs raw packets without a client library (none supports
 * Minecraft 26.3 yet, see docs/JOURNAL.md).
 * Done: framing, compression after the server enables it, thread-safe send.
 * Not done: encryption (bots only join offline-mode test servers).
 *
 * Part of Atlas. GPL-3.0.
 */
package atlas.bot;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

final class Connection implements AutoCloseable {
    /** Compression is off until the server sends a threshold. */
    private static final int COMPRESSION_OFF = -1;
    /** Socket buffers sized for chunk bursts (a chunk packet is often 10-30 KiB). */
    private static final int SOCKET_BUFFER_BYTES = 1 << 20;
    private static final int STREAM_BUFFER_BYTES = 1 << 16;
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    /** Same 8 MiB cap the vanilla protocol uses for one decompressed packet. */
    private static final int MAX_PACKET_BYTES = 8 * 1024 * 1024;

    /** A received packet: its ID and body, with the body positioned after the ID. */
    record Packet(int id, ByteBuffer body, int wireBytes) {
    }

    private final Socket socket;
    private final DataInputStream in;
    private final OutputStream out;
    private final Inflater inflater = new Inflater();
    private final Deflater deflater = new Deflater();
    private volatile int compressionThreshold = COMPRESSION_OFF;

    Connection(final String host, final int port) throws IOException {
        this.socket = new Socket();
        this.socket.setTcpNoDelay(true);
        this.socket.setReceiveBufferSize(SOCKET_BUFFER_BYTES);
        this.socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
        this.in = new DataInputStream(new BufferedInputStream(this.socket.getInputStream(), STREAM_BUFFER_BYTES));
        this.out = new BufferedOutputStream(this.socket.getOutputStream(), STREAM_BUFFER_BYTES);
    }

    void enableCompression(final int threshold) {
        this.compressionThreshold = threshold;
    }

    /** Blocks for the next packet; returns null when the server closed the connection. */
    Packet read() throws IOException {
        int frameLength = Wire.readVarInt(this.in);
        if (frameLength < 0) {
            return null;
        }
        if (frameLength > MAX_PACKET_BYTES) {
            throw new IOException("frame too large: " + frameLength);
        }
        byte[] frame = new byte[frameLength];
        this.in.readFully(frame);
        ByteBuffer buf = ByteBuffer.wrap(frame);
        if (this.compressionThreshold != COMPRESSION_OFF) {
            int dataLength = Wire.readVarInt(buf);
            if (dataLength != 0) {
                buf = ByteBuffer.wrap(inflate(frame, buf.position(), dataLength));
            }
        }
        int id = Wire.readVarInt(buf);
        return new Packet(id, buf.slice(), frameLength);
    }

    private byte[] inflate(final byte[] frame, final int offset, final int dataLength) throws IOException {
        if (dataLength > MAX_PACKET_BYTES) {
            throw new IOException("packet too large: " + dataLength);
        }
        byte[] data = new byte[dataLength];
        this.inflater.reset();
        this.inflater.setInput(frame, offset, frame.length - offset);
        try {
            int done = 0;
            while (done < dataLength) {
                int n = this.inflater.inflate(data, done, dataLength - done);
                if (n == 0 && (this.inflater.finished() || this.inflater.needsInput())) {
                    throw new IOException("compressed packet shorter than announced");
                }
                done += n;
            }
        } catch (DataFormatException e) {
            throw new IOException("bad compressed packet", e);
        }
        return data;
    }

    synchronized void send(final int id, final Wire.Out body) throws IOException {
        byte[] payload = new Wire.Out().varInt(id).raw(body.toByteArray()).toByteArray();
        Wire.Out frame = new Wire.Out();
        int threshold = this.compressionThreshold;
        if (threshold == COMPRESSION_OFF) {
            frame.varInt(payload.length).raw(payload);
        } else if (payload.length < threshold) {
            Wire.Out inner = new Wire.Out().varInt(0).raw(payload);
            frame.varInt(inner.size()).raw(inner.toByteArray());
        } else {
            Wire.Out inner = new Wire.Out().varInt(payload.length).raw(deflate(payload));
            frame.varInt(inner.size()).raw(inner.toByteArray());
        }
        this.out.write(frame.toByteArray());
    }

    synchronized void flush() throws IOException {
        this.out.flush();
    }

    private byte[] deflate(final byte[] payload) {
        this.deflater.reset();
        this.deflater.setInput(payload);
        this.deflater.finish();
        Wire.Out compressed = new Wire.Out();
        byte[] chunk = new byte[STREAM_BUFFER_BYTES];
        while (!this.deflater.finished()) {
            int n = this.deflater.deflate(chunk);
            compressed.raw(Arrays.copyOf(chunk, n));
        }
        return compressed.toByteArray();
    }

    @Override
    public void close() {
        try {
            this.socket.close();
        } catch (IOException ignored) {
            // Closing a socket that already failed has nothing useful to report.
        }
        this.inflater.end();
        this.deflater.end();
    }
}
