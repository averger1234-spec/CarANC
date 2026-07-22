package com.example.caranc.shared.test

import com.example.caranc.shared.AncSessionContext
import com.example.caranc.shared.GlobalAncSessionContext
import com.example.caranc.shared.commercial.ProductCatalog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Guided test state.
 *
 * Advance is **data-valid seconds**, not wall clock for drive steps:
 * only counts when speed ≥ minSpeed (red light / idle pause collection).
 */
data class GuidedTestState(
    val active: Boolean = false,
    val scriptId: String = "",
    val scriptName: String = "",
    val stepIndex: Int = 0,
    val totalSteps: Int = 0,
    val currentStep: TestScriptStep? = null,
    /** Wall-clock seconds on this step (always ticks). */
    val wallElapsedSec: Int = 0,
    /** Accumulated **valid** seconds (drive conditions met). */
    val validSec: Int = 0,
    /** Required valid (or wall for prep/finish) seconds to advance. */
    val targetValidSec: Int = 0,
    val remainingValidSec: Int = 0,
    /** True this second is counting as valid data. */
    val collectingNow: Boolean = false,
    /** UI reason when not collecting, e.g. 紅燈/車速不足. */
    val pauseReason: String = "",
    val currentSpeedKmh: Float = 0f,
    val minSpeedKmh: Float = 40f,
    val wallClockOnly: Boolean = false,
    val autoAdvance: Boolean = true,
    val completedStepIds: List<String> = emptyList(),
    val finished: Boolean = false,
    val userNote: String = "",
    // Back-compat aliases for older UI that read elapsedSec/targetDurationSec
    val elapsedSec: Int = 0,
    val targetDurationSec: Int = 0,
    val remainingSec: Int = 0
)

typealias GuidedTestEventSink = (phase: String, fields: Map<String, Any?>) -> Unit

object GuidedTestController {

    private val _state = MutableStateFlow(GuidedTestState())
    val state: StateFlow<GuidedTestState> = _state.asStateFlow()

    var eventSink: GuidedTestEventSink? = null

    private var sessionContext: AncSessionContext = GlobalAncSessionContext

    fun configure(sessionContext: AncSessionContext = GlobalAncSessionContext) {
        this.sessionContext = sessionContext
    }

    private var stepStartedAtMs: Long = 0L
    private var currentSteps: List<TestScriptStep> = CarRoadTuningScript.steps
    private var currentMonitoredPhases: List<String> = CarRoadTuningScript.monitoredLogPhases
    private var currentMonitoredFields: List<String> = CarRoadTuningScript.monitoredSnapshotFields
    private var autoAdvanceEnabled: Boolean = true

    /**
     * [autoAdvance]=true: advance when enough **valid drive seconds** collected
     * (not while red light / speed below threshold).
     */
    fun start(
        script: List<TestScriptStep> = CarRoadTuningScript.steps,
        scriptId: String = CarRoadTuningScript.SCRIPT_ID,
        scriptName: String = CarRoadTuningScript.SCRIPT_NAME,
        autoAdvance: Boolean = true
    ) {
        if (script.isEmpty()) return
        currentSteps = script
        autoAdvanceEnabled = autoAdvance
        currentMonitoredPhases = if (scriptId == CarRoadTuningScript.SCRIPT_ID) {
            CarRoadTuningScript.monitoredLogPhases
        } else {
            CarAncTestScript.monitoredLogPhases
        }
        currentMonitoredFields = if (scriptId == CarRoadTuningScript.SCRIPT_ID) {
            CarRoadTuningScript.monitoredSnapshotFields
        } else {
            CarAncTestScript.monitoredSnapshotFields
        }
        stepStartedAtMs = currentTimeMs()
        val first = script.first()
        val target = requiredSec(first)
        _state.value = GuidedTestState(
            active = true,
            scriptId = scriptId,
            scriptName = scriptName,
            stepIndex = 0,
            totalSteps = script.size,
            currentStep = first,
            wallElapsedSec = 0,
            validSec = 0,
            targetValidSec = target,
            remainingValidSec = target,
            minSpeedKmh = first.minSpeedKmh,
            wallClockOnly = first.wallClockOnly || first.id.contains("prep") || first.id.contains("finish"),
            autoAdvance = autoAdvance,
            elapsedSec = 0,
            targetDurationSec = target,
            remainingSec = target,
            finished = false
        )
        emit(
            phase = "test_script_start",
            fields = mapOf(
                "scriptId" to scriptId,
                "scriptName" to scriptName,
                "advanceMode" to if (autoAdvance) "valid_drive_sec" else "manual",
                "totalSteps" to script.size,
                "stepIds" to script.map { it.id },
                "monitoredLogPhases" to currentMonitoredPhases,
                "monitoredSnapshotFields" to currentMonitoredFields,
                "productName" to ProductCatalog.PRODUCT_NAME,
                "subscriptionPlan" to sessionContext.entitlementManager.currentPlan.id,
                "subscriptionLabel" to sessionContext.entitlementManager.currentPlan.displayName
            )
        )
        enterCurrentStep()
    }

    /**
     * Call once per second. Drive steps only accumulate when speed is high enough.
     * Red light / idle → pauseReason, no validSec++.
     */
    fun tickSecond() {
        val state = _state.value
        val step = state.currentStep ?: return
        if (!state.active || state.finished) return

        val sm = sessionContext.stateManager
        val speed = sm.vehicleSpeedKmh.value
        val speedValid = sm.vehicleSpeedValid.value
        val ancRunning = sm.state.value::class.simpleName.orEmpty().let {
            it != "Stopped" && it.isNotEmpty()
        }
        val wall = state.wallElapsedSec + 1
        val minSpd = step.minSpeedKmh
        val wallOnly = step.wallClockOnly || step.id.contains("prep", true) || step.id.contains("finish", true)

        val (countThisSec, reason) = when {
            wallOnly -> {
                // prep: prefer ANC already running; still count wall so we don't hang
                true to if (!ancRunning && step.id.contains("prep", true)) "等待 ANC 啟動中…" else "準備/結束（壁鐘）"
            }
            !speedValid -> false to "GPS 無效 · 不計有效秒"
            speed < minSpd -> false to "車速 ${"%.0f".format(speed)} < ${"%.0f".format(minSpd)}（紅燈/怠速不計）"
            step.requiresAncRunning && !ancRunning -> false to "ANC 未運行 · 不計有效秒"
            else -> true to "收集中 · 車速 ${"%.0f".format(speed)} km/h"
        }

        val valid = if (countThisSec) state.validSec + 1 else state.validSec
        val target = state.targetValidSec.coerceAtLeast(1)
        val remaining = (target - valid).coerceAtLeast(0)
        val maxWall = step.maxWallSec.coerceAtLeast(target * 2)

        _state.value = state.copy(
            wallElapsedSec = wall,
            validSec = valid,
            remainingValidSec = remaining,
            collectingNow = countThisSec,
            pauseReason = if (countThisSec) {
                if (wallOnly) reason else ""
            } else reason,
            currentSpeedKmh = speed,
            minSpeedKmh = minSpd,
            wallClockOnly = wallOnly,
            elapsedSec = valid, // progress bar uses valid
            targetDurationSec = target,
            remainingSec = remaining
        )

        if (!state.autoAdvance) return

        val reachedValid = valid >= target
        val hitMaxWall = wall >= maxWall
        if (reachedValid || hitMaxWall) {
            val note = when {
                reachedValid && wallOnly -> "auto_wall"
                reachedValid -> "auto_valid_drive"
                else -> "auto_max_wall_timeout"
            }
            completeCurrentStep(
                userNote = note,
                autoAdvanced = true,
                extraFields = mapOf(
                    "validSec" to valid,
                    "wallElapsedSec" to wall,
                    "targetValidSec" to target,
                    "maxWallSec" to maxWall,
                    "lastSpeedKmh" to speed,
                    "minSpeedKmh" to minSpd
                )
            )
        }
    }

    private fun requiredSec(step: TestScriptStep): Int {
        if (step.durationSec > 0) return step.durationSec
        return when {
            step.id.contains("prep", true) -> 20
            step.id.contains("finish", true) -> 10
            else -> 45
        }
    }

    fun completeCurrentStep(
        userNote: String = "",
        autoAdvanced: Boolean = false,
        extraFields: Map<String, Any?> = emptyMap()
    ) {
        val state = _state.value
        val step = state.currentStep ?: return
        val wallSec = ((currentTimeMs() - stepStartedAtMs) / 1000L).toInt().coerceAtLeast(0)

        emit(
            phase = "test_step_complete",
            fields = stepLogFields(step) + mapOf(
                "elapsedSec" to wallSec,
                "validSec" to state.validSec,
                "wallElapsedSec" to state.wallElapsedSec,
                "targetValidSec" to state.targetValidSec,
                "autoAdvanced" to autoAdvanced,
                "userNote" to userNote.trim(),
                "completedCount" to (state.completedStepIds.size + 1),
                "advanceMode" to if (state.wallClockOnly) "wall" else "valid_drive_sec"
            ) + extraFields
        )

        val completed = state.completedStepIds + step.id
        val nextIndex = state.stepIndex + 1
        val script = currentSteps

        if (nextIndex >= script.size) {
            _state.value = state.copy(
                active = false,
                finished = true,
                completedStepIds = completed,
                currentStep = null,
                validSec = 0,
                wallElapsedSec = 0,
                userNote = userNote
            )
            emit(
                phase = "test_script_complete",
                fields = mapOf(
                    "scriptId" to state.scriptId,
                    "completedSteps" to completed,
                    "totalSteps" to script.size,
                    "advanceMode" to "valid_drive_sec"
                )
            )
            return
        }

        val nextStep = script[nextIndex]
        val target = requiredSec(nextStep)
        val wallOnly = nextStep.wallClockOnly ||
            nextStep.id.contains("prep", true) ||
            nextStep.id.contains("finish", true)
        _state.value = state.copy(
            stepIndex = nextIndex,
            currentStep = nextStep,
            wallElapsedSec = 0,
            validSec = 0,
            targetValidSec = target,
            remainingValidSec = target,
            collectingNow = false,
            pauseReason = "",
            minSpeedKmh = nextStep.minSpeedKmh,
            wallClockOnly = wallOnly,
            autoAdvance = autoAdvanceEnabled,
            completedStepIds = completed,
            userNote = userNote,
            elapsedSec = 0,
            targetDurationSec = target,
            remainingSec = target
        )
        enterCurrentStep()
    }

    fun skipCurrentStep(reason: String = "") {
        val step = _state.value.currentStep ?: return
        emit(
            phase = "test_step_skipped",
            fields = stepLogFields(step) + mapOf("reason" to reason.ifBlank { "user_skip" })
        )
        completeCurrentStep(userNote = "skipped: $reason", autoAdvanced = false)
    }

    fun abort(reason: String = "user_abort") {
        val state = _state.value
        if (!state.active) return
        emit(
            phase = "test_script_abort",
            fields = mapOf(
                "scriptId" to state.scriptId,
                "atStepId" to (state.currentStep?.id ?: "unknown"),
                "completedSteps" to state.completedStepIds,
                "reason" to reason
            )
        )
        _state.value = GuidedTestState()
    }

    fun logStepSnapshot() {
        val st = _state.value
        val step = st.currentStep ?: return
        if (!st.active) return
        emit(
            phase = "test_step_snapshot",
            fields = stepLogFields(step) + runtimeMetrics() + mapOf(
                "validSec" to st.validSec,
                "wallElapsedSec" to st.wallElapsedSec,
                "targetValidSec" to st.targetValidSec,
                "collectingNow" to st.collectingNow,
                "pauseReason" to st.pauseReason,
                "minSpeedKmh" to st.minSpeedKmh,
                "advanceMode" to if (st.wallClockOnly) "wall" else "valid_drive_sec"
            )
        )
    }

    private fun enterCurrentStep() {
        val step = _state.value.currentStep ?: return
        stepStartedAtMs = currentTimeMs()
        val target = requiredSec(step)
        val wallOnly = step.wallClockOnly ||
            step.id.contains("prep", true) ||
            step.id.contains("finish", true)
        _state.value = _state.value.copy(
            targetValidSec = target,
            remainingValidSec = target,
            validSec = 0,
            wallElapsedSec = 0,
            collectingNow = false,
            pauseReason = if (wallOnly) "準備/結束" else "等待車速 ≥ ${"%.0f".format(step.minSpeedKmh)}",
            minSpeedKmh = step.minSpeedKmh,
            wallClockOnly = wallOnly,
            autoAdvance = autoAdvanceEnabled,
            elapsedSec = 0,
            targetDurationSec = target,
            remainingSec = target
        )
        step.suggestedTier?.let { sessionContext.tierManager.setTier(it) }
        emit(
            phase = "test_step_start",
            fields = stepLogFields(step) + mapOf(
                "durationSec" to step.durationSec,
                "targetValidSec" to target,
                "minSpeedKmh" to step.minSpeedKmh,
                "maxWallSec" to step.maxWallSec,
                "wallClockOnly" to wallOnly,
                "advanceMode" to if (autoAdvanceEnabled) {
                    if (wallOnly) "wall" else "valid_drive_sec"
                } else "manual",
                "requiresAncRunning" to step.requiresAncRunning,
                "suggestedTier" to (step.suggestedTier?.name ?: "unchanged"),
                "expectedLogPhases" to step.logPhases
            )
        )
        if (step.debugPresets.isNotEmpty()) {
            emit(phase = "debug_presets_apply", fields = step.debugPresets)
        }
        logStepSnapshot()
    }

    private fun stepLogFields(step: TestScriptStep): Map<String, Any?> {
        return mapOf(
            "scriptId" to _state.value.scriptId,
            "stepId" to step.id,
            "stepTitle" to step.title,
            "stepIndex" to _state.value.stepIndex,
            "totalSteps" to _state.value.totalSteps
        )
    }

    private fun runtimeMetrics(): Map<String, Any?> {
        val sm = sessionContext.stateManager
        val tm = sessionContext.tierManager
        return mapOf(
            "rawDb" to sm.rawDb.value,
            "cancelledDb" to sm.cancelledDb.value,
            "reductionDb" to (sm.rawDb.value - sm.cancelledDb.value).coerceAtLeast(0f),
            "vehicleSpeedKmh" to sm.vehicleSpeedKmh.value,
            "vehicleSpeedValid" to sm.vehicleSpeedValid.value,
            "dominantNoiseBand" to sm.dominantNoiseBand.value,
            "tier" to tm.currentTier.value.name,
            "ancState" to sm.state.value::class.simpleName.orEmpty(),
            "estimatedLatencyMs" to sm.estimatedLatencyMs.value,
            "maxCancelFrequencyHz" to sm.maxCancelFrequencyHz.value,
            "latencyMidEnabled" to sm.latencyMidEnabled.value,
            "latencyHighEnabled" to sm.latencyHighEnabled.value,
            "latencyRecordMs" to sm.latencyRecordMs.value,
            "latencyTrackMs" to sm.latencyTrackMs.value,
            "latencyBlockMs" to sm.latencyBlockMs.value
        )
    }

    private fun emit(phase: String, fields: Map<String, Any?>) {
        eventSink?.invoke(phase, fields)
    }
}

internal expect fun currentTimeMs(): Long
