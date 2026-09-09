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
import de.dreier.mytargets.detection.corpus.CorpusEntry
import de.dreier.mytargets.detection.corpus.Score
import de.dreier.mytargets.detection.corpus.SpotPosition
import de.dreier.mytargets.detection.corpus.TruthShot
import org.junit.Test

class MetricsTest {

    private fun entry(
        name: String,
        tags: Set<String> = emptySet(),
        vararg shots: TruthShot
    ) = CorpusEntry(name, "WAFull", shots.toList(), tags)

    private fun truth(score: String, x: Double, y: Double) =
        TruthShot(Score.of(score), SpotPosition(0, x, y))

    private fun found(score: String, x: Double, y: Double, conf: Double = 0.9) =
        DetectedShotRecord(Score.of(score), SpotPosition(0, x, y), conf)

    private fun outcome(e: CorpusEntry, detected: List<DetectedShotRecord>) =
        EntryOutcome(e, ShotMatching.match(e, detected), detected)

    @Test
    fun aPerfectRunScoresOneEverywhere() {
        val e = entry("p.jpg", shots = arrayOf(truth("X", 0.0, 0.0), truth("9", 0.3, 0.0)))
        val m = Metrics.over(listOf(outcome(e, listOf(found("X", 0.0, 0.0), found("9", 0.3, 0.0)))))

        assertThat(m.detectionRate!!).isWithin(1e-9).of(1.0)
        assertThat(m.falsePositiveRate!!).isWithin(1e-9).of(0.0)
        assertThat(m.scoreAccuracy!!).isWithin(1e-9).of(1.0)
        assertThat(m.medianPositionError!!).isWithin(1e-9).of(0.0)
    }

    @Test
    fun aMissedArrowLowersDetectionAndScoreAccuracyTogether() {
        val e = entry("m.jpg", shots = arrayOf(truth("X", 0.0, 0.0), truth("9", 0.3, 0.0)))
        val m = Metrics.over(listOf(outcome(e, listOf(found("X", 0.0, 0.0)))))

        assertThat(m.detectionRate!!).isWithin(1e-9).of(0.5)
        assertThat(m.scoreAccuracy!!).isWithin(1e-9).of(0.5)
        assertThat(m.falsePositiveRate!!).isWithin(1e-9).of(0.0)
    }

    @Test
    fun anInventedArrowRaisesTheFalsePositiveRate() {
        val e = entry("f.jpg", shots = arrayOf(truth("X", 0.0, 0.0)))
        val m = Metrics.over(listOf(outcome(e, listOf(found("X", 0.0, 0.0), found("9", 0.8, 0.0)))))

        assertThat(m.detectionRate!!).isWithin(1e-9).of(1.0)
        assertThat(m.falsePositiveRate!!).isWithin(1e-9).of(1.0)
    }

    @Test
    fun theFalsePositiveRateIsMeasuredAgainstExpectedNotAgainstFound() {
        // Two expected arrows, one found, two invented. Against expected that
        // is 2/2 = 1.0; against matched it would be 2/1 = 2.0.
        val e = entry("fp.jpg", shots = arrayOf(truth("9", 0.0, 0.0), truth("8", 0.5, 0.0)))
        val detected = listOf(
            found("9", 0.0, 0.0),
            found("5", 0.2, 0.0),
            found("5", 0.3, 0.0)
        )
        val m = Metrics.over(listOf(outcome(e, detected)))

        assertThat(m.matchedShots).isEqualTo(1)
        assertThat(m.falsePositives).isEqualTo(2)
        assertThat(m.falsePositiveRate!!).isWithin(1e-9).of(1.0)
    }

    @Test
    fun aMatchedArrowWithTheWrongScoreCountsAsFoundButNotAsCorrect() {
        val e = entry("w.jpg", shots = arrayOf(truth("X", 0.0, 0.0)))
        val m = Metrics.over(listOf(outcome(e, listOf(found("9", 0.01, 0.0)))))

        assertThat(m.detectionRate!!).isWithin(1e-9).of(1.0)
        assertThat(m.scoreAccuracy!!).isWithin(1e-9).of(0.0)
    }

    @Test
    fun ringAccuracyComparesEachTruthWithItsOwnDetection() {
        // Every other test in this file happens to pair index i with index i,
        // which cannot tell a correct comparison from one that swaps the two
        // indices. These pairings form a three-cycle, which can: the right
        // answer is 2/3 and the swapped one is 0.
        val shots = arrayOf(
            truth("9", 0.0, 0.0),
            truth("8", 0.3, 0.0),
            truth("7", 0.6, 0.0)
        )
        val e = entry("cycle.jpg", shots = shots)
        val detected = listOf(
            found("6", 0.6, 0.0),   // pairs with truth 2, and is wrong
            found("9", 0.0, 0.0),   // pairs with truth 0, correct
            found("8", 0.3, 0.0)    // pairs with truth 1, correct
        )
        val m = Metrics.over(listOf(outcome(e, detected)))

        assertThat(m.detectionRate!!).isWithin(1e-9).of(1.0)
        assertThat(m.scoreAccuracy!!).isWithin(1e-9).of(2.0 / 3.0)
    }

    @Test
    fun scoreAccuracyIsMeasuredAgainstExpectedNotAgainstFound() {
        // Five of six arrows missed; the one found is scored correctly.
        // Rewarding that with 100 percent would be a lie.
        val shots = Array(6) { truth("9", 0.1 * it, 0.0) }
        val e = entry("s.jpg", shots = shots)
        val m = Metrics.over(listOf(outcome(e, listOf(found("9", 0.0, 0.0)))))

        assertThat(m.scoreAccuracy!!).isWithin(1e-9).of(1.0 / 6.0)
    }

    @Test
    fun positionErrorsUseTheMedianAndThe95thPercentile() {
        val shots = arrayOf(
            truth("9", 0.0, 0.0), truth("9", 0.2, 0.0),
            truth("9", 0.4, 0.0), truth("9", 0.6, 0.0)
        )
        val e = entry("q.jpg", shots = shots)
        val detected = listOf(
            found("9", 0.000, 0.0),   // error 0.000
            found("9", 0.210, 0.0),   // error 0.010
            found("9", 0.420, 0.0),   // error 0.020
            found("9", 0.630, 0.0)    // error 0.030
        )
        val m = Metrics.over(listOf(outcome(e, detected)))

        // Errors are 0, 0.01, 0.02, 0.03. With linear interpolation between
        // order statistics the median sits at rank 0.5 * 3 = 1.5, so
        // 0.01 * 0.5 + 0.02 * 0.5 = 0.015.
        assertThat(m.medianPositionError!!).isWithin(1e-9).of(0.015)
        // The 95th percentile sits at rank 0.95 * 3 = 2.85, so
        // 0.02 * 0.15 + 0.03 * 0.85 = 0.0285.
        assertThat(m.p95PositionError!!).isWithin(1e-9).of(0.0285)
    }

    @Test
    fun ringOnlyEntriesContributeEverythingButThePositionError() {
        val e = entry("r.jpg", shots = arrayOf(TruthShot(Score.of("9")), TruthShot(Score.of("8"))))
        val m = Metrics.over(listOf(outcome(e, listOf(found("9", 0.0, 0.0), found("8", 0.5, 0.0)))))

        assertThat(m.detectionRate!!).isWithin(1e-9).of(1.0)
        assertThat(m.scoreAccuracy!!).isWithin(1e-9).of(1.0)
        assertThat(m.entriesWithPositions).isEqualTo(0)
        assertThat(m.medianPositionError).isNull()
        assertThat(m.p95PositionError).isNull()
    }

    @Test
    fun anEmptyCorpusMeasuresNothingRatherThanReportingZero() {
        // A rate of 0.0 would read as a perfect run once printed as a
        // percentage, and would silently pass any regression bound placed on
        // it. An empty corpus must say it measured nothing instead.
        val m = Metrics.over(emptyList())
        assertThat(m.expectedShots).isEqualTo(0)
        assertThat(m.detectionRate).isNull()
        assertThat(m.falsePositiveRate).isNull()
        assertThat(m.scoreAccuracy).isNull()
        assertThat(m.medianPositionError).isNull()
    }

    @Test
    fun tagsSplitTheCorpusIntoComparableGroups() {
        val dark = entry("d.jpg", tags = setOf("dark"), shots = arrayOf(truth("9", 0.0, 0.0)))
        val plain = entry("p.jpg", shots = arrayOf(truth("9", 0.0, 0.0)))

        val byTag = Metrics.byTag(
            listOf(
                outcome(dark, emptyList()),
                outcome(plain, listOf(found("9", 0.0, 0.0)))
            )
        )

        assertThat(byTag.keys).containsExactly("dark", "untagged")
        assertThat(byTag["dark"]!!.detectionRate!!).isWithin(1e-9).of(0.0)
        assertThat(byTag["untagged"]!!.detectionRate!!).isWithin(1e-9).of(1.0)
    }
}
