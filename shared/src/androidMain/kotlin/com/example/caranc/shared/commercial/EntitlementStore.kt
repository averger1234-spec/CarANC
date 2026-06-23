package com.example.caranc.shared.commercial

import android.content.Context
import com.example.caranc.shared.AncTestPreferences

private const val PREFS = "caranc_entitlement"
private const val KEY_PLAN = "plan"
private const val KEY_SOURCE = "source"
private const val KEY_SAFETY = "safety_consent"
private const val KEY_SAFETY_VERSION = "safety_consent_version"
private const val KEY_MARKETING = "marketing_opt_in"

private var appContext: Context? = null

fun initEntitlementStore(context: Context) {
    appContext = context.applicationContext
    // Load via deprecated direct restore (prefs backed); will migrate to BillingRepository flows.
    // Scoped context recommended for testing/multi-session.
    @Suppress("DEPRECATION")
    loadEntitlement()?.let { EntitlementManager.restore(it) }
}

internal actual fun persistEntitlement(snapshot: EntitlementSnapshot) {
    val context = appContext ?: return
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        .putString(KEY_PLAN, snapshot.plan.id)
        .putString(KEY_SOURCE, snapshot.source)
        .putBoolean(KEY_SAFETY, snapshot.safetyConsentAccepted)
        .putInt(KEY_SAFETY_VERSION, snapshot.safetyConsentVersion)
        .putBoolean(KEY_MARKETING, snapshot.marketingOptIn)
        .apply()
}

internal actual fun loadEntitlement(): EntitlementSnapshot? {
    val context = appContext ?: return null
    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val planId = prefs.getString(KEY_PLAN, null) ?: return null
    val plan = SubscriptionPlan.entries.firstOrNull { it.id == planId } ?: SubscriptionPlan.FREE
    return EntitlementSnapshot(
        plan = plan,
        source = prefs.getString(KEY_SOURCE, "stored") ?: "stored",
        safetyConsentAccepted = prefs.getBoolean(KEY_SAFETY, false),
        safetyConsentVersion = prefs.getInt(KEY_SAFETY_VERSION, 0),
        marketingOptIn = prefs.getBoolean(KEY_MARKETING, false)
    )
}

/**
 * WARNING: DEV / QA ONLY client-side entitlement bypass.
 * Pure simulation of purchase with NO real commercial validation, receipts, or backend.
 * Directly mutates via deprecated EntitlementManager.setPlan.
 * This is the source of "commercial is pure client-side bypass".
 *
 * Harden: @Deprecated, will be removed/restricted for real billing prep.
 * Scoped context recommended for testing/multi-session.
 *
 * @deprecated Direct overrides; future use BillingRepository + secure flows only. Do not rely in production code.
 */
@Deprecated(
    message = "DevEntitlementOverrides is client-side bypass only. No real billing. Prepare replacement via BillingRepository.",
    level = DeprecationLevel.WARNING
)
object DevEntitlementOverrides {
    fun activate(plan: SubscriptionPlan) {
        // Uses deprecated direct setPlan (client bypass); callers from UI (CommercialPanel) etc. get deprecation warning.
        @Suppress("DEPRECATION")
        EntitlementManager.setPlan(plan, source = "dev_override")
    }
}

/**
 * Android skeleton impl of BillingRepository stub.
 * Uses the *current* prefs-backed mechanism (loadEntitlement from shared prefs).
 * This keeps parity during transition; real impl will query Play Billing / server.
 */
internal actual val billingRepository: BillingRepository = object : BillingRepository {
    override fun currentPlan(): SubscriptionPlan {
        // Current prefs one (EntitlementStore load logic).
        val stored = loadEntitlement()
        return stored?.plan ?: SubscriptionPlan.FREE
    }
}