# iOS 如何複用 Android 經驗（不是從頭來）

## 原則

同一 GitHub monorepo：

| 層 | 複用什麼 | 狀態 |
|----|----------|------|
| **產品策略** | 路噪/輪噪主力、風切不追消、`car_road_tuning_v1` 步驟 | ✅ 已搬 |
| **Log schema** | Android `running_snapshot` **欄位名** | ✅ `AncRunningSnapshotSchema` + iOS `AndroidSnapshotKeys` |
| **DSP 核心** | `shared` `MultiBandANCProcessor`（KMP） | ⏳ iosMain facade 已接；需 framework 進 Xcode |
| **音訊 I/O** | 平台不同：Android AA/Track vs iOS AVAudioEngine | 各自 actual（合理） |
| **商業/腳本狀態** | `UserTier`、Entitlement、GuidedTest 概念 | ✅ 對齊 |

**不要**再發明第二套 KPI 名字；缺能力就填 `n/a`，保留 key。

## 資料夾

```
app/        → 只給 Android 改 UI / AA
iosApp/     → 只給 iOS 改 Swift UI / AVAudio
shared/     → 兩邊共用：DSP、schema、commercial、腳本定義
```

## 迭代順序（建議）

1. **Log / 腳本 / 產品規則** ← 已對齊 Android 命名  
2. **KMP framework** `./gradlew :shared:linkDebugFrameworkIosArm64` → Xcode 連 `CarANC.framework`  
3. Swift 改呼叫 `MultiBandANCProcessor.process`（取代精簡 Swift FxLMS）  
4. plant residual / bank 等有 commonMain 的，自動進 log  

## 建 framework（本機需 JDK 17）

```bash
export JAVA_HOME=.../jdk-17.../Contents/Home
cd CarANC
./gradlew :shared:linkDebugFrameworkIosArm64
# 或模擬器 arm64 / 之後加 iosX64 for Intel sim
```

產出：`shared/build/bin/iosArm64/debugFramework/CarANC.framework`

## 分析路測 log

- 看 `snapshotSchemaVersion=1`  
- 看 `platform=ios`  
- 主 KPI 仍用 `lowBandRumbleReduction`  
- 若 `kpiSource=ios_spectrum_proxy`：表示尚未 plant residual，**趨勢可比、絕對值勿與 Android 硬比**  
- `n/a` 欄 = 待 KMP/模組 port，不是 log 壞掉  
