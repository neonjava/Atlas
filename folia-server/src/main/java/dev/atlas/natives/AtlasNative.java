/*
 * AtlasNative — finds and loads Atlas's Rust library (native/atlas-native).
 *
 * Why: some heavy, self-contained kernels run faster in Rust (see
 * docs/HOLES.md). The server must still start and behave the same when the
 * library is missing, built for another OS/CPU, or disabled, so every caller
 * checks isAvailable() and keeps its Java code as the fallback.
 * Where the library comes from, first match wins:
 *   1. -Datlas.native=false            -> disabled, Java code everywhere
 *   2. -Datlas.native.path=<file>      -> that file
 *   3. jar resource atlas-native/<os>-<arch>/<library name>, extracted to a
 *      temporary file
 * The library's ABI version must equal ABI_VERSION. One log line says which
 * case happened.
 * Done: loading, ABI check, fallback, logging.
 * Not done: per-kernel switches (one switch for all native code).
 *
 * Part of Atlas. GPL-3.0.
 */
package dev.atlas.natives;

import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;

public final class AtlasNative {
    /** Must match atlas_native::ABI_VERSION. */
    public static final int ABI_VERSION = 1;
    private static final String SWITCH_PROPERTY = "atlas.native";
    private static final String PATH_PROPERTY = "atlas.native.path";
    private static final String RESOURCE_ROOT = "atlas-native";
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final SymbolLookup LOOKUP = load();

    private AtlasNative() {
    }

    public static boolean isAvailable() {
        return LOOKUP != null;
    }

    /** Downcall handle for a library function, or empty when native code is off. */
    public static Optional<MethodHandle> function(final String name, final FunctionDescriptor descriptor, final Linker.Option... options) {
        if (LOOKUP == null) {
            return Optional.empty();
        }
        return LOOKUP.find(name).map(symbol -> Linker.nativeLinker().downcallHandle(symbol, descriptor, options));
    }

    private static SymbolLookup load() {
        if ("false".equalsIgnoreCase(System.getProperty(SWITCH_PROPERTY))) {
            LOGGER.info("Atlas native code disabled by -D{}=false; using Java code", SWITCH_PROPERTY);
            return null;
        }
        try {
            Path file = locate();
            if (file == null) {
                LOGGER.info("Atlas native library not bundled for {}; using Java code", platform());
                return null;
            }
            SymbolLookup lookup = SymbolLookup.libraryLookup(file, Arena.global());
            MethodHandle version = Linker.nativeLinker().downcallHandle(
                lookup.find("atlas_abi_version").orElseThrow(), FunctionDescriptor.of(ValueLayout.JAVA_INT));
            int abi = (int) version.invokeExact();
            if (abi != ABI_VERSION) {
                LOGGER.warn("Atlas native library {} has ABI {}, expected {}; using Java code", file, abi, ABI_VERSION);
                return null;
            }
            LOGGER.info("Atlas native library loaded from {} (ABI {})", file, abi);
            return lookup;
        } catch (Throwable t) {
            LOGGER.warn("Atlas native library could not be loaded ({}); using Java code", t.toString());
            return null;
        }
    }

    private static Path locate() throws IOException {
        String explicit = System.getProperty(PATH_PROPERTY);
        if (explicit != null) {
            return Path.of(explicit);
        }
        String name = System.mapLibraryName("atlas_native");
        String resource = RESOURCE_ROOT + "/" + platform() + "/" + name;
        try (InputStream in = AtlasNative.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                return null;
            }
            Path tmp = Files.createTempFile("atlas-native-", "-" + name);
            tmp.toFile().deleteOnExit();
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            return tmp;
        }
    }

    /** For example "linux-x86_64" or "windows-aarch64". */
    static String platform() {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        String osName = os.contains("win") ? "windows" : os.contains("mac") ? "macos" : "linux";
        String arch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
        String archName = arch.equals("amd64") || arch.equals("x86_64") ? "x86_64" : arch.equals("aarch64") || arch.equals("arm64") ? "aarch64" : arch;
        return osName + "-" + archName;
    }

    /** Heap arrays can be passed to "critical" downcalls without copying. */
    static MemorySegment heap(final char[] array) {
        return MemorySegment.ofArray(array);
    }

    static MemorySegment heap(final float[] array) {
        return MemorySegment.ofArray(array);
    }
}
