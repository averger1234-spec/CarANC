package com.example.caranc.shared.commercial

object SafetyDisclaimer {
    const val TITLE = "安全與使用聲明"

    val bullets: List<String> = listOf(
        "CarANC 為輔助降噪工具，不能取代車廠原廠 ANC 或安全駕駛判斷。",
        "請在車輛靜止或安全情境下完成校正；行駛中請以交通安全為優先。",
        "本 App 需要麥克風、定位與（可選）藍牙權限以提供降噪與 OBD 功能。",
        "測試 Log 可能包含車型、路況與音訊統計，不含通話內容；匯出前請自行確認。",
        "若感到暈眩、耳壓不適或聽不見警報聲，請立即停止降噪。"
    )
}