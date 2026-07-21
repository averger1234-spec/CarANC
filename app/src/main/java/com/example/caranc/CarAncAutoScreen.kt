package com.example.caranc

import android.content.Intent
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.example.caranc.shared.AncState
import com.example.caranc.shared.AncSessionContext
import com.example.caranc.shared.GlobalAncSessionContext
import com.example.caranc.shared.UserTier
import com.example.caranc.shared.service.ANCService
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * #11: Android Auto / Car App Library surface (template host).
 *
 * This is **not** a full AAOS OEM RNC ECU app. It runs on the phone projection host
 * (or template-compatible car head unit) and starts the phone-side ANCService.
 * True AAOS-native exclusive audio + vehicle HAL sensors would need a separate
 * automotive product module with OEM privileges — documented in MULTI_MACHINE_SYNC.md.
 */
class CarAncAutoScreen(carContext: CarContext) : Screen(carContext), DefaultLifecycleObserver {

    private val sessionContext: AncSessionContext = GlobalAncSessionContext

    private var currentState: AncState = AncState.Stopped()
    private var currentRawDb: Float = 0f
    private var currentCancelledDb: Float = 0f
    private var currentTier: UserTier = UserTier.LIGHT

    init {
        lifecycle.addObserver(this)
    }

    override fun onCreate(owner: LifecycleOwner) {
        super.onCreate(owner)

        lifecycleScope.launch {
            sessionContext.stateManager.state.collectLatest { newState ->
                currentState = newState
                invalidate()
            }
        }

        lifecycleScope.launch {
            sessionContext.tierManager.currentTier.collectLatest { tier ->
                currentTier = tier
                invalidate()
            }
        }

        lifecycleScope.launch {
            sessionContext.stateManager.rawDb.collectLatest { db ->
                currentRawDb = db
                invalidate()
            }
        }

        lifecycleScope.launch {
            sessionContext.stateManager.cancelledDb.collectLatest { db ->
                currentCancelledDb = db
                invalidate()
            }
        }
    }

    override fun onGetTemplate(): Template {
        val canStop = currentState !is AncState.Stopped
        val actionTitle = if (canStop) "停止降噪" else "啟動降噪"

        val mainAction = Action.Builder()
            .setTitle(actionTitle)
            .setOnClickListener {
                if (canStop) {
                    stopAncService()
                } else {
                    startAncService()
                }
            }
            .build()

        val statusText = when (val s = currentState) {
            is AncState.Calibrating -> s.message
            is AncState.Learning -> s.message
            is AncState.Running -> "降噪中 [${getTierLabel(currentTier)}] (-${"%.1f".format(currentRawDb - currentCancelledDb)} dB)"
            is AncState.DrivingMode -> s.message
            is AncState.MusicMode -> s.message
            is AncState.Paused -> s.message
            is AncState.Error -> "錯誤: ${s.message}"
            is AncState.Stopped -> s.message
        }

        val row = Row.Builder()
            .setTitle("車內主動降噪系統")
            .addText(statusText)
            .addText("原始: ${"%.1f".format(currentRawDb)} dB | 處理後: ${"%.1f".format(currentCancelledDb)} dB")
            .addText("建議：USB 有線 AA（非無線）。AA 高延遲時走 FF_PREVIEW + 預訓 bank。")
            .build()

        val noteRow = Row.Builder()
            .setTitle("架構說明")
            .addText("本畫面 = Car App 投影模板，非原廠 AAOS ECU RNC。")
            .addText("本機車庫測：手機喇叭用 AAudio-like 低延遲路徑。")
            .build()

        val paneBuilder = Pane.Builder()
            .addRow(row)
            .addRow(noteRow)
            .addAction(mainAction)

        val actionStrip = ActionStrip.Builder()
            .addAction(createTierAction("輕", UserTier.LIGHT))
            .addAction(createTierAction("中", UserTier.STANDARD))
            .addAction(createTierAction("高", UserTier.PRO))
            .build()

        return PaneTemplate.Builder(paneBuilder.build())
            .setHeaderAction(Action.APP_ICON)
            .setActionStrip(actionStrip)
            .setTitle("CarANC")
            .build()
    }

    private fun createTierAction(text: String, tier: UserTier): Action {
        val isSelected = currentTier == tier
        return Action.Builder()
            .setTitle(if (isSelected) "● $text" else text)
            .setOnClickListener {
                sessionContext.tierManager.setTier(tier)
            }
            .build()
    }

    private fun getTierLabel(tier: UserTier) = when (tier) {
        UserTier.LIGHT -> "輕"
        UserTier.STANDARD -> "中"
        UserTier.PRO -> "高"
    }

    private fun startAncService() {
        val intent = Intent(carContext, ANCService::class.java)
        carContext.startForegroundService(intent)
    }

    private fun stopAncService() {
        val intent = Intent(carContext, ANCService::class.java)
        carContext.stopService(intent)
    }
}
