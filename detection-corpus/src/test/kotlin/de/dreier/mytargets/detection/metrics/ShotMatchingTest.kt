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

class ShotMatchingTest {

    private fun entry(vararg shots: TruthShot) = CorpusEntry(
        imageName = "t.jpg", targetModel = "WAFull",
        shots = shots.toList(), tags = emptySet()
    )

    private fun truth(score: String, x: Double, y: Double, face: Int = 0) =
        TruthShot(Score.of(score), SpotPosition(face, x, y))

    private fun found(score: String, x: Double, y: Double, face: Int = 0, conf: Double = 0.9) =
        DetectedShotRecord(Score.of(score), SpotPosition(face, x, y), conf)

    @Test
    fun pairsNearestPositionsFirst() {
        val e = entry(truth("X", 0.0, 0.0), truth("9", 0.5, 0.0))
        val detected = listOf(found("9", 0.49, 0.0), found("X", 0.01, 0.0))

        val result = ShotMatching.match(e, detected)

        assertThat(result.pairs).hasSize(2)
        assertThat(result.unmatchedTruth).isEmpty()
        assertThat(result.unmatchedDetected).isEmpty()
        val byTruth = result.pairs.associateBy { it.truthIndex }
        assertThat(byTruth[0]!!.detectedIndex).isEqualTo(1)
        assertThat(byTruth[1]!!.detectedIndex).isEqualTo(0)
        assertThat(byTruth[0]!!.distance!!).isWithin(1e-9).of(0.01)
    }

    @Test
    fun aDetectionBeyondTheGateIsNotAMatch() {
        val e = entry(truth("X", 0.0, 0.0))
        val detected = listOf(found("X", 0.5, 0.0))

        val result = ShotMatching.match(e, detected, positionGate = 0.05)

        assertThat(result.pairs).isEmpty()
        assertThat(result.unmatchedTruth).containsExactly(0)
        assertThat(result.unmatchedDetected).containsExactly(0)
    }

    @Test
    fun positionsOnDifferentSpotsNeverPair() {
        val e = entry(truth("X", 0.0, 0.0, face = 0))
        val detected = listOf(found("X", 0.0, 0.0, face = 1))

        val result = ShotMatching.match(e, detected)

        assertThat(result.pairs).isEmpty()
        assertThat(result.unmatchedTruth).containsExactly(0)
    }

    @Test
    fun aSurplusDetectionStaysUnmatched() {
        val e = entry(truth("X", 0.0, 0.0))
        val detected = listOf(found("X", 0.0, 0.0), found("9", 0.4, 0.4))

        val result = ShotMatching.match(e, detected)

        assertThat(result.pairs).hasSize(1)
        assertThat(result.unmatchedDetected).containsExactly(1)
    }

    @Test
    fun ringOnlyTruthPairsByScore() {
        val e = entry(
            TruthShot(Score.of("9")),
            TruthShot(Score.of("9")),
            TruthShot(Score.of("7"))
        )
        val detected = listOf(
            found("7", 0.6, 0.0, conf = 0.5),
            found("9", 0.2, 0.0, conf = 0.9),
            found("8", 0.4, 0.0, conf = 0.7)
        )

        val result = ShotMatching.match(e, detected)

        // Two of the three detections carry a score the truth contains.
        assertThat(result.pairs).hasSize(2)
        assertThat(result.pairs.all { it.distance == null }).isTrue()
        // The eight matches nothing; one of the two nines stays unmatched.
        assertThat(result.unmatchedDetected).containsExactly(2)
        assertThat(result.unmatchedTruth).hasSize(1)
    }

    @Test
    fun ringOnlyMatchingPrefersConfidentDetections() {
        val e = entry(TruthShot(Score.of("9")))
        val detected = listOf(
            found("9", 0.0, 0.0, conf = 0.2),
            found("9", 0.3, 0.0, conf = 0.8)
        )

        val result = ShotMatching.match(e, detected)

        assertThat(result.pairs).hasSize(1)
        assertThat(result.pairs[0].detectedIndex).isEqualTo(1)
    }

    @Test
    fun noDetectionsLeavesEveryTruthUnmatched() {
        val e = entry(truth("X", 0.0, 0.0), truth("9", 0.5, 0.0))
        val result = ShotMatching.match(e, emptyList())
        assertThat(result.pairs).isEmpty()
        assertThat(result.unmatchedTruth).containsExactly(0, 1).inOrder()
    }

    @Test
    fun theGreedyChoiceIsGloballyNearestFirst() {
        // Truth A at 0.00, truth B at 0.03. One detection at 0.02.
        // Nearest first pairs it with B at 0.01, not with A at 0.02.
        val e = entry(truth("9", 0.0, 0.0), truth("9", 0.03, 0.0))
        val detected = listOf(found("9", 0.02, 0.0))

        val result = ShotMatching.match(e, detected)

        assertThat(result.pairs).hasSize(1)
        assertThat(result.pairs[0].truthIndex).isEqualTo(1)
        assertThat(result.pairs[0].distance!!).isWithin(1e-9).of(0.01)
    }
}
