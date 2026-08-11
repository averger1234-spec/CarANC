package com.example.caranc

import android.content.Context
import com.example.caranc.shared.GlobalAncSessionContext
import com.example.caranc.shared.commercial.EntitlementManager
import com.example.caranc.shared.commercial.SubscriptionPlan
import com.example.caranc.shared.commercial.initEntitlementStore

/**
 * Store-flavor launch policy until real Play Billing is wired.
 * Ensures public builds cannot retain a developer-unlocked paid plan from prefs.
 */
object StoreReleasePolicy {

    fun enforcePublicFreePlan(context: Context) {
        initEntitlementStore(context.applicationContext)
        val plan = EntitlementManager.currentPlan
        if (plan != SubscriptionPlan.FREE) {
            @Suppress("DEPRECATION")
            EntitlementManager.setPlan(SubscriptionPlan.FREE, source = "store_public_beta")
            GlobalAncSessionContext.tierManager.syncToEntitlement()
        } else {
            // Still clamp tier to free max (LIGHT) if user had higher tier in prefs.
            GlobalAncSessionContext.tierManager.syncToEntitlement()
        }
    }
}
