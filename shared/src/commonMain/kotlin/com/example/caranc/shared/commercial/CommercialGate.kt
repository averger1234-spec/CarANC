package com.example.caranc.shared.commercial

import com.example.caranc.shared.UserTier

object CommercialGate {

    fun isFeatureEnabled(feature: CommercialFeature, plan: SubscriptionPlan): Boolean = when (feature) {
        CommercialFeature.TIER_STANDARD -> plan != SubscriptionPlan.FREE
        CommercialFeature.TIER_PRO -> plan.maxTier() == UserTier.PRO
        CommercialFeature.GPS_ROAD_ANC -> plan != SubscriptionPlan.FREE
        CommercialFeature.MUSIC_BYPASS -> plan != SubscriptionPlan.FREE
        CommercialFeature.CALL_BYPASS -> plan != SubscriptionPlan.FREE
        CommercialFeature.MIMO_TRIAL -> plan.maxTier() == UserTier.PRO
        CommercialFeature.OBD_RPM -> false  // Bluetooth OBD removed (not needed); manual RPM via AncTestPreferences still available for testing
        CommercialFeature.LATENCY_OPTIMIZED -> plan != SubscriptionPlan.FREE
        CommercialFeature.GUIDED_TEST_FULL -> plan != SubscriptionPlan.FREE
        CommercialFeature.LOG_EXPORT -> true
        CommercialFeature.PROFILE_CLOUD -> false
    }

    fun canUseTier(tier: UserTier, plan: SubscriptionPlan): Boolean = when (tier) {
        UserTier.LIGHT -> true
        UserTier.STANDARD -> isFeatureEnabled(CommercialFeature.TIER_STANDARD, plan)
        UserTier.PRO -> isFeatureEnabled(CommercialFeature.TIER_PRO, plan)
    }

    fun clampTier(requested: UserTier, plan: SubscriptionPlan): UserTier {
        if (canUseTier(requested, plan)) return requested
        return plan.maxTier()
    }

    fun upgradeHint(tier: UserTier): String = when (tier) {
        UserTier.STANDARD -> "升級標準版以使用中度降噪、GPS 路噪與音樂 bypass"
        UserTier.PRO -> "升級專業版以使用重度降噪、MIMO 試作（引擎諧波參考改用手動 RPM 設定）"
        UserTier.LIGHT -> ""
    }
}