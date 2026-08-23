# CarANC 版本紀錄 / Changelog

**版本來源**

| 平台 | 檔案 | 主畫面顯示 |
|------|------|------------|
| **Android** | 專案根 `version.properties`（`VERSION_NAME` + `VERSION_CODE`） | `v{VERSION_NAME}`（internal 另加 `-internal`） |
| **iOS** | `iosApp/CarANC/Info.plist` + Xcode `MARKETING_VERSION` / `CURRENT_PROJECT_VERSION` | **`v{marketing} (build)`**（右上角） |

規則：

- 每次 **Play 上傳**（含內部測試）：Android `VERSION_CODE` **必須 +1**
- 每次 **iOS 實車／IPA**：`CFBundleVersion`（build）**必須 +1**
- 每次 **實車測試功能包**：`VERSION_NAME` / iOS marketing **同步**，並在本檔新增章節
- 共用 DSP／schema 功能：兩端 **marketing 宜對齊**；純 iOS 平台功能（如 CarPlay）可 iOS marketing 超前
- Commit 建議：`feat:` / `fix:` / `docs:` + 版本同步

---






## [1.2.26] — 2026-08-24 · Android code 28 · AA 走 48kHz 音樂埠（mixp:0）

實車 USB AA：Gearhead 開兩條 `remote_submix`——`mixp:0` 48 kHz 音樂、`mixp:1` 16 kHz 語音。1.2.25 同分時選到 **mixp:1** → 50Hz 送得出、艙裡 `NO_LINE`。

改為打分偏好 `mixp:0` / 48 kHz，罰 `mixp:1` / ≤16 kHz。Log：`AA_SUBMIX_PICK` + `AA_SUBMIX_CANDIDATE`。

裝完：**AA 仍插著 → 停止再開始降噪** → 暫停音樂、車機音量拉高 → 聽 50Hz 悶或沙。log `routedOutput` 應含 `mixp:0`。

---

## [1.2.25] — 2026-08-23 · Android code 27 · AA 連上就自動測路徑

USB AA 連上時自動跑路徑檢測，不再只信 `aa_path_check` 的「送出 peak」。

| 項 | 內容 |
|----|------|
| **何時測** | 開降噪時已連 AA；或 **先開降噪再插 AA**（rising edge） |
| **送出** | 400ms 麥 baseline → 1.8s 50Hz（僅在非手機喇叭時播） |
| **進沒進 AA** | `routedOutput` / type：`remote_submix` vs `SPEAKER`；backend 是否還停在 `AAUDIO_LIKE_LOCAL` |
| **車機有沒有出** | 麥 50Hz 相對 baseline 有沒有起來（`cabinHeard`） |
| **verdict** | `PHONE_SPEAKER` / `SUBMIX_SEND_OK_MIC_HEARD_50HZ` / `SUBMIX_SEND_OK_MIC_NO_50HZ` / … |

若先開降噪再插 AA，聲音常還在手機喇叭：通知「請停止再開始」。這不是 bug 隱瞞，是 Track 建在 local，必須重建。

Log：`aa_became_connected` + `aa_path_check`（含 `verdict`、`cabinHeard`、`micDelta50Db`）。

---

## [1.2.24] — 2026-08-23 · Android code 26 · 停止降噪真的會停

1.2.23 實車：App 開著，按「停止降噪」沒反應（或停一下又自己開）。Log：`onStartCommand` 連打兩次（約 80ms）→ 兩條 AudioEngine；`onDestroy` 只停到最新那條；腳本 `LaunchedEffect(ancRunning)` 一看到停就再 `startForegroundService`。服務已死時 UI 還卡在 Running，再按停止是 no-op。

| 項 | 內容 |
|----|------|
| **停** | 按鈕立刻 `latchStopped()`，再送 `STOP` intent；殘留 loop 不能把 UI 寫回 Running |
| **雙開** | 已有 engine 就忽略第二次 `onStartCommand`；`stop()` 設 `stopRequested` 再放音訊 |
| **腳本** | 不再在 `!ancRunning` 或換步驟時自動再開 ANC；開始腳本仍只開一次 |
| **通知** | MediaSession Pause/Stop 會停服務 |

裝 1.2.24 後：按停止 → 按鈕變「開始降噪」、通知消失、喇叭不再出 anti。腳本進行中也可以停；要再開就按「開始降噪」。

---

## [1.2.23] — 2026-08-23 · Android code 25 · 修復開 ANC 閃退

1.2.22 `AncMediaSession` 在 `session` 屬性尚未賦值時呼叫 `setPlaying()` → NPE，**開始降噪 / 腳本直接閃退**。改為 init 完成後再設 PLAYING；MediaSession 失敗不擋服務啟動。

---

## [1.2.22] — 2026-08-23 · Android code 24 · AA 當真正的音樂源（50Hz）

### 問題

USB AA 的 50Hz 低嗡一直沒進艙。先前改 USAGE / 改藍牙都沒打到真正差異：

Spotify 走 AA **音樂 DAC**（有低音）。CarANC 只是一支 `AudioTrack`，還開了 **LOW_LATENCY**，車機常把它當附加音/語音（高通 → 只有沙）。

USB AA 和藍牙**不能同時出聲**。要 AA，就必須讓 AA 把我們當「正在播放的音樂」。

### 這次只改這一條

| 項 | 內容 |
|----|------|
| **MediaSession** | 狀態 PLAYING、通知 MediaStyle、前景 `mediaPlayback` |
| **AA AudioTrack** | **關掉 LOW_LATENCY**（改走一般媒體路徑） |
| **50Hz 那幾秒** | 短暫 `AUDIOFOCUS_GAIN`（讓車機切到音樂通道） |
| **日常 ANC** | 仍 MAY_DUCK，不整段掐掉你的歌 |

測 50Hz：**自己暫停 Spotify/AA 音樂**，聽低沉嗡還是沙。路悶步可以再放音樂。

若這次艙錄仍沒有 50Hz 线：就是 Skoda AA 音樂解碼也切了 50Hz，App **無法用 EQ 補回**。

腳本：`三目標·1.2.22 AA當音樂源`

---

## [1.2.21] — 2026-08-23 · Android code 23 · AA 不搶音樂 + 腳本可再開降噪

### 用戶回饋（1.2.20）

- USB AA 與藍牙喇叭**不能同時出聲**；把 ANC 改走 A2DP 等於放棄 AA。
- 腳本測完無法手動開一般降噪。
- 腳本會切掉正在播的音樂。

低音被切是 **車機 AA 混音/EQ**（remote_submix），不是「再改成藍牙就能一邊 AA 一邊低音」。

### 修復

| 項 | 內容 |
|----|------|
| **路由** | AA 連上 → 仍走 `remote_submix`（AA 混音）。沒 AA 才走 A2DP |
| **焦點** | `MAY_DUCK`，不再 `AUDIOFOCUS_GAIN` 獨佔；不把 STREAM_MUSIC 拉滿 |
| **腳本結束** | 不再自動停 ANC；reset gain=1 / mute=false |
| **中止** | `test_script_abort` 也清 mute/gain（先前漏聽） |

腳本：`三目標·1.2.21 AA可聽音樂`

---

## [1.2.20] — 2026-08-23 · Android code 22 · A2DP 低音优先

### 路測（1.2.19）

- **藍牙 A2DP**（沒 AA）：怠速艙錄 50Hz **LINE**（峰 48Hz，Q +11.5）。車機音樂功放能出低音。
- **USB AA** `USAGE_MEDIA`：有聲但艙錄 **沒有 50Hz 线**；公平路悶 on 全頻 +7～11 dB（沙）。
- 兩條路都還沒測到路悶取消。

### 修復

ANC PCM **優先走車機 A2DP**（分數高於 `remote_submix`）。USB AA 仍可連，但 anti 不再往 submix 灌（那條是沙）。沒有 A2DP 才 fallback submix。

路測：**不要插 USB AA**（或關掉 AA），只留 Skoda 藍牙；怠速聽 50Hz 低嗡。log `bassSink=a2dp`。

腳本：`三目標·1.2.20 A2DP低音优先`

---

## [1.2.19] — 2026-08-21 · Android code 21 · MUSIC 总线 + 四频路径

### 問題（1.2.18 耳聽）

開 ANC **沒有低沉 50Hz**，只有沙。NAV overlay 是車機**語音通道**（VAG 高通約 150–300Hz）。50Hz 進不了功放；活下來的是中高頻殘邊 → 沙。這條总线**無法用 App EQ 補回**。

### 修復（唯一還能試的 AA 軟件路徑）

| 項 | 內容 |
|----|------|
| **总线** | ANC PCM → `USAGE_MEDIA` + `CONTENT_TYPE_MUSIC`（與 Spotify 同音乐总线） |
| **立体声** | L/R 複製，兩側門喇叭 |
| **音量** | 手機 `STREAM_MUSIC` 拉滿（車機旋鈕仍是艙音量）；停止時還原。1.2.18 log 是 6/25 |
| **Focus** | `AUDIOFOCUS_GAIN`（我們就是 media）；LOSS 後 5s 重請 |
| **不再** | NAV overlay、靜音 MEDIA keep-alive |
| **误伤** | AA 上不再用 STREAM_MUSIC 當「正在放音樂」去閘 anti |
| **Tone** | 振幅 0.72；脚本 **50 / 80 / 120 / 200 Hz** 分段艙錄 |
| **Anti** | 仍 70Hz LPF，避免沙走漏 |

### 路測怎麼判

- **50Hz 有低嗡** → 路径通，才能談消噪算法
- **50 没有、80/120 有** → 车机切超低频，路闷带部分可做
- **只有 200Hz 有声** → AA/喇叭物理切掉 40–80，**手机 AA 做不了路闷**（只能外接低频单元）
- **四档全沙或没声** → 路由仍死

腳本：`三目標·1.2.19 MUSIC总线+四频路径`

---

## [1.2.18] — 2026-08-21 · Android code 20 · AA overlay 混音 + 50Hz 艙錄

### 問題（1.2.17 路測）

用戶聽不到 50Hz 低嗡，只聽到**雜訊**。艙錄（路悶 A/B）已證明：路徑 PASS 時開 ANC **中高頻 +1～2 dB**（沙）。

根因：ANC `AudioTrack` 用 **USAGE_MEDIA + AUDIOFOCUS_GAIN**，車機當**音樂**混音（EQ/高通切掉 50Hz），並與 AA **搶 focus**（`holdingMediaFocus` 跳變 → path_check FAIL）。

### 修復

| 項 | 內容 |
|----|------|
| **Overlay** | ANC PCM → `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE`（不是 MUSIC） |
| **Keep-alive** | 靜音 `USAGE_MEDIA` track 維持車機管線 |
| **Focus** | `GAIN_TRANSIENT_MAY_DUCK`（不獨佔）；DELAYED 不算 live |
| **50Hz 艙錄** | `diag_tone_50` 自動 `cabin_diag_tone_50_*.wav`（不須車速） |
| **Send LPF** | 非 tone 再加 70Hz 雙極；diag 時不 duck sonif |
| **50Hz 振幅** | 0.22 → 0.38（overlay 較小聲） |

### 路測必收

1. 開頭應聽 **低沉 50Hz**，不是沙  
2. 艙錄 `cabin_diag_tone_50_*` 峰應在 ~50Hz  
3. 路悶 on vs off：**中高頻不大增**  
4. log `trackUsage` 含 `NAVIGATION_GUIDANCE`

腳本：`三目標·1.2.18 overlay混音+50Hz艙錄`

---

## [1.2.17] — 2026-08-20 · Android code 19 · 雙端 P0/P1 對齊

交叉改進（不是 Android 單向）：朋友 1.2.15 開 App 不閃、**開始降噪／腳本閃退**；1.2.16 (27) 修了 KMP 鎖但仍有 `removeTap` 首啟崩潰，且 iOS 沒套 classifier / plant D / ROAD 模式。

### iOS P0（build 28）

| 項 | 內容 |
|----|------|
| **removeTap** | 只在真的 `installTap` 後才 `removeTap`；先 `engine.stop` 再 detach |
| **stop 順序** | 先 `isStarted=false` 再拆 graph / `processor.release`，避免 tap 撞已釋放 KMP |
| **plant D** | `setMeasuredLatencyBreakdown` + store `refinePlantDelayFromProbe`（先前只 log 極性） |
| **classifier** | 每 block `applyBandSnapshotFromBlock` → `ROAD_NOISE_GPS` + NVH 增益（對齊 Android vis 迴圈） |

### 雙端 P1

| 項 | 內容 |
|----|------|
| **openScale** | 拿掉 `coerceAtLeast(0.5)`，短測 0.25/0.30 不再被抬回 0.5 |
| **path_check** | PASS 需 **live focus**（DELAYED 不算）+ **car sink / carAudio**；送出 peak 只是必要條件 |
| **AA focus LOSS** | 5s 路由刷新時 **重請 MEDIA focus** |
| **AA handshake** | CarConnection 最長等 2s 再 settle（不再死等 500ms） |
| **艙錄** | Android 改寫 **ANC 同路 PCM WAV**（不再第二路 MediaRecorder 搶 MIC） |
| **UI snapshot** | iOS 診斷在 NSLock 內拷貝，不跨執行緒讀 KMP 欄位 |

CarPlay **車機圖示**仍需 Apple `carplay-driving-task` entitlement（空 entitlements 無法自填）；音訊仍可走 `carAudio`。路徑自檢 FAIL 就不要信消噪 KPI。

腳本：`三目標·1.2.17 雙端對齊P0P1`

### 路測必收

1. 狀態頁 **`v1.2.17 (28)`** / Android **`v1.2.17`**
2. 開 ANC **不閃退**；開頭短 50Hz；log `carplay_path_check`/`aa_path_check` + `failReasons`
3. 行駛 `nvhFocus` 非長期 IDLE；`plantDelaySamples` 應 >64（有 store 或 HAL 估計）
4. 艙錄 `cabin_*.wav`（雙端同一 mic 路徑）

### iOS（build 28）

- 重編 `CarANCShared`（openScale 修正、`applyBandSnapshotFromBlock`）
- NSLock 在 **Swift `KotlinAncBridge`**（不是 Kotlin processor 本體）
- 狀態頁 **`v1.2.17 (28)`** · `dist/CarANC-ios-kmp-debug.ipa`

---

## [1.2.16] — 2026-08-20 · Android code 18 · AA MEDIA 焦点 + 路径自检

### 問題（1.2.15 公平艙錄 + 主觀）

1. **無音樂像喇叭沒輸出；開音樂才有電子雜訊** — AA 用 SONIFICATION 且運行中不持有 Media focus，車機常不開 Media 流
2. openBoom 滿幅 + corr≈0 → 艙錄 40–350 **全面 +3～+5 dB**
3. 極性 A/B 一開始就滿 open，無法短測
4. `carSinkRouted=false` 在 `remote_submix` 上誤報（標籤 bug）

### 修復

| 項 | 內容 |
|----|------|
| **AA track** | 預設 **USAGE_MEDIA**；log `trackUsage` |
| **MEDIA focus** | 運行中 **AUDIOFOCUS_GAIN 持有**（校正後不再 abandon）；log `aa_media_focus` |
| **路径自检** | Running 後 1.5s 自動 50Hz → log **`aa_path_check` PASS/FAIL**（focus+usage+sendPeak） |
| **carSinkRouted** | `remote_submix` 算作 AA sink |
| **openScale** | corr 未鎖 **25%**；腳本 forced 短測 **30%**；corr 鎖上後滿幅 |
| **mid** | 終端 LPF **~55 Hz×4**；high-lat LMS ×**0.50**；notch 再砍 |

### 路測必收（先路徑、後消噪）

1. 音樂全關 → 開 ANC → 開頭應聽 50Hz；log `aa_path_check=PASS` + `holdingMediaFocus=true`
2. 聽不到低嗡 = 喇叭路徑仍 FAIL，**不要信消噪 KPI**
3. 等長 off/ppos/pneg；40–80 on≤off；180–350 <+1.5 dB

腳本：`三目標·1.2.16 MEDIA焦点+路径自检`

### iOS（build 27 · 對齊 1.2.16 + 修開始降噪閃退 + CarPlay 路徑自檢）

- **閃退**：KMP `MultiBandANCProcessor` 加 NSLock（音訊執行緒 vs 主執行緒競態）；麥克風權限先請求；start 失敗清狀態
- **路徑自檢**：開 ANC 後 1.5s 50Hz → log `carplay_path_check` / `aa_path_check` PASS/FAIL + `routeOutputs`
- 重編 KMP（open 25–30%、55Hz×4、LMS ×0.50）
- 腳本對齊 1.2.16；保留 anc_logs 存檔分享
- 狀態頁 **`v1.2.16 (27)`** · `dist/CarANC-ios-kmp-debug.ipa`

---

## [1.2.15] — 2026-08-20 · Android code 17 · boomOut 修復 + 中頻 cabin winner + 更緊 LF

### 問題（1.2.14 公平艙錄 074721）

首次有降噪：60–90 Hz **−3～−4 dB**，但：

1. **openBoom=true 而 boomPressureOut≈0**：sonif `freeze` 把 `lastGain` 卡在 0  
2. **ppos 180–350 +2.9 dB**：LMS totalScale×1.28 + 85Hz 終端仍不夠陡  
3. **winner 選 +1（plant）**：mic 低頻兩邊都 −50 → cabin 低頻打平，中頻沒投票

### 修復

| 項 | 內容 |
|----|------|
| **openBoom×freeze** | openBoom 時強制套用 gain（不凍在 0）；LPF 冷啟動 nudge |
| **終端 LPF** | ~70 Hz ×4 極（原 85×3） |
| **LMS scale** | high-lat boom：totalScale ×0.72（不再 ×1.28） |
| **cabin winner** | score = lowDb + 0.8×midDb（180–350）；更安靜勝 |

### 路測必收

- openBoom 時 **boomOut med ≫ 0.01**
- 等長三件套：40–80 on<off；**180–350 <+1.5 dB**
- winner 看 `pos/negCabinMidAvg`

腳本：`三目標·1.2.15 boomOut+中頻winner`

### iOS（build 25 · 對齊 1.2.15）

- 重編 `CarANCShared`（openBoom×freeze 修復、70Hz×4、LMS high-lat ×0.72）
- cabin A/B：low + 0.8×mid（`pos/negCabinMidAvg`）；腳本對齊 1.2.15
- 保留 Embed framework（防開機閃退）
- 狀態頁 **`v1.2.15 (25)`** · `dist/CarANC-ios-kmp-debug.ipa`

### iOS（build 26 · 結束存 Log 檔 + 分享檔案）

- 對齊 Android：session 即時寫入 `Documents/anc_logs/anc_session_*.log`
- 腳本結束：另存 `anc_guided_export_*.log`，並自動分享 **Log 檔 + cabin_*.wav**（不是只丟文字）
- 開啟 UIFileSharingEnabled（檔案 App 可見）
- 狀態頁 **`v1.2.15 (26)`**

---

## [1.2.14] — 2026-08-19 · Android code 16 · open boom + 艙錄極性 −1

### 問題（1.2.13 公平艙錄）

1. **forced 時 boomPressureOut≈0**：y=−delayed·gain，delayed≈0 時能量地板只抬增益不抬訊號
2. **plant residual winner 選 +1**，但艙錄金標準 **−1 更好**（40–80 −1.1 vs −0.04）
3. corr≈0 時需要可控 open boom（40–80）+ 短延遲 probe 極性
4. 停速 / 不等長場仍被誤採信

### 修復

| 項 | 內容 |
|----|------|
| **openBoom** | forced 或 corr<0.05 且 ≥35 km/h：mic 過小時用 ±energyFloor 驅動；pressMix↑；KPI 應 ≫0 |
| **預設極性 −1** | processor / PlantPathStore 預設 −1；無 store 亦套 −1 |
| **cabin winner** | BoomPolarityAbTracker：mic low-band dB 優先（更安靜勝）；丟棄 <45 km/h；否則 plant；再否則 −1 |
| **short-lag probe** | ~35 ms corr 持續負 → auto-flip（非 forced） |
| **路測規則** | 只收等長 ~20s 三件套；停速場 log discardedLowSpeed |

### 路測必收

- 等長 off/ppos/pneg ~20s，≥45 km/h
- on：oomPressureOut med ≫ 0.01、openBoom=true
- 40–80 on < off（≤ −1.5 dB）；180–350 <+1.5 dB
- oom_polarity_winner 以 cabin 為準

腳本：`三目標·1.2.14 openBoom+艙錄極性`

### iOS（build 24 · 對齊 1.2.14）

- 重編 `CarANCShared`（openBoom、預設極性 −1、cabin winner、短延遲 probe flip）
- 腳本／log：`openBoom`、cabin A/B sample（≥45 km/h）、`boom_polarity_winner` 預設 −1
- 無 PlantPathStore 時啟動套用 default −1
- 保留 Embed framework（防開機閃退）
- 狀態頁 **`v1.2.14 (24)`** · `dist/CarANC-ios-kmp-debug.ipa`

---

## [1.2.13] — 2026-08-19 · Android code 15 · 真 LF 終端濾波 + 停中頻加噪

### 問題（1.2.12 公平艙錄）

Mute／極性 A/B／probe 皆正確，但艙錄仍：

1. **40–80 Hz +0.83 dB**（ppos 較佳仍變吵）
2. **180–350 Hz +6 dB** — 主因：`roadLowPassCutoffHz` 巡航 **320–400 Hz**，終端「boom LPF」根本沒擋中頻
3. FDAF / fixedBank / engineFf 在 HIGH_LAT ROAD 仍混入
4. boom corrGate=0 時 **forced 極性不影響送出**；notch soft-gate 地板 0.35 盲播

### 修復

| 項 | 內容 |
|----|------|
| **85 Hz 終端 LPF** | boom/high-lat ROAD 用三重 1-pole ~85 Hz，**忽略** speed 320 Hz cutoff |
| **清支路** | HIGH_LAT ROAD：FDAF=0、fixedBank=0、engineFf=0 |
| **forced boom** | `forceBoomPolarity` / winner 時 pressMix floor 0.55（corr≈0 也能推） |
| **notch** | high-lat gate 地板 0→權重；notchMix 1.45→0.7 |
| **boom delay** | `max(plantElectrical, 0.85×measured)` |

### 路測必收

- 等長 off/ppos/pneg ~20s
- **40–80 on < off（≤ −1.5 dB）**
- **180–350 增幅 < +1.5 dB**
- mute≈−200；forced 時 boomPressureOut 可 >0

腳本：`三目標·1.2.13真LF濾波+極性A/B`

### iOS（build 22 · 對齊 1.2.13）

- 重編 `CarANCShared`（85Hz 終端 LPF、HIGH_LAT 清 FDAF/bank/engine、forced boom floor、notch 收緊）
- 腳本文案／驗收對齊 Android 1.2.13（步驟同 1.2.12：mute + ppos/pneg）
- 保留 build 21 通話結束車機音量修復
- 狀態頁 **`v1.2.13 (22)`** · `dist/CarANC-ios-kmp-debug.ipa`

### iOS（build 23 · 修開機閃退）

- **根因**：Xcode 未 Embed `CarANCShared.framework`；IPA 用 `zip` 覆蓋時留下舊 framework → dyld 找不到／符號不符 → 一開就閃退
- **修復**：補上 Embed Frameworks（Copy Files → Frameworks）；IPA 改為刪檔重建；確認 bundle 內 framework 與 App 同編
- 狀態頁 **`v1.2.13 (23)`**

---

## [1.2.12] — 2026-08-18 · Android code 14 · 真 mute KPI + 極性 A/B + boom corr 鎖

### 問題（1.2.11 公平艙錄）

1. **muteAnti=true 但 log ntiNoiseDb≈−8**：AA 寫入已歸零，但 KPI 量的是 **mute 前** processed → A/B 日誌不可信
2. **plantResidualReductionDb 在 road ON 大幅為負**：錯相疊加；負 oomPlantCorr 仍用 **0.28×** boom mix → 艙內更響
3. **oomPressureOut 在 road_off 仍高**：mute 步仍算 boom；ON 時 corr 未鎖卻推幅
4. **不等長艙錄對**（25s vs 112s）不可當 KPI

### 修復

| 項 | 內容 |
|----|------|
| **mute KPI** | updateVisualization 改用 **post-mute write**；mute 時 skip probe；processor setAntiOutputMuted 歸零 boom/notch/bank 輸出與 KPI |
| **極性 A/B** | 腳本 	arget_road_ppos(+1) / 	arget_road_pneg(−1) 各 20s 艙錄；oom_polarity_winner → PlantPathStore.boomPolarity |
| **auto-flip** | 負 corr 更快翻轉；腳本 force 時關閉 auto |
| **corrGate** | corr<0.02 → **mix=0**（不再 0.28 盲推） |
| **A/B 規則** | 只採信等長對（≈20±3s）；時長差>30% 作廢 |

### 路測必收

- off：muteAnti=true、ntiNoiseDb≤−90（≈−200）、oomPressureOut=0
- ppos/pneg：各 ~20s 艙錄；看 oom_polarity_winner
- 等長 off vs 較佳極性：40–80 Hz on < off（目標 ≤−1.5 dB）
- **不要採信** 不等長對

腳本：三目標·1.2.12 mute真靜音+極性A/B

### iOS（build 20 · 對齊 1.2.12）

- 重編 `CarANCShared`（mute / polarity / corrGate / HIGH_LAT 120）
- **muteAnti**：喇叭硬歸零 + `setAntiOutputMuted`；KPI 用 **post-mute** antiDb
- **極性 A/B**：`target_road_ppos` / `target_road_pneg`；`BoomPolarityAbTracker` → `PlantPathStore`（UserDefaults）
- **艙錄**：達有效車速後從 ANC mic tap 寫 `cabin_*.wav`（與 Android m4a 同用途）
- 腳本／log：`muteAnti`、`forceBoomPolarity`、`boomPolarity`、`boom_polarity_winner`
- 狀態頁 **`v1.2.12 (20)`** · `dist/CarANC-ios-kmp-debug.ipa`

### iOS（build 21 · 通話結束車機音量暴衝）

- CarPlay session **`.voiceChat` → `.default`**（避免掛斷後卡電話聲道／音樂暴衝）
- 正確處理 `AVAudioSession` interruption：began 暫停引擎＋anti=0；ended 重套 session＋**1.6s 增益漸升**
- Log：`audio_interruption` / `audio_interruption_resumed`
- 狀態頁 **`v1.2.12 (21)`**

---

## [1.2.11] — 2026-08-18 · Android code 13 · 相位對齊：真正壓得下 40–80 Hz

### 問題（1.2.10 公平艙錄 A/B）

Mute／等長艙錄已正確，但 **on 比 off 更吵**（cabin FFT 40–80 Hz **+0.97 dB**）：

1. `HIGH_LATENCY_MS=180` → 真實 AA ~137 ms 仍走 NORMAL → **Wiener 開環 FF** 混入未鎖相位能量  
2. Boom 延遲只用 track+framework（缺總 RTT）；soft-boost **捏造 LF**  
3. Low FxLMS ref 混 **45% IMU 波形**（與艙壓不同相）  
4. Probe 假峰 **~12 ms** 仍寫入 plant D；breakdown 覆寫 refined D  
5. High-lat `plantAlignedReference(D)` 當 LMS x → 潛在 **雙倍 plant delay**  
6. `boomPriority` 含 TIRE → tire/wind notch KPI=0

### 修復

| 項 | 內容 |
|----|------|
| **HIGH_LAT** | 門檻 **180→120**；`LatencyAwareBandLimiter` mid 在 ≥120 ms 關閉 |
| **Wiener** | AA 上路自動 gated（`!highLat`） |
| **LMS x** | 禁止 full-D delayed ref；僅 mild predict ≤12% |
| **IMU** | 只做 mu/coherence，**不再混進 audio ref** |
| **Boom** | 延遲=總 `measuredLatencyMs`；刪 soft-boost；corr 閘 mix；極性 auto-flip |
| **Plant D** | breakdown 不縮小 refined；probe 拒假峰 → log `probe_rejected` |
| **Notch** | `boomPriority` **僅 ROAD**；TIRE/WIND 可跑 notch |
| **KPI** | `boomPlantCorr` 改 **plant-lagged**；spectrum 記 `boomPolarity` / `latencyStrategy` |

### 路測必收

- 艙錄 off/on ~20s；**40–80 Hz on < off（目標 ≤ −1.5 dB）**
- `latencyStrategy` 含 `HIGH_LAT`；假峰有 `probe_rejected`
- on：`antiE40_80` 有能量；plant-lagged `boomPlantCorr` 中位 >0.15
- tire 步：`tireNotchEnergy` 可 >0

腳本：`三目標·1.2.11相位對齊降噪`

---

## [1.2.10] — 2026-08-17 · Android code 12 · 真 mute baseline + 可感 boom + 公平艙錄 A/B

### 問題（1.2.9 路測 log）

1. **`target_road_off` 沒關 anti**：只 `requiresAncRunning=false`，喇叭仍播 anti（antiNoiseDb≈−30）→ 艙錄 baseline 髒
2. **`boomPressureOut≈0`**：push 用 road/IMU blend 非 mic low；gain 太小；log 只記單 sample
3. **艙錄時長不對等**：off 含等速 129s vs on 40s

### 修復

| 項 | 內容 |
|----|------|
| **muteAnti** | 腳本 off 步 `userAncGain=0` + `muteAnti=true`；AudioEngine **硬歸零** 寫入 AA |
| **CabinBoomPressure** | push **mic low**；能量 floor + 更高 gain；mix 1.35–1.55；KPI=RMS |
| **艙錄** | `cabinRecordOnValidSpeed`：達有效車速才開始 m4a（約 20s） |
| **A/B 時長** | off/on 皆 **20s** 有效秒 |
| 腳本 | 顯示名 `三目標·1.2.10 mute+boom+艙錄A/B` |

### 路測必收

- off：`muteAnti=true`、`userAncGain=0`、antiNoiseDb 極低
- on：`boomPressureOut` med ≫ 0.01
- cabin m4a 各 ~20s；40–80 Hz on < off
- 主觀悶 0–10

---
## [1.2.9] — 2026-08-14 · Android code 11 · P2 plant ŝ delay 寫入 + boomPlantCorr

| 項 | 內容 |
|----|------|
| **PlantPathStore** | 依 profileId+route 持久化 electricalDelaySamples / probeCorrMs |
| **probe 更新** | runtime 相關延遲 → `refinePlantDelayFromProbe` + `plant_path_saved` |
| **啟動載入** | `plant_path_loaded` 恢復上次 plant D |
| **boomPlantCorr** | mic low × anti 對立相關 EMA（log） |
| **腳本** | 1.2.9 文案；finish 收 plantDelay / boomPlantCorr |

含 1.2.8：antiE*、50Hz tone、艙錄、IMU 軸、music gate。

### iOS（build 19 · 對齊 1.2.9）

- 重編 `CarANCShared.framework`（`getBoomPressureOut` / `getBoomPlantCorr` / `setImuAxes` / plant delay）
- 腳本：`prep → diag_tone_50 → road_off → road_on → tire → wind → finish`（`三目標·1.2.9 P2 plantD+診斷+antiE`）
- 50Hz 診斷 tone 混音；`spectrum_kpi` 含 **antiE\***；IMU 三軸 → KMP
- `road_off` 自動停 ANC、`road_on` 自動開；結束自動分享 Log
- 狀態頁 **`v1.2.9 (19)`** · `dist/CarANC-ios-kmp-debug.ipa`
- Android 專用：艙錄 m4a、PlantPathStore 檔案持久化（iOS 以 log 欄位為主）

---
## [1.2.8] — 2026-08-14 · Android code 10 · 路徑診斷 + antiE 分帶 + 艙錄 + IMU 軸

**修改紀錄確認**：此前 **未** 做 antiE* / 50Hz tone / 腳本自動艙錄 / ImuAxis ref / entertainment 分級。

| 項 | 內容 |
|----|------|
| **antiE*** | spectrum_kpi：`antiE40_80` `antiE80_120` `antiE200_500` `antiE500_2k` `antiLfDominatesHf`（**送出前** PCM） |
| **diag_tone_50** | 腳本 30s 播 **50Hz** 純音經 AA；`diag_tone_active` log |
| **cabinRecord** | 腳本 `target_road_off` / `target_road` 自動 `cabin_*.m4a` |
| **P0 IMU** | LINEAR_ACCEL 三軸 FASTEST → low path 混合 ref |
| **P4-lite** | 系統 music 音量高 → 衰減/近 mute anti |

腳本名：`三目標·1.2.8診斷tone+antiE+艙錄+IMU`

---
## [1.2.7] — 2026-08-14 · Android code 9 · 悶感音壓：plant-delay 低頻壓力 + 強 boom

**方向修正**：不再「少播 anti」；要 **喇叭輸出可感的低頻音壓** 打 悶，並剝掉中高頻電子噪。

### 核心

| 項 | 內容 |
|----|------|
| **CabinBoomPressure** | 低頻 mic 經 plant 延遲後 **反相** 播出（~70Hz LPF）— 與艙內低頻相關的真實 LF 壓力 |
| **Boom notch** | 頻率對齊錄音峰 ~39/49/59/74；軟 gate + 更高 mix；boom 時關 tire/wind notch |
| **終端 LPF** | boom 模式雙重 road LPF → 只留悶帶 |
| **low mu** | boom 時 full / 略抬學習率 |
| **導測 PRO** | 腳本開始強制 PRO（修 FREE clamp→LIGHT） |

### 路測

- log：`boomPressureOut`、`roadBoomWeightEnergy`、`notchMixAnti`、`tier=PRO`
- 主觀：開 ANC 應感到 **低頻在動／悶在變**（不是電子沙）


### iOS（1.2.7 起腳本／log 對齊；1.2.9 build 19 已重編 framework）

- GuidedTestScript 文案／KPI 對齊 Android（boomPressureOut、PRO）
- SessionLogger / bridge 含 boomPressureOut（真值需重編 KMP；**build 19 已含**）
### 測試腳本對齊（car_road_tuning_v1 · 內容 1.2.7）

- 顯示名：`三目標·1.2.7悶音壓+PRO強制`
- `target_road` 必收 **`boomPressureOut`** + roadBoom* + spectrum_kpi deltaBoomDb
- prep/各步 checklist 版號 **v1.2.7**、tier=PRO（腳本開始強制）
- finish PASS：boomPressureOut≠0 且主觀悶有動；FAIL：全程0 或 LIGHT 或純電子噪

---
## [1.2.6] — 2026-08-14 · Android code 8 · 內建頻譜 KPI + 腳本強制三目標 + notch 觸發修復

### 為什麼上次 log「沒錄音／notch=0」

1. **腳本寫的「錄音」= 外部手機 m4a（可選）**，App **從未**內建錄艙音檔。  
2. **分析其實可用 log 內頻譜**：`bandE60–120`、plant residual；但欄位太粗、使用者不知。  
3. **tire/wind notch=0**：高延遲時 wind 被關掉；腳本步未 force focus → 常判 ROAD；輪 notch 只在 TIRE。  
4. **延遲 247ms**：AA track buffer 仍過大。

### 1.2.6 修復

| 項 | 內容 |
|----|------|
| **spectrum_kpi** | 每 2s 寫入 mic/plant 分帶 dB（40–120 悶、180–350 輪、500–2000 風）+ delta*Db |
| **forceNvhFocus** | 腳本步驟強制 ROAD/TIRE/WIND（`GuidedNvhOverride`） |
| **notch 觸發** | 輪：ROAD/TIRE 都跑；風：高延遲仍跑 3 線 notch（權重 gate） |
| **AA buffer** | track 強制 **8192**（降 HIGH 機率） |
| 腳本文案 | 主分析= log `spectrum_kpi`；外部 m4a 標可選 |

### 路測必收

- `spectrum_kpi`：`deltaBoomDb` / `deltaTireDb` / `deltaWindDb`
- `forcedNvhFocus`、`tireNotch*`、`windNotch*`、`roadBoomWeightEnergy`
- 主觀 0–10

---

### iOS（build 18 · 全自動腳本 UI）

- 開始腳本：**自動開降噪**
- 結束：**自動彈出分享 Log**
- 檢查清單僅提示；略過此步為緊急用
- 狀態頁 **`v1.2.6 (18)`** · `dist/CarANC-ios-kmp-debug.ipa`

### iOS（build 17 · 對齊 1.2.6）

- 重編 `CarANCShared.framework`（forceNvhFocus + notch 觸發修復；`GuidedNvhOverride` 原生編譯修正）
- 狀態頁 **`v1.2.6 (17)`**
- 每 2s `phase=spectrum_kpi`（mic/plant 分帶 + deltaBoom/Tire/Wind；iOS plant 為 input+anti 代理）
- 導引腳本：`forceNvhFocus=ROAD/TIRE/WIND` 寫入 KMP；步驟文案對齊 Android 1.2.6

---

## [1.2.5] — 2026-08-13 · Android code 7 · 悶感主力：解鎖 low + 鎖相 boom notch

**為什麼一直原地踏步**：高延遲 rumble 時 `highLatAdaptiveDamp(low)=0.08` × `lowMu×0.28` ≈ **只剩 2% 學習率**，真消悶路徑幾乎關掉；同時假 open-loop 又造成沙沙。關掉假 anti 後悶感仍無進步 = 低頻 adaptive 被自己掐死。

### 1.2.5 改什麼（針對 悶，不是再加噪）

| 項 | 內容 |
|----|------|
| low 解鎖 | high-lat low damp **0.08→0.90**；rumble lowMu **0.28→0.78** |
| low 中心 | 190Hz → **85Hz**（對齊錄音 ~65Hz 悶） |
| Boom notch | 3 線 complex LMS（~45–115Hz）；**權重 gate**（未鎖相不播） |
| Error | notch 用 **low-band residual** 學相位 |
| 混音 | boom 高延遲 mix **1.2×**；禁止 open-loop sin |
| 仍禁 | Wiener 自由合成、空 bank prior、HF wind under AA |

### 路測 KPI

- `effectiveLowMu` 應明顯高於 1.2.4 高延遲段
- `notchMixAnti` / road weight 隨行駛上升（鎖相後）
- 主觀：同路段開/關 ANC，**低頻悶**應可辨（非沙沙）

### 測試腳本對齊（car_road_tuning_v1 · 內容 1.2.5）

- `target_road`：effectiveLowMu + roadBoomWeightEnergy + roadNotchEnergy；外部錄音 關20s/開40s
- `target_wind`：高延遲 wind notch=0 可接受
- log 新增：`roadNotchEnergy`、`roadBoomWeightEnergy`
- `bandE60`–`120` = 粗 KPI；艙內 m4a 為頻譜金標
- **腳本 ID 未改號**（仍 `car_road_tuning_v1`，方便舊 log 比對）；顯示名稱含「1.2.5悶鎖相」

---
### iOS（build 16 · 對齊本版）

- 重編 `CarANCShared.framework`（1.2.5 low 解鎖 + 鎖相 boom notch）
- 狀態頁 **`v1.2.5 (16)`**；log：`roadNotchEnergy`、`roadBoomWeightEnergy`、`effectiveLowMu`
- 導引腳本名稱／步驟文案對齊 Android 1.2.5（`target_road` 60s + boom KPI）

## [1.2.4] — 2026-08-13 · Android code 6 · 路低悶 + 核心禁假 anti 雜訊

**核心審計（用戶聽感：無反向消悶、只有沙沙雜訊）**

| 結論 | 說明 |
|------|------|
| AA 會播 anti | 不「認／不認」ANC；PCM 原樣出喇叭 |
| App 有反相 | BandFxLms → speaker = −y；單測可消純音 |
| **真問題** | 多條 **自由振盪／空 prior** 路徑結構上是 **加噪**，不是反向消悶 |

已閘死（高延遲 AA）：
- **open-loop notch sin floor**（相位未鎖 = 注入沙沙）
- **RoadNoiseWiener** 自由多音合成
- **PreLearned 空 bin default prior** FIR 濾 mic
- **FDAF** 高延遲混音壓到極輕；風 HF notch 高延遲關閉
- 僅保留 **低頻 adaptive LMS −y** + 有 learned 的 bank

### 原 1.2.4 路／輪／風表（仍保留分類與 speed 表）


**現場依據**：車艙錄音 peak ~65Hz、能量 ~95% 在 <150Hz；log 1.2.3 `notchMixAnti` 近 0（LMS 在 AA 延遲下權重長不起來）。

### 算法下一刀

| 目標 | 改動 |
|------|------|
| **路噪** | 鎖 low：ROAD 表抬 low/total；mid 再壓；`ROAD_RUMBLE` total×1.15；**2 路 boom notch**（~48–110Hz）+ **open-loop floor** |
| **輪噪** | 分類更易進 TIRE（mid≥0.018 或高速）；TIRE 表 mid 抬；notch 混音加權×2.4；open-loop 在 TIRE 更強 |
| **風切** | 有 notch 且 **能量／混音夠**：≥70km/h 即開 6 notch；open-loop + windGain 抬；WIND total/high 表抬 |

### 關鍵模組

- `CabinNvhFocus`：tire before road；pureWind 更嚴；預設 road-low prior
- `AdaptiveNarrowbandBank`：road/tire/wind open-loop + adaptive；mixScale 1.9–2.55
- `MultiBandANCProcessor`：notch 混入 ×1.25、clip ±1.40、error 自減 0.12
- `SpeedScheduledNvhGains`：ROAD_LOW/TOTAL、TIRE_MID、WIND_HIGH/TOTAL 1.2.4 表

### 路測對照（log）

- 路：`nvhFocus=ROAD_RUMBLE`、`speedNvhLowGain` 高、`notchMixAnti` **明顯 >0**（不應再 ~0.002）
- 輪：`target_tire` 時 `nvhFocus=TIRE_NOISE` 比例↑、`tireNotchEnergy`/`tireNotchF0Hz` 有值
- 風：`windNotchEnergy>0`、`windNotchActiveCount>0`、`notchMixAnti` 非零

### 版本

- Android：`1.2.4` code **6**

---

## [1.2.3] — 2026-08-13 · Android code 5 · 輪噪 notch + 風切多 notch

**下一刀重點**：不靠 mid/high 增益 alone，而是 **窄帶／多 notch 注入 anti**。

### 算法

| 模組 | 內容 |
|------|------|
| `AdaptiveNarrowbandBank` | 新建：sin/cos 參考 + leaky LMS |
| **輪噪** | 3 條 notch，中心頻率隨車速（~160–400 Hz） |
| **風切** | 6 條固定 HF notch（550/750/1000/1400/1800/2400 Hz） |
| 混音 | 加到 broadband 輸出之後（`notchMixAnti`）、soft-clip |

### Log（必收）

- `tireNotchEnergy`、`tireNotchF0Hz`
- `windNotchEnergy`、`windNotchActiveCount`
- `notchMixAnti`
- 仍收：`nvhFocus`、`speedNvh*`、`lowBandRumbleReduction`、主觀 0–10

### 腳本

- `target_tire`：看 `tireNotchEnergy`、主觀嗡感
- `target_wind`：看 `windNotchEnergy`、`windNotchActiveCount`、主觀風切

### iOS（build 15 · 對齊算法）

- 重編 `CarANCShared.framework`（含 AdaptiveNarrowbandBank）
- 顯示版本 **`v1.2.3 (15)`**；log 含 tire/wind notch 欄位
- 導測腳本步驟 ID／寫入欄位與 Android 對齊 `GuidedTestController` + `CarRoadTuningScript`
- 說明：iOS 仍為 Swift 導測腳本（未直接綁 KMP Controller），autoAdvance 行為已對齊

---
## [1.2.2] — 2026-08-13 · Android code 4 · 三目標主動壓制 + 腳本重寫

**硬需求**：路噪 / 輪噪 / 風切 **都要壓**（各用 low / mid / high 武器），不是風切「只防護」。

### 算法

| 目標 | 武器 | 改動 |
|------|------|------|
| **路噪** | low (+mid 輔助) | 高延遲仍保證 LowGain/TotalAnti 下限；標籤 active low |
| **輪噪** | mid | 高延遲 mid 上限 0.78、下限抬升；標籤 active mid |
| **風切** | mid+**high** | WIND 表抬 high/total；suppressHigh=false；processor 不 duck、強制 high LMS |

### 測試腳本 `car_road_tuning_v1`

重寫為 5 步：**prep → target_road → target_tire → target_wind → finish**  
每步寫明 **要收集的 log 欄位 + 主觀 0–10 + PASS/FAIL 線索**（見 `AncTestScript.kt` 註解）。  
iOS `GuidedTestScript.swift` 步驟 ID 對齊。

### 路測怎麼對版

1. install **v1.2.2**；跑新腳本三段  
2. 分析按 `guidedTestStepId` 切：`target_road` / `target_tire` / `target_wind`  
3. 風：`TotalAnti≥0.85`；路：`lowBand`+悶感；輪：`MidGain`+嗡感  

---

## [1.2.1] — 2026-08-12 · Android code 3 · iOS build 14

**路測主題**：新版依賴車速 → **無 GPS 時 GPS 掉線保持 + IMU 代車速**，避免 SpeedScheduled 整段 idle。

### 功能

- 共用 `VehicleSpeedFusion`（commonMain）
  1. **gps** — 有效 GPS  
  2. **gps_hold** — 掉線後 ≤25s 保持上一檔（緩慢衰減）  
  3. **imu_proxy** — 線加速度映射粗速（上限 ~75 km/h）  
  4. **none**
- Android `VehicleSpeedProvider` / iOS `SpeedProvider` 接入 fusion  
- 無定位權限仍啟 IMU → 可行駛時 `imu_proxy` 餵 DSP  
- Log：`speedSource`、`speedHoldAgeSec`、`imuProxyKmh`、`speedValidForRoadTest`  
- UI：狀態列顯示「GPS / 保持 / IMU 估計」

### 對版

- Android 主畫面 `v1.2.1`（code 3）  
- iOS 狀態頁 **`v1.2.1 (14)`**  
- 嚴格路測 KPI 仍以 `speedSource=gps`（或短 hold）為準；`imu_proxy` 可累導引 valid、可跑速域表，但勿當精密 GPS

---

## [1.2.0] — 2026-08-12 · **iOS only** · build 13

**路測主題**：對齊 Android Auto → **CarPlay 車機路徑**（路由 + 模板 UI + 斷線策略）。

### 功能（iOS）

- `CarAudioRouteMonitor`：`aaLinkType` = `local` / `carplay_wired` / `carplay_wireless` / `carplay_unknown`
- `AppController`：手機與 CarPlay 共用 model / engine
- CarPlay 模板：啟停降噪、輕／中／高（對齊 `CarAncAutoScreen`）
- 斷線：非導引腳本中自動 stop（對齊 AA）
- Log：`aaLinkType`、`wirelessAaSuspected`、`carplay_connected` / `carplay_disconnected`
- 狀態頁／方案／測試平台／CarPlay 列表 **顯示 `v1.2.0 (13)`**

### 限制

- 車機主機 **圖示／模板** 需 Apple **CarPlay Driving Task** entitlement 核准後才能出現（見 `iosApp/CARPLAY.md`）
- 未核准前：路由層 + 手機 UI 可用；車機無圖示

### Android

- 仍為 **1.1.0 / code 2**（本版無 Android 程式變更）

### 文件

- `iosApp/CARPLAY.md`、`iosApp/README.md`、`ANDROID_REUSE.md`
- 根 `README.md`、`GROK_RESUME_CONTEXT.md`、`dist/WINDOWS_INSTALL_SIDELOADLY.md`

### 路測怎麼對版

1. 狀態頁看 **`v1.2.0 (13)`**
2. 連 CarPlay 時看 `aaLinkType=carplay_*`、log `audioBackend=…carplay…`
3. 斷線應停 ANC（非導引中）

---

## [1.1.0] — 2026-08-12 · code 2 · iOS build 11–12

**路測主題**：mic 高延遲對不齊 → **路噪／輪噪／風切 × 車速每 5 km/h 增益表** 當主增益路徑。

### 功能

- 新增 `SpeedScheduledNvhGains`：road / tire / wind / mixed / idle 表，**每 5 km/h** 插值
  - `lowGain` / `midGain` / `highGain=0` / **`totalAntiScale`**
  - 風切：**壓 totalAnti**，不開 high
- `CabinNvhFocus.classify` 掛上速度表；`bandGains` + 輸出 anti 乘 `totalAntiScale`
- Log 新欄位：
  - `speedNvhBinKmh`、`speedNvhLowGain`、`speedNvhMidGain`
  - `speedNvhTotalAnti`、`speedNvhTableId`
  - （既有）`nvhFocus`、`nvhTargetHz`

### 測試腳本

- `car_road_tuning_v1` 改名意圖：**路噪/輪噪/風切·5km/h增益驗證**
- 步驟對齊速度段：#4 40–50、#4b A/B、#6 50–60、#7 55–70、**#8 風切防護**
- finish：PASS/FAIL 對照 `speedNvh*` 與 #4b vs #7 KPI

### 文件

- `README.md`：產品段補車速增益表說明
- 本檔 `CHANGELOG.md` 建立

### 路測怎麼對版

1. 主畫面看 **`v1.1.0`**（或 `v1.1.0-internal`）
2. 跑新腳本 → pull log
3. 查 `speedNvhBinKmh` 是否隨速每 5 跳；#7 TotalAnti / lowBand KPI 是否優於 #4b

---

## [1.0.0] — 2026-08（及更早）· code 1

基線（pull 時已在 main，摘要）：

- Play：`internal` / `store` flavor、`StoreReleasePolicy`、targetSdk 35
- 產品：`CabinNvhFocus` 路噪／輪噪／風切分類
- 07-22 A/B/C：projection_submix、bank 輸出、誠實 reduction、主 KPI=`lowBandRumbleReduction`
- iOS App + KMP DSP + Sideloadly IPA（見 `iosApp/`、`dist/`）
- P0–#11 高延遲 FF / FDAF / bank / 有線 AA 偏好等

詳細歷史仍見 `README.md` / `GROK_RESUME_CONTEXT.md` 長文段落。
