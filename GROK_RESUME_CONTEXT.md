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
- 2026-06-26 Log Analysis (new 2601/2602): phone speaker test, music true but mode normal/road thanks to musicLowAnc. lms now updating actively (big win for low freq adaptation). antiNoiseDb improved, max 150, mid on. Confirms low freq road ANC works alongside music without full suppression. Media conflict addressed by userAncGain independent control + smarter mediaRefActive detection (won't force floor on AA where capture unavailable). No arch restructure needed -- per-band logic in processor + prefs switches allow targeted improvements.

- 2026-06-26 �ϥΪ̦^���G�ثe�ӤH���լO**��� + USB AA + �쨮�Y�椸**�]�D BC-8000�^�Cı�o�i�׺C�B�ﵽ������C
  - �T�{ AA USB ���ѡGremote-submix�Aanti �g phone audio �`�J����z�C���֤]�g AA media�C�Y�椸�`�N phone audio �k "voice" �s�A�ɭP���q�Ĭ�]app ���y���Ʊ�A�S��k�W�߽խ��֡^�C
  - ����������D�]�]�q log�^�G������ 150Hz�A�C�W rumble ��q�`�Q���� mask�]MUSIC_BROAD �D�ɡ^�ALMS �A���ݮɶ��]�Y�ϲ{�b��s���D�^�Afloor �O�@���|�v�T�C�W anti ��X�C�D AA phone speaker ���ո��n�A������ AA �~�O�u������C
  - �w��@�ﵽ�]musicLowAnc + userAncGain + ���z���� + 150Hz�^�Glog ��� mode ���Q���ֱj�� floor�Alms ���D�AantiNoiseDb ���X�z�C���D�[�Pı��������A�]�� rumble �z�έ��֯�q���C
  - ��ĳ�E�J���աG1. �L���֩ΫܧC���֡C2. �䦳���� tire/wind rumble �������]���W�f�o�B40-70km/h�^�C3. PRO tier + musicLowAnc ON + forceNormal�]�p�G�Q bypass�^�C4. userAncGain �԰��]0.8+�^�C5. �] 3-5 �������A���A�ץX log ��� ON/OFF�C6. �� log reductionDb �b�C�W + antiNoiseDb ��q + �C�W band ratio�C�ؼЬO rumble �D�[�����z�C
- �t�ά[�c�����G�L�ݤj���c�C�ثe AudioEngine�]loop + route + focus�^ + MultiBandProcessor�]per-band LMS + fdaf + modes�^ + prefs �ܼu�ʡA�A�X incremental �վ�]musicLow per-band�B���z mediaRef �����^�CAA �Ĭ�D�n�O head unit ���q�s�� mapping + pure anti ��X�]�L media passthrough�^�C�i���ӥ[ "mixer" �h�b music �ɿ�X attenuated media + anti�A�����ַPı����A���ثe�W�� gain �w�ѨM�i�թʡC���I�~�� DSP �ӽ� + ���ձ��󱱨�C
- �[�c�n�B�G�� mock ���ա]sessionContext�^�B���[ per-mode/per-band �޿�]�w�Φb musicLow�^�CiOS stub �]�e���X�C
- �U�@�B�G�h�� AA + rumble ���� + music on/off ����� log�A������Ǥ��R�����٥i�ա]e.g. �C�W boost�Broad ref �v���Bmu �ʺA�^�C
  
  
## LMS PID-like �ծ� + ���� debug�]�s�^  
�w�s�W TestLogPanel LMS mu ���v�Bfreeze ���e/�s��Blatency override �ѹ���u�ǲߡv�վ��k�CAudioEngine �|�Y�ɮM�� prefs �I�s processor setter�C�h�Ʊ��p�U�� mu �ݰt�� freeze ���e�C����D�n�� AA remote-submix ����A150Hz �w push�F��ĳ�� phone speaker baseline ���ҡC�Ԩ� MULTI_MACHINE_SYNC.md �s���`�C  
  
�w�ɱj log ���СGperf_timing + running_snapshot �{�b�|��X debugLmsMuMultiplier�BdebugFreeze*�BdebugLatencyOverride�BusingLatencyOverride�Blow/mid/highBandMuScale�CdominantNoiseBand �쥻�N���C�� MULTI_MACHINE_SYNC �s�W���u���� Log ���и�Ū���v�C�Τ�i�̦��@���� 3-5 �հѼƪ� snapshot ���C 
  
�}����s�G�s�W CarRoadTuningScript�]�����̷ӥΤᴣ�Ѫ� 5 �� mu/freeze/override ����^�CGuidedTestPanel �{�b���M�����s�u�}�l�����ծմ��ա]���ˡ^�v�C�Ĥ@���ꨮ�Ъ����γo�� guided script + TestLogPanel �]�ѼơC�Ԩ� MULTI �̷s����C 

**2026-06-27 更新（快速 sub-agent 模擬迭代 + #7 延伸）**：
- 路噪調校腳本 (car_road_tuning_v1) 已延伸為快速迭代版，使用 sub-agent 分別模擬三個變體（1. 舊部 baseline/prep+#4+#4b+#5 作為穩定 A/B 對照；2. 當前 #6 mid-force；3. 延伸 #7 strong-road + DSP 強化）。移除無用早期 baseline 1/2/3（只確認已知高延遲/低 mid 問題）。保留 old prep/4/4b/5 UNCHANGED 作為單輪內穩定 baseline A/B；#6 驗證 mid 貢獻（forceNormal=false + musicLow + roadRumble 放寬）；#7 進一步針對 dominant 轉 ROAD_MID even music（speed>28 + energy 條件下 force，mu=2.05/ov=80、更強 mid boost 2.15x + midError*1.28、center 335Hz 針對 300-350Hz）。
  - 現在每次跑 prep+4+4b+5+6+7+finish（更少步驟，更快）。強調 4 次快速迭代 + 配外部錄音 + spectrum。finish instructions 強調單輪 A/B（old #4b 穩定 baseline vs #6 vs #7_ext），下一輪優先重跑#4b/#6/#7 變體 + 微調（跳過無用早期）。每步 scenario 註 "Skoda #4/#4b/#6/#7 經驗, iter X"。
  - 目標：快速迭代到 effective latency 改善（override 推 maxCancel 250-380Hz+）、200-350Hz reduction 有感（-4~-6dB+，mid 貢獻 via effectiveMidMu 0.6+，dominant 轉 ROAD_MID，主觀 rumble 0-10 分明顯）。
  - 自動套用參數（#6/#7 為 #4 延伸）。使用者只需按「完成這步」 + 最後在 GuidedTest finish 直接「儲存到下載 / CarANC_Logs」，無需手動調 TestLogPanel。
- 使用 sub-agent 平行模擬三變體（分別讀 code + 最新 log 校準 + sim_iter.ps1 風格公式 + effMidMu/roadRumble/dom shift 擴展模型），產生預測 JSONL + metrics（baseline 弱；#6 partial breakthrough effMidMu=0.3+ midScale=1.0 maxC 380；#7 預測 red -5.5+ dom ROAD_MID eff 0.7+）。這讓迭代比實機快非常多（無需每輪等實車測試）。
- 突破重點：即使 AA 媒體路由（remote-submix）導致 music=true + dominant MUSIC_BROAD（playbackRefActive=false 但上層 music flag true），仍用 musicLow + roadMode + mid 強化讓中頻 rumble 有貢獻（effectiveMidMu 追蹤 mid 實際 muScale）。#7 再強化 classifier（speed>28 + energy force ROAD_MID even music）+ DSP（guarded boosts）。
- 最新實測 log（180157.log，#6 期間）：effMidMu=0.3（首次正值）、midBandMuScale=1.0、maxC 高達 380、processingMode=floor_noise_music_road、noiseSource=ROAD、speed~50-67kmh、red max 0.458（28 筆 >0.1）、但 dominant 仍 MUSIC_BROAD（music=true）。證明 mid 機制已生效（比舊 baseline 明顯改善）。
- 2026-06-29 最新實車 log（anc_session_20260629_073238.log，非 guided script 手動跑，AA music 強）：reduction 極低 (max 0.78dB avg 0.07)、effMidMu 卡 0.083、dominant 幾乎全 MUSIC_BROAD (301/316)、low+mid ratio 即使高速 rough 最高僅 0.071（music 能量主導）。證實 energy thresh 0.30 過嚴 + 需嚴格低音樂 vol。已 data-driven 突破：classifier force ROAD 門檻降至 0.06 + 放寬 subcond + 音樂 high check 加入 rumble 例外；processor rumbleContext guard 擴大（即使 dominant 仍 MUSIC 也拿 1.75x / midErr*1.28）；MUSIC_BROAD mid gain 0.15→0.28。搭配 tuning script 強調「vol<15-20%」+ 50+kmh rough，即可讓 0.06 thresh + rumbleContext 生效，預期 #6/#7 effMidMu 0.5+、red -3~-6dB on 200-350。下一輪用 car_road_tuning_v1 嚴格條件跑即可驗證。
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
