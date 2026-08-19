package com.example.caranc.shared

/**
 * 1.2.12/1.2.14: polarity A/B accumulator for `target_road_ppos` / `target_road_pneg`.
 *
 * Prefer **cabin proxy** (lower mic low-band dB = quieter = better) over plant residual.
 * Discard samples below [minSpeedKmh] (stopped / unequal sessions).
 * Default winner fallback: **−1** (1.2.14 cabin preference).
 */
object BoomPolarityAbTracker {
    const val DEFAULT_POLARITY = -1f
    const val MIN_SPEED_KMH = 45f

    private var posPlantSum = 0.0
    private var posPlantN = 0
    private var negPlantSum = 0.0
    private var negPlantN = 0

    private var posCabinSum = 0.0
    private var posCabinN = 0
    private var negCabinSum = 0.0
    private var negCabinN = 0

    private var discardedLowSpeed = 0

    fun reset() {
        posPlantSum = 0.0
        posPlantN = 0
        negPlantSum = 0.0
        negPlantN = 0
        posCabinSum = 0.0
        posCabinN = 0
        negCabinSum = 0.0
        negCabinN = 0
        discardedLowSpeed = 0
    }

    /**
     * @param residualReductionDb plant KPI (higher better) — secondary
     * @param cabinLowBandDb mic low-band dB during step (lower quieter = better cabin) — primary
     * @param speedKmh discard if &lt; [MIN_SPEED_KMH]
     */
    fun sample(
        stepId: String?,
        residualReductionDb: Float,
        cabinLowBandDb: Float? = null,
        speedKmh: Float = 100f
    ) {
        if (stepId != "target_road_ppos" && stepId != "target_road_pneg") return
        if (speedKmh < MIN_SPEED_KMH) {
            discardedLowSpeed++
            return
        }
        when (stepId) {
            "target_road_ppos" -> {
                posPlantSum += residualReductionDb.toDouble()
                posPlantN++
                if (cabinLowBandDb != null) {
                    posCabinSum += cabinLowBandDb.toDouble()
                    posCabinN++
                }
            }
            "target_road_pneg" -> {
                negPlantSum += residualReductionDb.toDouble()
                negPlantN++
                if (cabinLowBandDb != null) {
                    negCabinSum += cabinLowBandDb.toDouble()
                    negCabinN++
                }
            }
        }
    }

    fun posCount(): Int = posPlantN
    fun negCount(): Int = negPlantN
    fun discardedLowSpeedCount(): Int = discardedLowSpeed

    fun posAvg(): Float? = if (posPlantN >= 3) (posPlantSum / posPlantN).toFloat() else null
    fun negAvg(): Float? = if (negPlantN >= 3) (negPlantSum / negPlantN).toFloat() else null

    fun posCabinAvg(): Float? = if (posCabinN >= 3) (posCabinSum / posCabinN).toFloat() else null
    fun negCabinAvg(): Float? = if (negCabinN >= 3) (negCabinSum / negCabinN).toFloat() else null

    /**
     * Winner polarity:
     * 1) cabin proxy if both legs have ≥3 samples — **lower** low-band dB wins
     * 2) else plant residual — **higher** reduction wins
     * 3) else [DEFAULT_POLARITY] (−1)
     */
    fun winnerPolarity(): Float {
        val pc = posCabinAvg()
        val nc = negCabinAvg()
        if (pc != null && nc != null) {
            // quieter cabin (more negative / lower dB) wins
            return if (nc < pc) -1f else 1f
        }
        val p = posAvg()
        val n = negAvg()
        if (p != null && n != null) {
            return if (n > p) -1f else 1f
        }
        return DEFAULT_POLARITY
    }

    /** True when both polarity legs had enough *in-speed* samples. */
    fun isFairAbComplete(): Boolean = posPlantN >= 3 && negPlantN >= 3
}
