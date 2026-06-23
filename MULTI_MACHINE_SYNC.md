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