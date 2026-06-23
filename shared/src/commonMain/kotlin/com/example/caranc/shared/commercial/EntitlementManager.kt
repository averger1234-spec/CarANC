package com.example.caranc.shared.commercial

import com.example.caranc.shared.AncSessionContext
import com.example.caranc.shared.GlobalAncSessionContext
import com.example.caranc.shared.UserTier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class EntitlementSnapshot(
    val plan: SubscriptionPlan = SubscriptionPlan.FREE,
    val source: String = "default",
    val safetyConsentAccepted: Boolean = false,
    val safetyConsentVersion: Int = 0,
    val marketingOptIn: Boolean = false
)

sealed class TierChangeResult {
    data class Applied(val tier: UserTier) : TierChangeResult()
    data class Clamped(val requested: UserTier, val applied: UserTier, val reason: String) : TierChangeResult()
}

/**
 * Stub for future real billing integration (Google Play Billing etc).
 * Currently commercial is pure client-side bypass (no server verify).
 * Android actual uses the existing prefs-backed load (EntitlementStore).
 * iOS/other will provide real impl when targets added.
 */
interface BillingRepository {
    fun currentPlan(): SubscriptionPlan
}

internal expect val billingRepository: BillingRepository

object EntitlementManager {

    const val CURRENT_SAFETY_CONSENT_VERSION = 1

    private val _snapshot = MutableStateFlow(EntitlementSnapshot())
    val snapshot: StateFlow<EntitlementSnapshot> = _snapshot.asStateFlow()

    /**
     * Wired (P2) to prefer billingRepository.currentPlan() as authoritative for subscription plan.
     * billingRepository is the migration path away from direct snapshot/restore/setPlan (bypass).
     * Snapshot still owns safetyConsent + full state for now; plan in snapshot may lag billing.
     */
    val currentPlan: SubscriptionPlan get() = billingRepository.currentPlan()

    /**
     * Provides default global context (for backward compat).
     * Scoped context recommended for testing/multi-session.
     */
    val defaultSessionContext: AncSessionContext by lazy { GlobalAncSessionContext }

    /**
     * WARNING: Direct restore is part of client-side dev bypass.
     * Commercial currently has NO real billing; this allows arbitrary plan injection from UI/tests.
     * DO NOT call from production paths. Prepare for BillingRepository + real verification.
     *
     * @deprecated Prefer future billing-initiated restore. Will be restricted.
     */
    @Deprecated(
        message = "Direct restore is dev bypass for client-side only (no real billing). Use BillingRepository when wired.",
        level = DeprecationLevel.WARNING
    )
    fun restore(snapshot: EntitlementSnapshot) {
        _snapshot.value = snapshot
    }

    /**
     * WARNING: Direct setPlan allows arbitrary subscription plan change from anywhere (UI, dev panel, tests).
     * This is PURE CLIENT-SIDE BYPASS. No receipt validation, no server, no Google Play Billing.
     * Used by DevEntitlementOverrides. Will be deprecated further when real billing lands.
     *
     * @deprecated Direct overrides harden for commercial. Migrate to BillingRepository.currentPlan() + real flows.
     */
    @Deprecated(
        message = "Direct setPlan is client-side bypass only (dev/commercial gate bypass). Prepare real BillingRepository integration.",
        level = DeprecationLevel.WARNING
    )
    fun setPlan(plan: SubscriptionPlan, source: String = "manual") {
        _snapshot.value = _snapshot.value.copy(plan = plan, source = source)
        persistEntitlement(_snapshot.value)
    }

    fun acceptSafetyConsent(marketingOptIn: Boolean = false) {
        _snapshot.value = _snapshot.value.copy(
            safetyConsentAccepted = true,
            safetyConsentVersion = CURRENT_SAFETY_CONSENT_VERSION,
            marketingOptIn = marketingOptIn
        )
        persistEntitlement(_snapshot.value)
    }

    fun canUseFeature(feature: CommercialFeature): Boolean =
        CommercialGate.isFeatureEnabled(feature, currentPlan)

    fun setTierWithEntitlement(requested: UserTier): TierChangeResult {
        val plan = currentPlan
        return if (CommercialGate.canUseTier(requested, plan)) {
            TierChangeResult.Applied(requested)
        } else {
            val clamped = CommercialGate.clampTier(requested, plan)
            TierChangeResult.Clamped(
                requested = requested,
                applied = clamped,
                reason = CommercialGate.upgradeHint(requested)
            )
        }
    }

    fun requiresSafetyConsent(): Boolean {
        val state = _snapshot.value
        return !state.safetyConsentAccepted ||
            state.safetyConsentVersion < CURRENT_SAFETY_CONSENT_VERSION
    }
}

internal expect fun persistEntitlement(snapshot: EntitlementSnapshot)
internal expect fun loadEntitlement(): EntitlementSnapshot?