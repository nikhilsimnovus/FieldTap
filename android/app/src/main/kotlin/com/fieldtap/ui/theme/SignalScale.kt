package com.fieldtap.ui.theme

/**
 * The one signal-quality scale of the app, best first. It matches the report's route colours
 * (`fieldtap report`: green at or above -85 dBm, light green at or above -95, orange at or above
 * -105, red below). Colour never stands alone: every place that shows a quality also shows its
 * label (see [com.fieldtap.ui.components.SignalQualityChip]).
 */
enum class SignalQuality {
    EXCELLENT,
    GOOD,
    FAIR,
    POOR,
    ;

    /** Filled bars out of four in [com.fieldtap.ui.components.SignalBars]. */
    val bars: Int
        get() = when (this) {
            EXCELLENT -> 4
            GOOD -> 3
            FAIR -> 2
            POOR -> 1
        }
}

/** A measured quantity with its own scale. The same thresholds apply to LTE and NR (SS-) values. */
enum class SignalMetric {
    /** dBm. */
    RSRP,

    /** dB. */
    RSRQ,

    /** dB (LTE RSSNR, NR SS-SINR). */
    SINR,
}

/**
 * Lower bounds, inclusive, of the three better levels; anything below [fairAtLeast] is
 * [SignalQuality.POOR].
 */
data class SignalThresholds(val excellentAtLeast: Int, val goodAtLeast: Int, val fairAtLeast: Int) {
    init {
        require(excellentAtLeast > goodAtLeast && goodAtLeast > fairAtLeast) {
            "thresholds must fall strictly: $excellentAtLeast > $goodAtLeast > $fairAtLeast"
        }
    }

    fun quality(value: Int): SignalQuality = when {
        value >= excellentAtLeast -> SignalQuality.EXCELLENT
        value >= goodAtLeast -> SignalQuality.GOOD
        value >= fairAtLeast -> SignalQuality.FAIR
        else -> SignalQuality.POOR
    }

    fun quality(value: Double): SignalQuality = when {
        value >= excellentAtLeast -> SignalQuality.EXCELLENT
        value >= goodAtLeast -> SignalQuality.GOOD
        value >= fairAtLeast -> SignalQuality.FAIR
        else -> SignalQuality.POOR
    }

    /** The three boundaries, highest first, for reference lines and bar notches. */
    val boundaries: List<Int> get() = listOf(excellentAtLeast, goodAtLeast, fairAtLeast)
}

/**
 * Thresholds and display ranges for [SignalMetric]s. Pure; the colours live in [SignalColors].
 *
 * - RSRP: -85 / -95 / -105 dBm, as in the report.
 * - RSRQ: -10 / -15 / -20 dB.
 * - SINR: 20 / 13 / 0 dB.
 *
 * Display ranges are for drawing only (bars and charts clamp to them); text always shows the
 * measured value. RSRP -140..-40 and SINR -25..40 are the report's chart axes.
 */
object SignalScale {
    val RSRP_THRESHOLDS: SignalThresholds = SignalThresholds(excellentAtLeast = -85, goodAtLeast = -95, fairAtLeast = -105)
    val RSRQ_THRESHOLDS: SignalThresholds = SignalThresholds(excellentAtLeast = -10, goodAtLeast = -15, fairAtLeast = -20)
    val SINR_THRESHOLDS: SignalThresholds = SignalThresholds(excellentAtLeast = 20, goodAtLeast = 13, fairAtLeast = 0)

    val RSRP_DISPLAY_RANGE: IntRange = -140..-40
    val RSRQ_DISPLAY_RANGE: IntRange = -30..0
    val SINR_DISPLAY_RANGE: IntRange = -25..40

    fun thresholds(metric: SignalMetric): SignalThresholds = when (metric) {
        SignalMetric.RSRP -> RSRP_THRESHOLDS
        SignalMetric.RSRQ -> RSRQ_THRESHOLDS
        SignalMetric.SINR -> SINR_THRESHOLDS
    }

    fun displayRange(metric: SignalMetric): IntRange = when (metric) {
        SignalMetric.RSRP -> RSRP_DISPLAY_RANGE
        SignalMetric.RSRQ -> RSRQ_DISPLAY_RANGE
        SignalMetric.SINR -> SINR_DISPLAY_RANGE
    }

    /** The emphasised reference line: -105 dBm (the report's), -15 dB RSRQ, 0 dB SINR. */
    fun keyReference(metric: SignalMetric): Int = when (metric) {
        SignalMetric.RSRP -> RSRP_THRESHOLDS.fairAtLeast
        SignalMetric.RSRQ -> RSRQ_THRESHOLDS.goodAtLeast
        SignalMetric.SINR -> SINR_THRESHOLDS.fairAtLeast
    }

    /** Null when the value is unknown (Android's unavailable value is already null by then). */
    fun quality(metric: SignalMetric, value: Int?): SignalQuality? =
        value?.let { thresholds(metric).quality(it) }

    /** For averages and other decimals; NaN is unknown. */
    fun quality(metric: SignalMetric, value: Double): SignalQuality? =
        if (value.isNaN()) null else thresholds(metric).quality(value)

    /** Position of [value] in the metric's display range, clamped to 0..1 (0 = bottom of the range). */
    fun fraction(metric: SignalMetric, value: Int): Float = fraction(value, displayRange(metric))

    /** Position of [value] in [range], clamped to 0..1. */
    fun fraction(value: Int, range: IntRange): Float {
        val span = (range.last - range.first).toFloat()
        if (span <= 0f) return 0f
        return ((value - range.first) / span).coerceIn(0f, 1f)
    }
}
