# CarANC iOS

SwiftUI + AVAudioEngine 本機主動降噪 App，對齊 Android 產品策略（路噪 / 輪噪主力、風切不追消）。

**路測驗證與 Log 欄位**：見 [`ROAD_TEST_VERIFY.md`](./ROAD_TEST_VERIFY.md)

## 與 Android 在同一 GitHub 怎麼分

同一個 repo `CarANC`，用**資料夾**分開，不是兩個 repo：

| 路徑 | 平台 | 說明 |
|------|------|------|
| `app/` | **Android only** | Compose UI、AA、install-debug、Play flavor |
| `iosApp/` | **iOS only** | Xcode / SwiftUI / 本機 AVAudioEngine |
| `shared/` | **共用 KMP** | 演算法與 commercial；Android 完整、iosMain 有 actual |
| `version.properties` | Android 版號 | 目前 **1.2.15 / code 17** · 主畫面 `v1.2.15` |
| `iosApp/.../Info.plist` + Xcode | iOS 版號 | 目前 **1.2.15 (26)** · **狀態頁顯示 `v1.2.15 (26)`** |

推 GitHub 時 **一次 push 兩邊都在**；改 iOS 不影響 Android APK 建置，反之亦然。  
Commit 訊息建議標前綴：`ios:` / `android:` / `shared:`。

## 開啟專案

```bash
open ~/CarANC/iosApp/CarANC.xcodeproj
```

真機安裝（已設 Team 後）：

```bash
cd ~/CarANC/iosApp
xcodebuild -project CarANC.xcodeproj -target CarANC -sdk iphoneos \
  -allowProvisioningUpdates DEVELOPMENT_TEAM=<YOUR_TEAM> \
  PRODUCT_BUNDLE_IDENTIFIER=com.lutony.caranc.ios build
xcrun devicectl device install app --device <UDID> build/Debug-iphoneos/CarANC.app
```

## 功能對照

| 能力 | iOS 現況 | Android |
|------|----------|---------|
| 版號顯示 | ✅ 狀態頁 `v1.2.15 (26)` | ✅ 主畫面 `v1.2.15` |
| 無車速備用 | ✅ gps_hold + imu_proxy | ✅ 同 `VehicleSpeedFusion` |
| 多頻段 DSP | ✅ **KMP `MultiBandANCProcessor`** + SpeedScheduled | ✅ 同核心 |
| `speedNvh*` / boom / antiE / mute / 極性 / 真LF / openBoom | ✅ 1.2.15 | ✅ |
| 路測腳本 | ✅ car_road_tuning_v1（1.2.15 openBoom+艙錄極性） | ✅ 同腳本 + 艙錄 m4a |
| 艙錄 | ✅ 達速 `cabin_*.wav` | ✅ `cabin_*.m4a` |
| Session log | ✅ running_snapshot 同欄位名 | ✅ JSONL |
| GPS / IMU 三軸 | ✅ setImuAxes | ✅ |
| Android Auto / CarPlay | ✅ 見 `CARPLAY.md`（含通話結束音量修復） | ✅ AA |
| 通話中斷保護 | ✅ pause + session restore + 增益漸升 | ✅ MODE_IN_CALL bypass |
| 本機喇叭 duplex | ✅ AVAudioEngine + 50Hz tone | ✅ |
| 預編 IPA | ✅ `dist/…ipa` **1.2.15 (26)** | — |
| 開機不閃退 | ✅ Embed `CarANCShared`（build 23；勿用 22） | — |

### 路測對版（必看）

1. 打開 App → **狀態** 頁右上角 / 副標：**`v1.2.15 (26)`**（**勿裝 build 22（缺 Embed）**，會閃退）  
2. Session log 開頭：`appVersion=1.2.13` `build=26`  
3. 改功能後：`CFBundleVersion` +1，功能包再升 marketing，並寫 `CHANGELOG.md`  
4. 重編 IPA 必須確認 `.app/Frameworks/CarANCShared.framework` 存在，且 **刪除舊 IPA 再打包**（勿 `zip` 覆蓋殘留舊 framework）

## 結構

```
iosApp/
├── CarANC.xcodeproj
├── README.md
├── ROAD_TEST_VERIFY.md
└── CarANC/
    ├── Audio/          # AVAudioEngine + SessionLogger 掛點
    ├── DSP/
    ├── Models/         # SessionLogger, GuidedTestScript, state
    ├── Sensors/
    ├── AppIcons/
    └── Views/          # 狀態 / 方案 / 測試腳本 / 測試平台
```

## 測試腳本與 Android 自動測試

| | Android | iOS |
|--|---------|-----|
| 步驟定義 | KMP `CarRoadTuningScript` | Swift **鏡像**（ID/秒數/門檻一致） |
| 自動進階 | `GuidedTestController` autoAdvance | `GuidedTestRunner.autoAdvance=true` |
| 有效秒 | 車速≥minSpeed 才累加 | 同 |
| debugPresets | bake 進 DSP（mu 等） | 主要套用 **tier**；完整 mu 覆寫待接 KMP API |
| 長腳本 `car_field_v3` | 有 | 尚未（主路測用 car_road_tuning_v1） |

為何曾不像自動：iOS 早期手點步進 + 腳本文字落後；1.2.3 起 **自動進階與步驟已對齊** Android 主腳本。

### 全自動路測（1.2.6+ UI）

| 項目 | 說明 |
|------|------|
| **開始腳本** | **自動開降噪**（未開則 start；需已同意安全聲明） |
| **過程** | 有效秒／壁鐘達標 → **自動下一步**；不用勾清單 |
| **結束** | **自動彈出系統分享** Log；也可再按「再次匯出」 |
| **「略過此步」** | 僅緊急用 |
| **秒數不漲** | 車速不足／GPS 無效 → 看橘字 pause 原因 |
