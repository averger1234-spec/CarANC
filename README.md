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
- **實車引導測試 v3**：14 步手動步進測試腳本與 JSONL session log 匯出
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
   AudioRecord        AudioTrack      GPS / OBD / 媒體參考
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
| `EngineCombCanceller` | 引擎諧波前饋消除（需 OBD RPM 或手動 RPM） |
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
│       └── androidMain/    # ANCService、音訊路由、GPS、OBD、Logger
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
- 可選：ELM327 OBD 藍牙轉接器（引擎 RPM 參考）

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
| `BLUETOOTH` / `BLUETOOTH_CONNECT` | OBD 藍牙 RPM 讀取（可選） |

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
- **音樂模式**：播放媒體時僅處理底噪，避免干擾音樂
- **通話模式**：保護語音頻帶，同時維持低頻降噪

---

## 實車測試流程

App 內建 **實車進階測試 v3**（`car_field_v3`），共 14 步、手動步進，適合分段完成。

| # | 步驟 ID | 名稱 |
|---|---------|------|
| 0 | `prep` | 準備與啟動 |
| 1 | `latency_verify` | 延遲優化驗證 |
| 2 | `mimo_verify` | AMBEEO-lite MIMO 驗證 |
| 3 | `idle_light` | 怠速降噪（輕度） |
| 4 | `idle_obd` | 怠速 OBD／引擎諧波 feedforward |
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
3. （可選）設定手動 RPM（怠速約 800）或 OBD 藍牙位址
4. 啟動降噪並完成校正

### 操作方式

1. 主畫面進入 **實車引導測試** 區塊
2. 點擊開始測試腳本
3. 依每步說明操作，觀察 UI 指標與 checklist
4. 完成後按 **完成這步** 進入下一步（可跨多次通勤分段進行）
5. 最後一步：停止降噪 → **匯出 Log** → 傳送分析

### 關鍵驗證指標

| 指標 | 期望 |
|------|------|
| `estimatedLatencyMs` | < 120 ms（低延遲模式） |
| `maxCancelFrequencyHz` | > 50 Hz |
| `reductionDb` | > 0（怠速／行駛時） |
| `mimoZoneCount` | ≥ 4 |
| `engineRpmValid` | true（OBD 或手動 RPM 步驟） |
| `playbackRefActive` | true（音樂 bypass 步驟） |

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
- **OBD**：需自行配置 ELM327 或手動 RPM
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

- 產品網站條款：https://caranc.app/terms
- 隱私政策：https://caranc.app/privacy
- 支援信箱：support@caranc.app

---

## 授權

本專案授權條款尚未定案。若需商用或再散布，請聯絡專案維護者。