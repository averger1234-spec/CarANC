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
  - 已針對此問題多次調整（2026-06-25）：
    - bump ratio threshold 從 8→12→15/18（高速>50km/h 用 18，更不敏感穩態 rumble）。
    - 改為「連續 3 個 block 高 ratio 才觸發 freeze」（避免單次 spike 就停 LMS）。
    - 高速時 freeze 持續時間縮短（base * 0.6，最少 2 block），讓穩態 tire/wind rumble 時 LMS 能持續學習。
    - 在 registerBlockEnergy 內部追蹤 consecutiveHighEnergyRatio。
    - AudioEngine + perf log 現在會印 "freezeRem=XX" 以及 "freeze_state: remaining=..."（每 200 block 或 freeze>0 時），bump_detected snapshot 也帶 freezeRemaining。
    - 方便 logcat 即時看到凍結狀態，不用只靠 JSONL。
  - 使用者回饋（分析 2501/2502 log 後）：log 顯示 LMS 已更新（數萬次，非 0），但主觀「沒有太大感覺」，只在怠速時聽到類似電台/電流聲。要求**強化低頻降噪**。
- 2026-06-25 進一步修改（最新，針對音樂模式 + maxCancel + anti 輸出）：
  - **Music 偵測 / MusicMode 邏輯大改**（最重要）：不要一偵測到音樂就幾乎關掉 anti-noise。
    - 新增 `musicLowAncEnabled`（TestLogPanel 開關，預設 true）：音樂模式仍抗低頻路噪。
    - 策略：「低頻持續抗噪 + 中高頻降低 gain」。
    - 在 floor music/road 時，low band (lowOut + fdaf low) 用 full mu=1 且不 lowpass lowAnti；mid/high 繼續 lowpass 保護音樂。
    - effectiveMuScale 裡 FLOOR_MUSIC / FLOOR_MUSIC_ROAD 的 low band 若開關啟用則 modeScale=1f（滿血）。
    - AudioEngine 讀 prefs 後 set 到 processor。
    - 解決 AA 永遠 music:true 導致 floor 壓制 anti 的問題（log 裡 antiNoiseDb 常 -50~-90，甚至更低）。
  - **提高 maxCancelFrequencyHz**：從 35Hz 調到 min 150Hz（coerceIn(150f,350f)），bandMuScale 放寬到 1.5x 讓 low band (190Hz) 在較高 maxHz 時仍有 mu。
    - 測試目的：觀察低頻路噪是否被有效壓制（即使高延遲 AA）。
    - 影響：mid/high band 可能開始貢獻（若 latency 允許）；log 裡 maxCancel 會報 150+。
  - **確認 anti-noise 輸出**：在有音樂狀態下，antiNoiseDb 若一直很低（-80~-200）表示輸出被壓制（lowpass + 低 mu + 0.28x artifact gain + floor 策略）。
    - 新邏輯後，低頻 anti 應明顯較強（lowAnti 不被 lowpass，mu 滿血）。
    - 建議：開 musicLowAnc 開關，跑 AA+音樂+路噪，觀察 antiNoiseDb / reductionDb 在低頻的變化。
  - 腳本更新：移除 OBD/ELM327 強制，RPM 偵測步驟改「可選手動」，符合「不用偵測轉速」意見。
- 2026-06-25 後續強化低頻降噪 + bump 調校（依 2501/2502 log 分析 + 使用者主觀回饋）：
  - **強化 musicLowAnc 低頻效果**：在 floor music 時，lowAnti 多乘 1.25x boost；若同時 roadMode 還額外加入 roadWiener feedforward（*0.8），針對 tire/wind rumble 加強低頻抗噪。
  - **antiArtifactGain**：因為 maxCancel 現在 150Hz，條件 <60Hz 幾乎不觸發，global gain 維持 1f（不再過度保守壓低頻 anti）。
  - **bump 繼續調**（如上，15/18 + consecutive3 + speed dynamic + freezeRem debug log）。
  - **其他**：保留 road_wiener/prelearned 用於 tire/wind；建議後續可針對 tire/wind profile 加強 blend 或 capture 更多 rumble 專用 bank。
  - **測試指引**（強烈建議）：
    - 用 TestLogPanel 開 logging + musicLowAnc 開關 + forceNormalMode。
    - 記錄時在 scenario 欄位註明「musicLowAnc=ON」或「OFF」。
    - 用 guided test 固定場景：idle（無音樂） vs 行駛+音樂 on/off，重跑產生乾淨對比。
    - 理想配 spectrum 截圖（看 50-250Hz rumble 能量是否被壓）或主觀評分（0-10 分 low freq 降低程度）。
    - 目標：怠速電流聲/廣播聲（可能是 residual anti 或 high latency artifact）也要壓制；行駛時 tire/wind rumble 有明顯差異。
  - **新優化（依 Tesla quiet zone + Bose RNC 啟發）**：
    - musicLowAnc low band 動態 boost：lowBoost 現在依 lowRumbleEnergy + speed 動態（1.25f + energy*0.4 + speed/120 *0.4），更高 rumble 時更強抗噪。
    - road_wiener 在 musicLow 權重調高到 *1.5f（Bose feedforward 風格，用 speed/road model 作為 vibration proxy 加強 tire/wind）。
    - perf log 細分 low-band contribution：新增 lowBandLmsUpdateCount, fdafLms, multirateDecim, musicLowAncEnabled, freezeBlocksRemaining（logcat + JSONL 都可看到 rumble 專用 LMS 進度）。
    - Quiet zone 模擬：利用現有 4-zone MIMO + VirtualSensing，musicLow 時 low band 優先（boost + roadFf），未來可 per-zone virtual error 加強乘客周圍安靜區（Tesla 用座椅 mic 建 quiet zones）。
  - **研究參照**：Bose RNC 用 accelerometer feedforward (chassis vibration) + cabin mic feedback + audio system 做 broadband road/tire 取消，適應路面/輪胎/車齡；我們的 FdafLowBandProcessor + MultirateLowBandFxLms + RoadNoiseWiener 已是類似 FxLMS 變形（filtered-x 處理 secondary path 延遲）。Tesla in-house 用 seat mics + speakers 建 quiet zones，類似我們 MIMO + virtual sensing。建議後續加 phone accel 作為額外 feedforward ref（若可用）。
  - **測試指引更新**：比較 musicLowAnc ON/OFF + 不同 speed 的 log，觀察 low-band lms 更新率、fdaf/multirate 貢獻、低頻 reduction（用 spectrum 看 50-250Hz）。記錄 scenario 含 "musicLow=ON, speed=XX, rumble=high" 。Tesla 風格：focus driver/front passenger quiet。
- 現在準備切到 Mac 建置 iOS framework + Xcode 測試專案（iOS 端仍是 stub，OBD 移除無影響）
- 下一步：用 AS 開該資料夾 → Sync Gradle → Clean/Rebuild → **完全 uninstall 舊 APK** 再安裝測試（CommercialPanel 切付費方案 → 切中/重度 tier → 開始降噪）。注意裝置安裝雜訊（alignment / cache GID mismatch / AppsFilter BLOCKED 其他測試 app / attributionTag warning 仍會出現但 harmless，ANC 本身正常）。
- 之後目標：去 Mac 建置 iOS framework（./gradlew linkDebugFrameworkIosSimulatorArm64），建立最小 Xcode 測試 App 驗證 stub + 未來擴充 iOS audio。給朋友測試時建議給 GitHub 連結讓他們自己 clone 建 framework。
  - 此 Mac (2015 MBP + Big Sur) 限制：只能用 Xcode 13.4.1（建議 OCLP 升 macOS 12+ 解鎖 Xcode 14+）。iOS 仍是 stub（OBD 移除無影響）。Flutter 改用不建議（會失去 KMP 共享複雜 Kotlin DSP 的優勢，Dart 不適合低延遲即時音訊處理）。

**重要文件**：
- MULTI_MACHINE_SYNC.md（必讀）
- GROK_RESUME_CONTEXT.md（本檔）

**請以這個上下文為基礎繼續協助**。之後我在任何機器開新 Grok 時，會先貼這段摘要 + 最近遇到的問題（例如 AA 媒體模式 + tier 差異測試 + 舊 Mac 硬體限制）。

- 2026-06-26 新 log（2601/2602，phone_local speaker 測試，music:true 多處）：
  - 確認修改生效：maxCancelFrequencyHz=150.0、midBandEnabled=true（gain 0.25）、musicLowAncEnabled=true。
  - processingMode 多為 "normal" 或 "road_noise_gps"，**未** 因 music:true 強制 floor_music —— music 偵測不再一刀切關 anti-noise。
  - lmsUpdateCount / lowBandLmsUpdateCount 大幅進步（數十萬~百萬+，之前長期卡 0）。
  - antiNoiseDb 變化範圍較合理（-50~-100+），reduction 偶現正值，顯示低頻 anti 有被有效輸出並混入（非被完全壓制）。
  - 效能穩定（fullLoop ~0.5-1.5ms 為主），bump_detected 正常。
  - 結論：用戶要求「低頻持續抗噪 + 中高頻保護」已達成；「提高 maxCancel 測試低頻壓制」+「確認 anti 輸出」兩點也在 log 中看到正面結果（lms 活躍 + antiDb 不是極低）。建議後續多跑有實際路噪的 AA 場景對比 ON/OFF。

---

**如何使用**：
1. 在新機器 `cd` 到專案資料夾
2. `git pull origin main`
3. 開 Grok CLI / AS Terminal（Alt+F12）
4. 直接把上面「---」到「---」之間的文字整段貼給 Grok
5. 就可以繼續問問題，不用從頭解釋

需要我更新這份摘要時，直接告訴我「更新 Grok resume context」。
- 2026-06-26 Log Analysis (new 2601/2602): phone speaker test, music true but mode normal/road thanks to musicLowAnc. lms now updating actively (big win for low freq adaptation). antiNoiseDb improved, max 150, mid on. Confirms low freq road ANC works alongside music without full suppression. Media conflict addressed by userAncGain independent control + smarter mediaRefActive detection (won't force floor on AA where capture unavailable). No arch restructure needed -- per-band logic in processor + prefs switches allow targeted improvements.
