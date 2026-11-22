/*
 * NativeNoise — Java entry to the Rust Perlin volume kernel
 * (atlas_perlin_add_volume in native/atlas-native/src/noise.rs).
 *
 * Why: hole R1 (docs/HOLES.md, issue #4). The kernel gives bit-identical
 * results to PerlinNoise/SmearedPerlinNoise.addToVolume and is up to 2.2x
 * faster for low-frequency octaves. The call uses FFM with
 * Linker.Option.critical, which passes the Java arrays without copying; FFM
 * was chosen over JNI by measurement (bench/results/native/noise-kernel.csv).
 * Thread safety: the kernel keeps no state; each call only touches the arrays
 * it is given, which belong to the calling region's thread.
 * Done: plain and smeared Perlin volumes.
 * Not done: point sampling (one call per point costs more than it saves).
 *
 * Part of Atlas. GPL-3.0.
 */
package dev.atlas.natives;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;

public final class NativeNoise {
    /** Passed as fudgeYScale to select plain (not smeared) Perlin noise. */
    public static final double PLAIN_PERLIN = 0.0;
    private static final int OK = 0;
    private static final MethodHandle ADD_VOLUME = AtlasNative.function(
        "atlas_perlin_add_volume",
        FunctionDescriptor.of(ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_DOUBLE, ValueLayout.JAVA_DOUBLE, ValueLayout.JAVA_DOUBLE,
            ValueLayout.JAVA_DOUBLE,
            ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
            ValueLayout.JAVA_DOUBLE, ValueLayout.JAVA_DOUBLE, ValueLayout.JAVA_FLOAT),
        Linker.Option.critical(true)).orElse(null);

    private NativeNoise() {
    }

    /**
     * Adds amplitude * noise for every sample of the volume to out.
     * Returns false (and changes nothing) when native code is not available,
     * so the caller runs its Java code instead.
     */
    public static boolean addToVolume(final char[] perm, final double offsetX, final double offsetY, final double offsetZ,
                                      final double fudgeYScale, final float[] out, final DensityVolume volume,
                                      final double xzScale, final double yScale, final float amplitude) {
        if (ADD_VOLUME == null) {
            return false;
        }
        try {
            int rc = (int) ADD_VOLUME.invokeExact(AtlasNative.heap(perm), offsetX, offsetY, offsetZ, fudgeYScale,
                AtlasNative.heap(out), (long) out.length,
                volume.sizeX(), volume.sizeY(), volume.sizeZ(),
                volume.minBlockX(), volume.minBlockY(), volume.minBlockZ(),
                volume.stepBlockX(), volume.stepBlockY(), volume.stepBlockZ(),
                xzScale, yScale, amplitude);
            return rc == OK;
        } catch (Throwable t) {
            throw new IllegalStateException("Atlas native noise call failed", t);
        }
    }
}
