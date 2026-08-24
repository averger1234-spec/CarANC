# 1.2.30 論壇音訊路徑（Android Auto + CarPlay）

對版：**Android `v1.2.30` / code 32** · **iOS `v1.2.30 (29)`**  
改版細節：[`CHANGELOG.md`](CHANGELOG.md) · CarPlay：[`iosApp/CARPLAY.md`](iosApp/CARPLAY.md)

Skoda Octavia 2019（MIB2）在 **USB Android Auto / CarPlay 沒低音、純藍牙有**，論壇與協定都對得上。1.2.30 不是放棄投影，是按那些做法繼續推 anti。

## 論壇／規格對照

| 來源 | 結論 | 我們怎麼用 |
|------|------|------------|
| Google AA 社群：Huawei + **Skoda Octavia** | AA flat / 沒 bass，藍牙正常 | 同車系已知病 |
| Drive2.ru VAG 線纜 | USB 常切 **40–50Hz 以下** | 路徑音 **50+80Hz**；只聽到 80Hz = 喇叭有出、50Hz 被刀 |
| r/AndroidAuto、XDA Wavelet | AA 沒 EQ；低架可補 | 送出前 **+~8 dB LF @ 90Hz** |
| AA 開發者選項 | **PCM** 比 AAC-LC 保真 | 手機自己設 PCM（App 改不了） |
| HUIG / open-android-auto | 音樂 = 48k 立體聲 MEDIA；語音 = 16k | mixp:0、不要 LOW_LATENCY |
| WWDC 2016 CarPlay | 有線 Main = **LPCM**；無線媒體 = **AAC-LC**；Alternate = 導航 | 有線優先；不要 voicePrompt |
| r/skoda、MacUser Octavia Columbus、VW Vortex | **CarPlay 同樣沒低音、比藍牙小聲** | 與 AA 同一病 |
| 我們 1.2.18 | 70Hz 送出 LPF 防沙 | 車機改 **220Hz**，70Hz 會把悶濾掉 |

## 這版程式（兩邊對齊）

| 項 | Android Auto | CarPlay |
|----|----------------|---------|
| 路徑音 | 50+80Hz | 同 |
| 艙麥 | `heard50` / `heard80` | `carplay_path_check` 同欄 |
| 測不到低音 | **仍送 anti** | **仍送 anti** |
| 低音架 + 送出 | shelf + 220Hz LPF | 同 |
| 音樂通道 | GAIN + STREAM_MUSIC 拉滿、mixp:0、48k mixer | `.default` + `longFormAudio` + Now Playing |
| 不要當導航 | 不用 LOW_LATENCY / 16k 語音埠 | 不用 voicePrompt、mixWithOthers、HFP |
| Pause | 不停 ANC | 不停 ANC（Stop 才停） |
| 自己關小聲 | focus duck 不再把 anti 打到 0.05 | secondary hint 不再打到 0.15 |

## 你車上要設（App 做不到）

**Android（Pixel + USB AA）**

1. Android Auto → 連點「版本」約 10 下 → 開發者 → **音訊編解碼 = PCM**
2. 暫停音樂、車機音量高、Bass 高；Adaptive Sound / GALA 關
3. Spotify Normalize / EQ 關
4. USB 仍插著 → **停止再開始**降噪

**iPhone + CarPlay**

1. **只用 USB 有線**（無線是 AAC）
2. 暫停音樂 → 開頭聽 50 或 80Hz 悶
3. anti 出聲時再轉車機音量（音樂／導航音量分開記）
4. iPhone 音樂 EQ / Sound Check 關；車機 Bass 高、GALA 關
5. 狀態頁必須是 **`v1.2.30 (29)`**（這台 Windows 編不出 IPA，需 Mac Xcode）

## 怎麼判 log

| 欄位 | 含義 |
|------|------|
| `heard50=true` | 艙裡有 50Hz，音樂 DAC 通 |
| `heard80=true` 且 50 沒起來 | 喇叭有出，車機刀了 ~50Hz；繼續用 80Hz 以上推悶 |
| 送到車機、艙麥都沒起來 | 仍送 anti；再查 PCM／有線／音量那一組 |
| 路由還在手機喇叭 / 沒有 carAudio | **停止再開始** |

Android log：`aa_path_check`、`AA_MIXER_ATTR`、`STREAM_MUSIC boost`  
iOS log：`carplay_path_check`、`audio_session_configured`（`longFormAudio`）
