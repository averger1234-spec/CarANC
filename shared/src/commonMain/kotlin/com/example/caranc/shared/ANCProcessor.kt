package com.example.caranc.shared

import kotlin.math.*

// CYCLE3_P2: use sessionContext.roadNoise... (scoped) instead of direct RoadNoiseReferenceModel singleton.
import com.example.caranc.shared.AncSessionContext
import com.example.caranc.shared.GlobalAncSessionContext

interface AudioProcessor {
    fun process(input: ShortArray): ShortArray
    fun release()
}

enum class AncProcessingMode {
    NORMAL,
    FLOOR_NOISE_MUSIC,
    FLOOR_NOISE_CALL,
    ROAD_NOISE_GPS,
    /** 音樂播放 + GPS 行車：底噪 bypass 與路噪 Wiener 並行 */
    FLOOR_NOISE_MUSIC_ROAD
}

class ANCProcessor(
    private val sampleRate: Int,
    private val bufferSize: Int,
    initialTier: UserTier = UserTier.STANDARD,
    // CYCLE3_P2: accept context to provide scoped RoadNoiseReferenceModel (for consistency with MultiBand + mock support).
    // Default keeps legacy direct construction working.
    private val sessionContext: AncSessionContext = GlobalAncSessionContext
) : AudioProcessor {

    private var filterLength = getLengthForTier(initialTier)
    private var mu = getMuForTier(initialTier)

    private val maxFilterLength = 512
    private val bufferMask = 1023
    private val w = FloatArray(maxFilterLength) { 0f }
    private val xBuffer = FloatArray(1024) { 0f }

    private val sHatLength = 64
    private val sHat = FloatArray(sHatLength) { 0f }.apply { this[0] = 1.0f }
    private val filteredXBuffer = FloatArray(1024) { 0f }

    private var processingMode = AncProcessingMode.NORMAL
    private val leakage = 0.9998f
    private var bufferIndex = 0
    private var acousticDelaySamples = 0

    private var vehicleSpeedKmh = 0f
    private var vehicleSpeedValid = false

    private val lpCoeff = (2.0 * PI * 250.0 / sampleRate).toFloat().coerceIn(0.005f, 0.12f)
    private var lpInputState = 0f
    private var lpOutputState = 0f

    private var roadLpCoeff = lpCoeff
    private var roadLpInputState = 0f
    private var roadLpOutputState = 0f

    private var blockEnergyEma = 0f
    private var freezeWeightUpdates = 0
    private var bumpDetectedFlag = false

    fun updateSecondaryPath(model: FloatArray) {
        for (i in 0 until sHatLength.coerceAtMost(model.size)) {
            sHat[i] = model[i]
        }
    }

    fun setAcousticDelay(samples: Int) {
        acousticDelaySamples = samples.coerceIn(0, 256)
    }

    fun getAcousticDelaySamples(): Int = acousticDelaySamples

    fun setVehicleSpeed(speedKmh: Float, valid: Boolean) {
        vehicleSpeedValid = valid
        vehicleSpeedKmh = if (valid) speedKmh.coerceAtLeast(0f) else 0f
        if (valid) {
            // CYCLE3: scoped road model
            val cutoff = sessionContext.roadNoiseReferenceModel.lowPassCutoffHz(vehicleSpeedKmh)
            roadLpCoeff = (2.0 * PI * cutoff / sampleRate).toFloat().coerceIn(0.005f, 0.15f)
        }
    }

    fun getVehicleSpeedKmh(): Float = vehicleSpeedKmh
    fun isVehicleSpeedValid(): Boolean = vehicleSpeedValid

    private fun getLengthForTier(tier: UserTier) = when (tier) {
        UserTier.LIGHT -> 128
        UserTier.STANDARD -> 256
        UserTier.PRO -> 512
    }

    private fun getMuForTier(tier: UserTier) = when (tier) {
        UserTier.LIGHT -> 0.005f
        UserTier.STANDARD -> 0.01f
        UserTier.PRO -> 0.02f
    }

    fun updateTier(newTier: UserTier) {
        val newLength = getLengthForTier(newTier)
        val newMu = getMuForTier(newTier)
        mu = newMu
        if (newLength != filterLength) {
            if (newLength > filterLength) {
                for (i in filterLength until newLength) {
                    w[i] = 0f
                }
            }
            filterLength = newLength
        }
    }

    fun setProcessingMode(mode: AncProcessingMode) {
        processingMode = mode
    }

    fun getProcessingMode(): AncProcessingMode = processingMode

    fun registerBlockEnergy(rms: Float): Boolean {
        if (blockEnergyEma < 1e-6f) {
            blockEnergyEma = rms
            return false
        }
        val ratio = rms / blockEnergyEma.coerceAtLeast(1e-6f)
        blockEnergyEma = 0.95f * blockEnergyEma + 0.05f * rms
        if (ratio > 8.0f && rms > 0.02f) {
            freezeWeightUpdates = (sampleRate / bufferSize.coerceAtLeast(256)).coerceIn(4, 12)
            bumpDetectedFlag = true
            return true
        }
        return false
    }

    fun consumeBumpDetected(): Boolean {
        val detected = bumpDetectedFlag
        bumpDetectedFlag = false
        return detected
    }

    fun isWeightUpdateFrozen(): Boolean = freezeWeightUpdates > 0

    private fun lowPassInput(x: Float): Float {
        lpInputState += lpCoeff * (x - lpInputState)
        return lpInputState
    }

    private fun lowPassOutput(x: Float): Float {
        lpOutputState += lpCoeff * (x - lpOutputState)
        return lpOutputState
    }

    private fun roadLowPassInput(x: Float): Float {
        roadLpInputState += roadLpCoeff * (x - roadLpInputState)
        return roadLpInputState
    }

    private fun roadLowPassOutput(x: Float): Float {
        roadLpOutputState += roadLpCoeff * (x - roadLpOutputState)
        return roadLpOutputState
    }

    private fun modeMuScale(): Float = when (processingMode) {
        AncProcessingMode.NORMAL -> 1.0f
        AncProcessingMode.FLOOR_NOISE_MUSIC -> 0.38f
        AncProcessingMode.FLOOR_NOISE_CALL -> 0.08f
        AncProcessingMode.ROAD_NOISE_GPS -> 0.75f
        AncProcessingMode.FLOOR_NOISE_MUSIC_ROAD -> 0.55f
    }

    private fun speedMuScale(): Float {
        // CYCLE3: via context (scoped/mockable)
        return sessionContext.roadNoiseReferenceModel.muScale(vehicleSpeedKmh, vehicleSpeedValid)
    }

    private fun buildReference(xRaw: Float, xMic: Float): Float {
        val roadWeight = when (processingMode) {
            AncProcessingMode.ROAD_NOISE_GPS,
            AncProcessingMode.FLOOR_NOISE_MUSIC_ROAD -> sessionContext.roadNoiseReferenceModel.roadBlendWeight(
                vehicleSpeedKmh,
                vehicleSpeedValid
            )
            AncProcessingMode.NORMAL -> sessionContext.roadNoiseReferenceModel.roadBlendWeight(
                vehicleSpeedKmh,
                vehicleSpeedValid
            ) * 0.5f
            else -> 0f
        }
        if (roadWeight <= 0f) return xMic

        val roadComponent = roadLowPassInput(xRaw)
        // CYCLE3: scoped
        val energyScale = sessionContext.roadNoiseReferenceModel.roadEnergyScale(vehicleSpeedKmh)
        val scaledRoad = roadComponent * (0.55f + 0.45f * energyScale)
        return (1f - roadWeight) * xMic + roadWeight * scaledRoad
    }

    override fun process(input: ShortArray): ShortArray {
        val output = ShortArray(input.size)
        val floorMode = processingMode == AncProcessingMode.FLOOR_NOISE_MUSIC ||
            processingMode == AncProcessingMode.FLOOR_NOISE_CALL ||
            processingMode == AncProcessingMode.FLOOR_NOISE_MUSIC_ROAD
        val roadMode = processingMode == AncProcessingMode.ROAD_NOISE_GPS ||
            processingMode == AncProcessingMode.FLOOR_NOISE_MUSIC_ROAD
        val adaptInput = floorMode || roadMode

        for (i in input.indices) {
            val xRaw = input[i] / 32768.0f
            val xMic = if (floorMode) lowPassInput(xRaw) else xRaw
            val x = buildReference(xRaw, xMic)
            xBuffer[bufferIndex] = x

            var y = 0.0f
            for (j in 0 until filterLength) {
                val idx = (bufferIndex - acousticDelaySamples - j) and bufferMask
                y += w[j] * xBuffer[idx]
            }

            val outputFloat = when {
                floorMode -> -lowPassOutput(y)
                roadMode -> -roadLowPassOutput(y)
                else -> -y
            }
            output[i] = (outputFloat * 32767.0f).coerceIn(-32768.0f, 32767.0f).toInt().toShort()

            var filteredX = 0.0f
            for (j in 0 until sHatLength) {
                val idx = (bufferIndex - j) and bufferMask
                filteredX += sHat[j] * xBuffer[idx]
            }
            filteredXBuffer[bufferIndex] = filteredX

            if (freezeWeightUpdates > 0) {
                freezeWeightUpdates--
                bufferIndex = (bufferIndex + 1) and bufferMask
                continue
            }

            val currentMu = mu * modeMuScale() * speedMuScale()
            val error = x

            var pfx = 0.0f
            for (j in 0 until filterLength) {
                val idx = (bufferIndex - j) and bufferMask
                pfx += filteredXBuffer[idx] * filteredXBuffer[idx]
            }

            val muNorm = if (pfx > 20.0f) {
                currentMu / (pfx * 2.0f)
            } else {
                currentMu / (pfx + 1e-3f)
            }

            for (j in 0 until filterLength) {
                val idx = (bufferIndex - j) and bufferMask
                w[j] = (w[j] * leakage + muNorm * error * filteredXBuffer[idx]).coerceIn(-0.8f, 0.8f)
            }

            bufferIndex = (bufferIndex + 1) and bufferMask
        }
        return output
    }

    fun adjustMu(newMu: Float) {
        mu = newMu.coerceAtMost(0.01f)
    }

    override fun release() {}

    fun finishLearning() {
        mu = 0.004f
    }
}