# iOS 實車驗證與 Log 指南

**現行對版**：**iOS 狀態頁 `v1.2.12 (21)`** · Android **`v1.2.13` / code 15** · 腳本 1.2.13  
**Log 欄位名與 Android `running_snapshot` 同一套**（見 `shared/.../AncRunningSnapshotSchema.kt`、`ANDROID_REUSE.md`）。  
缺能力填 `n/a`，不改名。

## 要驗證什麼 → Log 哪一欄

| 驗證目標 | Log 欄位（Android 同名） | 怎麼判 |
|----------|--------------------------|--------|
| **裝對版本** | `appVersion`、`build` | 應為 **1.2.0** / **13**（與狀態頁一致） |
| 有在播反噪 | `outputPathActive`、`antiNoiseDb` | antiNoiseDb 不應長期 -90；outputPathActive=true |
| 低頻路噪 KPI | `lowBandRumbleReduction` | 主 KPI；看趨勢（`kpiSource=ios_spectrum_proxy` 時勿硬比 Android 絕對值） |
| 速域增益 1.1.0 | `speedNvhBinKmh`、`speedNvhTotalAnti`、`speedNvhTableId` | 行駛時 bin 隨速跳；table 非 none |
| 麥克風有聲 | `rawDb` | 行駛應高於安靜怠速 |
| LMS 有在學 | `lmsLowUpdates` | 隨時間遞增；卡 0 表示沒更新 |
| 延遲／可消頻 | `estimatedLatencyMs`、`maxCancelFrequencyHz` | 啟動後有值 |
| NVH 分類 | `nvhFocus` | 行駛多 ROAD_RUMBLE / TIRE_NOISE |
| GPS 有效行駛 | `vehicleSpeedKmh`、`vehicleSpeedValid` | 開闊路 true |
| 行駛 rumble | `isDrivingRumble` | speed>40 且 accel>0.5（**同 Android 規則**） |
| IMU | `rumbleAccel` | 路面震動有變化 |
| Plant 閉環 | `plantResidualReductionDb` | iOS 暫 `n/a` |
| Bank/FDAF | `fixedBankOut`、`fdafDelayless` | 可看 kmp_diag |
| 後端／車機 | `audioBackend` / `aaLinkType` | local 或 `carplay_*`；CarPlay 時 backend 含 carplay |

## 建議流程

1. **測試平台**填車型、placement（floor/seat）、情境  
2. **狀態**→ 開始降噪 → 允許 mic/定位  
3. **測試腳本**→ 開始 `car_road_tuning_v1`  
4. 依步驟開車（紅燈不計有效秒）  
5. 結束後 **匯出 / 分享 Log**（腳本頁或測試平台）  
6. 傳到電腦 / GitHub issue / 分析用  

## PASS / FAIL（主觀 + Log）

**PASS 傾向**

- 怠速相對安靜、無電台靜電  
- 行駛低頻悶／沙沙有感下降（主觀 0–10 記在 scenario）  
- Log：`lmsLowUpdates`↑、`outputPathActive=true`、GPS valid  

**FAIL 傾向**

- 開 ANC 更吵／嘶聲  
- `antiDb` 幾乎無輸出  
- GPS 全程 invalid（權限／室內）  

## 與 Android Log 差異

| | Android | iOS |
|--|---------|-----|
| 格式 | JSONL session + running_snapshot | 純文字 phase= 行 |
| plant residual | 有 | 尚未 |
| AA / bank / FDAF | 有 | 無（本機 duplex MVP） |
| 主 KPI 名 | `lowBandRumbleReduction` | 同名（演算法為代理） |

分析時請標 **platform=ios**，勿與 Android 數值直接橫向比絕對值。

## 車速備用（1.2.1+）

| speedSource | 含義 |
|-------------|------|
| `gps` | 有效 GPS |
| `gps_hold` | GPS 掉線後 ≤25s 保持 |
| `imu_proxy` | IMU 振動估計（非精密） |
| `none` | 無 |

DSP / SpeedScheduled 使用 `vehicleSpeedValid`（含 hold／imu）。嚴格路測看 `speedValidForRoadTest` 與 `speedSource=gps`。

## 1.2.3 notch（輪／風）

| 欄位 | 含義 |
|------|------|
| `tireNotchEnergy` | 輪噪窄帶能量 |
| `tireNotchF0Hz` | 輪噪 notch 中心頻 |
| `windNotchEnergy` / `windNotchActiveCount` | 風切多 notch |
| `notchMixAnti` | notch 混入 anti 量 |

腳本步驟與 Android 相同：`tuning_prep` → `target_road` → `target_tire` → `target_wind` → `tuning_finish`；**有效秒達標自動進階**。

## 1.2.5 悶鎖相 KPI

| 欄位 | 含義 |
|------|------|
| `effectiveLowMu` | 高延遲段應明顯 > 舊版近 0 |
| `roadBoomWeightEnergy` | boom LMS 權重能量（鎖相後上升） |
| `roadNotchEnergy` | 路噪 boom notch 能量 |
| `notchMixAnti` | notch 混入 anti |

腳本顯示名：`三目標壓制·路/輪/風 + 1.2.5悶鎖相`；`target_road` 有效秒 **60**（≥45 km/h）。

## 1.2.6 spectrum_kpi + forceNvhFocus

| 欄位/事件 | 含義 |
|-----------|------|
| `phase=spectrum_kpi` | 每 ~2s 內建分帶 KPI |
| `deltaBoomDb` / `deltaTireDb` / `deltaWindDb` | mic−plant（iOS plant=input+anti 代理） |
| `forcedNvhFocus` | 腳本強制 ROAD/TIRE/WIND |
| `forceNvhFocus` preset | 進入步驟時寫入 KMP |

主分析用 log；外部 m4a 可選。

## 1.2.7 悶音壓 KPI

| 欄位 | 含義 |
|------|------|
| `boomPressureOut` | plant-delay 反相低頻壓力路徑輸出（路步應非 0） |
| `roadBoomWeightEnergy` / `roadNotchEnergy` | boom notch 權重／能量 |
| `tier` | Android 導測應 **PRO**（腳本強制） |
| `forcedNvhFocus` | ROAD / TIRE / WIND |

腳本顯示名：三目標·1.2.7悶音壓+PRO強制（**現行為 1.2.12**）。iOS **build 21** 已含 mute/極性/艙錄。

## 1.2.8–1.2.9 診斷 + P2 plant

| 欄位/事件 | 含義 |
|-----------|------|
| `diag_tone_active` / `diagToneHz=50` | 腳本 30s 播 50Hz 純音（路徑暢通檢測） |
| `antiE40_80` … `antiE500_2k` / `antiLfDominatesHf` | 送出前 anti PCM 分帶（spectrum_kpi） |
| `boomPlantCorr` | mic low × anti 對立相關 EMA |
| `plantElectricalDelaySamples` / `plantDelaySamples` | plant 電氣延遲樣本 |
| `setImuAxes` | 三軸 userAcceleration → KMP low path |

腳本（**現行 1.2.12**）：`prep → diag_tone_50 → target_road_off → target_road_ppos → target_road_pneg → tire → wind → finish`。  

| 1.2.12 | iOS |
|--------|-----|
| muteAnti 真靜音 | ✅ 喇叭歸零 + KMP `setAntiOutputMuted`；antiNoiseDb 量 post-mute |
| 極性 A/B | ✅ forceBoomPolarity ±1；`boom_polarity_winner` → UserDefaults PlantPathStore |
| 艙錄 | ✅ 達速後 `cabin_*.wav`（Documents/anc_logs；用途同 Android m4a） |
| corrGate / HIGH_LAT | ✅ 隨 KMP framework |
| 通話結束車機音量 | ✅ build 21：`.default` session + interruption pause + 增益漸升（`audio_interruption*`） |