package com.example.caranc.shared

/**
 * Polarity A/B accumulator for `target_road_ppos` / `target_road_pneg`.
 *
 * 1.2.15: cabin score = lowDb + 0.8×midDb (lower quieter = better).
 * 1.2.14 fair: ppos won plant residual but cabin 180–350 was +2.9 dB louder;
 * pneg cleaned mid — mid must vote.
 *
 * Discard samples below [minSpeedKmh]. Fallback winner: **−1**.
 */
object BoomPolarityAbTracker {
    const val DEFAULT_POLARITY = -1f
    const val MIN_SPEED_KMH = 45f
    /** Weight of mid-band (180–350) in cabin score — mid boost hurts more subjectively. */
    const val MID_WEIGHT = 0.80f

    private var posPlantSum = 0.0
    private var posPlantN = 0
    private var negPlantSum = 0.0
    private var negPlantN = 0

    private var posCabinScoreSum = 0.0
    private var posCabinN = 0
    private var negCabinScoreSum = 0.0
    private var negCabinN = 0

    private var posCabinLowSum = 0.0
    private var negCabinLowSum = 0.0
    private var posCabinMidSum = 0.0
    private var negCabinMidSum = 0.0

    private var discardedLowSpeed = 0

    fun reset() {
        posPlantSum = 0.0
        posPlantN = 0
        negPlantSum = 0.0
        negPlantN = 0
        posCabinScoreSum = 0.0
        posCabinN = 0
        negCabinScoreSum = 0.0
        negCabinN = 0
        posCabinLowSum = 0.0
        negCabinLowSum = 0.0
        posCabinMidSum = 0.0
        negCabinMidSum = 0.0
        discardedLowSpeed = 0
    }

    /**
     * @param residualReductionDb plant KPI (higher better) — tertiary
     * @param cabinLowBandDb mic ~40–80/120 dB (lower quieter)
     * @param cabinMidBandDb mic ~180–350 dB (lower quieter) — 1.2.15
     */
    fun sample(
        stepId: String?,
        residualReductionDb: Float,
        cabinLowBandDb: Float? = null,
        speedKmh: Float = 100f,
        cabinMidBandDb: Float? = null
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
                    val mid = cabinMidBandDb ?: cabinLowBandDb
                    val score = cabinLowBandDb + MID_WEIGHT * mid
                    posCabinScoreSum += score.toDouble()
                    posCabinLowSum += cabinLowBandDb.toDouble()
                    posCabinMidSum += mid.toDouble()
                    posCabinN++
                }
            }
            "target_road_pneg" -> {
                negPlantSum += residualReductionDb.toDouble()
                negPlantN++
                if (cabinLowBandDb != null) {
                    val mid = cabinMidBandDb ?: cabinLowBandDb
                    val score = cabinLowBandDb + MID_WEIGHT * mid
                    negCabinScoreSum += score.toDouble()
                    negCabinLowSum += cabinLowBandDb.toDouble()
                    negCabinMidSum += mid.toDouble()
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

    fun posCabinAvg(): Float? =
        if (posCabinN >= 3) (posCabinScoreSum / posCabinN).toFloat() else null

    fun negCabinAvg(): Float? =
        if (negCabinN >= 3) (negCabinScoreSum / negCabinN).toFloat() else null

    fun posCabinLowAvg(): Float? =
        if (posCabinN >= 3) (posCabinLowSum / posCabinN).toFloat() else null

    fun negCabinLowAvg(): Float? =
        if (negCabinN >= 3) (negCabinLowSum / negCabinN).toFloat() else null

    fun posCabinMidAvg(): Float? =
        if (posCabinN >= 3) (posCabinMidSum / posCabinN).toFloat() else null

    fun negCabinMidAvg(): Float? =
        if (negCabinN >= 3) (negCabinMidSum / negCabinN).toFloat() else null

    /**
     * 1) cabin score (low+0.8×mid) if both ≥3 — **lower** wins
     * 2) else plant residual — **higher** wins
     * 3) else [DEFAULT_POLARITY] (−1)
     */
    fun winnerPolarity(): Float {
        val pc = posCabinAvg()
        val nc = negCabinAvg()
        if (pc != null && nc != null) {
            return if (nc < pc) -1f else 1f
        }
        val p = posAvg()
        val n = negAvg()
        if (p != null && n != null) {
            return if (n > p) -1f else 1f
        }
        return DEFAULT_POLARITY
    }

    fun isFairAbComplete(): Boolean = posPlantN >= 3 && negPlantN >= 3
}
