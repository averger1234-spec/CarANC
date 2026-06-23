# Grok CLI Resume Context（跨機器使用）

**用途**：在任何新機器（第二台 Windows、Mac 等）第一次開啟 Grok CLI / AS 內建 Grok Terminal 時，先 `git pull`，然後把下面整段文字複製貼給 Grok，快速恢復完整上下文。

---

目前正在開發 **CarANC** 專案：
- Kotlin Multiplatform（Android 主力 + common/shared + iOS skeleton）
- 手機麥克風 + 喇叭 / Android Auto 實現主動降噪（ANC）
- 等級制度：LIGHT（免費輕度）、STANDARD（普通中/重度）、PRO（專業中/重度）
- 商業 gating + dev panel 可切方案測試

**最新進度（2026-06）**：
- 多機開發環境已建立（兩台 Windows + 一台 Mac）
- GitHub: https://github.com/averger1234-spec/CarANC.git
- 已完成第二台 Windows 的 clone + 巢狀資料夾清理（現在 C:\...\CarANC 是乾淨單層根目錄）
- 專案根目錄有 `MULTI_MACHINE_SYNC.md`（詳細 Git 流程 + iOS 準備）
- 最近重要修正（已 push）：
  - AudioEngine.kt tier 相關 bug：
    - 按開始降噪**前**先切 STANDARD/PRO → 啟動會閃退
    - STANDARD/PRO 開啟後喇叭**沒有聲響**
    - LIGHT 開喇叭有**白噪音**
  - 修正內容：
    - processor 建立時重新 snapshot `initialTier`（避免 stale）
    - 非 LIGHT 的 learning delay 期間主動寫 silence block 餵 audioTrack（避免 underrun 導致無聲）
    - 高 latency 情境（AA remote-submix 等）自動把 anti-output gain 壓到 0.28x（減少無效 anti 造成的白噪音 artifact），好 latency 時維持原 gain
    - 新增 "processor_created" log 方便診斷實際用哪個 tier 建立
- 目前第二台機器已 `git pull` 成功，結構正確（dir 顯示 app、shared、gradlew、MULTI_MACHINE_SYNC.md 直接在根）
- 下一步：用 AS 開該資料夾 → Sync Gradle → 測試 tier 修正（CommercialPanel 切付費方案 → 切中/重度 tier → 開始降噪，看是否還會閃退 + 聲音是否正常）
- 之後目標：Mac 上 build iOS framework + 建立最小 Xcode 測試專案（給朋友用 iPhone 測試）

**重要文件**：
- MULTI_MACHINE_SYNC.md（必讀）
- GROK_RESUME_CONTEXT.md（本檔）

**請以這個上下文為基礎繼續協助**。之後我在任何機器開新 Grok 時，會先貼這段摘要 + 最近遇到的問題。

---

**如何使用**：
1. 在新機器 `cd` 到專案資料夾
2. `git pull origin main`
3. 開 Grok CLI / AS Terminal（Alt+F12）
4. 直接把上面「---」到「---」之間的文字整段貼給 Grok
5. 就可以繼續問問題，不用從頭解釋

需要我更新這份摘要時，直接告訴我「更新 Grok resume context」。