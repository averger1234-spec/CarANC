# Windows 安裝 CarANC 到 iPhone（Sideloadly）

## 重要

- **Windows 不能編譯 iOS**，只能安裝 **Mac 已編好的 .ipa**
- 本檔 `CarANC-ios-kmp-debug.ipa` 為 **Development 簽名（免費 Team）**
- 通常 **約 7 天過期**，過期後需 Mac 重簽再裝
- 安裝時建議用 **同一個 Apple ID**（簽 ipa 的帳號）

## 本機環境準備（這台 Windows）

在專案根目錄 PowerShell（可重複執行）：

```powershell
# 1) Apple USB 裝置支援（讓 Windows 認 iPhone）
winget install --id Apple.AppleMobileDeviceSupport -e --accept-package-agreements --accept-source-agreements

# 2) Sideloadly（裝 IPA）
winget install --id iOSGods.Sideloadly -e --accept-package-agreements --accept-source-agreements

# 3) 確認 IPA 存在
dir dist\CarANC-ios-kmp-debug.ipa
```

一鍵開啟 Sideloadly 並複製 IPA 路徑到剪貼簿：

```bat
scripts\install-ios-sideloadly.bat
```

或：

```powershell
.\scripts\install-ios-sideloadly.ps1
```

## 步驟（裝到手機）

1. iPhone 用 **可傳資料的 USB** 接 Windows  
2. 手機跳出「信任此電腦？」→ **信任**（輸入鎖屏密碼）  
3. 雙擊 `scripts\install-ios-sideloadly.bat`（或開始選單開 Sideloadly）  
4. Sideloadly 選你的 **iPhone**  
5. IPA 欄貼上路徑（腳本已複製）或拖入：  
   `dist\CarANC-ios-kmp-debug.ipa`  
6. Apple 欄填 **Apple ID**（建議與 Mac 簽 ipa 同一帳號）+ 密碼  
7. **Start** → 等完成  
8. iPhone：**設定 → 一般 → VPN 與裝置管理**（或「裝置管理」）→ 信任該開發者  
9. 開啟 **CarANC**，允許 **麥克風**、**定位**

## 從 GitHub 取得

```text
Mac 推送後：
  dist/CarANC-ios-kmp-debug.ipa
  dist/WINDOWS_INSTALL_SIDELOADLY.md
```

Windows：`git pull` 後再跑 `scripts\install-ios-sideloadly.bat`。

## 若安裝失敗

- 換線 / 重插 / 信任電腦；拔插後重開 Sideloadly  
- 尚未裝 Apple Mobile Device Support → 見上方 winget  
- Apple ID 若要求「App 專用密碼」→ [appleid.apple.com](https://appleid.apple.com) 產生  
- 免費帳號裝置數達上限 → 移除舊開發 App  
- 過期（約 7 天）：回 Mac 重編 ipa，`git pull` 再裝  
- 裝完打不開：先完成「信任開發者」

## 本包內容（KMP 共用 DSP）

- App 版本：**1.2.12 (21)** — 狀態頁應顯示 **`v1.2.12 (21)`**
- 含：1.2.12 mute/極性A/B/艙錄 + **通話結束後車機音樂暴衝修復**（見 `iosApp/CARPLAY.md`）
- DSP：KMP MultiBand + `SpeedScheduledNvhGains`
- 車速備用：`speedSource=gps|gps_hold|imu_proxy`（無 GPS 仍可能餵速域）
- CarPlay：路由 + 模板（車機圖示需 Apple entitlement，見 `iosApp/CARPLAY.md`）
- Log：`dsp` / `aaLinkType` / `speedNvh*` / `speedSource` / `appVersion`+`build`

