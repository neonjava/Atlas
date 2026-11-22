/*
 * PacketIds — packet ID lookup for the Atlas benchmark bot.
 *
 * Why: packet IDs change with every Minecraft version, so they are read from a
 * properties file generated from the server's own data report
 * (see gen-packet-ids.py) instead of being written into the code.
 * Done: lookup by phase, direction and packet name; protocol version.
 * Not done: no check that packet layouts match; layouts live in Bot.java.
 *
 * Part of Atlas. GPL-3.0.
 */
package atlas.bot;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

final class PacketIds {
    enum Phase { HANDSHAKE, LOGIN, CONFIGURATION, PLAY }

    private final Properties props = new Properties();
    private final Map<String, String> reverse = new HashMap<>();
    final int protocolVersion;
    final String versionName;

    PacketIds(final Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            this.props.load(in);
        }
        this.protocolVersion = Integer.parseInt(required("version.protocol"));
        this.versionName = required("version.name");
        for (String key : this.props.stringPropertyNames()) {
            if (key.contains(".clientbound.")) {
                String phase = key.substring(0, key.indexOf('.'));
                this.reverse.put(phase + ".clientbound#" + this.props.getProperty(key), key.substring(key.lastIndexOf('.') + 1));
            }
        }
    }

    private String required(final String key) {
        String value = this.props.getProperty(key);
        if (value == null) {
            throw new IllegalStateException("packet id file has no entry " + key);
        }
        return value;
    }

    int serverbound(final Phase phase, final String name) {
        return Integer.parseInt(required(phase.name().toLowerCase() + ".serverbound." + name));
    }

    /** Name of a clientbound packet, or null if the ID is unknown in this phase. */
    String clientboundName(final Phase phase, final int id) {
        return this.reverse.get(phase.name().toLowerCase() + ".clientbound#" + id);
    }
}
