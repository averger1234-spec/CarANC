# CarANC

**手機即裝即用車內主動降噪（Active Noise Cancellation）**

CarANC 是一款 Android 應用，透過手機麥克風收音、即時 DSP 演算法產生反相聲波，並經喇叭（含 Android Auto 車機）播放，為沒有原廠 ANC 的車主提供軟體降噪方案。目標使用者為 Android Auto 通勤族、二手車或無內建主動降噪的車主。

---

## 功能概覽

- **多頻帶主動降噪**：低／中／高頻 FxLMS，主攻 80–300 Hz 引擎與底噪
- **延遲感知頻帶控制**：依估計延遲自動調整可抵消頻率與頻帶增益
- **車廂聲學校正**：對數掃頻（log chirp）估計 secondary path，支援 profile 儲存與老化重校
- **情境模式**：怠速、GPS 路噪、音樂播放 bypass、通話語音保護
- **安全機制**：警笛偵測自動降增益、啟動前安全聲明
- **Android Auto 整合**：AA 連線偵測、斷線自動停止、車載簡易介面
- **實車引導測試**：
  - `car_field_v3`：一般實車驗證（延遲、MIMO、怠速、市區、高速、音樂 bypass 等）
  - `car_road_tuning_v1`：**第一次實車 LMS 調校推薦**（5 組 muMult / freeze / latencyOverride 對照，專為 40-70 km/h 粗糙路設計）
- **訂閱分級**（開發中）：免費／標準／專業方案與功能閘道

---

## 架構

```
┌─────────────────────────────────────────────────────────┐
│  app 模組                                                │
│  MainActivity (Compose UI) · CommercialPanel              │
│  GuidedTestPanel · ANCAppService (Android Auto)          │
└──────────────────────────┬──────────────────────────────┘
                           │
┌──────────────────────────▼──────────────────────────────┐
│  shared 模組 (Kotlin Multiplatform · androidTarget)      │
│  ANCService · AudioRouteManager · AncStateManager        │
│  MultiBandANCProcessor · TierManager · EntitlementManager│
└──────────────────────────┬──────────────────────────────┘
                           │
         ┌─────────────────┼─────────────────┐
         ▼                 ▼                 ▼
   AudioRecord        AudioTrack      GPS / 手動 RPM / 媒體參考
   (麥克風收音)        (反噪音輸出)     (VehicleSpeedProvider 等)
```

### 音訊處理流程

1. `ANCService` 以前景服務啟動，初始化 `AudioRecord` / `AudioTrack`
2. `AudioRouteManager` 解析輸入／輸出設備（含 Android Auto 路由）
3. 載入或執行車廂聲學校正（`CabinTransferModel`）
4. `MultiBandANCProcessor` 進行多頻帶降噪（含引擎諧波、路噪 Wiener、虛擬感測等）
5. 即時頻譜與 dB 透過 `AncStateManager`（StateFlow）回傳 UI

### 主要演算法模組

| 模組 | 說明 |
|------|------|
| `MultiBandANCProcessor` | 多頻帶 FxLMS 核心處理器 |
| `LatencyAwareBandLimiter` | 依延遲限制中／高頻抵消 |
| `EngineCombCanceller` | 引擎諧波前饋消除（需手動 RPM，OBD 已移除） |
| `RoadNoiseWienerBank` | GPS 路噪 Wiener 濾波 |
| `VirtualSensingModel` | 虛擬感測 |
| `FdafLowBandProcessor` / `MultirateLowBandFxLms` | 低頻高效處理 |
| `CabinMimoProfile` | 多區域 MIMO（AMBEEO-lite 試驗） |
| `SirenDetector` | 警笛偵測與增益縮放 |
| `ReferenceSignalPipeline` | 媒體參考扣除與 AEC |

---

## 專案結構

```
CarANC/
├── app/                    # Android 應用（Compose UI、Android Auto 入口）
│   └── src/main/java/com/example/caranc/
├── shared/                 # 共用核心（KMP commonMain + androidMain）
│   └── src/
│       ├── commonMain/     # 演算法、狀態、商業邏輯、測試腳本
│       └── androidMain/    # ANCService、音訊路由、GPS、手動 RPM、Logger
├── log/                    # Session log 輸出目錄（執行時產生）
├── gradle/
├── build.gradle.kts
├── settings.gradle.kts
└── README.md
```

---

## 環境需求

| 項目 | 版本 |
|------|------|
| Android Studio | Ladybug 或更新版本（建議） |
| JDK | 17 |
| minSdk | 26 |
| targetSdk / compileSdk | 34 |
| Kotlin | 2.2.10 |
| AGP | 9.2.1 |

實車測試建議：

- 支援 **Android Auto** 的車機（USB 或無線）
- 手機放置於中控台附近（利於麥克風收音）
- （已移除）OBD 藍牙；僅支援 TestLogPanel 手動輸入 RPM 用於引擎諧波測試

---

## 建置方式

### Android Studio

1. 以 Android Studio 開啟本專案根目錄
2. 等待 Gradle Sync 完成
3. 連接實機（需麥克風與定位權限，模擬器無法完整測試 ANC）
4. 選擇 `app` → Run

### 命令列

```bash
# Windows
gradlew.bat assembleDebug

# macOS / Linux
./gradlew assembleDebug
```

Debug APK 輸出路徑：

```
app/build/outputs/apk/debug/app-debug.apk
```

Release 建置：

```bash
gradlew.bat assembleRelease
```

---

## 權限說明

| 權限 | 用途 |
|------|------|
| `RECORD_AUDIO` | 麥克風收音，進行主動降噪 |
| `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` | GPS 車速，切換路噪模式 |
| `FOREGROUND_SERVICE` | 背景持續降噪 |
| `FOREGROUND_SERVICE_MICROPHONE` | 前景服務使用麥克風（Android 14+） |
| `FOREGROUND_SERVICE_LOCATION` | 前景服務使用定位（Android 14+） |
| `POST_NOTIFICATIONS` | 顯示降噪狀態通知（Android 13+） |
| `BLUETOOTH` / `BLUETOOTH_CONNECT` | （已移除）僅 legacy 相容，實際已無 OBD 功能 |

首次啟動降噪時，App 會依序請求通知、麥克風與定位權限。使用前須接受安全聲明。

---

## 使用方式

1. 開啟 CarANC，選擇降噪等級（輕度／中度／重度）
2. 閱讀並接受安全聲明
3. 點擊 **開始降噪**，等待車廂聲學校正完成
4. 主畫面可即時查看：
   - 原始噪音 dB、處理後 dB、降噪效果
   - GPS 車速、主導頻帶、估計延遲與可抵消頻率
   - 即時頻譜圖
5. 點擊 **停止** 或通知欄「停止」結束服務

### 降噪等級

| 等級 | 說明 | 訂閱限制 |
|------|------|----------|
| 輕度（LIGHT） | 較保守，適合初次體驗 | 免費版可用 |
| 中度（STANDARD） | 日常通勤 | 需標準版以上 |
| 重度（PRO） | 完整演算法與較長濾波器 | 需專業版 |

### 運作模式

- **一般降噪**：怠速或靜態環境
- **路噪降噪**：GPS 偵測車速 > 門檻時自動切換
- **音樂模式**：播放媒體時預設保護中高頻（可透過 TestLogPanel 開啟「音樂模式仍抗低頻路噪」，讓低頻持續進行 ANC）
- **通話模式**：保護語音頻帶，同時維持低頻降噪

### 進階調校面板（TestLogPanel）

位於主畫面「實車測試 Log」區塊，提供以下實驗性控制（適合調校者）：

- 強制正常模式（forceNormalMode）：繞過 AA 音樂偵測
- 音樂模式仍抗低頻路噪（musicLowAncEnabled）
- ANC 獨立強度（userAncGain）：0~1，獨立於車機音量
- **LMS 調校實驗**（新）：
  - LMS 學習率倍率（muMult）
  - 凍結門檻 / 凍結連續次數
  - 延遲覆蓋測試（用於模擬不同延遲對 bandMuScale 的影響）

這些參數會即時寫入 log（`debugLmsMuMultiplier`、`debugFreezeThreshold` 等），方便搭配引導測試進行對照實驗。

---

## 實車測試流程

App 內建兩套引導測試腳本：

- `car_field_v3`：一般實車驗證流程
- **`car_road_tuning_v1`**：**第一次實車 LMS 調校推薦流程**（專為 phone + USB AA + 粗糙路設計；已延伸 #6/#7 + sub-agent 模擬迭代，使用 old parts 作為單輪 A/B 穩定 baseline）

### 推薦：第一次實車 LMS 調校測試（car_road_tuning_v1，快速迭代版 + sub-agent 模擬）

**為何在「測試腳本」分頁有兩個按鈕？**
- 「標準 v3 實車測試」：啟動完整一般驗證腳本 `car_field_v3`（14 步，涵蓋延遲/MIMO/怠速/市區/高速/音樂/通話/顛簸等全功能）。適合做廣泛實車驗證。
- 「開始路噪調校測試（推薦）」：啟動**專為你 Skoda Octavia 2019 路噪 rumble（200-350Hz 主導）設計的快速迭代腳本** `car_road_tuning_v1`。已根據「快速迭代」需求 prune 掉早期無用 baseline（1/2/3，那些只重複確認已知高延遲問題），直接聚焦有用的 #4/#4b 低延遲 + musicLow 對比 + 對照。**第一次實車調校強烈建議按這個**。

目前腳本內容（已 streamline，prep + 3 組核心對比 + finish，更快可重複跑 3 輪）：

請用以下順序測試（**同一段粗糙路面，40-70km/h，無/低音樂**）。每步進入時**系統自動套用 debug 參數**（mu/freeze/override/musicLow），你**不需要**手動去「測試平台」調滑桿，只負責開車 + 按「完成這步」+ 最後匯出。

| 測試編號 | muMult | freezeThreshold | freezeConsecutive | latencyOverride | musicLow | 預期觀察重點 |
|----------|--------|-----------------|-------------------|---------------|----------|--------------|
| tuning_prep | auto | - | - | - | ON | 快速準備 + 自動 forceNormal + musicLow + gain=1.0；強調 Skoda 200-350Hz 專用 + 跳過無用 baseline，計劃 3 次快速循環 |
| #4 | 1.7 | 11 | 2 | 120 | ON | **#4 強制低延遲 + musicLow 對比（Skoda 200-350Hz rumble 專用）**。override=120 推 maxCancel 接近 250Hz+，觀察 mid band 是否開始有貢獻（你的主力頻段） |
| #4b_Skoda | 1.6 | 12 | 2 | 150 | ON | **#4b 延伸**（基於#4經驗）。override=150 再推延遲，mu 微調針對 mid-focus，讓 200-350Hz rumble 降得更深更穩 |
| #5_contrast | 2.2 | 9 | 2 | 0 | OFF | **musicLow OFF 快速對比**（證明 musicLow 對 rumble 的重要性）。比較前兩步有無 musicLow 時 rumble 降低程度 |
| tuning_finish | - | - | - | - | - | 結束 + 直接「儲存到下載 / CarANC_Logs」按鈕（不用切測試平台）。記錄 scenario（含 "Skoda #4/#4b 經驗, iter X"）、配外部錄音+spectrum。下一輪優先重跑 #4/#4b 變體 + 針對 mid 微調 mu |

**操作方式**（已簡化）：
1. 開啟「實車測試 Log」面板，把「強制正常模式」與「音樂模式仍抗低頻路噪」打開，userAncGain 設 ~0.8~1.0，選擇 PRO 等級。
2. 切到底部「測試腳本」分頁，按「開始路噪調校測試（推薦）」。
3. 啟動 ANC 完成校正後，依照每步說明維持同一段路跑夠時間（腳本會在進入步驟時自動套用對應參數）。
4. 每步按「完成這步」（可填簡單 user note）。
5. 最後一步直接用 GuidedTest 面板的「儲存到下載 / CarANC_Logs」按鈕匯出（或分享 Log）。記錄情境與主觀 rumble 降低 0-10 分。

詳細說明與 log 指標請參考 `MULTI_MACHINE_SYNC.md` 中的「腳本更新 + 第一次實車測試推薦參數組合」章節。

---

> **注意**：一般驗證請使用 `car_field_v3`。第一次真實調校強烈建議使用上方的 `car_road_tuning_v1`。

App 內建 **實車進階測試 v3**（`car_field_v3`），共 14 步、手動步進，適合分段完成。

| # | 步驟 ID | 名稱 |
|---|---------|------|
| 0 | `prep` | 準備與啟動 |
| 1 | `latency_verify` | 延遲優化驗證 |
| 2 | `mimo_verify` | AMBEEO-lite MIMO 驗證 |
| 3 | `idle_light` | 怠速降噪（輕度） |
| 4 | `idle_engine` | 怠速引擎諧波（手動 RPM，可選） |
| 5 | `idle_standard` | 怠速降噪（中度） |
| 6 | `drive_city` | 市區行駛 30–50 km/h |
| 7 | `drive_highway` | 高速行駛 80+ km/h |
| 8 | `music_bypass` | 音樂 bypass（媒體參考扣除） |
| 9 | `nav_voice` | 導航語音清晰度 |
| 10 | `call_bypass` | 通話 bypass（語音頻帶保護） |
| 11 | `siren_sim` | 警笛模擬（可選，可跳過） |
| 12 | `bump_stress` | 顛簸壓力測試 |
| 13 | `finish` | 結束與匯出 |

### 測試前準備

1. USB 連接 Android Auto（或記錄本機模式）
2. 在「實車測試 Log」填寫車型、手機放置位置、連線方式
3. 強烈建議開啟「強制正常模式」與「音樂模式仍抗低頻路噪」
4. （可選）在 TestLogPanel 設定手動 RPM（怠速約 800）
5. 啟動降噪並完成校正

**第一次實車調校測試請直接使用引導測試的「路噪 LMS 調校測試（推薦）」腳本**。

### 操作方式

1. 主畫面進入 **實車引導測試** 區塊
2. 點擊開始測試腳本
3. 依每步說明操作，觀察 UI 指標與 checklist
4. 完成後按 **完成這步** 進入下一步（可跨多次通勤分段進行）
5. 最後一步：停止降噪 → **匯出 Log** → 傳送分析

### 關鍵驗證指標

| 指標 | 期望 |
|------|------|
| `estimatedLatencyMs` | 實測值（AA 常 ~130-140 ms） |
| `maxCancelFrequencyHz` | 應為 150（AA 環境已硬性拉高） |
| `reductionDb` / `antiNoiseDb` | > 0 / 越負越好 |
| `lmsUpdateCount` / `lowBandLmsUpdateCount` | 越高越好（持續上升） |
| `freezeBlocksRemaining` | 大多為 0（避免過度凍結 LMS） |
| `debugLmsMuMultiplier` / `debugFreezeThreshold` | 記錄當前調校組合 |
| `lowBandMuScale` / `midBandMuScale` | 觀察延遲對各頻帶 mu 的影響 |
| `processingMode` | 希望維持 normal / road（即使有音樂） |
| `dominantNoiseBand` | 粗糙路時應為 ROAD / TIRE_WIND |

---

## Session Log

測試與運行期間，`AncSessionLogger` 會寫入 JSONL 格式 log：

- 目錄：`log/`（或 App 內匯出路徑）
- 檔名：`CarANC Session Log YYYYMMDDHH.txt`
- 格式：每行一筆 JSON，含 `phase`、`ts` 與各項運行指標

常見 phase：

- `audio_init`、`calibration`、`running_snapshot`
- `latency_optimization_applied`、`mimo_profile_applied`
- `test_step_start`、`test_step_complete`、`test_script_complete`
- `bump_detected`、`siren_detected`、`profile_aging_detected`

### 調校實驗時特別建議關注的欄位

- `perf_timing`：`lmsUpdateCount`、`lowBandLmsUpdateCount`、`freezeBlocksRemaining`
- `running_snapshot`：`antiNoiseDb`、`reductionDb`、`maxCancelFrequencyHz`、`processingMode`、`estimatedLatencyMs`、`debugLatencyOverrideMs`、`usingLatencyOverride`、`dominantNoiseBand`、`lowBandMuScale` / `midBandMuScale`、`debugLmsMuMultiplier`、`debugFreezeThreshold`

詳細解讀請見 `MULTI_MACHINE_SYNC.md`。

---

## 訂閱方案（規劃中）

| 方案 | 最高等級 | 參考價格 |
|------|----------|----------|
| 免費版 | 輕度 | NT$ 0 |
| 標準月訂 | 中度 | NT$ 99–149／月 |
| 專業月訂 | 重度 | NT$ 199–299／月 |
| 專業年訂 | 重度 | NT$ 1,490–1,990／年 |
| 終身版 | 重度 | NT$ 2,990–4,990 一次 |

> 目前訂閱為開發階段模擬，尚未接入 Google Play Billing。

---

## 已知限制

- **音訊路由**：P0 已強化 `AudioRouteManager`（設備評分、AA 時排除手機喇叭、play 後重試、週期性刷新）。部分 AA USB 裝置仍可能只暴露手機 sink，需以 log 中 `route_after_play`、`carSinkRouted` 驗證
- **延遲**：Record／Track buffer 已分離計算；Track 端仍受裝置 `getMinBufferSize()` 限制，部分手機估計延遲可能 > 120 ms
- **媒體參考**：高版本 Android 上 `MediaPlaybackCapture` 可能不可用
- **RPM 參考**：僅支援手動 RPM（TestLogPanel 輸入），OBD 藍牙已移除（避免權限與 crash 問題）
- **安全**：主動降噪不能取代專注駕駛；請勿在需要完整環境音的情境下過度依賴

---

## 安全聲明

CarANC 為輔助降噪工具，**不能**保證消除所有噪音或取代駕駛注意力。使用時請：

- 保持對道路、行人與警笛等環境音的警覺
- 遵守當地法規與車廠使用建議
- 首次使用前閱讀並接受 App 內安全聲明

---

## 技術棧

- **語言**：Kotlin（KMP `androidTarget`）
- **UI**：Jetpack Compose + Material 3
- **服務**：LifecycleService 前景音訊服務
- **車載**：AndroidX Car App Library 1.4.0
- **定位**：Google Play Services Location
- **建置**：Gradle 9.x + Version Catalog

---

## 聯絡與支援

> **注意**：目前沒有獨立產品網站（caranc.app 為預留域名）。隱私政策與服務條款以 GitHub Markdown 為公開來源，同時完整內嵌於 App 內「方案」分頁的對話框（離線也可閱讀）。

- 隱私政策（GitHub 版）：https://github.com/averger1234-spec/CarANC/blob/main/PRIVACY.md
- 服務條款與免責聲明（GitHub 版）：https://github.com/averger1234-spec/CarANC/blob/main/TERMS.md
- 支援信箱：support@caranc.app
- GitHub Issues：https://github.com/averger1234-spec/CarANC/issues

未來有正式網站後，會更新 ProductCatalog 內的 URL 並同步 README。

---

## 授權

本專案授權條款尚未定案。若需商用或再散布，請聯絡專案維護者。

隱私政策與服務條款請見本頁上方「聯絡與支援」區塊及 App 內「方案」分頁。使用本軟體前務必閱讀服務條款（特別是安全與免責聲明）。


## 2026-06-29 最新 ANC 技術 + 相關演算法新設計導入 ANC 路徑

- Leaky LMS + VSS（mu=2.0 + freeze=10）：已在 BandFxLms 實作（leakage alpha、B energyFactor VSS + gradient clip）。debugLeakage 僅供實驗（AncTestPreferences + TestLogPanel 控制 + car_road_tuning_v1 presets 可 A/B 0.9998/0.9995）。
- pfx EMA variance logging：AncPerfMetrics 新增 lastLmsPfxEma / lastLmsPfxVarEma，寫入 perf_timing + running_snapshot。monitored fields 更新，方便 VSS 調校。
- IMU 震動 + rumble 導入 ANC：VehicleSpeedProvider + Snapshot 新增 linearAccelMagnitude（TYPE_LINEAR_ACCELERATION，僅 log 初期輸出）。AudioEngine 每 block 取得並 setRumbleAccel。MultiBandANCProcessor 實作 rumbleVibBoost（roadMode 時 1 + mag*0.08 max 1.4）影響 effectiveLowMu。未來可作為 rumble feedforward（與 speed ref + mic error 混合，降低 acoustic feedback）。
- Native low band 準備：NativeLowBandLms.cpp skeleton 已 port VSS/Leaky/clip 邏輯到 processor/facade/iOS stub 切換點。NDK 實作中（預期 2x+ 低頻性能）。
- 模擬支援：sim_iter.ps1 模型更新（支援 leakage/VSS/accel/native 參數 + A/B 測試，strict 情境下 cons leak + VSS 改善對 varEma、rumble gain）。AncTestScript / GuidedTest / TestLogPanel 同步 UI/presets/fields 更新。
- 已 push（e8bd471） + install debug 部署。GROK_RESUME / MULTI / README 同步更新。
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