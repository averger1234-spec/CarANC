# Grok CLI Resume Context（跨機器使用）

**用途**：在任何新機器（第二台 Windows、Mac 等）第一次開啟 Grok CLI / AS 內建 Grok Terminal 時，先 `git pull`，然後把下面整段文字複製貼給 Grok，快速恢復完整上下文。

---

目前正在開發 **CarANC** 專案：
- Kotlin Multiplatform（Android 主力 + common/shared + iOS skeleton）
- 手機麥克風 + 喇叭 / Android Auto 實現主動降噪（ANC）
- 等級制度：LIGHT（免費輕度）、STANDARD（普通中/重度）、PRO（專業中/重度）
- 商業 gating + dev panel 可切方案測試

**最新進度（2026-06-25）**：
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
    - 原因：PRO 版（CommercialPanel 切專業）啟動 ANC 會直接閃退（FATAL EXCEPTION: SecurityException: Need android.permission.BLUETOOTH_CONNECT ... getBondedDevices in ObdRpmProvider.autoDiscoverAndConnect）。側邊 coroutine 未 catch，導致 process 被 SIG 9 kill。裝置上無 BT 權限 + auto scan 觸發。
    - 移除內容：
      - AudioEngine.kt：移除 ObdRpmProvider 建立、start/stop、currentSnapshot 使用。改為只用 AncTestPreferences.getManualTestRpm() 提供 RPM（valid = manualRpm > 0f），用於 EngineCombCanceller 引擎諧波。
      - CommercialGate.kt：OBD_RPM 永遠回傳 false（feature 仍保留在清單但 disabled）。
      - ANCService.kt、ProductCatalog.kt、SafetyDisclaimer.kt、商業 panel 相關 log/comment 更新。
      - Manifest：移除 BLUETOOTH_CONNECT / BLUETOOTH_SCAN（之前為 OBD 加的）。移除 android:attributionTag（會造成 AAPT build error "attribute not found"）。
      - MainActivity.kt：移除 BT 權限請求流程（恢復原本 record audio + location chain）。
      - TestLogExporter.kt：FileProvider authority 從 ".fileprovider" 改為 ".provider"（避免 "Typo: In word 'fileprovider'" 警告 + tools:ignore="Typos"）。
    - 結果：build 乾淨（無 attributionTag / fileprovider 錯誤）。付費版（標準/專業 + 中/重度）啟動 ANC 不再閃退，可正常 learning + 長時間運行（perf block 數萬，fullLoop ~0.25-0.6ms，mode floor_noise_music 正常）。手動 RPM 仍可透過 TestLogPanel / prefs 設定測試引擎諧波。
  - **新增 debug 工具（最新）**：在 TestLogPanel 加入「強制正常模式」開關（forceNormalMode）。
    - 用途：繞過 audioManager.isMusicActive 與 isCall 偵測，強制走 NORMAL 模式。
    - 解決痛點：開 AA 後 isMusicActive 永遠 true，永遠強制 floor_noise_music[_road] 模式，導致 LIGHT/STANDARD/PRO 差異不明顯（floor mode 壓 mu + lowpass + 參考混合）。
    - 使用：在 TestLogPanel 開啟後，再切 tier 測試，processingMode 會變 normal，可明顯觀察 tier 差異（filter length、mu、learning 效果）。
    - AudioEngine 內部已用 `!forceNormal && isMusicActive` 控制。
- 目前第二台機器已 `git pull` 成功，結構正確（dir 顯示 app、shared、gradlew、MULTI_MACHINE_SYNC.md 直接在根）
- 已將 OBD 移除 + manifest 清理 + MULTI_MACHINE_SYNC.md 更新 + resume 更新 commit 並 push（包括這次的 MULTI_MACHINE_SYNC.md 補 commit）。Windows 端 git 狀態已 clean + up-to-date with origin/main（2026-06-25 push 完成 merge resolve + 清除重複段落）。
- 最新 log 分析（2026062401/2402）：
  - pro_monthly + AA remote-submix。
  - 確認 AA 環境下 isMusicActive 幾乎永遠 true → 強制 floor 模式（不管有無實際音樂）。
  - 有 LIGHT → STANDARD 切換、直接 PRO + learning 的記錄，無 crash（OBD 移除成功）。
  - 效能好（fullLoop 0.2-1.6ms），但 reduction 低、antiNoiseDb 很安靜（高延遲 + floor + 0.28x gain 共同作用，預期）。
  - lmsUpdateCount=0（延遲限制）。
  - 使用者回報：基本/中度/重度無明顯差異 + 開 AA 就處於媒體模式。
- 2026-06-25 logcat/ 新增分析（單一 logcat dump，~06:45，process 8792，~40 秒短測試）：
  - **手機直連模式（非 AA）**：Car Connection Type: 0，AudioRoute 偏好 SPEAKER + bottom mic，無 remote_submix，預期 latency 較低。
  - ANC 啟動正常：FGS 成功、載入 skoda_octavia_2019 profile、通知「主動降噪運作中 [低]」、GPS 啟動。
  - **全程 mode=normal**（2900+ perf log，符合 forceNormal 目標；無 music 強制 floor/road）。
  - 效能：fullLoop 暖機後 ema ~0.5-2ms（偶 spike 5-8ms），block 跑到 ~21000+，整體穩定。
  - **但 lmsUpdates 依然永遠=0**（和 AA log 相同核心問題！即使 normal + 低延遲手機喇叭也沒更新）。
  - 無明顯 bump log（bump 只寫 JSONL session log）、無 isMusic log（符合 normal）、nativeLowAvail=false（proto 目前 no-op）。
  - 關閉正常（onDestroy、資源釋放）。雜訊仍有一些（attributionTag E、MediaPlaybackCapture W on API37、FGS tracking W）。
  - 開頭有 chooser 分享舊 session log。
  - 結論：LMS 不更新問題不限於 AA remote（bump 凍結太敏感 ratio>8 即 freeze 4-12 blocks，或 muScale 在 low band 為 0，或測試環境能量不足）。對 tire/wind rumble 仍是主要瓶頸（即使避開 AA 地獄，學習仍停）。
  - 對應 code：MultiBandANCProcessor BandFxLms.processSample 需 !freeze && muScale>0 才 ++ lmsUpdateCount；registerBlockEnergy 是主凍結來源。
  - 已針對此問題小幅調整（2026-06-25）：把 bump ratio threshold 從 8.0f 放寬到 12.0f（較不敏感於穩態 rumble），並在 AudioEngine 加上 Log.d("ANCService", "bump_detected...") 讓 logcat 也能看到 freeze 事件。後續可再調或依 speed 動態。
- 現在準備切到 Mac 建置 iOS framework + Xcode 測試專案（iOS 端仍是 stub，OBD 移除無影響）
- 下一步：用 AS 開該資料夾 → Sync Gradle → Clean/Rebuild → **完全 uninstall 舊 APK** 再安裝測試（CommercialPanel 切付費方案 → 切中/重度 tier → 開始降噪）。注意裝置安裝雜訊（alignment / cache GID mismatch / AppsFilter BLOCKED 其他測試 app / attributionTag warning 仍會出現但 harmless，ANC 本身正常）。
- 之後目標：去 Mac 建置 iOS framework（./gradlew linkDebugFrameworkIosSimulatorArm64），建立最小 Xcode 測試 App 驗證 stub + 未來擴充 iOS audio。給朋友測試時建議給 GitHub 連結讓他們自己 clone 建 framework。
  - 此 Mac (2015 MBP + Big Sur) 限制：只能用 Xcode 13.4.1（建議 OCLP 升 macOS 12+ 解鎖 Xcode 14+）。iOS 仍是 stub（OBD 移除無影響）。Flutter 改用不建議（會失去 KMP 共享複雜 Kotlin DSP 的優勢，Dart 不適合低延遲即時音訊處理）。

**重要文件**：
- MULTI_MACHINE_SYNC.md（必讀）
- GROK_RESUME_CONTEXT.md（本檔）

**請以這個上下文為基礎繼續協助**。之後我在任何機器開新 Grok 時，會先貼這段摘要 + 最近遇到的問題（例如 AA 媒體模式 + tier 差異測試 + 舊 Mac 硬體限制）。

---

**如何使用**：
1. 在新機器 `cd` 到專案資料夾
2. `git pull origin main`
3. 開 Grok CLI / AS Terminal（Alt+F12）
4. 直接把上面「---」到「---」之間的文字整段貼給 Grok
5. 就可以繼續問問題，不用從頭解釋

需要我更新這份摘要時，直接告訴我「更新 Grok resume context」。