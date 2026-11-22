/*
 * Wire — reading and writing Minecraft protocol primitives (VarInt, strings,
 * UUIDs) for the Atlas benchmark bot.
 *
 * Why: the bot has no dependencies, so it carries the few encoders it needs.
 * Done: VarInt, UTF-8 strings, UUIDs, and fixed-size numbers.
 * Not done: NBT and text components (the bot skips packets that use them).
 *
 * Part of Atlas. GPL-3.0.
 */
package atlas.bot;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

final class Wire {
    /** A VarInt uses at most 5 bytes of 7 bits each. */
    static final int VARINT_MAX_BYTES = 5;
    private static final int SEGMENT_BITS = 0x7F;
    private static final int CONTINUE_BIT = 0x80;

    private Wire() {
    }

    static int readVarInt(final ByteBuffer in) {
        int value = 0;
        for (int i = 0; i < VARINT_MAX_BYTES; i++) {
            byte b = in.get();
            value |= (b & SEGMENT_BITS) << (7 * i);
            if ((b & CONTINUE_BIT) == 0) {
                return value;
            }
        }
        throw new IllegalStateException("VarInt too long");
    }

    /** Reads a VarInt from a stream; returns -1 at a clean end of stream. */
    static int readVarInt(final InputStream in) throws IOException {
        int value = 0;
        for (int i = 0; i < VARINT_MAX_BYTES; i++) {
            int b = in.read();
            if (b < 0) {
                if (i == 0) {
                    return -1;
                }
                throw new IOException("stream ended inside a VarInt");
            }
            value |= (b & SEGMENT_BITS) << (7 * i);
            if ((b & CONTINUE_BIT) == 0) {
                return value;
            }
        }
        throw new IOException("VarInt too long");
    }

    static String readString(final ByteBuffer in) {
        int length = readVarInt(in);
        byte[] bytes = new byte[length];
        in.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /** Growable little writer for one outgoing packet body. */
    static final class Out {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        Out varInt(final int value) {
            int v = value;
            while ((v & ~SEGMENT_BITS) != 0) {
                this.bytes.write((v & SEGMENT_BITS) | CONTINUE_BIT);
                v >>>= 7;
            }
            this.bytes.write(v);
            return this;
        }

        Out string(final String value) {
            byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
            varInt(utf8.length);
            this.bytes.writeBytes(utf8);
            return this;
        }

        Out u8(final int value) {
            this.bytes.write(value);
            return this;
        }

        Out bool(final boolean value) {
            return u8(value ? 1 : 0);
        }

        Out u16(final int value) {
            this.bytes.write(value >>> 8);
            this.bytes.write(value);
            return this;
        }

        Out i64(final long value) {
            for (int shift = 56; shift >= 0; shift -= 8) {
                this.bytes.write((int) (value >>> shift));
            }
            return this;
        }

        Out i32(final int value) {
            for (int shift = 24; shift >= 0; shift -= 8) {
                this.bytes.write(value >>> shift);
            }
            return this;
        }

        Out f64(final double value) {
            return i64(Double.doubleToLongBits(value));
        }

        Out f32(final float value) {
            return i32(Float.floatToIntBits(value));
        }

        Out uuid(final UUID id) {
            return i64(id.getMostSignificantBits()).i64(id.getLeastSignificantBits());
        }

        Out raw(final byte[] data) {
            this.bytes.writeBytes(data);
            return this;
        }

        byte[] toByteArray() {
            return this.bytes.toByteArray();
        }

        int size() {
            return this.bytes.size();
        }
    }
}
