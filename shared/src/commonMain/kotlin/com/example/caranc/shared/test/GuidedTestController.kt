package com.example.caranc.shared.test

import com.example.caranc.shared.AncSessionContext
import com.example.caranc.shared.GlobalAncSessionContext
import com.example.caranc.shared.commercial.ProductCatalog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class GuidedTestState(
    val active: Boolean = false,
    val scriptId: String = "",
    val scriptName: String = "",
    val stepIndex: Int = 0,
    val totalSteps: Int = 0,
    val currentStep: TestScriptStep? = null,
    val elapsedSec: Int = 0,
    /** Target seconds for this step (timed auto-advance). */
    val targetDurationSec: Int = 0,
    /** Seconds left before auto next step (target − elapsed). */
    val remainingSec: Int = 0,
    /** When true, tickSecond advances steps when remaining hits 0. */
    val autoAdvance: Boolean = true,
    val completedStepIds: List<String> = emptyList(),
    val finished: Boolean = false,
    val userNote: String = ""
)

typealias GuidedTestEventSink = (phase: String, fields: Map<String, Any?>) -> Unit

object GuidedTestController {

    private val _state = MutableStateFlow(GuidedTestState())
    val state: StateFlow<GuidedTestState> = _state.asStateFlow()

    var eventSink: GuidedTestEventSink? = null

    // Support obtaining from provided AncSessionContext (instead of direct singletons inside test controller).
    // Callers (e.g. panels) can configure for scoped. Default global for backward.
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
     * Start script. [autoAdvance]=true (default): each step runs for durationSec then auto-next;
     * user only needs Start + Save log (Abort optional).
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
        val target = effectiveDurationSec(first)
        _state.value = GuidedTestState(
            active = true,
            scriptId = scriptId,
            scriptName = scriptName,
            stepIndex = 0,
            totalSteps = script.size,
            currentStep = first,
            elapsedSec = 0,
            targetDurationSec = target,
            remainingSec = target,
            autoAdvance = autoAdvance,
            finished = false
        )
        emit(
            phase = "test_script_start",
            fields = mapOf(
                "scriptId" to scriptId,
                "scriptName" to scriptName,
                "advanceMode" to if (autoAdvance) "timed_auto" else "manual",
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

    fun tickSecond() {
        val state = _state.value
        if (!state.active || state.finished || state.currentStep == null) return
        val elapsed = state.elapsedSec + 1
        val target = state.targetDurationSec.coerceAtLeast(0)
        val remaining = if (target > 0) (target - elapsed).coerceAtLeast(0) else 0
        _state.value = state.copy(elapsedSec = elapsed, remainingSec = remaining)
        // Timed auto-advance: no need to press 「完成這步」
        if (state.autoAdvance && target > 0 && elapsed >= target) {
            completeCurrentStep(userNote = "auto_timed", autoAdvanced = true)
        }
    }

    /** Effective timed duration: step.durationSec if set, else sensible defaults for prep/finish. */
    private fun effectiveDurationSec(step: TestScriptStep): Int {
        if (step.durationSec > 0) return step.durationSec
        return when {
            step.id.contains("prep", ignoreCase = true) -> 25
            step.id.contains("finish", ignoreCase = true) -> 12
            else -> 30
        }
    }

    fun completeCurrentStep(userNote: String = "", autoAdvanced: Boolean = false) {
        val state = _state.value
        val step = state.currentStep ?: return
        val elapsedSec = ((currentTimeMs() - stepStartedAtMs) / 1000L).toInt().coerceAtLeast(0)

        emit(
            phase = "test_step_complete",
            fields = stepLogFields(step) + mapOf(
                "elapsedSec" to elapsedSec,
                "autoAdvanced" to autoAdvanced,
                "userNote" to userNote.trim(),
                "completedCount" to (state.completedStepIds.size + 1)
            )
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
                elapsedSec = 0,
                userNote = userNote
            )
            emit(
                phase = "test_script_complete",
                fields = mapOf(
                    "scriptId" to state.scriptId,
                    "completedSteps" to completed,
                    "totalSteps" to script.size
                )
            )
            return
        }

        val nextStep = script[nextIndex]
        val target = effectiveDurationSec(nextStep)
        _state.value = state.copy(
            stepIndex = nextIndex,
            currentStep = nextStep,
            elapsedSec = 0,
            targetDurationSec = target,
            remainingSec = target,
            autoAdvance = autoAdvanceEnabled,
            completedStepIds = completed,
            userNote = userNote
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
        val step = _state.value.currentStep ?: return
        if (!_state.value.active) return
        emit(
            phase = "test_step_snapshot",
            fields = stepLogFields(step) + runtimeMetrics() + mapOf(
                "elapsedSec" to _state.value.elapsedSec,
                "suggestedObserveSec" to step.durationSec
            )
        )
    }

    private fun enterCurrentStep() {
        val step = _state.value.currentStep ?: return
        stepStartedAtMs = currentTimeMs()
        val target = effectiveDurationSec(step)
        _state.value = _state.value.copy(
            targetDurationSec = target,
            remainingSec = target,
            elapsedSec = 0,
            autoAdvance = autoAdvanceEnabled
        )
        step.suggestedTier?.let { sessionContext.tierManager.setTier(it) }
        emit(
            phase = "test_step_start",
            fields = stepLogFields(step) + mapOf(
                "durationSec" to step.durationSec,
                "effectiveDurationSec" to target,
                "suggestedObserveSec" to target,
                "advanceMode" to if (autoAdvanceEnabled) "timed_auto" else "manual",
                "requiresAncRunning" to step.requiresAncRunning,
                "suggestedTier" to (step.suggestedTier?.name ?: "unchanged"),
                "expectedLogPhases" to step.logPhases
            )
        )
        if (step.debugPresets.isNotEmpty()) {
            emit(
                phase = "debug_presets_apply",
                fields = step.debugPresets
            )
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