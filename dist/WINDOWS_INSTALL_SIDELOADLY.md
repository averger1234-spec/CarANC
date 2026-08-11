# Windows 安裝 CarANC 到 iPhone（Sideloadly）

## 重要

- **Windows 不能編譯 iOS**，只能安裝 **Mac 已編好的 .ipa**
- 本檔 `CarANC-ios-kmp-debug.ipa` 為 **Development 簽名（免費 Team）**
- 通常 **約 7 天過期**，過期後需 Mac 重簽再裝
- 安裝時建議用 **同一個 Apple ID**（簽 ipa 的帳號）

## 步驟

1. iPhone 用 USB 接 Windows（信任電腦）
2. 下載安裝 [Sideloadly](https://sideloadly.io/)
3. 開啟 Sideloadly → 選裝置
4. Apple 欄填你的 **Apple ID**（建議與開發簽名同一帳號）
5. IPA 欄選 `CarANC-ios-kmp-debug.ipa`
6. Start → 等安裝完成
7. iPhone：**設定 → 一般 → VPN 與裝置管理** → 信任開發者
8. 開啟 **CarANC**

## 從 GitHub 取得

```text
Mac 推送後：
  dist/CarANC-ios-kmp-debug.ipa
  dist/WINDOWS_INSTALL_SIDELOADLY.md
```

Windows：`git pull` 或下載 Release，再依上面 Sideloadly 步驟。

## 若安裝失敗

- 換線 / 重插 / 信任電腦
- Apple ID 開「允許安全性」App 密碼（若需要）
- 免費帳號裝置數達上限 → 移除舊開發 App
- 過期：回 Mac 重編 ipa 再裝

## 本包內容（KMP 共用 DSP）

- App 版本：1.1.0 (10)
- 內含 `CarANCShared.framework` = Android 同源 `MultiBandANCProcessor`
- Log：`dsp=kmp_MultiBandANCProcessor` / `audioBackend=AVAudioEngine_local+KMP`

