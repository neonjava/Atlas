//! jni_bench — JNI version of the noise entry point, for measurement only.
//!
//! Why: Atlas picks JNI or Java's FFM API by measuring both with the same
//! kernel. This module exists only with the `jni-bench` feature, which the
//! benchmark in `native/bench` builds; the server library does not include it.
//! Done: one JNI function using GetPrimitiveArrayCritical (no copies).
//! Not done: anything for production use.

use std::ffi::c_void;

/// Indexes in the JNI function table (JNINativeInterface_, JNI specification).
const GET_PRIMITIVE_ARRAY_CRITICAL: usize = 222;
const RELEASE_PRIMITIVE_ARRAY_CRITICAL: usize = 223;
/// Release mode 0: copy back (if copied) and free.
const RELEASE_COPY_BACK: i32 = 0;

/// A JNI environment points to a pointer to the table of JNI functions.
type JniEnv = *const *const *const c_void;
type GetCritical = unsafe extern "system" fn(JniEnv, *mut c_void, *mut u8) -> *mut c_void;
type ReleaseCritical = unsafe extern "system" fn(JniEnv, *mut c_void, *mut c_void, i32);

/// `static native int addVolumeJni(char[] perm, double ox, double oy, double oz,
/// double fudgeYScale, float[] out, int sx, int sy, int sz, int minX, int minY,
/// int minZ, int stepX, int stepY, int stepZ, double xzScale, double yScale,
/// float amplitude)` of class `NoiseKernelBench`.
///
/// # Safety
/// Called only by the JVM with a valid environment and live arrays.
#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub unsafe extern "system" fn Java_NoiseKernelBench_addVolumeJni(
    env: JniEnv, _class: *mut c_void, perm: *mut c_void,
    ox: f64, oy: f64, oz: f64, fudge: f64, out: *mut c_void,
    sx: i32, sy: i32, sz: i32, mnx: i32, mny: i32, mnz: i32, stx: i32, sty: i32, stz: i32,
    xz: f64, ys: f64, amp: f32,
) -> i32 {
    // SAFETY: the function table is provided by the JVM; the indexes are fixed by the JNI spec.
    let table: *const *const c_void = *env;
    let get: GetCritical = std::mem::transmute::<*const c_void, GetCritical>(*table.add(GET_PRIMITIVE_ARRAY_CRITICAL));
    let release: ReleaseCritical =
        std::mem::transmute::<*const c_void, ReleaseCritical>(*table.add(RELEASE_PRIMITIVE_ARRAY_CRITICAL));
    let p = get(env, perm, std::ptr::null_mut());
    let o = get(env, out, std::ptr::null_mut());
    let len = sx as i64 * sy as i64 * sz as i64;
    let rc = crate::atlas_perlin_add_volume(p as *const u16, ox, oy, oz, fudge, o as *mut f32, len,
                                            sx, sy, sz, mnx, mny, mnz, stx, sty, stz, xz, ys, amp);
    release(env, out, o, RELEASE_COPY_BACK);
    release(env, perm, p, RELEASE_COPY_BACK);
    rc
}
