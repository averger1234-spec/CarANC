# iOS 如何複用 Android 經驗（不是從頭來）

## 原則

同一 GitHub monorepo：

| 層 | 複用什麼 | 狀態 |
|----|----------|------|
| **產品策略** | 路噪/輪噪主力、風切不追消、`car_road_tuning_v1` 步驟 | ✅ |
| **Log schema** | Android `running_snapshot` **欄位名** | ✅ `AncRunningSnapshotSchema` + iOS `AndroidSnapshotKeys` |
| **DSP 核心** | `shared` `MultiBandANCProcessor`（KMP） | ✅ `CarANCShared.framework` + `KotlinAncBridge` |
| **音訊 I/O** | Android AA/Track vs iOS AVAudioEngine | 各自平台（合理） |
| **商業/腳本狀態** | `UserTier`、Entitlement、GuidedTest 概念 | ✅ |

**不要**再發明第二套 KPI 名字；缺能力就填 `n/a`，保留 key。

## 資料夾

```
app/        → 只給 Android 改 UI / AA
iosApp/     → 只給 iOS 改 Swift UI / AVAudio
shared/     → 兩邊共用：DSP、schema、commercial、腳本定義
dist/       → 預編 .ipa + Windows 安裝說明
```

## 目前架構（2026-08-24 · 1.2.30 論壇路徑）

```
iPhone 麥克風
    → AVAudioEngine (Swift)
    → KotlinAncBridge（NSLock + applyBandSnapshotFromBlock）
    → MultiBandANCProcessor (KMP / 與 Android 同源 + SpeedScheduled)
    → AVAudioEngine 喇叭 或 CarPlay 車機（aaLinkType=carplay_*）

車速：GPS → gps_hold → imu_proxy（VehicleSpeedFusion，與 Android 同策略）
```

**對版**：iOS / Android **1.2.30**（狀態頁 `v1.2.30 (29)` · Android `v1.2.30` code 32）。

| 對齊項 | iOS | Android |
|--------|-----|---------|
| 開 ANC 不閃退 | 只在有 tap 才 `removeTap`；先 stop 再 detach | AudioRecord 路徑 |
| NVH classifier | 每 block `applyBandSnapshotFromBlock` → `ROAD_NOISE_GPS` | vis 迴圈 `applyClassifierResult` |
| plant D | `setMeasuredLatencyBreakdown` + store `refinePlantDelayFromProbe` | 同 KMP API |
| 路徑自檢 | `carplay_path_check` 送到 carAudio 即 PASS；艙麥 `heard50`/`heard80`；FAIL 仍送 anti | `aa_path_check` 同：送到即 PASS，艙麥分開記 |
| 艙錄 | ANC tap → `cabin_*.wav` | AudioRecord 同路 → `cabin_*.wav`（不再第二路 MediaRecorder） |

建 framework（本機需 JDK 17）：

```bash
export JAVA_HOME=.../jdk-17.../Contents/Home
cd CarANC
./gradlew :shared:linkDebugFrameworkIosArm64
cp -R shared/build/bin/iosArm64/debugFramework/CarANCShared.framework iosApp/Frameworks/
```

> Framework 二進位預設 **不進 git**（見 `.gitignore`）；CI／本機建完再嵌進 App。

## 分析路測 log

- `snapshotSchemaVersion=1`、`platform=ios`
- 主 KPI：`lowBandRumbleReduction`（Android 同名）
- `dsp=kmp_MultiBandANCProcessor` / `audioBackend=AVAudioEngine_local+KMP` → 確認為共用 DSP 版
- `imuMicCoherence` / `bankMatchQuality` / `fixedBankOut` 等由 KMP 回報
- 部分 plant residual 頻帶若仍為代理或 `n/a`，以趨勢 + 主觀為準，勿硬比 Android 絕對值

## CarPlay（對齊 AA）

見 [`CARPLAY.md`](./CARPLAY.md)。程式已落地；車機 template UI 需 Apple **Driving Task** entitlement。

## 迭代順序（後續）

1. 路測 log 對照 Android 同場景  
2. 補 iOS plant residual 閉環 KPI（若 commonMain 已有、Swift 側未掛齊）  
3. Apple 核准後打開 `carplay-driving-task` entitlement → 車機圖示  
