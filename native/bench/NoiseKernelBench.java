/*
 * NoiseKernelBench — compares the server's Java Perlin volume kernel with the
 * Rust kernel in native/atlas-native, called through FFM and through JNI.
 *
 * Why: Atlas only moves code to Rust when a measurement shows it wins, and it
 * picks FFM or JNI by measuring both. This program answers three questions
 * with the real server classes (not copies):
 *   1. Parity: does Rust give bit-identical output for the same inputs?
 *      (fixed seeds, plain and smeared noise, many volume shapes)
 *   2. Speed: nanoseconds per call for Java, Rust via FFM, Rust via JNI.
 *   3. Crossing cost: the same on a 1x1x1 volume, where the call is all cost.
 * Done: all three, printed as CSV (shape,impl,ns_per_call_median,min,max).
 * Not done: whole-server effect (that is the chunk benchmark's job).
 *
 * Usage (run.sh builds the classpath):
 *   java --enable-native-access=ALL-UNNAMED -cp <server classes> \
 *        NoiseKernelBench.java <libatlas_native.so with jni-bench>
 *
 * Part of Atlas. GPL-3.0.
 */
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Random;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.densityfunction.DensityBuffer;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.synth.PerlinNoise;
import net.minecraft.world.level.levelgen.synth.SmearedPerlinNoise;

public final class NoiseKernelBench {
    private static final int PARITY_CASES = 2_000;
    private static final long WARMUP_NANOS = 3_000_000_000L;
    private static final int BATCHES = 25;
    private static final long BATCH_NANOS = 200_000_000L;
    /** Smeared Perlin in the overworld uses fudge scales in this range. */
    private static final double SMEAR_FUDGE = 1.0;
    /** Shapes: a 1x1x1 call (crossing cost), a chunk's interpolation cells, a full chunk column slice. */
    private static final int[][] SHAPES = {{1, 1, 1}, {5, 49, 5}, {4, 48, 4}, {16, 64, 16}};
    private static final int[] STEPS = {4, 8, 4};
    /**
     * Sample spacing in noise units (xz, y) for a high-frequency octave (the
     * lattice cell changes at almost every sample) and a low-frequency one (a
     * cell spans many samples, the case where SIMD over Y can help most).
     */
    private static final double[][] SCALES = {{0.25 * 684.412 / 80.0, 0.125 * 684.412 / 160.0}, {1.0 / 512.0, 1.0 / 256.0}};
    private static final String[] SCALE_NAMES = {"high", "low"};
    private static double xzScale;
    private static double yScale;

    static native int addVolumeJni(char[] perm, double ox, double oy, double oz, double fudge, float[] out,
                                   int sx, int sy, int sz, int minX, int minY, int minZ, int stepX, int stepY, int stepZ,
                                   double xzScale, double yScale, float amplitude);

    private static MethodHandle ffm;
    private static Field offX;
    private static Field offY;
    private static Field offZ;
    private static Field fudgeField;

    public static void main(final String[] args) throws Throwable {
        System.load(args[0]);
        SymbolLookup lib = SymbolLookup.libraryLookup(java.nio.file.Path.of(args[0]), Arena.global());
        ValueLayout.OfDouble d = ValueLayout.JAVA_DOUBLE;
        ValueLayout.OfInt i = ValueLayout.JAVA_INT;
        ffm = Linker.nativeLinker().downcallHandle(lib.find("atlas_perlin_add_volume").orElseThrow(),
            FunctionDescriptor.of(i, ValueLayout.ADDRESS, d, d, d, d, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
                i, i, i, i, i, i, i, i, i, d, d, ValueLayout.JAVA_FLOAT),
            Linker.Option.critical(true));
        Class<?> gradient = Class.forName("net.minecraft.world.level.levelgen.synth.GradientNoise");
        offX = field(gradient, "offsetX");
        offY = field(gradient, "offsetY");
        offZ = field(gradient, "offsetZ");
        fudgeField = field(SmearedPerlinNoise.class, "fudgeYScale");

        parity();
        System.out.println("shape,impl,ns_per_call_median,ns_min,ns_max");
        for (int sc = 0; sc < SCALES.length; sc++) {
        xzScale = SCALES[sc][0];
        yScale = SCALES[sc][1];
        for (boolean smeared : new boolean[] {false, true}) {
            PerlinNoise noise = smeared ? new SmearedPerlinNoise(RandomSource.create(1L), SMEAR_FUDGE) : new PerlinNoise(RandomSource.create(1L));
            for (int[] s : SHAPES) {
                DensityVolume vol = new DensityVolume(s[0], s[1], s[2], 1000, -64, 2000, STEPS[0], STEPS[1], STEPS[2]);
                String shape = SCALE_NAMES[sc] + "-" + (smeared ? "smeared-" : "perlin-") + s[0] + "x" + s[1] + "x" + s[2];
                bench(shape, "java", () -> javaCall(noise, vol));
                bench(shape, "rust-ffm", () -> rustCall(noise, vol, buffer(vol).values, true));
                bench(shape, "rust-jni", () -> rustCall(noise, vol, buffer(vol).values, false));
            }
        }
        }
    }

    private static Field field(final Class<?> c, final String name) throws NoSuchFieldException {
        Field f = c.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    private static final ThreadLocal<DensityBuffer> BUFFER = new ThreadLocal<>();

    private static DensityBuffer buffer(final DensityVolume v) {
        DensityBuffer b = BUFFER.get();
        int n = v.sizeX() * v.sizeY() * v.sizeZ();
        if (b == null || b.values.length != n) {
            b = DensityBuffer.createUnpooled(n);
            BUFFER.set(b);
        }
        return b;
    }

    private static float javaCall(final PerlinNoise noise, final DensityVolume v) {
        DensityBuffer b = buffer(v);
        noise.addToVolume(b, v, xzScale, yScale, 1.0F);
        return b.values[0];
    }

    private static float rustCall(final PerlinNoise noise, final DensityVolume v, final float[] out, final boolean useFfm) {
        try {
            double fudge = noise instanceof SmearedPerlinNoise ? fudgeField.getDouble(noise) : 0.0;
            char[] perm = noise.moonrise$getPAdj();
            double ox = offX.getDouble(noise);
            double oy = offY.getDouble(noise);
            double oz = offZ.getDouble(noise);
            int rc;
            if (useFfm) {
                rc = (int) ffm.invokeExact(MemorySegment.ofArray(perm), ox, oy, oz, fudge, MemorySegment.ofArray(out), (long) out.length,
                    v.sizeX(), v.sizeY(), v.sizeZ(), v.minBlockX(), v.minBlockY(), v.minBlockZ(),
                    v.stepBlockX(), v.stepBlockY(), v.stepBlockZ(), xzScale, yScale, 1.0F);
            } else {
                rc = addVolumeJni(perm, ox, oy, oz, fudge, out, v.sizeX(), v.sizeY(), v.sizeZ(), v.minBlockX(), v.minBlockY(), v.minBlockZ(),
                    v.stepBlockX(), v.stepBlockY(), v.stepBlockZ(), xzScale, yScale, 1.0F);
            }
            if (rc != 0) {
                throw new IllegalStateException("native call failed: " + rc);
            }
            return out[0];
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    /** Fixed-seed comparison over random volumes; any difference aborts. */
    private static void parity() throws Throwable {
        Random r = new Random(20261123L);
        for (int c = 0; c < PARITY_CASES; c++) {
            boolean smeared = (c & 1) == 1;
            long seed = r.nextLong();
            PerlinNoise noise = smeared ? new SmearedPerlinNoise(RandomSource.create(seed), 0.5 + r.nextDouble() * 8.0) : new PerlinNoise(RandomSource.create(seed));
            DensityVolume v = new DensityVolume(1 + r.nextInt(17), 1 + r.nextInt(64), 1 + r.nextInt(17),
                r.nextInt(2_000_000) - 1_000_000, r.nextInt(384) - 64, r.nextInt(2_000_000) - 1_000_000,
                1 + r.nextInt(8), 1 + r.nextInt(8), 1 + r.nextInt(8));
            int n = v.sizeX() * v.sizeY() * v.sizeZ();
            DensityBuffer javaOut = DensityBuffer.createUnpooled(n);
            float[] start = new float[n];
            for (int k = 0; k < n; k++) {
                start[k] = r.nextFloat() - 0.5F;
            }
            System.arraycopy(start, 0, javaOut.values, 0, n);
            float[] rustOut = start.clone();
            double xz = Math.pow(10, -3 + 3 * r.nextDouble());
            double y = Math.pow(10, -3 + 3 * r.nextDouble());
            float amp = r.nextFloat() * 2;
            noise.addToVolume(javaOut, v, xz, y, amp);
            double fudge = smeared ? fudgeField.getDouble(noise) : 0.0;
            int rc = (int) ffm.invokeExact(MemorySegment.ofArray(noise.moonrise$getPAdj()), offX.getDouble(noise), offY.getDouble(noise),
                offZ.getDouble(noise), fudge, MemorySegment.ofArray(rustOut), (long) n, v.sizeX(), v.sizeY(), v.sizeZ(),
                v.minBlockX(), v.minBlockY(), v.minBlockZ(), v.stepBlockX(), v.stepBlockY(), v.stepBlockZ(), xz, y, amp);
            if (rc != 0 || !Arrays.equals(javaOut.values, rustOut)) {
                throw new IllegalStateException("parity failure in case " + c + " (smeared=" + smeared + ", volume=" + v + ")");
            }
        }
        System.out.println("parity,ok," + PARITY_CASES + " cases bit-identical");
    }

    private interface Call {
        float run();
    }

    private static volatile float sink;

    private static void bench(final String shape, final String impl, final Call call) {
        long end = System.nanoTime() + WARMUP_NANOS;
        while (System.nanoTime() < end) {
            sink += call.run();
        }
        double[] perCall = new double[BATCHES];
        for (int b = 0; b < BATCHES; b++) {
            long calls = 0;
            long start = System.nanoTime();
            long stop = start + BATCH_NANOS;
            long now;
            do {
                sink += call.run();
                calls++;
                now = System.nanoTime();
            } while (now < stop);
            perCall[b] = (double) (now - start) / calls;
        }
        Arrays.sort(perCall);
        System.out.printf("%s,%s,%.1f,%.1f,%.1f%n", shape, impl, perCall[BATCHES / 2], perCall[0], perCall[BATCHES - 1]);
    }
}
