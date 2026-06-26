package com.example.caranc.shared.commercial

import com.example.caranc.shared.UserTier

enum class SubscriptionPlan(
    val id: String,
    val displayName: String,
    val marketLabel: String
) {
    FREE("free", "免費版", "體驗"),
    STANDARD_MONTHLY("standard_monthly", "標準版（月訂）", "日常通勤"),
    PRO_MONTHLY("pro_monthly", "專業版（月訂）", "完整車載"),
    PRO_YEARLY("pro_yearly", "專業版（年訂）", "完整車載·省更多"),
    LIFETIME("lifetime", "終身版", "一次買斷");

    val isPaid: Boolean get() = this != FREE

    fun maxTier(): UserTier = when (this) {
        FREE -> UserTier.LIGHT
        STANDARD_MONTHLY -> UserTier.STANDARD
        PRO_MONTHLY, PRO_YEARLY, LIFETIME -> UserTier.PRO
    }
}

enum class CommercialFeature(val id: String, val title: String) {
    TIER_STANDARD("tier_standard", "中度降噪"),
    TIER_PRO("tier_pro", "重度降噪"),
    GPS_ROAD_ANC("gps_road_anc", "GPS 路噪降噪"),
    MUSIC_BYPASS("music_bypass", "音樂底噪 bypass"),
    CALL_BYPASS("call_bypass", "通話語音保護"),
    MIMO_TRIAL("mimo_trial", "AMBEEO-lite 多區域"),
    OBD_RPM("obd_rpm", "OBD 引擎諧波參考（藍牙自動已移除，僅手動 RPM）"),
    LATENCY_OPTIMIZED("latency_optimized", "低延遲演算法套件"),
    GUIDED_TEST_FULL("guided_test_full", "完整實車引導測試 v3"),
    LOG_EXPORT("log_export", "測試 Log 匯出"),
    PROFILE_CLOUD("profile_cloud", "車廂 Profile 雲端同步（規劃中）")
}

object ProductCatalog {
    const val PRODUCT_NAME = "CarANC"
    const val PRODUCT_TAGLINE = "手機即裝即用車內主動降噪"
    const val MARKET_POSITION = "Android Auto 通勤族 · 二手車／無原廠 ANC 車主"
    // 注意：目前沒有獨立網站（caranc.app 僅為預留域名）。
    // 隱私政策與服務條款暫時指向 GitHub 儲存庫的 Markdown 文件（可直接在瀏覽器閱讀渲染後內容）。
    // 未來有正式網站後，再更新為 https://caranc.app/privacy 等。
    // App 內也提供完整文字的 AlertDialog（離線可用）。
    const val PRIVACY_POLICY_URL = "https://github.com/averger1234-spec/CarANC/blob/main/PRIVACY.md"
    const val TERMS_URL = "https://github.com/averger1234-spec/CarANC/blob/main/TERMS.md"
    const val SUPPORT_EMAIL = "support@caranc.app"

    val planOrder: List<SubscriptionPlan> = listOf(
        SubscriptionPlan.FREE,
        SubscriptionPlan.STANDARD_MONTHLY,
        SubscriptionPlan.PRO_MONTHLY,
        SubscriptionPlan.PRO_YEARLY,
        SubscriptionPlan.LIFETIME
    )

    fun suggestedPriceHint(plan: SubscriptionPlan): String = when (plan) {
        SubscriptionPlan.FREE -> "NT$ 0"
        SubscriptionPlan.STANDARD_MONTHLY -> "NT$ 99–149 / 月"
        SubscriptionPlan.PRO_MONTHLY -> "NT$ 199–299 / 月"
        SubscriptionPlan.PRO_YEARLY -> "NT$ 1,490–1,990 / 年"
        SubscriptionPlan.LIFETIME -> "NT$ 2,990–4,990 一次"
    }

    fun featuresForPlan(plan: SubscriptionPlan): List<CommercialFeature> =
        CommercialFeature.entries.filter { CommercialGate.isFeatureEnabled(it, plan) }
}