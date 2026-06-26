package com.example.caranc.shared.commercial

/**
 * 隱私政策文字（應用內顯示版 + 未來網站同步來源）
 * 目前無獨立網站，完整版同步維護於 GitHub：
 * https://github.com/averger1234-spec/CarANC/blob/main/PRIVACY.md
 */
object PrivacyPolicy {
    const val TITLE = "隱私政策"
    const val LAST_UPDATED = "2026-06-26"
    const val VERSION = "v1.0"

    const val SHORT_SUMMARY =
        "本應用程式（CarANC）僅在您的裝置本機記錄測試資料，包括車型、測試場景、手機放置位置、麥克風音訊統計、車速、降噪參數等，用於個人降噪效果調校與分析。\n\n" +
        "資料完全不會上傳至任何伺服器、雲端服務或分享給第三方。\n\n" +
        "您可隨時在「測試平台」匯出或刪除 log 檔。\n\n" +
        "本應用為實驗性質，使用時請注意周圍環境安全。"

    val PARAGRAPHS: List<String> = listOf(
        "CarANC（以下稱「本應用」）重視您的隱私。本政策說明我們如何處理資料。",
        "資料收集與使用：\n• 本應用程式僅在本機裝置進行所有處理與記錄。\n• 收集的資訊僅限於：車型、測試情境描述、麥克風放置位置、車速估計、降噪演算法參數、音訊 RMS/頻譜統計（不含原始語音或通話內容）、以及您主動輸入的 log 註記。\n• 音訊資料永遠不會離開您的裝置，也不會用於訓練模型或任何雲端服務。\n• 完全無第三方分析、廣告 SDK、crash report 上傳（除非您主動匯出 log 並自行分享）。",
        "權限說明：\n• 麥克風：用於即時 ANC 收音與 log 記錄。\n• 定位：用於車速估計與路噪情境（GPS road ANC）。\n• 通知/前景服務：維持 ANC 持續運作。\n• 儲存：僅用於匯出 log 檔案到您選擇的位置。",
        "資料儲存與刪除：\n• 所有 log 檔案儲存在本機「log」目錄。\n• 您可隨時透過 App 內「測試平台」匯出或手動刪除檔案。\n• 解除安裝本應用即清除所有本地資料。",
        "無資料外傳：\n本應用設計上「不上傳任何資料」。若未來加入雲端 profile 同步功能，將事先更新本政策並徵求同意。",
        "聯絡：support@caranc.app（暫時，GitHub Issues 亦可）\n\n變更：本政策可能更新，更新後會在 App 內版本與 GitHub 同步公告。"
    )

    const val GITHUB_URL = "https://github.com/averger1234-spec/CarANC/blob/main/PRIVACY.md"
}