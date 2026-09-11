/*
 * Copyright (C) 2026 MyTargets contributors
 *
 * This file is part of MyTargets.
 *
 * MyTargets is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License version 2
 * as published by the Free Software Foundation.
 *
 * MyTargets is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 */

package de.dreier.mytargets.detection.metrics

import java.util.Locale

/** Arrow design, Schranken: the percentile a bound on the position error allows. */
object PositionBound {

    /** Noise between platforms: 1.4 to 2.7 px of the rectified image. */
    const val NOISE = 0.002

    /**
     * The [fraction] percentile after the smallest of [errors] is swapped for
     * [worstBudget] -- the worst a single flipped match can do -- plus NOISE.
     * From 21 errors on, the value of [worstBudget] no longer matters. Null
     * when nothing was measured.
     */
    fun of(errors: List<Double>, fraction: Double, worstBudget: Double): Double? {
        if (errors.isEmpty()) return null
        val swapped = errors.sorted().drop(1) + worstBudget
        return Percentiles.of(swapped, fraction)!! + NOISE
    }
}

/** What the arrow run pins for the oblique photographs. */
data class ArrowPins(
    val photographs: Int,
    val listed: Int,
    val matched: Int,
    val falsePositives: Int,
    val correctScores: Int,
    val comparableScores: Int,
    val medianErrorBound: Double?,
    val p95ErrorBound: Double?
)

/** One measurement of the oblique group: the numbers the bounds look at. */
class ArrowMeasurement(
    val photographs: Int,
    val listed: Int,
    val matched: Int,
    val falsePositives: Int,
    val correctScores: Int,
    val comparableScores: Int,
    val positionErrors: List<Double>
) {
    val medianError: Double?
        get() = Percentiles.of(positionErrors, 0.5)

    val p95Error: Double?
        get() = Percentiles.of(positionErrors, 0.95)

    companion object {
        fun of(photographs: Int, metrics: Metrics) = ArrowMeasurement(
            photographs, metrics.expectedShots, metrics.matchedShots, metrics.falsePositives,
            metrics.correctScores, metrics.scoreComparableShots, metrics.positionErrors
        )
    }
}

/**
 * Arrow design, Schranken. Counts are pinned, not rates: with the number of
 * listed hits pinned the rates follow, and the slack is exactly one arrow.
 * Numerator and denominator of the ring accuracy are held separately, because
 * a ratio can rise while both fall.
 */
object ArrowBounds {

    /** The pins for [measurement]: its counts, and the error bounds of PositionBound. */
    fun pinsFor(measurement: ArrowMeasurement, worstBudget: Double) = ArrowPins(
        photographs = measurement.photographs,
        listed = measurement.listed,
        matched = measurement.matched,
        falsePositives = measurement.falsePositives,
        correctScores = measurement.correctScores,
        comparableScores = measurement.comparableScores,
        medianErrorBound = PositionBound.of(measurement.positionErrors, 0.5, worstBudget),
        p95ErrorBound = PositionBound.of(measurement.positionErrors, 0.95, worstBudget)
    )

    /** Every bound [measurement] breaks, one sentence each; empty when it keeps them all. */
    fun violations(measurement: ArrowMeasurement, pins: ArrowPins): List<String> {
        if (measurement.photographs != pins.photographs || measurement.listed != pins.listed) {
            return listOf(
                "the oblique group has ${measurement.photographs} photographs and ${measurement.listed} " +
                    "listed hits, the bounds were pinned at ${pins.photographs} and ${pins.listed}: " +
                    "the corpus changed, so set the bounds again against a new report"
            )
        }
        val broken = ArrayList<String>()
        if (measurement.matched < pins.matched - 1) {
            broken += "matched hits: ${measurement.matched}, the bound is at least ${pins.matched - 1}"
        }
        if (measurement.falsePositives > pins.falsePositives + 1) {
            broken += "false positives: ${measurement.falsePositives}, the bound is at most ${pins.falsePositives + 1}"
        }
        if (measurement.correctScores < pins.correctScores - 1) {
            broken += "correct ring values: ${measurement.correctScores}, the bound is at least ${pins.correctScores - 1}"
        }
        if (measurement.comparableScores < pins.comparableScores - 1) {
            broken += "hits comparable for the ring accuracy: ${measurement.comparableScores}, " +
                "the bound is at least ${pins.comparableScores - 1}"
        }
        errorBound("median position error", measurement.medianError, pins.medianErrorBound)?.let { broken += it }
        errorBound("95th percentile of the position error", measurement.p95Error, pins.p95ErrorBound)?.let { broken += it }
        return broken
    }

    private fun errorBound(name: String, value: Double?, bound: Double?): String? = when {
        bound == null -> null
        value == null -> "$name: nothing measured, the bound is at most ${format(bound)}"
        value > bound -> "$name: ${format(value)}, the bound is at most ${format(bound)}"
        else -> null
    }

    private fun format(value: Double) = String.format(Locale.ROOT, "%.4f", value)
}
