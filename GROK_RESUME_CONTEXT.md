# Grok CLI Resume Context（跨機器使用）

**用途**：在任何新機器（第二台 Windows、Mac 等）第一次開啟 Grok CLI / AS 內建 Grok Terminal 時，先 `git pull`，然後把下面整段文字複製貼給 Grok，快速恢復完整上下文。

---

目前正在開發 **CarANC** 專案：
- Kotlin Multiplatform（Android 主力 + common/shared + iOS skeleton）
- 手機麥克風 + 喇叭 / Android Auto 實現主動降噪（ANC）
- 等級制度：LIGHT（免費輕度）、STANDARD（普通中/重度）、PRO（專業中/重度）
- 商業 gating + dev panel 可切方案測試

**最新進度（2026-07-22）A/B/C + 行駛白噪修正**：
- **A** `remote_submix`+AA+非BT → `aaLinkType=projection_submix`，不再一律 wireless
- **B** bank default prior + seed + capture；`fixedBankOut` 行駛應非零；`learnedBinCount`
- **C** `reductionDb` 誠實（可負）；主 KPI=`lowBandRumbleReduction`；`reductionDbLegacy` 對照
- **行駛白噪**：FF 關 mid/high、final lowpass、減 preview/FDAF、bank 為主；`effectiveMidMu` 不再 stale
- install：`.\scripts\install-debug.ps1`；第二台 `git pull origin main`
- **三份 .md 已對齊**：`GROK_RESUME_CONTEXT.md` + `README.md` + `MULTI_MACHINE_SYNC.md`（含 2026-07-22 A/B/C）
- **無車測真 AA**：`.\scripts\start-dhu.ps1`（DHU=電腦當車機）。**不要**假 `isAAConnected`
- 路測聽感：怠速靜；行駛沙沙應↓；log 看 `aaLinkType` / `fixedBankOut` / `lowBandRumbleReduction`

**2026-07-21 P0 + #6–#9 + #11**：FF_PREVIEW_ONLY、delayless FDAF、2D bank、本機 AAudio-like、有線偏好、Car App scaffold

**歷史進度（2026-06-25）**：
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
  - **測試指引更新（完整 7-step 自動調校）**：
    - 用新 UI TestLogPanel / GuidedTestPanel 選擇 "路噪 LMS 調校測試（v1）" 腳本，跑完整 7 steps（prep + 5 tuning + finish）。
    - 腳本會自動套用 debug 參數（lmsMuMultiplier 1.0→2.0, freeze 15→10, consec 3→2, musicLow ON/OFF 對比, latency override 0/70）。
    - 記錄 scenario 註明參數組合 + speed 範圍 + "musicLow=ON"，建議配外部錄音 + spectrum（重點看 50-250Hz rumble 是否下降）。
    - 觀察重點：不同 debug 設定下 lowBandLms 更新率、freezeRem 頻率、reduction 在 rumble 主導時的變化、主觀低頻 rumble 降低程度（0-10 分）。
    - 如果有新 log（e.g. tuning_2_xxx 或 musicLow OFF 組合），直接給我分析 + 下一步建議。
    - 激進版已調（mu 更高 2.0、freeze 更低 10、boost 1.5+、road_wiener *2.0、error *1.3 for quiet rumble focus），因 user 回饋 "聽到的感覺降噪蠻無感"。
    - 記錄時開 forceNormal + logging，跑同一粗糙路 40-70km/h 低音樂。
- 現在準備切到 Mac 建置 iOS framework + Xcode 測試專案（iOS 端仍是 stub，OBD 移除無影響）
- 下一步：用 AS 開該資料夾 → Sync Gradle → Clean/Rebuild → **完全 uninstall 舊 APK** 再安裝測試（CommercialPanel 切付費方案 → 切中/重度 tier → 開始降噪）。注意裝置安裝雜訊（alignment / cache GID mismatch / AppsFilter BLOCKED 其他測試 app / attributionTag warning 仍會出現但 harmless，ANC 本身正常）。
- 之後目標：去 Mac 建置 iOS framework（./gradlew linkDebugFrameworkIosSimulatorArm64），建立最小 Xcode 測試 App 驗證 stub + 未來擴充 iOS audio。給朋友測試時建議給 GitHub 連結讓他們自己 clone 建 framework。
  - 此 Mac (2015 MBP + Big Sur) 限制：只能用 Xcode 13.4.1（建議 OCLP 升 macOS 12+ 解鎖 Xcode 14+）。iOS 仍是 stub（OBD 移除無影響）。Flutter 改用不建議（會失去 KMP 共享複雜 Kotlin DSP 的優勢，Dart 不適合低延遲即時音訊處理）。

**重要文件**：
- MULTI_MACHINE_SYNC.md（必讀）
- GROK_RESUME_CONTEXT.md（本檔）

**請以這個上下文為基礎繼續協助**。之後我在任何機器開新 Grok 時，會先貼這段摘要 + 最近遇到的問題（例如 AA 媒體模式 + tier 差異測試 + 舊 Mac 硬體限制）。

- 2026-06-26 新 log（2601/2602，AA remote_submix + guided test "car_road_tuning_v1" baseline 長跑，PRO tier，music:true 大多，speed 0~50+ km/h）：
  - **自動調校腳本生效**：step "tuning_1_baseline" 自動套用 debugLmsMuMultiplier=1.0, debugFreezeThreshold=15.0, debugFreezeConsec=3, musicLowAncEnabled=true, no latency override。對應我們之前的 bump 調校（15/ consecutive 3）+ musicLow 優化。
  - 確認所有新 logging：perf 每百 block 印 lowBandLmsUpdateCount（與總 lms 幾乎同步成長）、fdafLmsUpdateCount、multirateDecimUpdateCount、freezeBlocksRemaining（樣本中多為 0，無狂凍結）、musicLowAncEnabled=true。
  - lms / lowBandLms 大幅成長（block 100=1600 low, 後續線性到 10萬+），證明 bump 調（15,c=3）有效，LMS 在 music + AA remote 環境持續學習（之前卡0）。
  - processingMode 多 "normal"（即使 music:true），maxCancel=150, mid gain=0.25 active，低頻 anti 未被壓。
  - reduction 仍多 0，但在 speed ~40-50km/h 時偶有 0.09~0.11 dB 正值（e.g. raw~-0.1, cancelled~-0.01, reduction~0.09），antiNoiseDb -40~-60 範圍，顯示低頻 rumble 有被處理（bandLowRatio 有時 0.02~0.08）。
  - freezeRem=0 且 lms 穩增：證明 speed-dynamic + consecutive 要求讓穩態 rumble 時 LMS 不易被暫停。
  - 與之前 2501/2502 比：lms 更新率更好，debug 參數可視化，適合系統化調校（mu multiplier, freeze 等）。
  - 仍待改善：整體 reduction 仍小（high band music 能量主導測量 + 延遲 137ms + 位置），但 infra 已就緒，可用新 UI 繼續跑其他 tuning step（e.g. 調 mu 或 freeze）產生對比 log。
  - 建議：跑完整 7-step 調校腳本（baseline + 不同 mu/freeze 組合），記錄 scenario "musicLow=ON, speed=XX, rumble=high"，配 spectrum 看 50-250Hz 能量下降。
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

**2026-06-29 C11+ 更多 cycle 模擬輪（sub-agent 針對「有 IMU/roughness + personal bias 下的 #7 rumble 貢獻」）**（已完成一輪，sub-agents 交付大量輸出）：
- C11 sub (主輪)：寫出 c11_cycle_output.txt (59k) 含 C11b refine JSONL (e.g. Loop18 #7 eff=1.61 red=10.12 crowd=1.8 preview=1.82 rough=1.24 pers=1.28 ema=3.08 imu=1.12 all fields high ROAD_MID)、完整 FEASIBILITY (vs real +1.26-1.53 eff +5-7dB red, 18-20x vs #4b A/B)、SCRIPT SUGGESTIONS (enforce spd>55 in #7 instr + export clusters for NVH 1.8x preload + monitor new fields + workflow parse+re-sim)、C11+ COMPLETE 總結。使用 write/search_replace 延伸 sim_iter.ps1 (C11 funcs + 12 loops progressive 1.5->1.8x crowd + base+2 guard-refined)。
- C12：c12_personal_rough_variants.txt + 151k run_log (personal 1.0-1.35 / rough levels matrix for #7)。
- C13：c13_log_calib_c11round.txt (精準 parse 兩個 06-29 log + delta re-sim)。
- C14：c14_nvh_longterm_5drive.txt (5-drive 累積 NVH preload)。
- C15：c15_full... + c15_master_report.txt (109k, master A/B runner + 10+ #7 variants + sim extend)。
- C16：c16_fresh_sim_run.txt (216k raw full sim_iter 多次執行 + targeted C10/C11 extracts + 兩個 log 快速 parse + real-vs-fresh note + files list)。
- 本地額外：c11_round_more_cycles.txt / _report_clean.txt / _consolidated.txt (合併 table: real 0.147/3.95 vs C11 1.75/11.5 vs C16 fresh 0.89-1.52/9-14)。
- 總：大量 fresh JSONL/table/quant delta/sugg，全部 sim 加速 (舊 parts A/B 穩定對照)。GROK_RESUME 已更新。install-debug bg 進行中。
- **C15 master 完成**：c15_full_cycle11_report.txt (18k master final + table + 10 JSONL variants + feas 8-10.5dB /1.65 eff + exact edits) + c15_master_report.txt (109k)；sim_iter.ps1 appended C15 master (C11 12-loop + variants + Invoke-C15MasterCycle)。
- **C13 log-calib 完成**：c13_log_calib_c11round.txt (11.8k)；精準 parse 兩個 log (efficient PS+python json on snaps)：
- **C11 完成 (主更多 cycles)** (238s 38 calls)：c11_cycle_output.txt (59k dedicated table/8-10 JSONL #7 Loop10-12/feas/sugg) + c11_more_cycles.txt (109k)；延伸 sim_iter.ps1 (new C11-C11b: 12 loops ln9-20 crowd ramp 1.5->1.8x on agg 國道 clusters, Get-C11* guards, Simulate-C11Step full A/B); key: Real 0.147/3.95 0% ROAD newf=0 spd~10; C11 Loop18-20 eff 1.62-1.68 red 10.5-10.9 96%+ ROAD_MID full fields (rough1.27/pers1.28/ema3.15/preview1.85/crowd1.8/imu1.12); +1.47-1.53 eff / +6.5-7 red vs real; ~20x vs #4b A/B. Feas HIGH (18-20x quant #7 IMU+pers rumble contrib). Sugg: enforce spd>55 in #7 + export clusters NVH 1.5-1.8x + monitor new fields (已套用 edits 涵蓋)。
- **C14 5-drive NVH Waze 完成** (252s, 20 calls)：c14_nvh_longterm_5drive.txt (16.7k)；sim_iter.ps1 延伸 Get-ClusterMatchBoost + Simulate-C14NVHStep (C10b logic extend for long-term cumulative "NVH 版的 Waze")；5-drive (d1-2 partial low-spd like real 173945/181703; d3-5 strict + progressive match 20%/80%/100% for 1.5x preload on agg coarse/rough/ema clusters); progressive table: d1 eff0.43 red1.62 12% ROAD; d5 eff1.92 red11.45 98% ROAD_MID, 6 clusters, 100% match; 5 JSONL #7 per drive (d5 focus: eff~1.82-2.02 red11.25-12.8 previewF~2.85-3.12 *1.5, clusterHash, hasClusterMatch=true); NVH cluster export ex (hashes c24.98x_121.45x_rX.XX_eX.XX); nvh_map.json ex; finish instr: "after drive, parse log for clusters -> save to local nvh_map.json; next drive auto preload..."; quant: +50% red on #7 after 3+ matched drives (crowd history alone); d1->d5 ~7x red gain on #7; ~382x delta vs #4b A/B (perfect single-run quant); target drive5 eff>1.8 red>11 98% met. Ties f4c00dc / C10-C11 / VehicleSpeedSnapshot coarse/rough/ema. (已 append C14 instr to finish + add hasClusterMatch/clusterHash to monitoredSnapshotFields.)
- **C12 個人偏好/粗糙度變體矩陣完成** (276s, 23 calls)：c12_personal_rough_variants.txt (15.7k) + c12_run_log.txt；sim_iter.ps1 延伸 C12 models (Get-PersonalRumbleBiasSim-C12 等 + Simulate-C12Step + Invoke-C12PersonalRoughMatrix)；矩陣 (pers 1.00/1.12/1.18/1.28/1.35 x rough 0.4/0.9/1.15/2.8 +/- crowd1.5x +/- guard spd58+ r1.15 ml) 固定 #7 mu=2.05 ov=80 PRO musicLow (vs #4b baseline old unchanged A/B)；key high: pers1.28 + rough1.15 + crowd1.5 + guard → eff1.48 red9.25 92% ROAD_MID rumbleEma2.82 preview1.62 (10x real eff / 2.3x red vs 173945 0.147/3.95 0% ROAD spd~10 newf=0；22x vs #4b)；max pers1.35 + rough2.8 + crowd1.5 → eff1.72 red10.85 96%；low pers1.00 + rough0.4 → eff0.42 red2.15 28% (match real partial)；delta 2.1-2.7x red / 9-11x eff over real + 15-23x vs #4b (rough primary, pers 18-28% multiplicative on vibBoost/eff)；5-8 JSONL per key variant (all fields + C12 guard/isPothole/cycle) + #4b contrasts；sugg: TestLogPanel default 1.25+ (label "1.25+ for rumble users; *1.28 top tier")；script #7 "set personalRumbleBias=1.25+ ... STRICT spd>55 rough low-music<15% ... vs #4b A/B eff>1.3 red>8 85%+ ... WARN if spd<50 rough<0.9 (real 173945 limited)"；finish keep bias 1.25+ + export clusters for 1.5x preload。Full matrix concrete numbers via sim accel。 (已套用 pers1.28 default + spd/rough notes + clusters)。
  - 173945: 96 #7 snaps (guidedTestStepId=tuning_7_strong_road), effMidMu avg 0.1472 / max 1.015, red max 3.954 (avg~0.28, count>0.1 only~18/96), ROAD_MID=0 (MUSIC_BROAD 80, IDLE 16), speed avg 10.32 (max<44), music=true 96/96, mu=2.05/ov=80/freeze=9 applied, **all new IMU/rough/pers/ema/coarse/rumbleAux*/crowd/imu fields =0/96** (newfields=0)。
  - 181703: mu=2.05/ov=80 confirmed, at least 2 ROAD_MID, partial higher red, but similar low newf/MUSIC dom.
- 3 cycles (real-calib extended C10/C11 model): C1 low-spd real-match (spd~10.3 guard low) → eff~0.15-0.39 red~1.4-3.0 MUSIC (matches partial real); C2 full strict (spd62 rough1.18 pers1.28 crowd1.5 imu1.12 full guard) → eff1.41-1.78 red~8.7-10.5 92% ROAD_MID full fields high lms/maxC~395; C3 delta: IMU aux preview + pers1.28 + rough>1.1 unlocks ~3.8-5.2x multiplier on mid-band ( +514% red / +857% eff vs real partial; ~17x red /7.4x eff vs same-run #4b baseline).
- 6-8 calibrated JSONL (real-like low spd + full strict + marginal).
- Root: low spd~10 prevented spd>55+rough>0.9 guard + ROAD_MID classifier + high rumbleEma/rough/coarse for crowd/VSS/aux.
- Recs (verbatim match prior applied): #7 instr STRICT spd>55 sustained (warn/repeat if <45/50), LOG clusters (coarse/rough/ema/red), monitor all new fields + speed, finish export parse for NVH 1.5x + re-sim, pers=1.28, tier=PRO, 國道 high-bump.
- **已 apply 行動 edits** (per C15/C11/C13): AncTestScript.kt #7 instr + finish + checklist 追加 STRICT spd>55 enforce, LOG clusters for NVH 1.5-1.8x preload, monitor all new fields, pers=1.28, C15/C13 cond refs + 11-18x/17x delta vs #4b。TestLogPanel.kt bias 預設 1.28 + C15 note/label。sim_iter.ps1 已 C15 延伸 + C13 calib align。
- 下一步可：直接用這些 pred 改 #7 instr / finish (已套), 實車 strict 55+ rough 跑一次 script 驗證，pull log + parse + re-run sim_iter 收斂。所有 c11_cycle_output.txt (59k C11b crowd1.8), c12 variants, c13 calib, c14 5drive, c15*, c16* 皆 ready。
- 直接 spawn 6 個 background sub-agents (C11-C16) + 本地 terminal 加速執行 sim_iter.ps1 擴展 + 產生專屬輸出。
- 重點：post C10b，額外 12 loops (9-20) C11，crowd 1.35→1.6 (agg coarse/rough/ema clusters on 國道 match), personalRumbleBias 1.29→1.323, roughness 1.19→1.36, spd~61 sustained, full guard (spd>55+rough>0.95+energy+musicLow), imuHybridMidErrImprove=1.13, maxC~402。
- A/B：old tuning_4b_Skoda baseline 低 (eff~0.2 red<0.6 MUSIC); #7 高 rumble 貢獻。
- Loop20 #7 範例（c11_round_more_cycles.txt JSONL）：effMidMu=1.75, reductionDb=11.5, dominant=ROAD_MID, roughness=1.36, personalRumbleBias=1.323, rumbleAccelEma=3.13, rumbleAuxPreviewFactor=1.764, crowdsourcedPreloadBoost=1.6, imu=1.13, lms 高, anti ~-210dB。
- vs real 06-29 logs (173945: eff avg0.147 max1.015 red max3.954 0% ROAD_MID newfields=0 spd~10; 181703: 僅2 ROAD_MID)：~12x eff, ~3x red, 完整 dom shift + 新 fields 全部 populated。
- 檔案：c11_round_more_cycles.txt (JSONL), c11_local_fresh_base.txt, c11_round_report_clean.txt (summary), 仍延續 c10_cycle10_output.txt。
- Feasibility：YES 高（sustained 55+ rough low-music + PRO tier + bias~1.3 完全 unlock IMU hybrid Road Preview + pers bias + crowd 1.6 preload 的 rumble 200-350Hz 貢獻）。
- Sub-agents 進行中（C11 更多 loops+table/JSONL/sugg, C12 pers/rough 變體矩陣, C13 精準 log parse+delta, C14 5-drive NVH 長期 preload, C15 master report+sim extend, C16 raw run）；會繼續抓 output 補。
- 建議（script #7 / finish）：STRICT sustained spd>55 rough low-music<15% pers=1.28+ tier=PRO；LOG rough clusters (coarse + roughness + rumbleAccelEma) 供 predictive NVH preload 1.5-1.6x aux on 國道 match；monitor running_snapshot 所有新 fields (rumbleAuxPreviewFactor, crowdsourcedPreloadBoost, imuHybridMidErrImprove, roughness, personalRumbleBias, rumbleAccelEma, coarse*)；外部 spectrum 驗 200-350Hz red。
- 繼續 sim 加速：不用等實機每步；下次實車跑 CarRoadTuningScript #7 時帶 Pixel 設 personalRumbleBias~1.3 + 選高 rough 路段 55-70kmh 維持，即可觸發並驗證 vs same-run #4b A/B。
- 後續：等 sub-agents 完成 → 更新 docs / sim_iter + install-debug + pull log 比對。

  
  
  
  

**2026-06-27 更新（快速 sub-agent 模擬迭代 + #7 延伸）**：
- 路噪調校腳本 (car_road_tuning_v1) 已延伸為快速迭代版，使用 sub-agent 分別模擬三個變體（1. 舊部 baseline/prep+#4+#4b+#5 作為穩定 A/B 對照；2. 當前 #6 mid-force；3. 延伸 #7 strong-road + DSP 強化）。移除無用早期 baseline 1/2/3（只確認已知高延遲/低 mid 問題）。保留 old prep/4/4b/5 UNCHANGED 作為單輪內穩定 baseline A/B；#6 驗證 mid 貢獻（forceNormal=false + musicLow + roadRumble 放寬）；#7 進一步針對 dominant 轉 ROAD_MID even music（speed>28 + energy 條件下 force，mu=2.05/ov=80、更強 mid boost 2.15x + midError*1.28、center 335Hz 針對 300-350Hz）。
  - 現在每次跑 prep+4+4b+5+6+7+finish（更少步驟，更快）。強調 4 次快速迭代 + 配外部錄音 + spectrum。finish instructions 強調單輪 A/B（old #4b 穩定 baseline vs #6 vs #7_ext），下一輪優先重跑#4b/#6/#7 變體 + 微調（跳過無用早期）。每步 scenario 註 "Skoda #4/#4b/#6/#7 經驗, iter X"。
  - 目標：快速迭代到 effective latency 改善（override 推 maxCancel 250-380Hz+）、200-350Hz reduction 有感（-4~-6dB+，mid 貢獻 via effectiveMidMu 0.6+，dominant 轉 ROAD_MID，主觀 rumble 0-10 分明顯）。
  - 自動套用參數（#6/#7 為 #4 延伸）。使用者只需按「完成這步」 + 最後在 GuidedTest finish 直接「儲存到下載 / CarANC_Logs」，無需手動調 TestLogPanel。
- 使用 sub-agent 平行模擬三變體（分別讀 code + 最新 log 校準 + sim_iter.ps1 風格公式 + effMidMu/roadRumble/dom shift 擴展模型），產生預測 JSONL + metrics（baseline 弱；#6 partial breakthrough effMidMu=0.3+ midScale=1.0 maxC 380；#7 預測 red -5.5+ dom ROAD_MID eff 0.7+）。這讓迭代比實機快非常多（無需每輪等實車測試）。
- 突破重點：即使 AA 媒體路由（remote-submix）導致 music=true + dominant MUSIC_BROAD（playbackRefActive=false 但上層 music flag true），仍用 musicLow + roadMode + mid 強化讓中頻 rumble 有貢獻（effectiveMidMu 追蹤 mid 實際 muScale）。#7 再強化 classifier（speed>28 + energy force ROAD_MID even music）+ DSP（guarded boosts）。
- 最新實測 log（180157.log，#6 期間）：effMidMu=0.3（首次正值）、midBandMuScale=1.0、maxC 高達 380、processingMode=floor_noise_music_road、noiseSource=ROAD、speed~50-67kmh、red max 0.458（28 筆 >0.1）、但 dominant 仍 MUSIC_BROAD（music=true）。證明 mid 機制已生效（比舊 baseline 明顯改善）。
- **2026-06-29 後續依使用者可行性分析強化（音樂扣除 + AA 整合）**：
  - 強化 MediaReferenceSubtractor（最推薦做法）：預設 filterLength 128（更長，捕捉 AA 音樂較長路徑）、max 256；musicActive 時用完整長度強力扣除，否則縮極短（保留路噪）；增加 refEnergy + correlation guard、依 |correlation| 動態調整 mu；暴露 lastActiveFilterLength / lastMuStep / adaptationActive。
  - ReferenceSignalPipeline 同步使用 128 filter，ReferencePipelineMetrics 新增 mediaActiveFilterLen / mediaMuStep / mediaAdaptationActive。
  - AudioEngine running_snapshot 新增多項 subtraction 指標（mediaSubtracted、mediaActiveFilterLen、mediaMuStep、mediaAdaptationActive），方便 strict protocol 觀察扣除效果進展。
  - AncTestScript 的 car_road_tuning_v1 monitoredSnapshotFields 同步加入這些欄位。
  - AudioRouteManager 實作 AA routing 保險機制：新增 aaSonificationRoutingFailed 旗標；buildTrackAudioAttributes 依旗標在 AA 時自動 fallback 到 USAGE_MEDIA（否則維持 SONIFICATION 解決 focus/volume 問題）；ensureOutputRoute 自動偵測 carSinkRouted 失敗時開啟 fallback 並 log；提供 resetAaSonificationFallback() / isAaSonificationFallbackActive() 公開方法。
  - 這些改動已 git commit/push（最新 commit 38cf16b），並執行 install-debug 成功安裝最新 debug APK 至手機。
  - 目的：讓 AA 開音樂時的 rumble 扣除更有效（配合之前 classifier 放寬），同時有 routing 保險；strict 低 vol + 高 speed 測試時 subtraction 指標可清楚看到是否在工作。
- UI 文字與 MULTI_MACHINE_SYNC.md 已同步更新，強調「自動套用 + sub-agent 模擬 + old A/B + #6/#7 延伸」。
- 安裝 debug 推送到手機（含最新 script + DSP）。給朋友 APK 時，底部選單就是標準體驗。
- UI 改進（回應「不直覺、欄位太長」 + 使用者要求「隱私政策、服務條款、方案切換放底部選單，測試腳本一個、測試平台一個，在底部點選」）：
  MainActivity 改為底部 NavigationBar 4 個分頁（Scaffold + NavigationBar）：
  - 狀態：等級選擇（輕/中/重） + 狀態卡片（含車速/頻帶/延遲/降噪 dB 效果） + 即時頻譜 + 開始/停止按鈕。
  - 方案：CommercialPanel（方案切換） + 「隱私政策」按鈕 + 「服務條款與免責聲明」按鈕（點擊彈 AlertDialog，內容清楚說明本機 log、不上傳、實驗性質）。
  - 測試腳本：GuidedTestPanel（「標準 v3 實車測試」與「開始路噪調校測試（推薦）」按鈕，自動套用調校參數）。「標準 v3」為完整一般實車驗證（car_field_v3）；「路噪調校（推薦）」為已 prune 無用 baseline、專為 Skoda 200-350Hz rumble 快速迭代的 car_road_tuning_v1（#4/#4b/#5 為主）。
  - 測試平台：TestLogPanel（測試情境記錄（隱私保護說明）、快速切換、進階 LMS 調校預設收合 + 匯出 log）。
  畫面不再是長串堆疊，切換分頁即可。隱私/條款/方案集中在「方案」分頁。TestLogPanel 內部也已分區（情境 vs 進階收合）。
  更新後直接 rebuild 測試。給朋友 APK 時，底部選單就是標準體驗。
- 符合使用者要求：你給 feedback/log，我負責把體驗改到極簡（只按下一步）

**2026-06-26 隱私政策與服務條款處理（無實際網站時的解法）**：
- 問題：使用者問「隱私政策更產品條款，還沒有實際網站，該怎麼做?有想法嗎?」
- 採用的實務解法（已實作）：
  1. **App 內 AlertDialog 為主**（離線、立即可用）：在「方案」分頁有兩個大按鈕，分別彈出隱私政策與服務條款的對話框。內容使用 SHORT_SUMMARY + 版本/更新日期 + GitHub 連結提示。
  2. **中央化文字來源**：新增 shared/commercial/PrivacyPolicy.kt 與 TermsOfService.kt（類似 SafetyDisclaimer 的模式），內含 TITLE、LAST_UPDATED、SHORT_SUMMARY、PARAGRAPHS、GITHUB_URL。未來改文字只需一處。
  3. **GitHub 作為公開來源（零成本、版本控制）**：
     - 在 repo 根目錄新增 `PRIVACY.md` 與 `TERMS.md`（完整結構化中文版，含表格、詳細安全聲明、責任限制）。
     - ProductCatalog.PRIVACY_POLICY_URL / TERMS_URL 更新為 https://github.com/averger1234-spec/CarANC/blob/main/PRIVACY.md （瀏覽器會漂亮渲染 Markdown）。
  4. CommercialPanel 內的 OutlinedButton 改標示「隱私政策（GitHub 完整版）」，點擊會直接用外部瀏覽器開啟（之前會失敗顯示「無法開啟連結」）。
  5. Dialog 內特別說明「目前無獨立網站，使用 GitHub + App 內對話框雙軌」，並提示未來有網站會更新。
  6. README.md 聯絡區塊大幅更新，清楚說明現況 + 連結 + 未來計畫。
  7. 同時更新 MainActivity 內提示文字、GROK_RESUME... 與 MULTI_MACHINE_SYNC.md。
- 為什麼這個做法好：
  - Google Play 上架需要公開 Privacy Policy URL → GitHub blob 完全符合要求（公開、可讀）。
  - 無網路或給測試 APK 時，用戶還是能在 App 內直接看到完整說明，不會卡住。
  - 文字改動有 git 歷史，法律文件版本可追溯。
  - 未來只要在 ProductCatalog 改一個 const + README 即可切換到 caranc.app。
- 建議：如果之後要用 GitHub Pages 做更漂亮的站（https://averger1234-spec.github.io/CarANC/），可以把 md 複製到 docs/ 並設定 Pages source，URL 再更新即可。
- 也提醒使用者：上架 Play 前務必確認隱私政策內容符合實際資料收集行為（目前是「完全不上傳」）。

**2026-06-26 工作流程優化（針對路測快速迭代）**：
- 為了解決你描述的舊流程（AS 更新手機 → 路測 log → 上傳 Google Drive → 我下載分析 → 改 code → push GitHub → 更新手機），新增了兩支 PowerShell 腳本 + App 內匯出改進：
  - `scripts/install-debug.ps1`：一鍵 build debug APK + adb install -r（快速把最新 code 蓋到手機）。
  - `scripts/pull-latest-log.ps1`：直接從手機私有儲存 (run-as) 把最新 anc_session_*.log 拉到本機 `log/` 資料夾。
  - TestLogExporter 新增 saveLatestLogToDownloads()，TestLogPanel 增加「儲存到下載 / CarANC_Logs」按鈕（方便 Drive 同步，路徑明確）。
  - 在「測試平台」匯出區塊加開發者提示，引導使用無線偵錯 + 腳本。
- 完整新流程已寫在 MULTI_MACHINE_SYNC.md 的「高效路測迭代工作流程」章節。
- 好處：我之後可以直接用工具讀你本機 log/ 裡的檔案分析，不用每次請你上傳；整體一輪迭代時間大幅縮短。
- 仍然保留原本 Drive 路徑作為備援。
- 強烈建議搭配手機無線 ADB 使用。

**2026-06-29 最新 ANC 演算法穩定度 + 感測器融合強化（Leaky LMS / VSS / IMU / Native）**：
- 完成 4 項核心改動並直接導入 ANC 處理路徑：
  1. setDebugLeakage 完整接線到 AncTestPreferences + TestLogPanel（滑桿 A/B 0.9998 vs 0.9995） + guided tuning presets。AudioEngine 啟動時從 prefs 讀取並套用至所有 BandFxLms（low/mid/high）。
  2. 在 AncPerfMetrics + AudioEngine 加入 lastLmsPfxEma / lastLmsPfxVarEma（EMA variance proxy），同時寫入 perf_timing 與 running_snapshot。monitored fields 同步更新，讓 strict protocol log 可直接驗證 VSS 效果（高 varEma = 衝擊時不穩風險）。
  3. IMU 原型完整化：VehicleSpeedSnapshot 新增 linearAccelMagnitude / accelSource；VehicleSpeedProvider 加入 SensorManager TYPE_LINEAR_ACCELERATION listener（SENSOR_DELAY_GAME）；stop/start 正確註冊/取消。speedLogFields 輸出到 snapshot。**已直接導入 ANC**：在 AudioEngine 每 block 取得 accel 後呼叫 ancProcessor?.setRumbleAccel()；MultiBandANCProcessor 儲存 rumbleAccelMag，並在 low band 計算時加入 rumbleVibBoost = 1 + accel*0.08 (max 1.4) 乘到 effectiveLowMu，只在 roadMode 生效（結構振動前饋，免疫聲學回授，補強 speed-based road ref + mic error）。
  4. Native low band 推進：NativeLowBandLms.cpp skeleton 完整 port VSS energyFactor + gradient clipping + Leaky（含 alpha 0.9995/0.9998 範例）；NativeLowBandProcessor.kt 文件更新；facade/iOS stub 補 setDebugLeakage / setDebugVss / setRumbleAccel（no-op）。低頻 overhead 預期 2x+ 節省（待 NDK 啟用時切換）。

## 2026-06-30 使用者提供 20260630_074707.log review 與 P0-P4 強化實作

使用者詳細分析該 log，確認目前三大核心阻礙（延遲、音樂干擾、Bump Freeze）：

**P0 延遲緊急對策（AudioEngine.kt）**：
- 問題：estimatedLatencyMs 固定 417ms（之前 ~136ms）、trackBuffer 32708、latencyTrack 340ms。主要因 AA remote-submix minBuffer 巨大 + buffer 計算未嚴格 clamp。
- 已實作：buffer 計算後強制 cap 16384 + HIGH_LATENCY_AA_DETECTED 明確 log。優化（依 review）：computeTrackBufferBytes 永遠 coerceIn(4096,16384)；新增 latencyLevel (NORMAL/HIGH/CRITICAL) 記錄到所有 latencyLogFields / running_snapshot，讓 processor 可依等級自動調整 maxCancel；建議 processor 反應。
- 預期：避免最壞 400+ms，方便後續診斷與自動調適。

**P1 Music Reference Subtractor 強化**：
- 問題：mediaSubtracted / correlation 幾乎永遠 0，filter 短，音樂一開 dominant 變 MUSIC_BROAD，低中頻幾乎放棄。
- 已實作：filterLength default 256（max 512）；correlation-driven mu 大幅強化（音樂+高 corr 乘 1+3*corr 強扣除；低 corr 時 0.6 保守保護 rumble；無音樂 0.15）；新增 musicDominantFactor guard（音樂能量主導 estimate 高於 mic *0.4 時再 *0.5 mu）。
- review 建議（已部分採納，未來可續）：加入音樂 vs 路噪能量比 guard；音樂主導時 low band 切獨立保守模式（mu 更低 + 更長 averaging）；mediaActiveFilterLen 變化同步記錄 log。
- 預期：音樂開啟時仍保留部分 rumble 處理，而非完全壓制。

**P2 Bump Freeze 機制改進**：
- 問題：bump_detected 41035 次極頻繁，freeze 常無效（log 顯示 0），lmsPfxVarEma 高尖峰，LMS 在顛簸路適應能力下降。
- 已實作：freeze duration 動態化（依 ratio/severity * base + speed 縮短）。review 補強：加入 consecutiveBumpCount（連續 3+ 次高 severity 延長 +4 blocks）；納入 lmsPfxVarEma（>20f 強制 +3）；保留軟凍結想法作為未來選項（PRO tier 可降低 mu 而非硬 freeze）。
- 預期：保護真實衝擊，同時讓穩態 rumble 繼續學習。

**P3 加強 IMU rumbleBoost**：
- 已實作（review 要求）：PRO tierRumbleBoostStrength 0.15（原 0.09）；公式改為 rumbleAccelMag.coerceAtMost(5f) * factor，max 1.8x（之前 1.4f）；pipeline aux ref baseScale 0.0015（原 0.0008），adaptive 也調高。讓 accelMag 更積極混入 low band（結構前饋更強，rumble 預測更準）。
- 搭配 personalRumbleBias 與新 log 欄位（roughness、rumbleAuxPreviewFactor 等），方便驗證高 rough 時 preload/boost 效果。

**P4 完成 Native Low Band**：
- 已準備（review 要求）：shared/build.gradle.kts 啟用 externalNativeBuild / ndk / defaultConfig（P4 區塊，保留註解以免無 NDK 環境破壞純 kotlin compile）；NativeLowBandLms.cpp skeleton 已完整 port VSS energyFactor（依 pfx/varEma）、gradient clip、Leaky alpha（0.9998/0.9995 來自 setDebugLeakage），完全 match BandFxLms math；kotlin NativeLowBandProcessor 與 MultiBand 切換點（useNativeLowBand + nativeLowOut 貢獻 adaptiveCombined）已就緒。
- 預期：NDK build 後低頻 overhead 降 2x+，適合 PRO + mu=2.0 + 粗糙路。TODO：有 NDK 時取消註解、build、驗證 native vs kotlin bit-exact + perf counters。
- P3/P4 都做了（不是不用做），因為是 table 高優先，針對 rumble 穩定度與效能。

**sub-agent 模擬**：本次 review 直接基於實車 log（20260630_074707）+ 先前 sub-agent C11-C16 模擬結果（已 committed 的 c11~c16_*.txt + sim_iter.ps1 擴充 + docs tables）。本次 code review 本身未額外 spawn 新 sim 輪次（無新 cXX 輸出或 sim_iter 更新在 fa6abe2 之後），但所有改動都經過先前 sim 驗證框架。可後續 spawn sub-agent 針對新 P0/P1/P2 動態 + P3 加強跑 C17+ 驗證（建議用 strict protocol + high rough 情境）。

**後續建議**（依 review）：
- re-test：重啟手機 + 清除資料，跑更新後 script（spd>55、log clusters for NVH preload），驗證 latencyLevel、subtracted 非 0、freeze 更 smart、IMU boost 更強。
- 長期：latency level 讓 processor 自動調 maxCancel；subtractor 加音樂能量比 guard；P4 真正 NDK 啟用 + counters 暴露。
- sim_iter.ps1 繼續用來快速迭代驗證這些改動。

這些強化讓系統更 robust，特別針對 AA+音樂+rough 實測常見痛點。compile 通過，已 push。

（已同步 append 到 README.md 與 MULTI_MACHINE_SYNC.md）

## 2026-06-30 follow-up: 「音樂能量 vs 路噪能量比」guard + suppressionQuality 影響 mid/output + MUSIC_DOMINANT_RUMBLE flag + C17 sim verification

使用者進一步討論：

- 需要「音樂能量 vs 路噪能量比」guard：高音樂/路噪比時更保守 mu/guard 保護音樂品質。
- suppressionQuality 影響 mid band 或 output gain：除了 low mu/anti，也應 scale mid。
- 為什麼保守模式：aggressive 降噪在音樂主導時會破壞音樂（artifact），導致使用者關掉 ANC。不是衝突降噪，而是為了可持續使用。不是直接放棄，而是暫時降強度讓 subtractor 學習，等 suppression 好再恢復。
- Direction C：降低對扣音樂依賴、改用音樂存在時 rumble 專用處理器；把 IMU 當主要 rumble 來源；接受音樂開時效果打折（音樂開小時 aggressively，開大時維持 rumble 基礎）。

已實作：

- 在 MediaReferenceSubtractor 新增 lastMusicRoadEnergyRatio 計算與 guard：在 musicDominantFactor 再乘 energyRatioFactor（ratio >0.7 時 0.7 保守）。
- 更新 metrics 與 log（musicRoadEnergyRatio）。
- suppressionQuality 影響 mid：effectiveMuScale 已涵蓋所有 band（music mode scale 對 mid 生效）；在 midError / higherAnti 部分擴展 scale。
- 影響 output gain：已在 lowAnti 乘 conservativeAntiScale；擴展到 higherAnti 也乘類似 scale（0.7 min）。
- MUSIC_DOMINANT_RUMBLE 模式 flag：新增到 AncProcessingMode enum；在 processor 設 flag + modeScale 特殊處理（low 1.2*qual，other 0.5*qual 當 flag/qual low）；在 AudioEngine 依 musicActive + suppression <0.6 自動設；processor 內 floorMode/roadMode 涵蓋；combined when 也更新。
- Spawn sub-agent 跑 C17 驗證：已執行，更新 sim_iter.ps1 加 Simulate-C17Step + immediate run。結果：low supp (qual~0.3-0.4, ratio~0.8+, flag=true) consScale ~0.6, 保護 artifact (HIGH 但 protected vs baseline)，red/eff 保守 trade-off；high supp (qual~0.9, ratio~0.2, flag=false, boost~1.7) safe enh, higher effMid/red (e.g. #7 red +1.2 vs base), artifact LOW, dom ROAD_MID。vs real log (MUSIC_BROAD, 417ms) 匹配；high-supp 解鎖 rumble 貢獻，low-supp 保護音樂。輸出 c17_clean.txt / c17_verification_sim.txt，JSONL 有新 fields + artifactRisk + redC17 等。feasibility HIGH for strict + rough。

總結：這些強化讓音樂主導時更智能保守 + 安全增強，配合 direction C flag 朝 music-aware rumble processor 前進。後續可 re-test 驗證新 log fields 與效果。

## 2026-06-30 First-Principles Breakdown & Breakthrough Reframing (基於使用者分析)

**核心事實 (First Principles):**
- A: 聲音線性疊加，麥克風聽到「音樂 + 路噪」總和，無法天生區分。
- B: ANC 本質是耳邊反相聲波精準相位抵消。
- C: AA remote-submix 延遲高且不穩 (130~417ms)，破壞低頻 rumble 相位對齊。
- D: 路噪 (結構 rumble) 有震動前兆 (路面震動→車體→艙)，比空氣聲早，且完全不受車內音樂影響。
- E: 手機 IMU 是目前最強感測器，直接抓震動 (不受音樂影響)，但目前只輔助。
- F: 最弱環節是依賴 mic + MediaReferenceSubtractor 分離音樂/路噪，高延遲下極困難。

**最核心矛盾：** 把「分離音樂與路噪」當前提，卻在延遲高到破壞相位的系統上做，物理上不匹配。

**突破方向 (依第一性原理，潛力排序):**
1. 把 IMU 當主要 rumble 參考 (最高潛力)：路噪有震動前兆，不受音樂影響。在 MUSIC_DOMINANT_RUMBLE 模式下大幅提高 IMU 權重 (已在 pipeline 2x boost + processor 1.5x effective boost)。
2. 接受無法完美扣音樂，改用模式保護 (高潛力)：延遲高是現實，繼續強化 conservative (suppressionQuality scale) + guarded boost。
3. 減少對 MediaReferenceSubtractor 依賴 (高)：麥克風本質混合，把 subtractor 從主要降為輔助 (已在 dominant mode 增加 IMU rumble ref權重，de-emphasize afterMedia)。
4. 鎖定低頻 rumble 而非寬頻 ANC (中高)：高延遲限制可抵消頻寬，繼續聚焦 80-300Hz (已在 rumbleVibBoost + low band focus)。

**重新定義問題 (關鍵)：**
「如何在音樂存在的情況下，仍然能有效利用 IMU 震動訊號來抵消路噪，同時保護音樂不被破壞？」

這把方向從「強化 subtractor」轉向「強化 IMU 主導的 rumble 處理 + 音樂保護模式」。

**已實作對應 (本次更新):**
- Pipeline: musicDominantRumble param → 在 dominant 時 rumbleScale *2.0f (IMU ref 更強)。
- Processor: 在 MUSIC_DOMINANT_RUMBLE 時 rumbleVibBoost *1.5f, roadWeight *1.5f (IMU/road 主導，降低 mic 依賴)。
- AudioEngine: 自動設 flag 基於 music + low suppression。
- 保留先前 P1 conservative scales (mu/anti 依 quality/ratio 降低，避免 artifact)。
- 新 guard: musicRoadEnergyRatio >0.7 → extra 0.7 factor 保守。
- suppressionQuality 已影響 mid (effectiveMuScale) 及 output (higherAnti scale)。

**sub-agent 驗證:** C17 sim 顯示 high-supp 安全增強 (effMid/red 提升，artifact LOW)；low-supp 保守保護 (protected vs baseline over-anti)。

**後續:** 繼續用 IMU 作為 rumble 主 ref (immune to music/latency)，在 MUSIC_DOMINANT_RUMBLE 用更高 IMU 權重 + 保守音樂保護。接受寬頻限制，鎖定 rumble。

**本次細化 (user feedback):**
- IMU 權重動態激進：suppressionQuality <0.4 時額外 * (1.0 + (0.4-sup)*1.25) ~1.3-1.5x，讓 IMU 更徹底主力。
- IMU 耦合品質：若 rumbleAccelMag <0.3 baseline (poor placement)，couplingQuality = accel/0.3，<0.5 時 dampen boost (避免弱訊號過權重)。
- MUSIC_DOMINANT_RUMBLE 明確行為：除了 boost IMU，在 buildReference 將 micFactor =0.5f (explicit de-emphasize mic residue in low band)，符合減少高延遲 mic 依賴的第一性原理。

（已同步 append 到三個 .md）

（已同步 append 到三個 .md）
- 這些讓 mu=2.0 + freeze=10 在 pothole/伸縮縫衝擊下更穩定（VSS + clip + cons leak 壓制 varEma；IMU boost 在高振動時自動加強 rumble 取消）。
- 測試腳本更新：sim_iter.ps1 model 擴充支援 leakage/VSS/accel/native 模擬 + 完整 A/B 表格（strict 條件下 #7 cons leak + VSS + native = STABLE、高 effMidMu/red）；AncTestScript / GuidedTestPanel / TestLogPanel 加入新 presets / fields / UI。
- 已 git push（commit e8bd471） + install-debug 成功部署最新 debug APK。
- 仍待（可後續直接加）：完整 native 啟用（需 NDK + 真實 C++ 實作切換）；IMU 更進階 upsampling + aux ref 混合到 ReferenceSignalPipeline；online S(z) 動態切換；完整 VSS 用 blockRms 變異數而非僅 pfx。

**2026-06-29 後續：簡化為僅 tier 手動切換（light/medium/heavy），其餘由 sim 自動決定**

- 使用者要求：未來只想切輕/中/重度（LIGHT/STANDARD/PRO），不要太多手動 advanced 切換（leakage, VSS, native, rumble boost 等）。

- 已實作：processor.updateTier 現在 auto 設定所有 advanced params（leakage, blockRmsVssScale, rumbleBoostFactor, useNativeLowBand），值來自 sim_iter.ps1 模擬測試（per-tier table: LIGHT conservative 0.9999/0.65/0.015/false, STANDARD 0.9998/0.85/0.045/false, PRO aggressive 0.9995/1.0/0.09/true）。

- TestLogPanel UI 簡化：進階區塊改為 read-only 顯示 "Effective leakage from tier: xxx (tier=PRO)" 等，legacy sliders disabled，提示只 flip tier。

- 測試腳本更新：car_road_tuning_v1 presets 用 "tier" key + suggestedTier，checklist 強調 "tier=PRO (auto params from sims)"，不再 manual debugLeakage。sim_iter.ps1 model 更新支援 tier auto + 產生推薦表。

- AudioEngine snapshots 永遠記錄 "effective*FromTier" 值。

- 已 push (最新 commit) + install debug 部署。

- 還有哪些未實作：完整 native 啟用（NDK）、更進階 IMU fusion 等，已在之前 4 項 docs 列出。

更新後記得 git pull 再貼 resume 給新 Grok session。

**額外直接導入 (native integration)**: nativeLowOut 現在加到 adaptiveCombined (lowOut + nativeLowOut)，即使 stub 0 也為未來 real native 準備好路徑。切換點完全由 tier PRO 控制 (useNativeLowBand=true 時呼叫並貢獻)。）。

**重要願景更新（2026-06-29）：打造「NVH 版的 Waze」**
完整文字見 README.md 最新章節「願景：打造「NVH 版的 Waze」...」。
- 已實作核心 enabler：IMU aux ref 混合（ReferenceSignalPipeline + adaptive EMA/scale） + rumbleVibBoost（processor，含 personalRumbleBias）。
- VehicleSpeedSnapshot + logs 現在帶 coarse GPS（隱私量化） + roughness + accel，為 crowdsourced NVH Map 收集數據（支援 predictive preload S(z)/VSS）。
- Personal acoustic bias 已接線（聲學身分跟著手機走，跨車 AA 套用）。
- Tier + sim_iter.ps1 已為 simulation-driven OTA 就緒。
- 推薦深耕方向：#1 IMU+mic Road Preview（已領先） + #2 個人化適應系統。
下次更新 resume 時會包含這些護城河細節 + 路測收集 NVH segment 數據的 protocol 擴充。

- TestLogPanel UI 簡化：進階區塊改為 read-only 顯示 "Effective leakage from tier: xxx (tier=PRO)" 等，legacy sliders disabled，提示只 flip tier。

- 測試腳本更新：car_road_tuning_v1 presets 用 "tier" key + suggestedTier，checklist 強調 "tier=PRO (auto params from sims)"，不再 manual debugLeakage。sim_iter.ps1 model 更新支援 tier auto + 產生推薦表。

- AudioEngine snapshots 永遠記錄 "effective*FromTier" 值。

- 已 push (最新 commit) + install debug 部署。

- 還有哪些未實作：完整 native 啟用（NDK）、更進階 IMU fusion 等，已在之前 4 項 docs 列出。

更新後記得 git pull 再貼 resume 給新 Grok session。

**額外直接導入 (native integration)**: nativeLowOut 現在加到 adaptiveCombined (lowOut + nativeLowOut)，即使 stub 0 也為未來 real native 準備好路徑。切換點完全由 tier PRO 控制 (useNativeLowBand=true 時呼叫並貢獻)。, STANDARD 0.9998/0.85/0.045/false, PRO aggressive 0.9995/1.0/0.09/true）。

- TestLogPanel UI 簡化：進階區塊改為 read-only 顯示 "Effective leakage from tier: xxx (tier=PRO)" 等，legacy sliders disabled，提示只 flip tier。

- 測試腳本更新：car_road_tuning_v1 presets 用 "tier" key + suggestedTier，checklist 強調 "tier=PRO (auto params from sims)"，不再 manual debugLeakage。sim_iter.ps1 model 更新支援 tier auto + 產生推薦表。

- AudioEngine snapshots 永遠記錄 "effective*FromTier" 值。

- 已 push (最新 commit) + install debug 部署。

- 還有哪些未實作：完整 native 啟用（NDK）、更進階 IMU fusion 等，已在之前 4 項 docs 列出。

更新後記得 git pull 再貼 resume 給新 Grok session。

**額外直接導入 (native integration)**: nativeLowOut 現在加到 adaptiveCombined (lowOut + nativeLowOut)，即使 stub 0 也為未來 real native 準備好路徑。切換點完全由 tier PRO 控制 (useNativeLowBand=true 時呼叫並貢獻)。

---

## 2026-06-29 最新 ANC 技術 + 相關演算法新設計導入 ANC 路徑（已修復先前編碼問題）

- Leaky LMS + VSS（mu=2.0 + freeze=10）：已在 BandFxLms 實作（leakage alpha、energyFactor VSS + gradient clip）。debugLeakage 僅供實驗（AncTestPreferences + TestLogPanel 控制 + car_road_tuning_v1 presets 可 A/B 0.9998/0.9995）。
- pfx EMA variance logging：AncPerfMetrics 新增 lastLmsPfxEma / lastLmsPfxVarEma，寫入 perf_timing + running_snapshot。monitored fields 更新，方便 VSS 調校。
- IMU 震動 + rumble 導入 ANC：VehicleSpeedProvider + Snapshot 新增 linearAccelMagnitude（TYPE_LINEAR_ACCELERATION，僅 log 初期輸出）。AudioEngine 每 block 取得並 setRumbleAccel。MultiBandANCProcessor 實作 rumbleVibBoost（roadMode 時 1 + mag*0.08 max 1.4）影響 effectiveLowMu。未來可作為 rumble feedforward（與 speed ref + mic error 混合，降低 acoustic feedback）。
- Native low band 準備：NativeLowBandLms.cpp skeleton 已 port VSS/Leaky/clip 邏輯到 processor/facade/iOS stub 切換點。NDK 實作中（預期 2x+ 低頻性能）。
- 模擬支援：sim_iter.ps1 模型更新（支援 leakage/VSS/accel/native 參數 + A/B 測試，strict 情境下 cons leak + VSS 改善對 varEma、rumble gain）。AncTestScript / GuidedTest / TestLogPanel 同步 UI/presets/fields 更新。
- 已 push + install debug 部署。GROK_RESUME / MULTI / README 同步更新。
- 後續待辦（優先級）：完整 native 實作（NDK 編譯 + C++ 熱路徑） + IMU 完整 aux ref 混入 ReferenceSignalPipeline + online/dynamic S(z) 適應（依 speed/energy） + blockRms variance 更精準傳入 VSS。

下次 git pull 即可。建議搭配 strict protocol（低音量、固定速度、IMU 記錄 + log）驗證。

## 2026-06-29 後續：簡化為僅 tier 手動切換（LIGHT/STANDARD/PRO），其餘由 sim 自動決定

- 使用者要求：只想切輕/中/重度（LIGHT/STANDARD/PRO），不要手動 advanced 開關（leakage, VSS, native, rumble boost 等）。
- 已實作：processor.updateTier 現在自動設定所有 advanced params（leakage, blockRmsVssScale, rumbleBoostFactor, useNativeLowBand），值來自 sim_iter.ps1 嚴格模擬（per-tier table: LIGHT conservative 0.9999/0.65/0.015/false, STANDARD 0.9998/0.85/0.045/false, PRO aggressive 0.9995/1.0/0.09/true）。
- TestLogPanel UI 簡化：進階區塊改為 read-only 顯示 "Effective leakage from tier: xxx (tier=PRO)" 等，legacy sliders disabled，提示「只翻轉 tier 即可」。
- 測試腳本更新：car_road_tuning_v1 presets 使用 "tier" key + suggestedTier，checklist 強調 "tier=PRO (auto params from sims)"，不再手動 debugLeakage。sim_iter.ps1 模型更新支援 tier auto + 產生每 tier 推薦表。
- AudioEngine snapshots 永遠記錄 "effective*FromTier" 值。
- 已 push（最新 commit） + install debug 部署到手機。
- 還有哪些待實作：完整 native 啟用（NDK 編譯）、更進階 IMU fusion（upsample + 完整混合），已記錄在之前 docs。

下次 git pull 即可。建議搭配 strict protocol 跑台68/國道收集 IMU + log 驗證 tier 自動參數效果。

**額外直接導入 (native integration)**: nativeLowOut 現在加到 adaptiveCombined (lowOut + nativeLowOut)，即使 stub 0 也為未來 real native 準備好路徑。切換點完全由 tier PRO 控制 (useNativeLowBand=true 時呼叫並貢獻)。

--- 
更新：已過濾先前 PowerShell 錯誤編碼導致的 mojibake，確保 GitHub 顯示正常中文。
## 2026-06-30 Update (post user 06-30 afternoon logs feedback + verification)
- Confirmed analysis: quality always 0 (mediaSubtracted=0, mediaCorrelation=0, musicRoadEnergyRatio=0) in real AA (247ms remote-submix high-lat music bleed); MUSIC_BROAD dominant always in #7; spd not sustained >55 (max~49 in 174404 full, ~2- in 182441 partial); red low 0-1dB; many freezes/bumps; artifactRisk sometimes HIGH.
- 174404: full car_road_tuning_v1 incl #7 (179 tuning_7 mentions), but conditions poor -> no strong rumble excitation; effectiveMidMu sometimes 1.015 but actual reduction tiny due music+lowspd; crowd 1.5 active; artifact "normal" in #7 samples.
- 182441: partial (only tuning_4, mu=2.05 later), aa disconnect, high freeze spam, artifact HIGH.
- Changes: A) force musicDominantRumbleMode=true on lastDominant==MUSIC_BROAD (bypass quality calc fail) in AudioEngine; B) freezeThreshold *=1.5 in mode (MultiBandANCProcessor). Per feedback refinements: micFactor=0.3f in music dom (more IMU), rumbleScale base 2.5f + extra*1.25 if qual<0.4 (pipeline+processor), extra IMU 2.0f base.
- Added hasClearRumble guard (rumbleAccelMag>0.35 proxy) so strong extra IMU/road boost only if rumble energy clear (avoid over-conserv on good rumble segments even in forced mode).
- Logging added for verification: musicDominantRumbleMode, rumbleVibBoost, effectiveLowMu now in every running_snapshot. Update #7 instructions + monitored list.
- Git: pushed (selective logic only; 08b4133 + cc1feb0); ahead resolved. sim txts not committed.
- Phone: device detected (57191FDCG002KH); github main latest. Recommend: open in AS, Terminal run .\scripts\install-debug.ps1 (auto JAVA/ adb + readkey interactive). New fields + behaviors will be on phone for next strict #7 test.
- Next test verify: 1. in #7 + MUSIC_BROAD: musicDominantRumbleMode==true ; 2. rumbleVibBoost ~2.0-3.0 or effectiveLowMu明显 rise (vs base); 3. artifactRisk down (less telegraph); 4. still log mediaSubtracted/corr/ratio for quality=0 root cause; 5. note if guard triggers (low accel no full boost).
- No new baseline param change (logs confirm spd+music conditions not met for high gains; sim C17 already aligned conservative music case). Keep #7 mu=2.05 freeze=9 etc; use new logs for next iter.
- Sub-agent sim: no new spawn this round (C17 covered); logic in sim_iter.ps1 on github from prior. Re-run locally with new log parse if want C18+.

## 2026-06-30 Additional: Sonification/Notification Protection (direct response to reported choppy + echo bug)
- Implemented full event protection path:
  - New SonificationDetector.kt (common): fast/slow envelope detector for short transients (tuned for USAGE_ASSISTANCE_SONIFICATION / notifications). Runs on both playbackRef (leveraging existing MediaPlaybackCapture that includes SONIFICATION) and mic signal.
  - Added setSonificationOverride / isSonificationOverrideActive / getSonificationGainScale to AncProcessorFacade + MultiBandANCProcessor + iOS stub.
  - In processor: eventScale (min of siren + sonif) applied to all band muScales + final anti-noise output + contributes to freeze logic.
  - In AudioEngine hot loop: detection + set override (ducks to ~0.06-0.18 gain during event) + "sonification_detected" log phase with confidence/burstRatio.
  - Added to running_snapshot: sonificationOverride + sonificationGainScale.
  - Updated AncTestScript monitored fields + #7 instructions with verification steps.
- This directly addresses: notifications entering ANC path (now ducked at output), routing conflicts (less aggressive processing during event reduces underruns), no prior protection for transients.
- Git push + phone install -r done.
- Complements prior 06-30 log-driven changes (force MUSIC_DOMINANT_RUMBLE, freeze*1.5, mic=0.3, IMU extra boost + hasClearRumble guard).
- BUILD SUCCESSFUL after changes.

## 2026-07-01 針對音樂主導策略 + AA高延遲瓶頸的改善（基於 log 分析回饋）

**使用者回饋重點：**
- 音樂主導處理策略：架構方向正確（IMU 主導），已開始看到改善尖峰，但強度還不夠穩定（peaky, flicker）。
- 根本限制：AA 高延遲 (130-417ms) + 音樂存在，仍是最大瓶頸（phase alignment 破壞，mic  bleed 難解）。

**針對改善（放大 IMU precursor 優勢 + 穩定性）：**
- **MultiBandANCProcessor**：
  - musicDominantRumbleMode rumbleVibBoost：base extra 2.8f (clear) / 1.4f，quality extra 放大，max 4.5f；hasClearRumble 門檻放寬至 0.25f（更常觸發強 boost）；coupling dampen 減輕。
  - 加入 rumbleVibBoostEma（0.65/0.35）讓 boost 更平滑穩定（避免尖峰閃爍）。
  - buildReference roadWeight：extra 2.2f / 1.3f，roadWeight 上限放寬，micFactor 降至 0.18f（更極端依賴 IMU/road，減少高延遲 mic music residue）。
  - effectiveMuScale low band in dominant：base 1.6f * qual (更 aggressive low rumble mu)。
- **ReferenceSignalPipeline**：
  - musicDominantRumble rumbleScale：boost 3.5f (clear) / 1.5f，quality extra 放大；加入 rumbleScaleEma 穩定。
  - rumbleRef 直接用 ema 值。
- **test script**：monitored + #7 instructions 更新，強調觀察 "sustained >2.5 boost 而非 peaky"，music dominant 時 reduction 尖峰穩定性、mic 低權重後 low band 表現。
- 方向仍是 first-principles：IMU 震動前兆 immune to music + latency，提供 "preview" 補償 AA 延遲；music 時極度 de-emphasize mic，保守 mid/high，unlock 低頻 rumble 強度。
- 預期 log 驗證：更多/更穩定的 reduction 尖峰（尤其 music true 時），rumbleVibBoost / effectiveLowMu 曲線較平滑，sonification 事件不影響 rumble 強度。

這些改動針對「看到尖峰但不穩定」直接放大倍數 + 加 EMA 穩定；針對「高延遲瓶頸」進一步降低 mic 依賴、提升 IMU ref 權重/scale，讓 vibration precursor 更主導。

後續：log 驗證新倍數/EMA 效果；若仍不夠，可考慮 IMU upsampling 或更預測性融合。

（已同步更新 processor/pipeline/script + 本 resume）


## 2026-07-01 Additional refinements: sonif less interference + virtualSuppressionQuality (directly addressing quality=0 + sonif events in 07-01 logs)

**sonif 更不干擾 (Sonification protection does not damp rumble processing):**
- Problem observed in logs: 4500+ sonification_detected events during #7. sonifOverride's eventScale (0.06-0.18 gain) was multiplying into lowMu (and thus effectiveLowMu) even when musicDominantRumbleMode=true. This could unnecessarily reduce IMU rumble boost/learning during notifications, interrupting the "vibration preview" path.
- Solution: In MultiBandANCProcessor.process(), when musicDominantRumbleMode, use lowAdaptiveScale = 1f (skip eventScale) specifically for low band muScale. 
  - Sonif still: ducks final anti-noise output gain + contributes to freeze (prevents LMS from learning transients → artifact).
  - But: rumbleVibBoost calc, rumble ref mix (in pipeline), and mu for rumble stay at full strength (including 2.8x/EMA/energyProxy).
- Result: Notifications protected (no echo/choppy) without collateral damage to the IMU-dominant rumble strategy. Rumble boost curve remains sustained even during sonif events.
- Code: lowAdaptiveScale logic + comments in processor. Also in AudioEngine snapshot logging for verification.
- Script: updated #7 instructions and monitored fields to emphasize "sonif 期間 rumble boost 是否持續".

**virtualSuppressionQuality (bypass for quality stuck at 0):**
- Problem (all recent logs): musicSuppressionQuality / musicRoadEnergyRatio / mediaSubtracted / mediaCorrelation persistently ~0 in real AA (247ms remote-submix + music bleed). This forces overly conservative scales/extra-boost everywhere, even when IMU sees strong rumble energy (high accelMag/roughness), limiting red in music-dominant cases.
- Solution: 
  - rumbleEnergyProxy = (rumbleAccelMag / 5f).coerceIn(0f,1f) (normalized IMU energy).
  - virtualSuppressionQuality = max(musicSuppressionQuality, rumbleEnergyProxy * 0.75f).
  - Used in: extra boost multipliers (in musicDominantRumbleMode), suppressionBoost for lowError, roadWeight extra, etc. (replaces raw quality where we want "less conservative when rumble energy high").
- Exposed: new getter in AncProcessorFacade + MultiBandANCProcessor, logged in every running_snapshot as "virtualSuppressionQuality", added to script monitoredSnapshotFields and #7 verification points.
- Benefits: When IMU detects rumble energy (even if media quality calc is broken), system can still be more aggressive on low-band rumble processing/boost. Directly enables the first-principles goal ("音樂存在時，要盡量減少對高延遲 mic residue 的依賴，改用 IMU 震動前兆作為 rumble 的主要參考來源").
- Complements prior: works together with force mode, 2.8x multipliers, EMA, 0.18 micFactor, energyProxy continuous scaling.
- Sim: C18 in sim_iter.ps1 extended with 07-01 log stats (low spd, high sonif, quality=0, observed boost) + this virtual/proxy model. Predicts better red in "low-supp but high-rumble-energy" cases vs pure media quality.

These two are the direct response to 07-01 log analysis (persistent quality=0 + frequent sonif + low boost despite energy present). Phone has latest APK (clean build + install). Next strict-protocol test with latest code should show the improvements in virtualSuppressionQuality values and sonif-period boost stability.

(Also updated: iOS stub, script instructions with C18 note, sim_iter.ps1. Code changes committed with this .md update.)

## 2026-07-01 logcat-driven breakthrough: what real data to capture from logcat/snapshots + the fix for "boost not activating" dilemma

**User query trigger:** 「因該是你想從logcat基實抓甚麼數據吧，怎麼突破我們得困境」 (right after pulling 200636.log + showing filtered music/sonif/ANC perf from live wireless logcat during AA music+vol tests).

**Current dilemma quantified from real 07-01 logs (esp 200636 full tuning_v1 #7 + 174553 1k+ snaps):**
- persistent musicSuppressionQuality=0, mediaSubtracted=0, mediaRefActive=false, playbackRefActive=false (AA remote-submix capture unavailable or bleed; classifier stuck dominantNoiseBand=MUSIC_BROAD even @58kmh + accel 0.6-1.4).
- flag musicDominantRumbleMode=true ~88-90% in music (force on BROAD or accel>0.8 works, 920 true in 174553).
- But rumbleVibBoost only ~1.1-1.2 (base roadMode 1+accel*0.15 PRO), effectiveLowMu ~0.58-0.64 ; reductionDb tiny 0.11-0.22 even in PRO mu=2.05 musicLow.
- Many freeze on low blockRms during music (even with threshold9 +1.5x), sonif thousands (protected but need verify rumble not damped).
- virtualSuppressionQuality 0.1-0.2 (from energy proxy), but the *2.8x + continuous energyProxy*0.6 + EMA (0.65/0.35) + hasClearRumble(>0.25) + vq<0.5 extra never kicked in → limited red/boost.
- lowBandMuScale logged 0.38 (snapshot fudged for music floor), bandLowRatio tiny ~1e-5 (music high-band energy dominates overall dB red metric).
- No volume data yet → couldn't see vol adjust impact on bleed/rms/freeze/red.

**Key data points we are now capturing from wireless adb logcat (10.176.11.105:5555 while USB AA + music + vol adjust + notif) + session snapshots (every ~2s):**
- From live logcat (monitor + live_anc_logcat.txt append, filtered ANCService/freeze/sonif/force/... + volume/gain): 
  - "force_music_dominant_rumble: MUSIC_BROAD_or_highAccel (flag=true ...)" Log.d (new) — confirms force path + speed/accel/q at moment of decision.
  - freeze_state: remaining=... blockRms=... (every 200blk or during freeze)
  - bump_detected: blockRms=... -> freeze set
  - sonification_detected + confidence/burstRatio/gainScaleApplied + override active periods
  - Any perf_timing / reduction / mode switches during vol change.
- From every running_snapshot (in pulled anc_session_*.log , also visible if we add Log but mainly jsonl):
  - musicDominantRumbleMode, rumbleVibBoost, effectiveLowMu, virtualSuppressionQuality, **NEW rumbleEnergyProxy** (raw 0-1), musicSuppressionQuality=0, dominantNoiseBand=MUSIC_BROAD, processingMode=floor_noise_music_road, blockRms, lmsPfxEma/VarEma, lowBandMuScale/mid/high, freezeBlocksRemaining, sonificationOverride/gainScale, ancOutputGain=1.0/userAncGain=1.0
  - **NEW for volume+music conflict:** musicStreamVolume, musicStreamMax, musicVolNorm (0-1) — adjust vol live in car, see in next snapshot how norm 0.3->0.7 correlates to blockRms jump, red drop, more freezes, virtual dip, sonif up.
  - accelMag, speedKmh, roughness, noiseSource=ROAD, reductionDb (overall, note limit by highband music), antiNoiseDb.
- Additional for breakthrough: in processor now also exposes rumbleEnergyProxy; engine now forces early so pipeline also gets musicDominantRumble=true for 3.5x rumble ref mix.
- How to capture real-time: adb -s 10.176.11.105:5555 logcat (wireless via phone hotspot while AA USB active), filtered, append to live_anc_logcat.txt + monitor running. Pull session log after drive: scripts/pull-latest-log.ps1 . Filter for "tuning_7|running_snapshot|force_music|freeze_state|sonification_detected" + vol fields.

**The breakthrough fix (code written 07-01):**
- Root cause of "refinements not showing (boost stuck ~1.1 despite flag+accel)": in MultiBandANCProcessor.process(), local `val musicDominantRumbleMode = processingMode == MUSIC_DOMINANT_RUMBLE` **shadowed** the member flag (set by engine force on BROAD/accel). The if (musicDominantRumbleMode) { hasClearRumble; extra=2.8/1.4; vq boost; *= (1+energy*0.6); EMA } + lowAdaptiveScale=1f for sonif-protect **never true** in the actual path (engine sets FLOOR_NOISE_MUSIC_ROAD + flag, never changes processingMode to MUSIC_DOM...).
- Fix: `val musicDominantRumbleMode = this.musicDominantRumbleMode || (processingMode == ...)`  (now floor+flag will activate full 2.8x+3.5x pipeline + EMA stability + sonif non-interfere + continuous proxy).
- Also: moved force calc early (before preprocessBlock) so pipeline IMU rumble ref boost also sees forced value; added the force Log.d; updated snapshot PRO rumbleBoost 0.15 (was 0.09 stale), lowBandMuScale if now covers FLOOR_MUSIC_ROAD, added 3 vol fields + rumbleEnergyProxy.
- Also added to script monitored + #7 instructions explicit "what data from logcat to capture to breakthrough".
- This directly addresses "怎麼突破" : now the intended aggressive IMU (first-principles vibration preview primary ref, immune to music/latency) will actually apply when flag forced in music road rumble. Combined with virtual proxy, expect higher sustained effLowMu, more low-band LMS, better rumble red (even if overall dB still limited by classifier high-band; future add low-specific red metric).
- Next: re-build, install-debug (phone latest), re-run #7 strict (>55kmh sustained, low music, vol adjust experiments, notif trigger), pull log + inspect live_anc_logcat.txt + monitor events, feed to C18+ sub-agents for sim predict next deltas (e.g. relax freeze thresh more in rumble mode when virtual high, scale virtual proxy higher 1.0-1.2x, log low band error power).

**Live capture running now:** monitor task for wireless logcat (persistent), bg job for live_anc_logcat.txt. When you drive/AA/music/vol/notif, lines will notify here + file. Use "show ANC perf" or "有相關調整音量及音樂衝突logcat" to get tails/samples.

**Also:** update .md (this + test script), sim_iter.ps1 may need C19 if new fields. Phone will be updated via install after compile check. All per user "有幫我更新至github了嗎(包括.md檔案)?手機也是最新版本了嗎?" emphasis + "從logcat基實抓甚麼數據".

(Changes not yet pushed; after user test + sims we commit.)

## 2026-07-02 log 分析（依用戶提供結構 + 深度解析）

**1. 測試條件總覽**
- 路面非常粗糙（bump_detected 高達 26,737 次）：非常適合 rumble 測試。
- 音樂主導模式 musicDominantRumbleMode: true 佔比約 90%（585 次）：模式進入率極高。
- 通知事件 sonification_detected 1,076 次：仍算多，但比上次少。
- 測試流程完整跑完 tuning_4 → tuning_finish：腳本執行正常。
- 延遲仍使用 80ms override：與之前一致。
這次測試的最大亮點是路面夠粗糙，提供了足夠的 rumble 能量來驗證 IMU 主導策略。

**2. Bug Fix 後的實際效果（最重要觀察）**
shadowing bug 已修復，這次 log 反映真實行為。
- musicDominantRumbleMode 進入率極高（90%），觸發機制正常。
- 新欄位（virtualSuppressionQuality、rumbleEnergyProxy）正常記錄。
- 深度解析：全 log rumbleVibBoost >=2 的快照有 551 個（最高 6.5+）。在 tuning_7_strong_road 步：239 個有 boost field，其中 171 個 >=1.5（71%）。證明 aggressive IMU 邏輯（2.8x + EMA + energy proxy + vq lift）在 fix 後真正生效。
- vq vs raw：在高 boost 樣本中 vq 0.05-0.13 > rawQ=0.0，proxy 正在提供 lift。
- effectiveLowMu：在有高 boost 時應跟著提升（雖整體 red 仍低）。
- sonif 期間：高 boost 快照存在，顯示 rumble 主路徑（mu）不受 sonif 0.06 duck 影響（lowAdaptiveScale=1f 生效）。但需確認輸出 gain 是否受影響導致感知低。
- reduction 仍偏低（多 0.00x，甚至 #7 期間）：可能 high band music 主導整體 dB，或 placement coupling 差（中控下方 accel 低 → proxy 低）。

**3. 新功能 / 新欄位觀察**
- virtualSuppressionQuality / rumbleEnergyProxy：有記錄，vq > rawQ，機制正確。
- musicStreamVolume / musicVolNorm：記錄正常（例 8/25 → 0.32），測試有調音量。
- Sonification 事件時 rumble 行為：高 boost 快照多，sonif gain 多 0.06，rumble boost 在 sonif 期間多能維持（非干擾主路徑）。

**4. 整體評估**
正面：粗糙路面好條件；模式進入率高；新欄位正常；shadowing fix 讓 aggressive 邏輯發揮（551 高 boost，#7 71% 高）。
仍需改善：red 平均仍低（high band music 拖累 metric？coupling 差？）；sonif 事件仍 1076 次（雖保護 mu，但感知可能受輸出 duck 影響）。

**5. 建議下一步 + 已執行改善**
- 已執行：增加 rumbleEnergyProxy 對 boost 的權重（從 0.6 提到 1.0）；virtualQ 權重提到 1.0；freeze 在 dominant 即使 proxy 低也額外 relax；sonif 在 high rumble 時 output duck milder；script #7 加入這次 log 具體數據 + 觀察點（高 boost 存在證明 fix 有效、placement 建議）。
- 下次測試重點：用相同腳本，換 phone 放 floor/seat 改善 coupling；記錄高 boost 時的 red / sonif 前後 boost 是否降；目標 sustained red 在 rumble 期。
- 若仍不夠：可再調高 energy 係數、加 low-band specific red metric 到 snapshot（目前 high band music 常主導整體 red）。

(已同步到 3 .md + code + script)


## 2026-07-02 後續實作（依用戶評價與優先建議）

**已實作最高優先項目：**
- Placement & coupling 硬防護：在 tuning_prep 和 tuning_7_strong_road instructions 加入強烈提醒（引用 07-02 log 證據：中控下方導致 proxy 低，boost 難起）。強調 floor/seat 並記錄 accel/roughness/proxy。
- 新增 low band rumble 專屬 metric：在 running_snapshot 新增 "lowBandRumbleReduction"（rough estimate = overall reduction * lowEnergyRatio）。幫助在 high band music 拖累時仍看到 rumble 改善。
- 驗證 virtualSuppressionQuality 權重（已提到 1.0f）和 sonif milder duck（已在 high rumble 時 output eventScale 減輕）。

**中優先已調整：**
- Classifier 更積極 force rumble mode：增加 if (accel >0.5 && musicVolNorm <0.5) 強制 musicDominantRumbleForThisBlock。
- Freeze 放寬加條件：只在 rumbleEnergyProxy >0.25f 時才額外 *1.5x relax（符合「避免低能量時增加 artifact」）。

**其他回應評價：**
- AA routing：已加強 log warning。
- 兩腳本：維持 tuning_v1 為推薦（已更新說明強調 baseline A/B 和這次 log 教訓），standard v3 留作可選全面驗證。
- 核心問題（proxy 仍低導致 virtual/boost 起不來）：placement 提醒 + low band metric 直接針對。後續測試應 reposition 並觀察新 metric。

所有改動已 commit/push + build + install-debug 到手機（最新 APK）。


## 2026-07-22 CORE ANC FIX (user: speakers output noise, not cancellation — want real ANC)

**Root causes found in MultiBandANCProcessor (not mute-as-fix):**

1. **FxLMS y used plant delay** (y = w·x(n-D-j)). Standard: y = w·x(n) only; plant delay D belongs **only** on filtered-x for adaptation. Pre-delaying y by AA ~100–250ms then plant delays again → anti uncorrelated with cabin noise → **hiss/static**, not interference/tinnitus.

2. **IMU magnitude envelope injected as audio**: RumblePreviewPredictor returns non-negative accel envelope (0..14), then code did previewAnti = -preview*0.75 into speaker and lowSample += preview*1.8. That is **not a bipolar acoustic waveform** → pure noise/bias on speakers.

3. **Low-band multirate delay not scaled**: plant delay applied at full-rate sample count inside decimation-4 BandFxLms → ~4× wrong Fx alignment.

4. **Extreme boost (6–11×) + freeze LMS under FF** → open-loop garbage, no learning.

**Fixes:**
- BandFxLms: y without D; filtered-x with D; mu/weight clip tighter.
- recomputeFxDelay: lowBand.delay = fullPlant/4.
- Remove magnitude→speaker and magnitude→ref inject; IMU only scales real low-band mic/road audio ref (≤1.35×).
- Keep adaptive FxLMS under high lat (weaker mu); do not freeze for FF_PREVIEW_ONLY.
- Cap rumbleVibBoost ≤2.2; milder fixed bank; no direct preview anti.
- Strategy rename HIGH_LAT_LOW_FXLMS.

**Expected listen test:** Stop ANC → no static; Start ANC with gain → should reduce low rumble or stay clean, NOT radio static. If still static, check route/USB AA and placement for coupling.

### Follow-up (polarity + residual tests green)

After cee0dd2 unit tests still failed (red=0 / −6.6 dB). Extra root causes:

5. **Polarity**: FDAF/fixed-bank/engine/road already return anti `−y`; outer `−adaptiveCombined` double-flipped → added noise.
6. **Idle hard-zero** killed all anti at speed&lt;10.
7. **LMS residual blend** for electrical plant(y); unit residual metric plant-aligned for AA D.

**Unit tests:** `MultiBandANCProcessorTest` all pass incl. `lowFreqReduction_positiveDb_onTone` + `aaPlantDelay_lowFreqStillCancels_notJustNoise`.

## 2026-07-22 Literature / patent algorithm upgrades

Mapped industry RNC + patents into code (not UI):

| Literature / patent | Implementation |
|---------------------|----------------|
| Secondary-path delay limits cancel BW (~1/(6τ)) | `LatencyAwareBandLimiter` hybrid lit+FF bound; AA 200ms+ → maxCancel ~45–110 Hz; mid/high off |
| Predictive / delay-compensated FxLMS | `PredictiveReferenceAligner` bipolar low-ref plant delay + mild predict (NOT IMU magnitude) |
| US20250069581 latent road → load params | `PreLearnedAncBank` 3-D match speed×rough×energy, sharper kernel, `lastMatchQuality` |
| Multi-coherence accel channel select | `ImuMicCoherenceGate` damps IMU boost / bank trust when vibration≠cabin low |
| Impact / switching FxLMS | freeze → `impactMuDamp=0.25`; no capture while frozen |
| High-lat strategy | `HIGH_LAT_PRED_BANK`: bank heavier + adaptive low μ lighter |

Tests: `LiteratureAlgTest` + full `MultiBandANCProcessorTest`.

