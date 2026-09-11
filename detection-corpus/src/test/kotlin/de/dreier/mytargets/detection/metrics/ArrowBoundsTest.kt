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

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.random.Random

class ArrowBoundsTest {

    @Test
    fun thePositionBoundCoversEverySingleFlippedMatch() {
        // A match that flips adds an error, loses one, or swaps one for another.
        val random = Random(7)
        val budget = 0.08
        repeat(200) {
            val n = 21 + random.nextInt(80)
            val errors = List(n) { random.nextDouble() * 0.04 }
            for (fraction in listOf(0.5, 0.95)) {
                val bound = PositionBound.of(errors, fraction, budget)!! - PositionBound.NOISE
                val changed = buildList {
                    for (x in listOf(0.0, 0.02, 0.04, budget)) add(errors + x)
                    for (i in errors.indices) add(errors.filterIndexed { j, _ -> j != i })
                    for (i in errors.indices step 3) {
                        for (x in listOf(0.0, 0.04, budget)) add(errors.filterIndexed { j, _ -> j != i } + x)
                    }
                }
                for (c in changed) assertThat(Percentiles.of(c, fraction)!!).isAtMost(bound + 1e-12)
            }
        }
    }

    @Test
    fun fromTwentyOneErrorsTheBudgetDoesNotMatter() {
        val errors = List(21) { 0.001 * (it + 1) }

        assertThat(PositionBound.of(errors, 0.95, 0.06)).isEqualTo(PositionBound.of(errors, 0.95, 0.08))
    }

    @Test
    fun withFewErrorsTheBudgetCounts() {
        // [0.01, 0.02] becomes [0.02, 0.08]; its 95th percentile is 0.02 + 0.95 * 0.06.
        assertThat(PositionBound.of(listOf(0.01, 0.02), 0.95, 0.08)!!)
            .isWithin(1e-12).of(0.077 + PositionBound.NOISE)
        assertThat(PositionBound.of(emptyList(), 0.95, 0.08)).isNull()
    }

    private val pins = ArrowPins(
        photographs = 15, listed = 86, matched = 60, falsePositives = 5,
        correctScores = 50, comparableScores = 55, medianErrorBound = 0.010, p95ErrorBound = 0.030
    )

    private fun measurement(
        matched: Int = 60,
        falsePositives: Int = 5,
        correct: Int = 50,
        comparable: Int = 55,
        errors: List<Double> = List(60) { 0.005 },
        photographs: Int = 15,
        listed: Int = 86
    ) = ArrowMeasurement(photographs, listed, matched, falsePositives, correct, comparable, errors)

    @Test
    fun oneArrowOfSlackPasses() {
        val within = measurement(matched = 59, falsePositives = 6, correct = 49, comparable = 54)

        assertThat(ArrowBounds.violations(within, pins)).isEmpty()
    }

    @Test
    fun twoArrowsBreakTheBounds() {
        val broken = ArrowBounds.violations(
            measurement(matched = 58, falsePositives = 7, correct = 48, comparable = 53), pins
        )

        assertThat(broken).hasSize(4)
        assertThat(broken[0]).isEqualTo("matched hits: 58, the bound is at least 59")
    }

    @Test
    fun aChangedCorpusIsReportedInsteadOfTheBounds() {
        val broken = ArrowBounds.violations(measurement(photographs = 16, listed = 92), pins)

        assertThat(broken).hasSize(1)
        assertThat(broken[0]).contains("set the bounds again against a new report")
    }

    @Test
    fun anErrorAboveItsBoundBreaksIt() {
        val broken = ArrowBounds.violations(measurement(errors = List(60) { 0.02 }), pins)

        assertThat(broken).containsExactly("median position error: 0.0200, the bound is at most 0.0100")
    }

    @Test
    fun thePinsAreTheCountsAndThePositionBounds() {
        val errors = List(30) { 0.001 * (it + 1) }

        val p = ArrowBounds.pinsFor(measurement(errors = errors), worstBudget = 0.08)

        assertThat(p.matched).isEqualTo(60)
        assertThat(p.listed).isEqualTo(86)
        assertThat(p.medianErrorBound!!).isWithin(1e-12).of(PositionBound.of(errors, 0.5, 0.08)!!)
        assertThat(p.p95ErrorBound!!).isWithin(1e-12).of(PositionBound.of(errors, 0.95, 0.08)!!)
    }

    @Test
    fun theReportPrintsThePinsToCopy() {
        assertThat(ArrowReport.pinsSection(pins)).contains(
            "ArrowPins(photographs = 15, listed = 86, matched = 60, falsePositives = 5, " +
                "correctScores = 50, comparableScores = 55, medianErrorBound = 0.010000, " +
                "p95ErrorBound = 0.030000)"
        )
    }
}
