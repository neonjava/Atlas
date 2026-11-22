//! atlas-native — native kernels called from the Atlas server through Java's
//! Foreign Function & Memory API.
//!
//! Why: heavy, self-contained loops where a measurement shows Rust beats the
//! JIT-compiled Java version. Every exported function must give results
//! bit-identical to the Java code it replaces (tested from Java with fixed
//! seeds) and keeps no global mutable state, so Folia regions can call it
//! from any thread at once.
//! Done: Perlin / smeared-Perlin volume noise (`noise`).
//! Not done: anything else; new kernels are added only with numbers.

pub mod noise;

#[cfg(feature = "jni-bench")]
mod jni_bench;

/// ABI version; the Java side refuses to use a library with a different one.
pub const ABI_VERSION: i32 = 1;

#[no_mangle]
pub extern "C" fn atlas_abi_version() -> i32 {
    ABI_VERSION
}

/// Return codes of the exported functions.
pub const OK: i32 = 0;
pub const ERR_BAD_ARGUMENT: i32 = 1;

/// C entry point for `noise::add_to_volume`, called from Java (FFM).
/// `fudge_y_scale <= 0` selects plain Perlin, otherwise smeared Perlin.
/// Returns `OK`, or `ERR_BAD_ARGUMENT` without touching memory if a pointer is
/// null, a size is not positive, or the buffer is too small.
///
/// # Safety
/// `perm` must point to 256 readable `u16` values and `out` to `out_len`
/// writable `f32` values, both valid and not written by anyone else for the
/// whole call. The Java side passes its own arrays (`PerlinNoise.pAdj` and the
/// density buffer of the calling thread), which satisfies this.
#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub unsafe extern "C" fn atlas_perlin_add_volume(
    perm: *const u16,
    offset_x: f64, offset_y: f64, offset_z: f64,
    fudge_y_scale: f64,
    out: *mut f32, out_len: i64,
    size_x: i32, size_y: i32, size_z: i32,
    min_x: i32, min_y: i32, min_z: i32,
    step_x: i32, step_y: i32, step_z: i32,
    xz_scale: f64, y_scale: f64, amplitude: f32,
) -> i32 {
    if perm.is_null() || out.is_null() || size_x <= 0 || size_y <= 0 || size_z <= 0 {
        return ERR_BAD_ARGUMENT;
    }
    let n = size_x as i64 * size_y as i64 * size_z as i64;
    if out_len < n {
        return ERR_BAD_ARGUMENT;
    }
    // SAFETY: the caller guarantees both regions are valid for the given lengths.
    let perm = &*(perm as *const [u16; noise::PERM_SIZE]);
    let out = std::slice::from_raw_parts_mut(out, n as usize);
    let axis = |min, step, size: i32| noise::Axis { min, step, size: size as usize };
    let params = noise::Params {
        perm,
        offset: [offset_x, offset_y, offset_z],
        smear_y: if fudge_y_scale > 0.0 { Some(fudge_y_scale) } else { None },
        x: axis(min_x, step_x, size_x),
        y: axis(min_y, step_y, size_y),
        z: axis(min_z, step_z, size_z),
        xz_scale,
        y_scale,
        amplitude,
    };
    noise::add_to_volume(&params, out);
    OK
}
