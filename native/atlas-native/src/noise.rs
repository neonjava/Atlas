//! noise — Perlin and "smeared" Perlin noise over a 3D volume of sample points.
//!
//! Why: chunk generation fills density volumes with Perlin noise
//! (`PerlinNoise.addToVolume` / `SmearedPerlinNoise.addToVolume` in the server).
//! The Java loop handles one Y sample at a time; here the eight corner values
//! of a cell are prepared once per run of equal `floorY`, and the inner loop
//! over Y has no dependency between iterations, so the compiler can process
//! several samples per instruction (SIMD).
//!
//! Exactness: every value is computed with the same f32/f64 operations in the
//! same order as the Java code. Rust never fuses or reorders floating point
//! operations on its own, so SIMD lanes give bit-identical results. The Java
//! side may use FMA in `dotXz` and in `fma(g, y, d)`; because gradient parts
//! are only 0 or ±1, those products are exact and FMA gives the same bits as
//! separate multiply and add (checked by the Java parity test).
//!
//! Done: plain and smeared Perlin volume fill, scalar reference for tests.
//! Not done: octave stacking (each octave is one call from Java).

/// Size of the permutation table, as in the server (`NoiseGradients.PERM_SIZE`).
pub const PERM_SIZE: usize = 256;
const PERM_SHIFT: u32 = 8;
const PERM_MASK: i32 = PERM_SIZE as i32 - 1;
const GRADIENT_MASK: i32 = 15;
/// Coordinates beyond ±2^24 are wrapped by the server to keep float precision.
const ROUND_OFF: f64 = 33_554_432.0;
/// `Math.nextDown(1.6777216E7)`: the largest double below 2^24.
const HALF_ROUND_OFF: f64 = f64::from_bits(16_777_216.0f64.to_bits() - 1);
/// `SmearedPerlinNoise.computeFudgeY` adds this float constant (widened to double).
const FUDGE_EPSILON: f32 = 1.0e-7;

/// `GradientNoise.GRADIENT`: (x, y, z) of the 16 gradient directions.
const GRADIENTS: [[f32; 3]; 16] = [
    [1.0, 1.0, 0.0], [-1.0, 1.0, 0.0], [1.0, -1.0, 0.0], [-1.0, -1.0, 0.0],
    [1.0, 0.0, 1.0], [-1.0, 0.0, 1.0], [1.0, 0.0, -1.0], [-1.0, 0.0, -1.0],
    [0.0, 1.0, 1.0], [0.0, -1.0, 1.0], [0.0, 1.0, -1.0], [0.0, -1.0, -1.0],
    [1.0, 1.0, 0.0], [0.0, -1.0, 1.0], [-1.0, 1.0, 0.0], [0.0, -1.0, -1.0],
];

/// One axis of the sample grid: first block, step between samples, count.
#[derive(Clone, Copy, Debug)]
pub struct Axis {
    pub min: i32,
    pub step: i32,
    pub size: usize,
}

/// Everything a volume fill needs besides the output buffer.
#[derive(Clone, Copy, Debug)]
pub struct Params<'a> {
    /// `PerlinNoise.pAdj`: entry i holds p[i] | p[i + 1] << 8.
    pub perm: &'a [u16; PERM_SIZE],
    pub offset: [f64; 3],
    /// `Some(fudgeYScale)` for smeared noise, `None` for plain Perlin.
    pub smear_y: Option<f64>,
    pub x: Axis,
    pub y: Axis,
    pub z: Axis,
    pub xz_scale: f64,
    pub y_scale: f64,
    pub amplitude: f32,
}

#[inline(always)]
fn wrap(x: f64) -> f64 {
    if x >= -HALF_ROUND_OFF && x < HALF_ROUND_OFF {
        x
    } else {
        x - (x / ROUND_OFF + 0.5).floor() * ROUND_OFF
    }
}

#[inline(always)]
fn smoothstep(x: f32) -> f32 {
    x * x * x * (x * (x * 6.0 - 15.0) + 10.0)
}

#[inline(always)]
fn lerp(alpha: f32, p0: f32, p1: f32) -> f32 {
    p0 + alpha * (p1 - p0)
}

#[inline(always)]
fn dot_xz(hash: i32, x: f32, z: f32) -> f32 {
    let g = GRADIENTS[hash as usize];
    g[0] * x + g[2] * z
}

#[inline(always)]
fn grad_y(hash: i32) -> f32 {
    GRADIENTS[hash as usize][1]
}

/// Java `(int) (long) d` for a value that is already an integer.
#[inline(always)]
fn java_floor_to_int(floor: f64) -> i32 {
    floor as i64 as i32
}

/// Per-sample data along one axis: lattice cell, blend weight, position in cell.
struct AxisCache {
    floor: Vec<i32>,
    alpha: Vec<f32>,
    relative: Vec<f32>,
}

fn axis_cache(axis: Axis, scale: f64, offset: f64, smear: Option<f64>) -> AxisCache {
    let mut c = AxisCache {
        floor: Vec::with_capacity(axis.size),
        alpha: Vec::with_capacity(axis.size),
        relative: Vec::with_capacity(axis.size),
    };
    let mut v = axis.min as f64;
    let step = axis.step as f64;
    for _ in 0..axis.size {
        let original = v * scale;
        let val = wrap(original) + offset;
        let floor_d = val.floor();
        let relative = val - floor_d;
        c.floor.push(java_floor_to_int(floor_d));
        match smear {
            None => {
                let r = relative as f32;
                c.alpha.push(smoothstep(r));
                c.relative.push(r);
            }
            Some(fudge_scale) => {
                c.alpha.push(smoothstep(relative as f32));
                c.relative.push((relative - fudge_y(original, relative, fudge_scale)) as f32);
            }
        }
        v += step;
    }
    c
}

/// `SmearedPerlinNoise.computeFudgeY`.
#[inline(always)]
fn fudge_y(original_y: f64, relative_y: f64, fudge_scale: f64) -> f64 {
    let limit = if original_y >= 0.0 && original_y < relative_y { original_y } else { relative_y };
    // Mth.floor(double) is (int) Math.floor(v); `as i32` saturates like Java's cast.
    ((limit / fudge_scale + FUDGE_EPSILON as f64).floor() as i32) as f64 * fudge_scale
}

/// Adds `amplitude * noise` to every sample of the volume in `out`
/// (layout: index = y + size_y * (x + size_x * z), as in `DensityBuffer`).
/// Uses AVX2 when the CPU has it (same results, wider SIMD).
///
/// Panics if `out` is shorter than the volume; the FFI wrapper checks first.
pub fn add_to_volume(p: &Params, out: &mut [f32]) {
    #[cfg(target_arch = "x86_64")]
    if std::arch::is_x86_feature_detected!("avx2") {
        // SAFETY: the CPU supports AVX2 (checked just above), which is the only
        // requirement of a #[target_feature(enable = "avx2")] function.
        unsafe { add_to_volume_avx2(p, out) };
        return;
    }
    add_to_volume_generic(p, out);
}

/// The same code compiled with AVX2 enabled, so the Y loop uses 8-wide SIMD.
#[cfg(target_arch = "x86_64")]
#[target_feature(enable = "avx2")]
unsafe fn add_to_volume_avx2(p: &Params, out: &mut [f32]) {
    add_to_volume_generic(p, out);
}

#[inline(always)]
fn add_to_volume_generic(p: &Params, out: &mut [f32]) {
    let (sx, sy, sz) = (p.x.size, p.y.size, p.z.size);
    assert!(out.len() >= sx * sy * sz, "output buffer smaller than volume");
    let cx = axis_cache(p.x, p.xz_scale, p.offset[0], None);
    let cy = axis_cache(p.y, p.y_scale, p.offset[1], p.smear_y);
    let cz = axis_cache(p.z, p.xz_scale, p.offset[2], None);
    let perm = |i: i32| p.perm[(i & PERM_MASK) as usize] as i32;

    for ix in 0..sx {
        let (alpha_x, rel_x) = (cx.alpha[ix], cx.relative[ix]);
        let x0 = perm(cx.floor[ix]);
        let x1 = x0 >> PERM_SHIFT;
        for iz in 0..sz {
            let (alpha_z, rel_z, floor_z) = (cz.alpha[iz], cz.relative[iz], cz.floor[iz]);
            let column = &mut out[sy * (ix + iz * sx)..sy * (ix + iz * sx) + sy];
            let mut start = 0;
            while start < sy {
                // A run of samples in the same lattice cell along Y shares its corners.
                let floor_y = cy.floor[start];
                let mut end = start + 1;
                while end < sy && cy.floor[end] == floor_y {
                    end += 1;
                }
                let xy00 = perm(x0.wrapping_add(floor_y));
                let xy01 = xy00 >> PERM_SHIFT;
                let xy10 = perm(x1.wrapping_add(floor_y));
                let xy11 = xy10 >> PERM_SHIFT;
                let z000 = perm(xy00.wrapping_add(floor_z));
                let z010 = perm(xy01.wrapping_add(floor_z));
                let z100 = perm(xy10.wrapping_add(floor_z));
                let z110 = perm(xy11.wrapping_add(floor_z));
                let h = |z: i32| (z & GRADIENT_MASK, (z >> PERM_SHIFT) & GRADIENT_MASK);
                let ((g000, g001), (g010, g011)) = (h(z000), h(z010));
                let ((g100, g101), (g110, g111)) = (h(z100), h(z110));
                let corners = Corners {
                    d000: dot_xz(g000, rel_x, rel_z),
                    d001: dot_xz(g001, rel_x, rel_z - 1.0),
                    d010: dot_xz(g010, rel_x, rel_z),
                    d011: dot_xz(g011, rel_x, rel_z - 1.0),
                    d100: dot_xz(g100, rel_x - 1.0, rel_z),
                    d101: dot_xz(g101, rel_x - 1.0, rel_z - 1.0),
                    d110: dot_xz(g110, rel_x - 1.0, rel_z),
                    d111: dot_xz(g111, rel_x - 1.0, rel_z - 1.0),
                    g000: grad_y(g000), g001: grad_y(g001), g010: grad_y(g010), g011: grad_y(g011),
                    g100: grad_y(g100), g101: grad_y(g101), g110: grad_y(g110), g111: grad_y(g111),
                };
                run(&corners, alpha_x, alpha_z, p.amplitude,
                    &cy.alpha[start..end], &cy.relative[start..end], &mut column[start..end]);
                start = end;
            }
        }
    }
}

/// Corner values of one lattice cell: d = x/z part of the gradient dot product,
/// g = y part of the gradient.
struct Corners {
    d000: f32, d001: f32, d010: f32, d011: f32, d100: f32, d101: f32, d110: f32, d111: f32,
    g000: f32, g001: f32, g010: f32, g011: f32, g100: f32, g101: f32, g110: f32, g111: f32,
}

/// The vectorisable part: independent samples along Y within one cell.
#[inline(always)]
fn run(c: &Corners, ax: f32, az: f32, amp: f32, alpha_y: &[f32], rel_y: &[f32], out: &mut [f32]) {
    for ((o, &ay), &ry) in out.iter_mut().zip(alpha_y).zip(rel_y) {
        let ry1 = ry - 1.0;
        // Mth.lerp3(alphaX, alphaY, alphaZ, x000, x100, x010, x110, x001, x101, x011, x111)
        let lower = lerp(ay, lerp(ax, c.d000 + c.g000 * ry, c.d100 + c.g100 * ry),
                             lerp(ax, c.d010 + c.g010 * ry1, c.d110 + c.g110 * ry1));
        let upper = lerp(ay, lerp(ax, c.d001 + c.g001 * ry, c.d101 + c.g101 * ry),
                             lerp(ax, c.d011 + c.g011 * ry1, c.d111 + c.g111 * ry1));
        *o += amp * lerp(az, lower, upper);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Straightforward per-sample transcription of the server's vanilla
    /// `addToVolumeVanilla`, used to check the optimised kernel.
    fn reference(p: &Params, out: &mut [f32]) {
        let perm = |i: i32| (p.perm[(i & PERM_MASK) as usize] & 0xFF) as i32;
        let mut index = 0;
        for iz in 0..p.z.size {
            let z = wrap((p.z.min as f64 + iz as f64 * p.z.step as f64) * p.xz_scale) + p.offset[2];
            let fz = z.floor() as i32;
            let rz = (z - fz as f64) as f32;
            let az = smoothstep(rz);
            for ix in 0..p.x.size {
                let x = wrap((p.x.min as f64 + ix as f64 * p.x.step as f64) * p.xz_scale) + p.offset[0];
                let fx = x.floor() as i32;
                let rx = (x - fx as f64) as f32;
                let ax = smoothstep(rx);
                for iy in 0..p.y.size {
                    let orig = (p.y.min as f64 + iy as f64 * p.y.step as f64) * p.y_scale;
                    let y = wrap(orig) + p.offset[1];
                    let fy = y.floor() as i32;
                    let rel = y - fy as f64;
                    let ay = smoothstep(rel as f32);
                    let ry = match p.smear_y {
                        None => rel as f32,
                        Some(s) => (rel - fudge_y(orig, rel, s)) as f32,
                    };
                    let g = |a: i32, b: i32, c: i32| {
                        perm(perm(perm(fx.wrapping_add(a)).wrapping_add(fy.wrapping_add(b))).wrapping_add(fz.wrapping_add(c))) & 15
                    };
                    let d = |h: i32, dx: f32, dy: f32, dz: f32| dot_xz(h, rx - dx, rz - dz) + grad_y(h) * (ry - dy);
                    let v = lerp(az,
                        lerp(ay, lerp(ax, d(g(0, 0, 0), 0.0, 0.0, 0.0), d(g(1, 0, 0), 1.0, 0.0, 0.0)),
                                 lerp(ax, d(g(0, 1, 0), 0.0, 1.0, 0.0), d(g(1, 1, 0), 1.0, 1.0, 0.0))),
                        lerp(ay, lerp(ax, d(g(0, 0, 1), 0.0, 0.0, 1.0), d(g(1, 0, 1), 1.0, 0.0, 1.0)),
                                 lerp(ax, d(g(0, 1, 1), 0.0, 1.0, 1.0), d(g(1, 1, 1), 1.0, 1.0, 1.0))));
                    out[iz * p.x.size * p.y.size + ix * p.y.size + iy] += p.amplitude * v;
                    index += 1;
                }
            }
        }
        assert_eq!(index, p.x.size * p.y.size * p.z.size);
    }

    /// Small deterministic generator so tests need no dependencies.
    struct SplitMix(u64);
    impl SplitMix {
        fn next(&mut self) -> u64 {
            self.0 = self.0.wrapping_add(0x9E37_79B9_7F4A_7C15);
            let mut z = self.0;
            z = (z ^ (z >> 30)).wrapping_mul(0xBF58_476D_1CE4_E5B9);
            z = (z ^ (z >> 27)).wrapping_mul(0x94D0_49BB_1331_11EB);
            z ^ (z >> 31)
        }
        fn unit(&mut self) -> f64 {
            (self.next() >> 11) as f64 / (1u64 << 53) as f64
        }
    }

    fn perm_table(rng: &mut SplitMix) -> [u16; PERM_SIZE] {
        let mut p: Vec<u16> = (0..PERM_SIZE as u16).collect();
        for i in 0..PERM_SIZE {
            let j = i + (rng.next() % (PERM_SIZE - i) as u64) as usize;
            p.swap(i, j);
        }
        let mut adj = [0u16; PERM_SIZE];
        for i in 0..PERM_SIZE {
            adj[i] = p[i] | (p[(i + 1) % PERM_SIZE] << 8);
        }
        adj
    }

    #[test]
    fn kernel_matches_reference_bit_for_bit() {
        let mut rng = SplitMix(20_261_123);
        for case in 0..400 {
            let perm = perm_table(&mut rng);
            let axis = |rng: &mut SplitMix, max: usize| Axis {
                min: (rng.next() % 2_000_000) as i32 - 1_000_000,
                step: 1 + (rng.next() % 8) as i32,
                size: 1 + (rng.next() % max as u64) as usize,
            };
            let p = Params {
                perm: &perm,
                offset: [rng.unit() * 256.0, rng.unit() * 256.0, rng.unit() * 256.0],
                smear_y: if case % 2 == 0 { None } else { Some(0.5 + rng.unit() * 8.0) },
                x: axis(&mut rng, 17),
                y: axis(&mut rng, 64),
                z: axis(&mut rng, 17),
                xz_scale: 10f64.powf(-3.0 + 3.0 * rng.unit()),
                y_scale: 10f64.powf(-3.0 + 3.0 * rng.unit()),
                amplitude: (rng.unit() * 2.0) as f32,
            };
            let n = p.x.size * p.y.size * p.z.size;
            let start: Vec<f32> = (0..n).map(|_| (rng.unit() - 0.5) as f32).collect();
            let (mut a, mut b) = (start.clone(), start);
            add_to_volume(&p, &mut a);
            reference(&p, &mut b);
            for i in 0..n {
                assert_eq!(a[i].to_bits(), b[i].to_bits(), "case {case} index {i}: {} vs {}", a[i], b[i]);
            }
        }
    }

    #[test]
    fn wrap_matches_java_edges() {
        assert_eq!(wrap(1.0), 1.0);
        assert_eq!(wrap(HALF_ROUND_OFF), HALF_ROUND_OFF - ROUND_OFF);
        assert_eq!(wrap(-HALF_ROUND_OFF), -HALF_ROUND_OFF);
    }
}
