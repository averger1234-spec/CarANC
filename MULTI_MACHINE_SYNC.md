# CarANC 多機開發同步指南（Windows A/B + Mac）

專案已上傳 GitHub：https://github.com/averger1234-spec/CarANC.git

**核心原則**：任何修改（包含 AI 在這邊幫你改的）都必須透過 Git commit + push，其他機器用 git pull 取得。**不會自動跨機器同步**。

## 同一台電腦的 Android Studio 會自動看到修改嗎？

**不會完全自動同步**。

- 我在這裡（Grok 工作區）用工具直接修改檔案 → 磁碟上的原始碼**已經更新**（例如剛剛修正的 AudioEngine tier 問題）。
- 如果你**同一台 Windows** 的 Android Studio 正開著 `C:\Users\LENO\AndroidStudioProjects\CarANC` 這個資料夾：
  1. 在 Android Studio 執行：
     - **File → Reload All from Disk**（最快）
     - 或右鍵專案資料夾 → **Synchronize**
  2. 如果改到 `build.gradle.kts`、`settings.gradle.kts` 或 Gradle 相關：
     - 點擊右上角的 **Sync Project with Gradle Files**（大象圖示）
  3. 建議再做一次 **Build → Clean Project** → **Rebuild Project**。
- 在 AS 內的 Terminal 可以直接執行 `git status` 確認改動。
- **最佳習慣**：我改完後，你直接在 AS Terminal 執行下面三行：
  ```powershell
  git pull origin main
  git status
  # 然後 Reload / Sync Gradle
  ```

## 第二台 Windows 電腦完整設定步驟

1. **安裝軟體**（如果還沒裝）
   - Git for Windows：https://git-scm.com/download/win （安裝時選 "Use Git from the command line"）
   - Android Studio（版本盡量跟你第一台一樣）

2. **Clone 專案**（只要做一次）
   開 PowerShell 或 Android Studio Terminal，執行：
   ```powershell
   git clone https://github.com/averger1234-spec/CarANC.git
   cd CarANC
   ```

3. **用 Android Studio 開啟**
   - File → Open... → 選擇剛 clone 出來的 `CarANC` 資料夾。
   - 第一次會花比較久時間 Sync Gradle（下載依賴）。
   - 等待右下角 Gradle 完成。

4. **設定 Git 身份**（這台電腦第一次要做）
   ```powershell
   git config --global user.name "你的名字"     # 例如 avergerlu
   git config --global user.email "你的信箱"   # 例如 averger1234@gmail.com
   git config --global credential.helper manager   # 讓之後 push/pull 比較方便（瀏覽器或 Windows Credential Manager 記住登入）
   ```

5. **日常開發流程（每次都要做）**

   **開始工作前（最重要）**：
   ```powershell
   git pull origin main
   ```
   拉完後在 AS 裡 **Reload All from Disk** + **Sync Project with Gradle Files**。

   **改完程式後**：
   ```powershell
   git add .
   git commit -m "描述修改，例如：修正 tier 白噪音 + 無聲 + 閃退問題"
   git push origin main
   ```

6. **第一次 push / pull 可能遇到的認證**
   - Git 會開瀏覽器讓你用 GitHub 帳號 (averger1234-spec) 登入。
   - 如果要輸入密碼：用 **Personal Access Token (PAT)**，不要用 GitHub 登入密碼。
     - GitHub → Settings → Developer settings → Personal access tokens → Tokens (classic) → Generate new token（勾 repo 權限）。
   - 建議永遠用 `credential.helper manager`（上面已設定）。

## Mac 設定步驟（開發 iOS framework + 測試）

1. **安裝**：Git + Android Studio（或 Xcode 命令列工具）。
2. **Clone**（同 Windows）：
   ```bash
   git clone https://github.com/averger1234-spec/CarANC.git
   cd CarANC
   ```
3. **設定 Git 身份**（同上）。
4. **日常 pull**：
   ```bash
   git pull origin main
   ```
5. **建置 iOS Framework**（只有 Mac 能做）：
   - 開 Terminal 在專案根目錄。
   - 模擬器測試（最常用）：
     ```bash
     ./gradlew linkDebugFrameworkIosSimulatorArm64
     ```
   - 真機（iPhone）：
     ```bash
     ./gradlew linkDebugFrameworkIosArm64
     ```
   - Framework 位置通常在：
     `shared/build/bin/iosSimulatorArm64/debugFramework/CarANC.framework`
     （或 releaseFramework）
6. **在 Xcode 建立最小測試 App**（讓朋友或你自己用 iPhone 跑）：
   - 開 Xcode → Create a new Xcode project → iOS → App。
   - 專案設定：
     - Team：用你的 Apple ID（免費開發者帳號即可個人測試）。
     - Bundle Identifier：自訂（例如 com.yourname.CarANCTest）。
     - Signing：Automatically manage signing。
   - 把 framework 拖進專案：
     - 在 Project Navigator 右鍵專案 → Add Files... → 選擇上面產生的 `.framework`。
     - 在 **General** → **Frameworks, Libraries, and Embedded Content** 把 CarANC.framework 設為 **Embed & Sign**。
   - 在 Swift 檔案使用（範例）：
     ```swift
     import CarANC
     // 使用 iOS 端的 session context stub
     let ctx = IosGlobalAncSessionContext()   // 或你實作的對應名稱（看 iosMain 的暴露內容）
     // 之後可呼叫相關方法（目前主要是 stub，完整 audio loop 仍在 Android）
     ```
   - 連上 iPhone（用 USB），選對的裝置 → Run。
   - 免付費帳號限制：只能在你自己的已信任裝置上安裝，7 天後要重新 build（正常現象）。

7. **給朋友測試 iPhone（推薦來源碼方式）**
   - 把 GitHub repo 連結給朋友。
   - 朋友用**自己的 Mac** clone 下來（不要給你打包的 framework，簽章會錯）。
   - 朋友用自己的免費 Apple ID 在 Xcode 簽章 + 用自己的 iPhone 跑。
   - 你改 shared 程式碼 → git push → 朋友 git pull → 重新 link framework → Run。

## 推薦跨平台開發習慣（三台機器：Windows A/B + Mac）

| 機器          | 主要負責               | 每次開始前          | 改完重要功能後                  | 特殊注意事項                     |
|---------------|------------------------|---------------------|----------------------------------|----------------------------------|
| Windows A（這台） | Android + AI 幫改     | git pull           | git add . + commit + push       | 改完立刻 push 給其他人          |
| Windows B     | Android 開發           | git pull           | git add . + commit + push       | 同上                            |
| Mac           | iOS framework + Xcode 測試 | git pull        | git add . + commit + push       | 改完 shared 後要 re-link framework |

**鐵律**：
- 任何一台開始工作前 → 一定要 `git pull origin main`
- 改動後 → 立刻 commit + push（描述清楚）
- 不要一次改超多檔案再 push（容易衝突）
- `.gitignore` 已排除 build/、.idea/、.kotlin/、log/ 等，請不要手動 commit 它們。

## 常見問題處理

- **衝突（conflict）**：
  ```bash
  git pull
  # 如果有衝突，編輯衝突檔案（<<<<<<< 標記）
  git add 衝突的檔案
  git commit -m "解決衝突"
  git push
  ```

- **AS 裡看不到新檔案/改動**：
  File → Reload All from Disk，或右鍵專案 → Synchronize + Sync Project with Gradle Files。

- **想切分支開發大功能**（進階）：
  ```bash
  git checkout -b feature/tier-improvement
  # 改完
  git push -u origin feature/tier-improvement
  # 之後在 GitHub 開 PR 或直接 merge 回 main
  ```

## Grok CLI / AI 助手 跨機器使用注意事項

**Grok 的對話紀錄不會自動跨機器同步**。

Grok CLI（或 Android Studio 內建的 Grok Terminal 助手）的對話歷史、session 紀錄是**儲存在本地機器**（通常在 `~/.grok/sessions/` 或類似路徑）。

- 在另一台電腦（Windows B 或 Mac）開啟 Grok 時，會是**全新 session**，不會自動看到這台機器的完整對話歷史。
- **程式碼**靠 Git 同步（上面已說明）。
- **AI 對話上下文**要手動帶過去。

### 推薦做法
1. 每次在**新機器**第一次開 Grok 時：
   - 先 `git pull origin main`（拿到最新文件）
   - 讀取 `MULTI_MACHINE_SYNC.md`
   - 把 `GROK_RESUME_CONTEXT.md` 裡的 Resume Context 區塊**整段複製貼上**給 Grok 作為開頭提示。
2. 我會在需要時幫你更新 `GROK_RESUME_CONTEXT.md`，讓你用 Git 同步帶過去。

這樣在任何一台機器開 Grok 都能快速恢復目前專案狀態與進行中的任務。

- **iOS 目前狀態**：只有 stub（Android 完整實作，包含完整 ANC + tier + manual RPM）。Bluetooth OBD 已完全移除（Android 端只剩 manual RPM 測試支援）。建 framework 成功後，在 Xcode 裡主要用來驗證 KMP 整合 + 未來擴充 iOS audio 迴路（目前 process() 回傳 silence）。

## 如何讓朋友測試 Android 版（不用 ADB）

因為 Android 測試不需要 Xcode 簽章，分享 APK 檔案是最簡單的方式。

**推薦流程（你 build，朋友安裝）：**

1. 在你的開發機（Windows 或 Mac）用 Android Studio build debug APK：
   - Build > Build Bundle(s)/APK(s) > Build APK(s)
   - 等待完成，APK 位置通常在：
     `app/build/outputs/apk/debug/app-debug.apk`

2. 建議重新命名成有意義的名稱，例如 `CarANC-test-20240624-debug.apk`

3. 上傳到 Google Drive（或 GitHub Releases、Telegram 等容易下載的地方）。

4. 給朋友下載連結 + 以下安裝說明：

**朋友安裝步驟（Android）：**
- 下載 APK 檔案。
- 在手機上開啟「設定」 > 「應用程式」 > 「特殊應用程式存取」 > 「安裝未知應用程式」，允許你的瀏覽器或「檔案」App。
- （Android 13+ 可能在下載完成時直接提示「安裝」）。
- 點擊下載的 APK 開始安裝。
- 安裝時會要求權限：麥克風（必要）、位置（GPS 路噪用）、通知等。
- 安裝完成後開啟 App。
- 如果看到「開發用方案切換」面板（debug build 會有），可以用它切換到「專業」然後選「重度」測試完整功能。
- 測試時可以開啟引導測試（GuidedTestPanel），或手動開始降噪。
- 測試完想給回饋：App 內的 TestLogPanel 有「匯出 Log」按鈕，可以分享 log 檔給你。

**注意事項：**
- 請朋友先解除安裝任何舊版本的 CarANC（設定 > 應用程式 > CarANC > 解除安裝），避免快取問題。
- 因為 OBD 已移除，朋友的手機不需要藍牙特殊權限。
- AA 測試：朋友需要有支援 Android Auto 的車機/頭單元，把手機用 USB 連上車。
- Debug APK 會有 "debug" 標記，效能略低，但功能完整。
- 如果想給朋友 release-like 體驗（不顯示 dev panel），可以臨時修改 CommercialPanel.kt 把 if (BuildConfig.DEBUG) 改成 true，或建 release APK 但手動開啟 dev 功能（較複雜）。

**替代方案：**
- **朋友自己 build**：給 GitHub repo 連結，讓朋友用 Android Studio 開啟，接 USB 手機直接 Run（AS 會自動用 ADB 安裝，朋友不需要打指令）。
- **更專業分發**：設定 Firebase App Distribution（免費），上傳 APK 後加朋友 email，他們會收到安裝連結，支援自動更新，體驗像正式 App。

這樣可以讓朋友遠端測試，不需要你用 ADB 連他們的手機。

更新後，朋友測試時如果遇到 AA 永遠媒體模式的問題，可以請他們在 TestLogPanel 開啟「強制正常模式」來比較不同 tier 的差異。

## 相容性注意：舊車機 / Android 8.1 頭單元測試

如果你朋友的車機是 BC-8000（Android 8.1 aftermarket 頭單元）：

**可以測試，主要方式是手機 + AA 連頭單元：**
- App 安裝在**手機**（手機 Android 需 >= 8.0，minSdk=26）。
- BC-8000 作為 Android Auto 的顯示/音訊輸出端。
- 大多數此類 aftermarket 頭單元（BC-8000 系列常見）都支援手機 Android Auto 投影。
- OBD 已移除，不需要頭單元或手機額外藍牙權限。

**測試步驟（給朋友）：**
1. 你傳 debug APK 給朋友。
2. 朋友手機安裝（允許未知來源）。
3. 用支援資料傳輸的 USB 線把手機連到 BC-8000。
4. 在 BC-8000 頭單元切換到「Android Auto」模式（可能需要先在設定裡啟用 AA）。
5. 手機上允許 Android Auto 連線。
6. 開 CarANC App → 用 CommercialPanel 切專業 + 重度 → 開始降噪。
7. 建議開啟 TestLogPanel 的「強制正常模式」來測試不同 tier 的實際差異（否則 AA 常讓 isMusicActive 永遠 true，強制 floor mode，tier 差異不明顯）。
8. 測試中可用引導測試或手動觀察降噪效果（路噪、音樂等）。

**可能限制 / 注意：**
- Media 參考捕捉（media ref）在 AA 環境下通常不可用（capture_config_unavailable），這是已知行為（log 裡常出現），程式有 fallback，不影響基本 ANC。
- 舊頭單元 AA 協議可能較舊，音訊路由 latency 可能比現代車機高（app 內 latency_optimization 會處理）。
- GPS 精準度：用手機 GPS，車內可能受影響，但 fused 會盡力。
- 頭單元本身 mic/speaker 品質未知（app 預設用手機 mic + AA 輸出到車機喇叭）。
- 如果想直接在頭單元跑 App（sideload APK 到 BC-8000）：技術上可行（它是 Android 8.1），但 app 設計重點是手機 mic + AA 模式，頭單元 standalone 可能 mic 位置/品質不理想，且頭單元通常 24h 供電，耗電/發熱要注意。建議先試手機 + AA 模式。
- 權限：手機端需麥克風 + 位置（GPS 路噪）。
- 測試前務必讓朋友解除安裝舊版 CarANC。

**如果測試遇到問題：**
- 請朋友用 TestLogPanel 匯出 log 檔傳你。
- 常見 log phase：aa_connected、audio_init（會顯示 tier、route）、running_snapshot（tier、mode、reductionDb）。
- 如果 AA 連不上或 routing 怪，可能是頭單元 AA 支援版本問題（試換 USB 線或重啟頭單元/手機）。

總結：**可以測試**，預期行為跟現代車機類似（只是 latency 可能稍高、media ref 不可用）。先用手機 + AA 連 BC-8000 測試就好。

記得在給朋友的測試版 APK 裡，TestLogPanel 的 force normal 開關要強調使用，這樣才能好好比較 LIGHT / STANDARD / PRO 的差異。

---

我已經把**剛剛的 tier 修正**（pre-start 閃退、高 tier 無聲、LIGHT 白噪音）commit 並 push 到 GitHub main 了（commit 500027d）。

**現在你可以**：
- 在**這台** AS 做 Reload + Sync Gradle 確認改動。
- 在**其他機器**執行 `git pull origin main` 拿到最新修復。

需要我再幫你：
- 更新 README.md 放簡短說明？
- 寫一個最小 Xcode 測試專案的詳細截圖式步驟？
- 處理特定衝突或 branch 範例？

直接說下一步！現在三台機器都可以用 Git 順利同步開發了。✅