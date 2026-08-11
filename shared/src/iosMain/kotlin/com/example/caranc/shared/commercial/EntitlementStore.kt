package com.example.caranc.shared.commercial

import platform.Foundation.NSUserDefaults

private const val PREFS_SUITE = "caranc_entitlement"
private const val KEY_PLAN = "plan"
private const val KEY_SOURCE = "source"
private const val KEY_SAFETY = "safety_consent"
private const val KEY_SAFETY_VERSION = "safety_consent_version"
private const val KEY_MARKETING = "marketing_opt_in"

private fun defaults(): NSUserDefaults = NSUserDefaults.standardUserDefaults

internal actual fun persistEntitlement(snapshot: EntitlementSnapshot) {
    val d = defaults()
    d.setObject(snapshot.plan.id, KEY_PLAN)
    d.setObject(snapshot.source, KEY_SOURCE)
    d.setBool(snapshot.safetyConsentAccepted, KEY_SAFETY)
    d.setInteger(snapshot.safetyConsentVersion.toLong(), KEY_SAFETY_VERSION)
    d.setBool(snapshot.marketingOptIn, KEY_MARKETING)
    d.synchronize()
}

internal actual fun loadEntitlement(): EntitlementSnapshot? {
    val d = defaults()
    val planId = d.stringForKey(KEY_PLAN) ?: return null
    val plan = SubscriptionPlan.entries.firstOrNull { it.id == planId } ?: SubscriptionPlan.FREE
    return EntitlementSnapshot(
        plan = plan,
        source = d.stringForKey(KEY_SOURCE) ?: "stored",
        safetyConsentAccepted = d.boolForKey(KEY_SAFETY),
        safetyConsentVersion = d.integerForKey(KEY_SAFETY_VERSION).toInt(),
        marketingOptIn = d.boolForKey(KEY_MARKETING)
    )
}

internal actual val billingRepository: BillingRepository = object : BillingRepository {
    override fun currentPlan(): SubscriptionPlan {
        return loadEntitlement()?.plan ?: SubscriptionPlan.FREE
    }
}
