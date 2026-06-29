// NativeLowBandLms.cpp
// CYCLE3_EXTRA: Native low-freq path prototype (JNI stub + C++ LMS skeleton for low band).
//
// This provides the native side for NativeLowBandProcessor.actual on Android.
// Currently: stubs that return 0f (no effect) so hot path behavior unchanged.
//
// GOAL / ROADMAP (comments serve as spec for future full impl):
// - Offload low-band LMS (the rumble path: <250Hz road/engine noise) from kotlin Multirate + Fdaf + BandFxLms.
// - Why native: SIMD (NEON), no GC, better cache, lower overhead for inner LMS loops (dot-prod + weight update).
// - Target: keep real-time audio thread (< 5-8ms per 64-sample block @48k, decimated x4 =>16 samples effective).
// - Match existing semantics exactly (mu norm, leakage, freeze, error from virtual, pfx power etc).
//
// JNI EXPORTS (must match exact name in kotlin external fun):
//   Java_com_example_caranc_shared_latency_NativeLowBandProcessor_nativeProcessLowBand
//   Java_com_example_caranc_shared_latency_NativeLowBandProcessor_nativeReset
//
// Recommended C++ structure for full LMS low band (Fdaf-style or time-domain multirate):
//
// 1. Use fixed arrays (no new/malloc in audio callback!):
//    static constexpr int BLOCK = 64;      // or 16 post-decim
//    static constexpr int FILT = 64;
//    static constexpr int FFT = 128;       // next power for overlap-save
//    float w[FILT] = {0};
//    float xBuf[1024];  // ring like kotlin
//    float fxBuf[1024]; // filtered-x
//    int idx = 0;
//
// 2. For time-domain FXLMS (simpler start, like BandFxLms):
//    y = sum w[j] * x[(idx - delay - j) & mask]
//    fx = sum sHat * x[(idx - j) & mask]
//    if (!freeze && mu>0) {
//      pfx = sum fx*fx over filt
//      // VSS energyFactor + clipping + Leaky alpha from Kotlin BandFxLms.processSample (ported skeleton):
//      baseNorm = (baseMu * muScale) / (pfx + 1e-3f)
//      energyFactor = (pfx > 30f ? 1.0f : pfx > 10f ? 0.85f : pfx < 2f ? 0.22f : 0.6f)  // VSS based on lastLmsPfx energy proxy; high stable rumble = full step, low/variable = conservative (prevents pop on potholes)
//      muNorm = baseNorm * energyFactor
//      for(j) {
//        raw = muNorm * err * fx[(idx-j)&mask]
//        clipped = raw.clamp(-0.05f, 0.05f)  // gradient clip for impulse stability
//        w[j] = w[j] * leakage + clipped ; w[j].clamp(-0.8,0.8)
//      }
//      lastLmsPfx = pfx; lmsUpdateCount++
//      // leakage (alpha) from setDebugLeakage (0.9998 std vs 0.9995 conservative for mu=2.0)
//    }
//    idx = (idx+1)&mask
//    return y
//
// 3. For Fdaf overlap-save (better for longer filt / freq selective; see FdafLowBandProcessor.java):
//    - Block process every blockSize samples (collect decimated).
//    - FFT(input block + overlap), pointwise * W(freq), IFFT, overlap add/save for output y.
//    - Same for error block (mic + anti).
//    - Freq domain weight update: W -= mu * conj(Xf) * Ef / ( |Xf|^2 + eps )   (NLMS)
//    - Use overlap-save to avoid time alias.
//    - Prealloc all FFT temps (use stack or global static; or std::vector sized once).
//    - Implement own FFT or integrate kissfft (header-only, 1-file, permissive).
//
// 4. Multirate aspect: caller (MultirateLowBandFxLms) already does decimation before calling processLowBand.
//    Or move decim here and return upsampled interpolated (but match current frac interp in kotlin).
//
// 5. Secondary path (sHat): accept via JNI setSHat(float[], len) or bind once.
//    Currently lowBand uses bindSecondaryPath / bindZonePaths (mimo).
//    Extend JNI for mimo zones if needed (array of paths + blend weights).
//
// 6. Profiling counters:
//    - Bump global or instance lmsProcessCalls++ , lmsUpdateCount++ on actual weight write.
//    - lastLmsPfx = pfx
//    - To expose to kotlin: either return struct or have separate JNI getLmsUpdateCount().
//    - AudioEngine can pull via facade.getLowLmsUpdateCount() (extended) + feed to perfMetrics.
//
// 7. RT constraints:
//    - No malloc, no exceptions in hot path (use noexcept).
//    - No locks.
//    - Use __builtin_prefetch or restrict for speed.
//    - For NEON: #include <arm_neon.h>; vectorize the FIR dot and update loops (4-wide or 8).
//    - Leakage mul can be fused.
//
// 8. Integration:
//    - Call from kotlin actual: just forward scalars.
//    - For block, can extend API to processBlock(float* in, float* out, int n, ...)
//    - Init: zero weights, sHat[0]=1.0 like kotlin.
//    - Reset on mode change etc.
//
// 9. Build: linked via the CMakeLists (SHARED lib).
//    Symbol visibility: use JNIEXPORT for the bridge funcs.
//
// 10. Verify: use cycle3_verify_release_build_timing after enabling native (timing of assemble includes NDK build;
//      runtime: use extended AudioEngine perf logs + lms counters to compare kotlin vs native CPU via battery or systrace).
// Native port (when activated behind gate): 2-3x lower overhead on low band lmsProcessCalls (no kotlin loop/alloc, NEON dotprod+update; pfx calc faster in C++; sim_iter assumes native ~1/2.5 calls cost vs kotlin BandFxLms).
// VSS+clip+leaky same math as kotlin, plus leakage from debug prefs for A/B.
// TODO NEXT CYCLE:
//   - Wire the native path optionally behind CommercialGate or debug flag in MultiBandANCProcessor.
//   - Port exact math from BandFxLms.processSample + multirate decim.
//   - Add Fdaf freq impl or keep time domain for first native pass.
//   - Unit test native vs kotlin bit-exact on synthetic (use test vectors).
//   - Expose via sessionContext.perfMetrics.nativeLowUsed
//
// Current impl below: just the two JNI thunks returning 0 / no-op to satisfy load + declaration.

#include <jni.h>
#include <android/log.h>

#define LOG_TAG "NativeLowBandLms"
#define ALOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define ALOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

// Static state for prototype (in real: per-instance via long handle from kotlin, or global for simplicity since single ANC session).
static float s_w[64] = {0.0f};
static int   s_bufIdx = 0;
static float s_leakageAlpha = 0.9998f;  // default std; updated via future JNI setDebugLeakage or per-call (guarded, minimal change)
// (expand with xBuffer etc when implementing real LMS)

extern "C" {

JNIEXPORT jfloat JNICALL
Java_com_example_caranc_shared_latency_NativeLowBandProcessor_nativeProcessLowBand(
    JNIEnv* env,
    jobject /* this */,
    jfloat sample,
    jfloat muScale,
    jboolean freeze,
    jfloat errorSample
) {
    // PROTOTYPE: do nothing, return 0 contribution.
    // Real impl (port from Kotlin BandFxLms VSS/Leaky + leakage from prefs):
    //   update ring buffers (x, filteredX)
    //   y = dot(w, delayed x)
    //   fx = dot(sHat, x)  (or zone weighted)
    //   if (!freeze && muScale > 0) {
    //     pfx = sum(fx*fx)
    //     energyFactor = (pfx > 30f ? 1.0f : pfx > 10f ? 0.85f : pfx < 2f ? 0.22f : 0.6f)   // VSS from pfx (energy) + lastLmsPfxEma/var for verify
    //     muNorm = (baseMu * muScale * energyFactor) / (pfx + 1e-3f)
    //     raw = muNorm * error * fx[...]
    //     clipped = raw.clamp(-0.05f, 0.05f)
    //     w[j] = w[j] * leakageAlpha + clipped ;   // Leaky LMS α (tunable 0.9998 std / 0.9995 cons for mu=2.0 A/B via setDebugLeakage)
    //     s_lmsUpdateCount++; lastLmsPfx = pfx
    //   }
    //   return y;

    // For now count a process call (demo; use atomic for thread if multi).
    // (counters better mirrored or pulled via separate getter JNI)
    static long processCalls = 0;
    processCalls++;

    // Uncomment when real:
    // if (!freeze && muScale > 0.0f) { ... VSS + Leaky lmsUpdate with clipping ... }

    return 0.0f;
}

JNIEXPORT void JNICALL
Java_com_example_caranc_shared_latency_NativeLowBandProcessor_nativeReset(
    JNIEnv* env,
    jobject /* this */
) {
    for (int i = 0; i < 64; ++i) s_w[i] = 0.0f;
    s_bufIdx = 0;
    ALOGI("NativeLowBand reset (proto)");
}

} // extern "C"
