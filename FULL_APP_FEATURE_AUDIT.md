# CarANC 全 App 功能盤點與驗收狀態

**目的**：不只測試腳本，而是 **所有使用者路徑** 不應靜默失敗、不應讓路測白跑。  
**對應 commit**：`891ecd1`（路測完整性）+ 本版 SystemHealth / AA consent / 全表。

---

## 1. App 架構（四 Tab + 車機）

| 區塊 | 入口 | 職責 |
|------|------|------|
| 狀態 | MainActivity tab0 | 開始/停止、tier、dB 儀表、頻譜、**系統健康卡** |
| 方案 | CommercialPanel | 訂閱顯示、DEBUG 切方案、隱私/條款 |
| 測試腳本 | GuidedTestPanel | 路噪自動腳本 + 自動存 log |
| 測試平台 | TestLogPanel | 環境、log 開關、dev 調參、匯出 |
| Android Auto | CarAppService → CarAncAutoScreen | 車機啟停、tier |
| 背景 | ANCService → AudioEngine | FGS、AA 連線、音訊、校正、GPS |

---

## 2. 功能總表（完整性 / 靜默失敗風險）

### 2.1 狀態 Tab（日常使用）

| 功能 | 狀態 | 依賴 | 已加固 |
|------|------|------|--------|
| 開始/停止降噪 | 可用 | 權限、安全聲明 | 啟動強制 log ON |
| 安全聲明 | 可用 | Entitlement | 未同意無法真正跑 service |
| 輕/中/重度 | 可用 | 方案 clamp | FREE 選 PRO 會 toast |
| dB 儀表 / 頻譜 | 可用 | Service 在跑 | 無 cancel 時顯示接近 0 |
| 進階延遲/GPS | 可用 | toggle | — |
| **系統健康卡** | **新增** | 全路徑 | 顯示聲明/log/GPS/增益/延遲風險 |
| 開發者模式 7 連點 | 可用 | — | 解鎖測試平台進階 |

### 2.2 方案 Tab

| 功能 | 狀態 | 備註 |
|------|------|------|
| 方案列表 / 解鎖功能 | 顯示 | 真實 Play 計費 **未上線**（DEBUG 可切） |
| 隱私/條款 | 可用 | App 內 + GitHub |
| 升級按鈕 (Release) | stub | Toast「即將推出」 |

### 2.3 測試腳本 Tab

| 功能 | 狀態 | 已加固 |
|------|------|--------|
| car_road_tuning_v1 | 可用 | 有效行駛秒、自動存、不中途砍 service |
| car_field_v3 | **UI 已移除** | code 仍在 shared，不入口 |
| 自動開始 ANC | 可用 | 強制 log |
| 自動存 Download | 可用 | MB 大檔優先 |

### 2.4 測試平台 Tab

| 功能 | 狀態 | 風險 |
|------|------|------|
| Log 開關 | 可用 | 關閉則無 session（狀態啟動會強制開） |
| 車型/位置/情境 | 可用 | 寫入 log header |
| 使用者增益 | 可用 | **0 = 無喇叭 anti**（健康卡會警告） |
| musicLow / forceNormal | 可用 | 影響模式 |
| 手動 RPM | 可用 | OBD 路徑 **未接** |
| LMS debug 滑桿 | 需 dev 模式 | latencyOverride **不改 plant** |
| 分享/存 log | 可用 | 已修空檔問題 |

### 2.5 Android Auto 車機

| 功能 | 狀態 | 已加固 |
|------|------|--------|
| 啟動/停止 | 可用 | 未同意聲明時 **不盲啟** + 畫面提示先開手機 |
| Tier 切換 | 可用 | 同手機 clamp |
| USB AA 音訊 | 依車機 | 無線高延遲風險；文案已改 HIGH_LAT_PRED_BANK |

### 2.6 背景 / 音訊 / 感測

| 功能 | 狀態 | 風險 / 備註 |
|------|------|-------------|
| FGS + 通知 | 可用 | 通知關仍可能跑 |
| 校正 chirp | 可用 | 有 profile 會 skip |
| GPS 路噪模式 | 需定位+方案 | FREE 無 GPS 路噪 |
| 音樂/通話模式 | 需方案 | FREE 不保護 |
| Siren / Sonif duck | 可用 | 可能誤觸；rumble 時已放寬 |
| IMU / bank / neural | 可用 | bank 冷啟動可能弱 |
| Native NDK low | 常 **不可用** | 靜默走 Kotlin |
| OBD 藍牙 | **死路徑** | 用手動 RPM |
| iOS | stub | 非產品 |

---

## 3. 靜默失敗 → 必須在 UI 看得到

| 情況 | 以前 | 現在 |
|------|------|------|
| 未同意聲明 | service 秒退 | 健康卡 + 車機提示 |
| Log 關 | 存不到 | 啟動強制 ON + 健康卡 |
| 增益 0 | 無聲像壞掉 | 健康卡警告 |
| GPS 無 | 腳本不推進 | 健康卡 + 定位拒絕 Toast |
| 存到 1KB 檔 | 以為沒測 | 選大檔 + 自動存 + 獨立檔名 |
| finish 砍 log | 測不完全 | 完整結束才停 |
| AA 中斷砍 log | 中斷 | 腳本進行中保 service |

---

## 4. 驗收分層（對使用者說話用）

| 層級 | 意思 | 可否說「正常」 |
|------|------|----------------|
| L0 可編譯/安裝 | APK 在手機 | 否 |
| L1 單元測試綠 | shared 測試 | 否 |
| **L2 完整性** | 能開、能關、有 log、腳本跑完、UI 有警告 | **可以說「可上路驗證」** |
| L3 效果 KPI | 有感消噪、bank 有出力 | 需實車 log 才說 |

本文件承諾的是 **L2 全 App**，不是 L3 效果。

---

## 5. 建議你每次使用前看「狀態」頁健康卡

- 綠：可正常使用  
- 紅：先處理列點（聲明、增益、GPS…）  
- 再開始降噪或測試腳本  

---

## 6. 仍屬已知限制（產品邊界，非本次靜默 bug）

- Play 訂閱未接  
- 無線 AA 效果差  
- 無原廠級 <10ms RNC  
- Native NDK 常關  
- OBD 未接  
- 免費方案功能刻意縮小  

---

*盤點來源：app + shared 全模組靜態審閱 + 實車 log 已知故障修復。*
