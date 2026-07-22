# 路測完整擷取 — 工程驗收清單（給開發者 / 下一輪 AI）

在告訴使用者「可以路測」之前，下列必須為真。

## A. 腳本與 session（不可讓路測中斷的因素）

| # | 檢查項 | 狀態 |
|---|--------|------|
| A1 | 引導腳本只有路噪一條；有效行駛秒數推進 | 已做 |
| A2 | finish **開始**時不 stop ANC | 已做 |
| A3 | `test_script_complete` 後 **先 auto-save** 再 stop service | 已做 |
| A4 | `endSession` **drain writer** 再銷毀（不 cancel 丟 queue） | 已做 |
| A5 | 存 log 優先 **>50KB 最近大檔**，非 1KB 重啟 stub | 已做 |
| A6 | 下載夾每次 **獨立檔名** `_saved_HHmmss` | 已做 |
| A7 | 路測開始 **強制 logging ON** | 已做 |
| A8 | AA 斷線且 guided active → **不立刻 stopSelf**（保 log） | 已做 |
| A9 | bump_detected **≥2s 節流**（避免 log 爆炸） | 已做 |

## B. 路測當下使用者畫面

1. 安全聲明已接受  
2. USB AA 已連  
3. 按「開始完整路測」  
4. 進度只在車速達標時走  
5. 結束見「已自動存檔」路徑  
6. 檔案總管：`Download/CarANC_Logs/*_saved_*.log` 應為 **MB 級**

## C. 仍屬「效果」非「完整性」（可之後調）

- lowBand 正 red 比例、bank 出力、boost 策略  
- 這些不應再造成「測不完整 / 沒 log」

## D. 本機驗證指令

```powershell
git pull origin main
.\gradlew.bat :shared:testDebugUnitTest --tests com.example.caranc.shared.MultiBandANCProcessorTest --tests com.example.caranc.shared.LiteratureAlgTest
.\gradlew.bat :app:assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```
