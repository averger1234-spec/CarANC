# CarANC 上架前檢查清單（Play Console）

對應建置：`store` flavor · `applicationId=com.caranc.app` · 版本見 `version.properties`

---

## A. 本機建置（已在專案支援）

- [x] `internal` / `store` flavor 分離
- [x] store 隱藏調校／測試腳本
- [x] store 關閉假訂閱解鎖
- [x] store 啟動強制 FREE（公開體驗）
- [x] upload keystore + `keystore.properties`（本機 gitignored）
- [x] `targetSdk` / `compileSdk` = 35
- [x] 明確移除 Advertising ID 權限
- [x] 隱私／條款 GitHub URL + App 內文
- [ ] 你已**離線備份** `upload-keystore.jks` + `secrets/UPLOAD_KEY_BACKUP.txt`
- [ ] `.\scripts\bundle-store-release.ps1` 產出 AAB 成功
- [ ] 實機安裝 store debug/release：權限、開始/停止 ANC、安全聲明

```bat
scripts\bundle-store-release.bat
.\scripts\install-debug.ps1 -Flavor store
```

---

## B. Google 帳號與 App 建立

- [ ] 註冊 [Play Console](https://play.google.com/console)（一次性約 USD 25）
- [ ] 完成身分驗證
- [ ] 建立應用程式
  - 名稱：`CarANC`
  - 預設語言：中文（台灣）或英文
  - App / 遊戲：App
  - 免費 / 付費：**免費**（公開體驗；日後可加訂閱）
  - 宣告：不含廣告（目前）

---

## C. 商店資訊（可直接貼上）

### 短說明（≤80 字元建議）

```
手機麥克風收音、即時反相降噪，搭配 Android Auto 車機喇叭。
```

### 完整說明

見 `ProductCatalog.STORE_FULL_DESCRIPTION`（或下方摘要）：

- 多頻帶軟體 ANC、車廂校正、情境模式
- **公開免費體驗：輕度降噪**
- 安全聲明：不可取代駕駛注意力
- 權限：麥克風／定位／前景服務（本機處理、不上傳音訊）

### 圖形資產（需你製作／匯出）

| 資產 | 規格 | 狀態 |
|------|------|------|
| App 圖示 | 512×512 PNG | 可用現有 launcher 放大精修 |
| 手機截圖 | 至少 2 張，建議 4–8 | 待拍：主畫面 Gauge、降噪中、方案頁、安全聲明 |
| 功能圖（選） | 1024×500 | 選填 |
| 示範影片（強烈建議） | YouTube 未公開即可 | 權限→開始降噪→通知→停止 |

### 分類與聯絡

- 應用程式類別：建議「汽車與交通」或「工具」
- 電子郵件：`support@caranc.app`
- 隱私權政策 URL：  
  `https://github.com/averger1234-spec/CarANC/blob/main/PRIVACY.md`
- 可選網站：暫無（可留空或 GitHub repo）

---

## D. 政策表單（必填）

### 1. 應用程式內容／分級

- 依問卷填寫（無暴力／無社交／無 UGC 等）

### 2. 目標對象

- 目標年齡：**18+** 或一般成人通勤（勿選兒童）
- 非專為兒童設計

### 3. 資料安全（Data safety）

詳見 `DATA_SAFETY.md`。重點：

- **不收集**可識別個資到伺服器（目前極致本機）
- 麥克風／位置：功能必要，**資料留在裝置**
- 無資料分享給第三方
- 無廣告 ID

### 4. 廣告

- 此 App **不含廣告**

### 5. 政府 App / 金融等

- 否

### 6. 金融功能 / 健康

- 否（勿宣稱醫療或聽力治療）

---

## E. 敏感權限聲明（審核重點）

| 權限 | Console 說明建議 |
|------|------------------|
| 麥克風 | 即時收音以產生反相聲波做車內 ANC；音訊不離裝置 |
| 位置（精準） | 取得車速以切換路噪模式；不上傳軌跡 |
| 前景服務（麥克風+位置） | 駕駛中持續降噪並顯示狀態通知 |

**強烈建議上傳示範影片**（1–2 分鐘）到「應用程式存取權限」相關欄位。

---

## F. Android Auto

- 專案含 Car App（IoT category）
- 若審核卡在 AA 品質：可先做「僅手機」版本再加回 AA（需另開變更）
- 首次建議：內部測試軌道先用真車／USB AA 自測

---

## G. 發布軌道建議

1. 上傳 AAB → **內部測試**（加入自己的 Gmail）
2. 安裝測試：冷啟動、權限拒絕、開始/停止、AA 斷線
3. 無崩潰後 → **封閉測試** 或直接 **正式版** 送審
4. 之後每次更新：改 `version.properties` 的 `VERSION_CODE` +1 → 重新 bundle → 先內部再正式

---

## H. 文案禁忌（避免拒審／下架）

- 不要保證「完全安靜／100% 消除噪音」
- 不要宣稱取代原廠安全系統或醫療器材
- 不要在未接 Billing 時寫「立即購買 NT$xx」可完成付款

---

## I. 你現在要做的人工步驟（無法由程式代勞）

1. 備份 keystore（`upload-keystore.jks` + `secrets/UPLOAD_KEY_BACKUP.txt`）
2. 註冊 Play Console + 建立 App
3. 拍截圖 + 示範影片
4. 上傳 AAB 到內部測試
5. 填 Data safety / 權限聲明
6. 送審

完成 A 的建置與本清單後，技術面即達「可送審候選」。
