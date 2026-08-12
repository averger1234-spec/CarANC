# CarANC 改版紀錄（Changelog）

**版號來源**：根目錄 `version.properties`（`VERSION_NAME` + `VERSION_CODE`）  
App 主畫面顯示：`v{VERSION_NAME}`（internal 另有 `-internal` suffix）。

規則：

- 每次 **Play 上傳**（含內部測試）：`VERSION_CODE` **必須 +1**
- 每次 **可路測的功能包**：`VERSION_NAME` 遞增，並在本檔新增一節
- Commit 建議：`feat:` / `fix:` / `docs:` + 本檔同步

---

## [1.1.0] — 2026-08-12 · code 2

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
