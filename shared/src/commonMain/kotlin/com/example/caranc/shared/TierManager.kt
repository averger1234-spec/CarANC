package com.example.caranc.shared

import com.example.caranc.shared.commercial.CommercialGate
import com.example.caranc.shared.commercial.EntitlementManager
import com.example.caranc.shared.commercial.TierChangeResult
import com.example.caranc.shared.model.NoiseBandClassifier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Lightweight holder to reduce singleton abuse for Tier/Entitlement/AncStateManager (and others).
 * Previously 22+ global objects accessed directly everywhere.
 *
 * Call sites should obtain from provided context (e.g. activity/service scoped, or DI)
 * instead of direct TierManager / EntitlementManager / AncStateManager.
 *
 * Backward compat: GlobalAncSessionContext still works; provides default instances.
 *
 * CYCLE3_P2 + EXTRA: AncSessionContext now supports constructor injection for truly non-singleton
 * managers (AncStateManager + SpectrumAnalyzer, NoiseBandClassifier, RoadNoiseReferenceModel etc).
 * - Defaults create fresh class instances (see their ctors) for scoped use.
 * - Pass custom (e.g. mocks/fakes) for tests: AncSessionContext(stateManager = FakeStateManager(), roadNoiseReferenceModel = FakeRoadModel())
 * - Dependent managers (e.g. NoiseBandClassifier that refs Road) are wired to use the provided road ref.
 *
 * Scoped context recommended for testing/multi-session.
 * (No interface needed for these pure-DSP; class instances + defaults suffice for mock by subclass or replacement.)
 */
class AncSessionContext(
    // CYCLE3_P2: now injectable; non-singleton classes for state + key DSP managers (was object singletons).
    // Order: independents first, then dependents (noiseBand uses the roadRef).
    val stateManager: AncStateManager = AncStateManager(),
    val spectrumAnalyzer: SpectrumAnalyzer = SpectrumAnalyzer(),
    val roadNoiseReferenceModel: RoadNoiseReferenceModel = RoadNoiseReferenceModel(),
    // Dependent: receives the (possibly injected) road ref so scoped road affects band classify.
    val noiseBandClassifier: NoiseBandClassifier = NoiseBandClassifier(roadNoiseRef = roadNoiseReferenceModel),

    // CYCLE3_EXTRA: perf / timing metrics holder. Exposes extended AudioEngine loop stats + LMS profiling counters.
    // Populated from AudioEngine (fullLoop, ema, per-mode, lms from processor, probe corr time).
    // Accessible via sessionContext.perfMetrics (no singleton).
    // Use .update(...) or direct sets (for simplicity in hot audio loop; flows optional for UI observe).
    val perfMetrics: AncPerfMetrics = AncPerfMetrics()
) {
    // Legacy objects still via getters (future cycle can make Tier/Entitlement non-singleton too).
    // These remain global for now to keep backward compat with direct uses.
    val tierManager: TierManager get() = TierManager
    val entitlementManager: EntitlementManager get() = EntitlementManager
    val commercialGate: CommercialGate get() = CommercialGate
}

/**
 * CYCLE3_EXTRA: simple holder for extended loop timing + processor profiling.
 * - fullLoopMs / emaFullLoopMs : wall time of entire audio block (read+pre+anc+write+viz)
 * - probeCorrMs : time/cost of last measureRoundTrip (or 0)
 * - lmsUpdateCount / lmsProcessCalls : pulled from ancProcessor facade low-band
 * - perMode counters and EMA (e.g. for ROAD vs NORMAL)
 * - nativeLowUsed / lastNativeMs for future native proto timing diff.
 * Logging in AudioEngine is now more frequent/conditional using these.
 */
class AncPerfMetrics {
    var lastFullLoopMs: Double = 0.0
    var emaFullLoopMs: Double = 0.0   // exponential moving avg, alpha ~0.1-0.2 per sample
    var lastProbeCorrMs: Float = 0f
    var lmsUpdateCount: Long = 0L
    var lmsProcessCalls: Long = 0L
    var lastLmsPfx: Float = 0f
    var fdafLmsUpdates: Long = 0L
    var multirateDecimUpdates: Long = 0L

    var currentMode: String = "NORMAL"
    var modeBlockCounts: MutableMap<String, Long> = mutableMapOf()
    var modeEmaMs: MutableMap<String, Double> = mutableMapOf()

    var lastBlockTimestampNs: Long = 0L
    var nativeLowUsed: Boolean = false
    var lastNativeMs: Double = 0.0

    private val emaAlpha = 0.15

    fun updateFullLoop(dtMs: Double, mode: String = currentMode) {
        lastFullLoopMs = dtMs
        emaFullLoopMs = if (emaFullLoopMs < 1e-6) dtMs else (1 - emaAlpha) * emaFullLoopMs + emaAlpha * dtMs
        currentMode = mode
        val cnt = modeBlockCounts.getOrDefault(mode, 0L) + 1
        modeBlockCounts[mode] = cnt
        val prevEma = modeEmaMs.getOrDefault(mode, 0.0)
        modeEmaMs[mode] = if (prevEma < 1e-6) dtMs else (1 - emaAlpha) * prevEma + emaAlpha * dtMs
    }

    fun updateLmsCounters(updates: Long, calls: Long, pfx: Float) {
        lmsUpdateCount = updates
        lmsProcessCalls = calls
        lastLmsPfx = pfx
    }

    fun updateLowBandExtra(fdaf: Long, multi: Long) {
        fdafLmsUpdates = fdaf
        multirateDecimUpdates = multi
    }

    fun updateProbeCorr(ms: Float) {
        lastProbeCorrMs = ms
    }
}

val GlobalAncSessionContext = AncSessionContext()

object TierManager {
    private val _currentTier = MutableStateFlow(UserTier.LIGHT)
    val currentTier: StateFlow<UserTier> = _currentTier.asStateFlow()
    private val _lastTierChange = MutableStateFlow<TierChangeResult?>(null)
    val lastTierChange: StateFlow<TierChangeResult?> = _lastTierChange.asStateFlow()

    /**
     * Provides default global context for backward compatibility with direct singleton use.
     * Callers should prefer obtaining a (possibly scoped) AncSessionContext and using context.tierManager.
     * Scoped context recommended for testing/multi-session.
     */
    val defaultSessionContext: AncSessionContext by lazy { GlobalAncSessionContext }

    fun setTier(tier: UserTier): TierChangeResult {
        // Delegate internally via default for now (singletons provide default global context).
        val em = defaultSessionContext.entitlementManager
        val result = em.setTierWithEntitlement(tier)
        val applied = when (result) {
            is TierChangeResult.Applied -> result.tier
            is TierChangeResult.Clamped -> result.applied
        }
        _currentTier.value = CommercialGate.clampTier(applied, em.currentPlan)
        _lastTierChange.value = result
        return result
    }

    fun syncToEntitlement() {
        val em = defaultSessionContext.entitlementManager
        _currentTier.value = CommercialGate.clampTier(_currentTier.value, em.currentPlan)
    }
}
