# iOS CarPlay（對齊 Android Auto）

**本功能自 iOS 1.2.0 起**；現行全包 **iOS / Android 1.2.1**（含車速 hold／IMU 備用）。  
狀態頁應顯示 **`v1.2.6 (17)`**。

## 架構對照

| Android Auto | iOS CarPlay |
|--------------|-------------|
| `ANCAppService` + `CarAncAutoScreen` | `CarPlaySceneDelegate` + `CarPlayRootController` |
| `CarConnection` → `isAAConnected` | `CarAudioRouteMonitor`（`AVAudioSession` route） |
| `aaLinkType` = local / projection_submix / wireless_bt | `local` / `carplay_wired` / `carplay_wireless` / `carplay_unknown` |
| AA 斷線 → stop（導引中 keep log） | `carplay_disconnected` 同策略 |
| 車機：啟停 + 輕/中/高 | `CPListTemplate` 同操作 |
| 手機 DSP + 車機輸出 | KMP MultiBand + 優先車機 session |

## 已實作（本 repo）

- `App/AppController`：手機與 CarPlay 共用 model/engine  
- `Audio/CarAudioRouteMonitor`：路由分類 + session 偏好車機  
- `CarPlay/*`：模板 UI（狀態、啟動/停止、等級）  
- Log：`aaLinkType`、`wirelessAaSuspected`、`carplay_connected` / `carplay_disconnected`  
- 斷線：非導引測試中自動 `stopAnc()`  

## Apple 能力申請（車機 UI 必要）

CarPlay **畫面** 需要 entitlement：

```xml
<key>com.apple.developer.carplay-driving-task</key>
<true/>
```

1. [Apple Developer](https://developer.apple.com) → Identifiers → App ID `com.lutony.caranc.ios`  
2. 申請 **CarPlay Driving Task**（對齊 Android IOT / 非音樂播放器）  
3. 核准後在 `CarANC.entitlements` 打開上述 key  
4. Xcode 重新簽章 → 真機 + 車機 / 模擬器 CarPlay  

**未核准前：**

- App **可編譯、可本機路測**  
- 連上車機時仍會嘗試把輸出導向 `carAudio` 端口（路由層）  
- 車機主機 **不會** 顯示 CarANC 圖示（無 template entitlement）  
- 請用 **手機 UI** 啟停；狀態列會顯示 `aaLinkType` / CarPlay 連線  

## 路測 checklist

1. 手機先接受安全聲明  
2. USB 有線 CarPlay（優先於無線）  
3. 看狀態頁：`CarPlay 已連線 · carplay_*`  
4. 開始降噪 → log：`audioBackend=AVAudioEngine_carplay+KMP`、`aaLinkType=…`  
5. 斷開 CarPlay → 應自動停止（除非導引腳本進行中）  

## 模擬器

Xcode → I/O → External Displays → CarPlay（需有效 entitlement 才會進 template scene）。
