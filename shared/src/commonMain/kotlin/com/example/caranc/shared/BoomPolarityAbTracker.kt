package com.example.caranc.shared

/**
 * 1.2.12: accumulate plantResidualReductionDb during scripted polarity A/B
 * (`target_road_ppos` / `target_road_pneg`) and pick the winning sign.
 */
object BoomPolarityAbTracker {
    private var posSum = 0.0
    private var posN = 0
    private var negSum = 0.0
    private var negN = 0

    fun reset() {
        posSum = 0.0
        posN = 0
        negSum = 0.0
        negN = 0
    }

    fun sample(stepId: String?, residualReductionDb: Float) {
        when (stepId) {
            "target_road_ppos" -> {
                posSum += residualReductionDb.toDouble()
                posN++
            }
            "target_road_pneg" -> {
                negSum += residualReductionDb.toDouble()
                negN++
            }
        }
    }

    fun posCount(): Int = posN
    fun negCount(): Int = negN

    fun posAvg(): Float? = if (posN >= 3) (posSum / posN).toFloat() else null
    fun negAvg(): Float? = if (negN >= 3) (negSum / negN).toFloat() else null

    /**
     * @return +1 or −1 when both legs have enough samples; null if incomplete.
     * Higher plantResidualReductionDb wins (anti reducing plant residual).
     */
    fun winnerPolarity(): Float? {
        val p = posAvg() ?: return null
        val n = negAvg() ?: return null
        return if (n > p) -1f else 1f
    }
}
