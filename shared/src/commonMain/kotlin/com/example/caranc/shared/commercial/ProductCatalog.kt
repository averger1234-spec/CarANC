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
    /**
     * First Play listing strategy: free public beta (no real Billing yet).
     * Paid plan rows are shown as "即將推出", not purchasable.
     */
    const val PUBLIC_BETA = true
    const val PUBLIC_BETA_LABEL = "公開免費體驗版"
    const val PUBLIC_BETA_NOTE =
        "目前為公開免費體驗：免費方案僅開放「輕度」降噪。付費方案與 Google Play 訂閱將於後續版本啟用。"

    // 注意：目前沒有獨立網站（caranc.app 僅為預留域名）。
    // 隱私政策與服務條款暫時指向 GitHub 儲存庫的 Markdown 文件（可直接在瀏覽器閱讀渲染後內容）。
    // 未來有正式網站後，再更新為 https://caranc.app/privacy 等。
    // App 內也提供完整文字的 AlertDialog（離線可用）。
    const val PRIVACY_POLICY_URL = "https://github.com/averger1234-spec/CarANC/blob/main/PRIVACY.md"
    const val TERMS_URL = "https://github.com/averger1234-spec/CarANC/blob/main/TERMS.md"
    const val SUPPORT_EMAIL = "support@caranc.app"
    /** Short store listing blurb (also useful in Play Console). */
    const val STORE_SHORT_DESCRIPTION = "手機麥克風收音、即時反相降噪，搭配 Android Auto 車機喇叭。"
    /** Full Play listing text (not const — uses trimIndent). */
    val STORE_FULL_DESCRIPTION: String = """
CarANC 讓沒有原廠主動降噪的車主，用手機即可體驗車內軟體 ANC。

【主要功能】
• 多頻帶主動降噪（主攻低頻引擎／路噪）
• 車廂聲學校正與情境模式（怠速、路噪、音樂保護）
• 安全聲明與警覺提示（不可取代駕駛注意力）
• Android Auto 連線支援（依車機／手機而定）

【公開體驗版說明】
目前為免費公開體驗，開放「輕度」降噪。中度／重度與訂閱方案將於後續更新。

【重要安全聲明】
本 App 為輔助降噪工具，無法保證消除所有噪音，也絕不能取代您對道路、行人與警笛的注意力。請遵守交通法規，並於安全情況下使用。

【權限】
• 麥克風：即時收音做 ANC（音訊在裝置端處理，不上傳）
• 定位：取得車速以切換路噪模式（不上傳軌跡到伺服器）
• 通知／前景服務：降噪進行中狀態

隱私政策與服務條款見 App 內「方案」分頁，或 GitHub 公開文件。
支援：support@caranc.app
""".trimIndent()

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