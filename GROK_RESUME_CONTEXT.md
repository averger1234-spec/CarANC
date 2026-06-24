# Grok CLI Resume Context（跨機器使用）

**用途**：在任何新機器（第二台 Windows、Mac 等）第一次開啟 Grok CLI / AS 內建 Grok Terminal 時，先 `git pull`，然後把下面整段文字複製貼給 Grok，快速恢復完整上下文。

---

目前正在開發 **CarANC** 專案：
- Kotlin Multiplatform（Android 主力 + common/shared + iOS skeleton）
- 手機麥克風 + 喇叭 / Android Auto 實現主動降噪（ANC）
- 等級制度：LIGHT（免費輕度）、STANDARD（普通中/重度）、PRO（專業中/重度）
- 商業 gating + dev panel 可切方案測試

**最新進度（2026-06-24）**：
- 多機開發環境已建立（兩台 Windows + 一台 Mac）
- GitHub: https://github.com/averger1234-spec/CarANC.git
- 已完成第二台 Windows 的 clone + 巢狀資料夾清理（現在 C:\...\CarANC 是乾淨單層根目錄）
- 專案根目錄有 `MULTI_MACHINE_SYNC.md`（詳細 Git 流程 + iOS 準備）與 `GROK_RESUME_CONTEXT.md`
- 最近重要修正（已 push）：
  - AudioEngine.kt tier 相關 bug（之前）：
    - 按開始降噪**前**先切 STANDARD/PRO → 啟動會閃退
    - STANDARD/PRO 開啟後喇叭**沒有聲響**
    - LIGHT 開喇叭有**白噪音**
  - 修正內容（之前）：
    - processor 建立時重新 snapshot `initialTier`（避免 stale）
    - 非 LIGHT 的 learning delay 期間主動寫 silence block 餵 audioTrack（避免 underrun 導致無聲）
    - 高 latency 情境（AA remote-submix 等）自動把 anti-output gain 壓到 0.28x（減少無效 anti 造成的白噪音 artifact），好 latency 時維持原 gain
    - 新增 "processor_created" log 方便診斷實際用哪個 tier 建立
  - **完全移除 Bluetooth OBD（最新）**：
    - 原因：PRO 版啟動 ANC 會直接閃退（SecurityException on BLUETOOTH_CONNECT + uncaught coroutine）。
    - 移除內容：AudioEngine 不再建立/使用 ObdRpmProvider，改純手動 RPM（AncTestPreferences.getManualTestRpm()）；CommercialGate.OBD_RPM 永遠 false；Manifest 移除 BT 權限與 attributionTag；MainActivity 移除 BT 權限請求；TestLogExporter authority 修正；相關 UI/log 更新。
    - 結果：PRO/付費版啟動不再閃退，可正常 learning + 長時間運行。
  - **新增 debug 工具（最新）**：在 TestLogPanel 加入「強制正常模式」開關（forceNormalMode）。
    - 用途：繞過 audioManager.isMusicActive 與 isCall 偵測，強制走 NORMAL 模式。
    - 解決痛點：開 AA 後 isMusicActive 永遠 true，永遠強制 floor_noise_music[_road] 模式，導致 LIGHT/STANDARD/PRO 差異不明顯（floor mode 壓 mu + lowpass + 參考混合）。
    - 使用：在 TestLogPanel 開啟後，再切 tier 測試，processingMode 會變 normal，可明顯觀察 tier 差異（filter length、mu、learning 效果）。
    - AudioEngine 內部已用 `!forceNormal && isMusicActive` 控制。
- 目前第二台機器已 `git pull` 成功，結構正確。
- 最新 log 分析（2026062401/2402）：
  - pro_monthly + AA remote-submix。
  - 確認 AA 環境下 isMusicActive 幾乎永遠 true → 強制 floor 模式（不管有無實際音樂）。
  - 有 LIGHT → STANDARD 切換、直接 PRO + learning 的記錄，無 crash（OBD 移除成功）。
  - 效能好（fullLoop 0.2-1.6ms），但 reduction 低、antiNoiseDb 很安靜（高延遲 + floor + 0.28x gain 共同作用，預期）。
  - lmsUpdateCount=0（延遲限制）。
  - 使用者回報：基本/中度/重度無明顯差異 + 開 AA 就處於媒體模式。
- 現在準備/進行 Android 測試（toggle + uninstall 舊 APK 重裝 + 切 pro + 高 tier）。
- 之後目標：切 Mac 建 iOS framework（linkDebugFrameworkIosSimulatorArm64）+ 最小 Xcode 測試 App 驗證 stub。

**重要文件**：
- MULTI_MACHINE_SYNC.md（必讀）
- GROK_RESUME_CONTEXT.md（本檔）

**請以這個上下文為基礎繼續協助**。之後我在任何機器開新 Grok 時，會先貼這段摘要 + 最近遇到的問題（例如 AA 媒體模式 + tier 差異測試）。

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