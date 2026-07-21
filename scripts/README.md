# CarANC 路測快速迭代小工具（給自己用）

這些腳本是為了讓你「改程式 → 裝到手機 → 測試 → 拿 log 回來分析」這一整套動作變快很多。

注意：腳本輸出訊息已改為英文（純 ASCII），以避免在繁體中文 Windows 上因編碼問題導致 PowerShell 解析錯誤（missing }）或閃退。功能完全相同。

## 準備工作（只做一次）

1. 手機開啟「開發人員選項」（連續點擊「版本號」7次）。
2. 在開發人員選項裡開啟：
   - USB 偵錯
   - **無線偵錯**（非常推薦，之後幾乎不用插線）
3. 電腦上要有 adb（Android Studio 內建，通常已經可用）。

測試 adb 是否好用：
- 開 PowerShell 或終端機
- 輸入 `adb devices`
- 手機上允許偵錯後，應該會看到你的裝置。

## 三個主要工具

### 0. start-dhu.ps1 （電腦當 Android Auto 車機）

**用途**：沒有實車時，用 **Desktop Head Unit (DHU)** 讓 **Windows PC 扮演 AA 頭單元**，手機 USB 接電腦走**真正的 Android Auto 協議**（不是 App 內假開關）。

**怎麼用**：
1. 手機 USB 接電腦，開啟 USB 偵錯，`adb devices` 要看到 `device`。
2. 手機安裝 **Android Auto** → 連點版本進開發者設定 → 開 **未知來源** → **啟動 head unit 伺服器**。
3. 雙擊 `scripts\start-dhu.bat`，或：
   ```
   .\scripts\start-dhu.ps1
   ```
4. 電腦會跳出 DHU 視窗（= 假車機）。App 裡 connectionType 可填 `dhu` 或 `usb_aa`。
5. 若尚未安裝 DHU，腳本會用 sdkmanager 裝 `extras;google;auto`；也可只裝：
   ```
   .\scripts\start-dhu.ps1 -InstallOnly
   ```

**驗證 log**：應出現 `aa_connected`、`aaConnected=true`、`audioBackend=AUDIOTRACK_AA_SUBMIX`。

官方說明：https://developer.android.com/training/cars/testing/dhu

### 1. install-debug.ps1 （快速把新程式裝到手機）

**用途**：你改完 code 後，一鍵把最新版本蓋到手機上。

**怎麼用**（最簡單）：
1. 先 `git pull` 確保拿到最新 code（很重要，拿到修正後的 .bat）。
2. **直接在檔案總管雙擊** `scripts\install-debug.bat` （最推薦，會自動處理編碼 + bypass + 暫停）
   - 或者用 AS Terminal / 開 PowerShell 輸入：
     ```
     .\scripts\install-debug.ps1
     ```
3. 等它跑完（會自動編譯 + adb 安裝到手機）。
4. 手機上的 App 就變成最新版了。

跑完視窗會暫停，按任意鍵關閉。

### 2. pull-latest-log.ps1 （直接把手機上的 log 拉到電腦）

**用途**：跑完路測後，不用手動上傳 Google Drive。腳本會自動把最新的測試 log 複製到你電腦的 `log/` 資料夾。

**怎麼用**（最簡單）：
1. 手機跑完測試後（記得按「完成這步」或匯出）。
2. **直接在檔案總管雙擊** `scripts\pull-latest-log.bat` （最推薦）
   - 或者用 AS Terminal / 開 PowerShell 輸入：
     ```
     .\scripts\pull-latest-log.ps1
     ```
3. 它會自動從手機拉最新 log 到本機 `log/` 資料夾。
4. 完成後 log 就在你電腦的 `log` 資料夾裡。

**這對分析最有幫助**：
- log 到了你電腦的 `log/` 資料夾後，你可以直接在這裡跟我說：
  > 「看一下最新的 log」或「分析 log/ 裡最新的檔案」
- 我可以直接打開讀取那個檔案來分析，不用你再上傳到 Google Drive 給我下載。

## 更簡單的雙擊方式（推薦新手）

如果你雙擊 .ps1 檔案會直接閃退，或出現亂碼 / 不是內部命令的錯誤（這是因為中文在 Windows 傳統中文系統的 cmd / PowerShell 編碼衝突，導致解析錯誤如 "Missing closing '}' "），我已經幫你準備了修正版的 **.bat 啟動器** + 腳本本身已改為純英文輸出。

- 雙擊 `scripts\install-debug.bat` → 會自動切換到 UTF-8 (chcp 65001) + 用 PowerShell bypass 執行 + 暫停
- 雙擊 `scripts\pull-latest-log.bat` → 同上

直接用 .bat 就好。如果還是報錯，請先 `git pull` 拿到最新版本。

（腳本訊息現在是英文，但功能完全一樣。）

## 如果你還是不想用 adb / 腳本？

沒關係！我同時改了 App 裡的按鈕。

在「測試平台」分頁，現在有兩個按鈕：

- **分享 Log**：原本的功能，可以直接選 Google Drive 上傳。
- **儲存到「下載 / CarANC_Logs」**（新功能，推薦）：
  - 按下去後，log 會固定複製到手機的「下載」資料夾裡一個叫 **CarANC_Logs** 的資料夾。
  - 位置很明確，你用檔案總管或 Google Drive App 很容易找到，然後手動上傳。

這樣至少「上傳 log」這一步會比較好找，不用每次都在一堆檔案裡翻。

## 完整新流程建議（白話版）

1. 你在電腦改程式。
2. 雙擊 `install-debug.bat` → 新版立刻裝到手機。
3. 拿手機出去路測，跑測試腳本。
4. 回來後雙擊 `pull-latest-log.bat`，log 直接到本機 `log/` 資料夾（或用 App 按「儲存到 CarANC_Logs」）。
5. 告訴我「看最新的 log」，我可以直接讀你本機的檔案分析。
6. 我改完 code 推 GitHub，你 `git pull` + 再雙擊 install-debug.bat。
7. 重複。

這樣中間的手動傳檔案動作大幅減少。

---

有任何一步卡住（例如 adb 連不到、手機找不到 log、腳本報錯），直接把錯誤訊息貼給我，我再幫你調整。

這些工具只為了讓「你測試、我分析、你再改」的循環更快，不是一定要用。想繼續用原本的 Drive 方式也完全可以，我會配合你。