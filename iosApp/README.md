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
| `version.properties` | Android 版號 | 目前 **1.1.0 / code 2** · 主畫面 `v1.1.0` |
| `iosApp/.../Info.plist` + Xcode | iOS 版號 | 目前 **1.2.1 (14)** · **狀態頁顯示 `v1.2.1 (14)`** |

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
| 版號顯示 | ✅ 狀態頁 `v1.2.1 (14)` | ✅ 主畫面 `v1.1.0` |
| 多頻段 DSP | ✅ **KMP `MultiBandANCProcessor`** + SpeedScheduled | ✅ 同核心 |
| `speedNvh*` log | ✅ | ✅ |
| 路測腳本 | ✅ car_road_tuning_v1 | ✅ 同腳本 + 更全 log |
| Session log | ✅ running_snapshot 同欄位名 | ✅ JSONL |
| GPS / IMU | ✅ | ✅ |
| Android Auto / CarPlay | ✅ CarPlay 對齊 AA（見 `CARPLAY.md`） | ✅ AA |
| 本機喇叭 duplex | ✅ AVAudioEngine | ✅ |
| 預編 IPA | ✅ `dist/…ipa` **1.2.1 (14)** | — |

### 路測對版（必看）

1. 打開 App → **狀態** 頁右上角 / 副標：**`v1.2.1 (14)`**  
2. Session log 開頭：`appVersion=1.2.0` `build=13`  
3. 改功能後：`CFBundleVersion` +1，功能包再升 marketing，並寫 `CHANGELOG.md`

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
