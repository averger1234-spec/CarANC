# CarANC 改版紀錄（Changelog）

**版號來源**

| 平台 | 檔案 | 主畫面顯示 |
|------|------|------------|
| **Android** | 根目錄 `version.properties`（`VERSION_NAME` + `VERSION_CODE`） | `v{VERSION_NAME}`（internal 另有 `-internal`） |
| **iOS** | `iosApp/CarANC/Info.plist` + Xcode `MARKETING_VERSION` / `CURRENT_PROJECT_VERSION` | **`v{marketing} (build)`**（狀態頁右上角） |

規則：

- 每次 **Play 上傳**（含內部測試）：Android `VERSION_CODE` **必須 +1**
- 每次 **iOS 可路測 IPA**：`CFBundleVersion`（build）**必須 +1**
- 每次 **可路測的功能包**：`VERSION_NAME` / iOS marketing **遞增**，並在本檔新增一節
- 共用 DSP／schema 功能：兩邊 **marketing 版號宜對齊**；**僅 iOS 平台功能**（如 CarPlay）可 iOS marketing 超前
- Commit 建議：`feat:` / `fix:` / `docs:` + 本檔同步

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
