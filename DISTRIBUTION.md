# CarANC 發行通道（internal vs store）

本專案用 **product flavors** 分開「自己測」與「公開上架」，可同時裝在同一支手機。

| Flavor | applicationId | App 名稱 | 用途 |
|--------|---------------|----------|------|
| **internal** | `com.example.caranc` | CarANC Dev | 日常路測、腳本、調校、假訂閱切換 |
| **store** | `com.caranc.app` | CarANC | 未來 Play 公開版（消費者 UI） |

## BuildConfig 開關

| 欄位 | internal | store |
|------|----------|-------|
| `IS_STORE` | false | true |
| `ENABLE_TUNING_LAB` | true（測試腳本 + 測試平台） | false |
| `ENABLE_DEV_BILLING_BYPASS` | true（假方案切換） | false |
| `DISTRIBUTION` | `"internal"` | `"store"` |

## 常用指令

```bat
REM 日常自測（預設）
scripts\install-debug.bat
.\scripts\install-debug.ps1
.\scripts\install-debug.ps1 -Flavor internal

REM 預覽商店版 UI（另一個 package，可並存）
.\scripts\install-debug.ps1 -Flavor store

REM 打 Play AAB（store release）
scripts\bundle-store-release.bat

REM ProGuard 檢查（store release）
.\gradlew.bat :app:verifyReleaseProguard
```

APK 路徑：

- `app/build/outputs/apk/internal/debug/app-internal-debug.apk`
- `app/build/outputs/apk/store/debug/app-store-debug.apk`
- AAB：`app/build/outputs/bundle/storeRelease/app-store-release.aab`（實際路徑以建置輸出為準）

## 簽名

1. 複製 `keystore.properties.example` → `keystore.properties`
2. 產生 upload keystore（只做一次，**備份**）
3. 填入路徑與密碼
4. 再跑 `bundle-store-release` → 才是可上傳 Play 的簽名

沒有 `keystore.properties` 時，release 仍可用 **debug key** 建置（方便本機驗證 R8），**不能**拿去上架。

## 上架後怎麼持續更新

1. 繼續用 **internal** 改算法、拉 log、路測  
2. 確認穩定後打 **store** release AAB，**versionCode +1**  
3. 先丟 Play **內部測試** 軌道 → 自己驗 → 再推正式版  
4. 正式版與自測版可並存；正式版吃 Play 自動更新

## 版本號

### Android

編輯根目錄 `version.properties`：

```
VERSION_CODE=3
VERSION_NAME=1.2.1
```

- 每次上傳 Play（含內部測試）都必須 **VERSION_CODE + 1**
- 每次可路測的功能包：同步改 **VERSION_NAME**，並在 **`CHANGELOG.md`** 寫「改了什麼」
- App 主畫面：`v{VERSION_NAME}`（internal 另顯示 `-internal`）

### iOS

| 位置 | 欄位 | 目前 |
|------|------|------|
| `iosApp/CarANC/Info.plist` | `CFBundleShortVersionString` / `CFBundleVersion` | **1.2.30** / **31** |
| Xcode target | `MARKETING_VERSION` / `CURRENT_PROJECT_VERSION` | 同上 |
| App UI | 狀態頁 | **`v1.2.30 (31)`** |

- 每次可路測 IPA：**build +1**；功能包再升 marketing
- 寫 **`CHANGELOG.md`**；更新 `dist/WINDOWS_INSTALL_SIDELOADLY.md` 版本字樣

## 簽名備份

- 本機：`upload-keystore.jks` + `keystore.properties`（gitignored）
- 備份說明：`secrets/UPLOAD_KEY_BACKUP.txt`（gitignored）
- **務必複製到離線位置**；遺失會導致無法用同一 upload key 上傳更新

## 上架檢查

見 `PLAY_RELEASE_CHECKLIST.md`、`DATA_SAFETY.md`。

## 尚未做（上架後／下一階段）

- 接 Google Play Billing（真實訂閱）
- 把 internal package 從 `com.example.caranc` 遷到 `com.caranc.app.dev`（可選）
- 商店截圖、示範影片（人工）
- Play Console 帳號與送審（人工）
