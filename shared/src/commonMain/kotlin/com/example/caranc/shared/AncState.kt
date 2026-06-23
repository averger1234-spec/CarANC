package com.example.caranc.shared

/**
 * 使用構造函數傳遞 message，避免 abstract val 可能產生的初始化問題
 */
sealed class AncState(val message: String) {
    class Calibrating(msg: String = "收音校正中...請保持安靜") : AncState(msg)
    class Running(msg: String = "降噪中...") : AncState(msg)
    class DrivingMode(msg: String = "路噪降噪中（行駛中）...") : AncState(msg)
    class MusicMode(msg: String = "底噪降噪中（音樂播放）...") : AncState(msg)
    class Paused(msg: String = "底噪降噪中（通話中）...") : AncState(msg)
    class Learning(msg: String = "學習中...請保持安靜") : AncState(msg)
    class Error(msg: String) : AncState(msg)
    class Stopped(msg: String = "已停止") : AncState(msg)
}
