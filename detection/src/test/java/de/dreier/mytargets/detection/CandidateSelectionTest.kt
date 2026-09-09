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

package de.dreier.mytargets.detection

import com.google.common.truth.Truth.assertThat
import de.dreier.mytargets.detection.geometry.Vec2
import org.junit.Test

class CandidateSelectionTest {

    private fun candidate(confidence: Double, faceIndex: Int = 0) =
        Candidate(faceIndex, Vec2(0.0, 0.0), confidence)

    @Test
    fun exactlyEnoughCandidatesAreAllAccepted() {
        val outcome = CandidateSelection.select(
            listOf(candidate(0.9), candidate(0.8), candidate(0.7)),
            expectedShots = 3,
            maxPerSpot = 3
        )
        assertThat(outcome.accepted).hasSize(3)
        assertThat(outcome.reason).isEqualTo(SelectionReason.COMPLETE)
    }

    @Test
    fun fewerCandidatesLeaveTheRestOpen() {
        val outcome = CandidateSelection.select(
            listOf(candidate(0.9), candidate(0.8)),
            expectedShots = 3,
            maxPerSpot = 3
        )
        assertThat(outcome.accepted).hasSize(2)
        assertThat(outcome.reason).isEqualTo(SelectionReason.FEWER_THAN_EXPECTED)
    }

    @Test
    fun surplusIsDroppedWhenTheConfidenceGapIsClear() {
        val outcome = CandidateSelection.select(
            listOf(candidate(0.95), candidate(0.92), candidate(0.20)),
            expectedShots = 2,
            maxPerSpot = 2
        )
        assertThat(outcome.accepted).hasSize(2)
        assertThat(outcome.reason).isEqualTo(SelectionReason.COMPLETE)
    }

    @Test
    fun surplusWithoutAClearGapLeavesTheContestedPlaceOpen() {
        val outcome = CandidateSelection.select(
            listOf(candidate(0.95), candidate(0.61), candidate(0.60)),
            expectedShots = 2,
            maxPerSpot = 2
        )
        // The second place is contested, so only the uncontested one is set.
        assertThat(outcome.accepted).hasSize(1)
        assertThat(outcome.accepted[0].confidence).isWithin(1e-9).of(0.95)
        assertThat(outcome.reason).isEqualTo(SelectionReason.AMBIGUOUS_SURPLUS)
    }

    @Test
    fun everythingContestedLeavesTheWholeEndOpen() {
        // Any two of these could be the real arrows; picking the first would be
        // as much of a coin toss as picking the second.
        val outcome = CandidateSelection.select(
            listOf(candidate(0.70), candidate(0.69), candidate(0.68), candidate(0.67)),
            expectedShots = 2,
            maxPerSpot = 2
        )
        assertThat(outcome.accepted).isEmpty()
        assertThat(outcome.reason).isEqualTo(SelectionReason.AMBIGUOUS_SURPLUS)
    }

    @Test
    fun perSpotCapDropsTheSurplusOnThatSpot() {
        val outcome = CandidateSelection.select(
            listOf(
                candidate(0.9, faceIndex = 0),
                candidate(0.8, faceIndex = 0),
                candidate(0.7, faceIndex = 1)
            ),
            expectedShots = 3,
            maxPerSpot = 1
        )
        assertThat(outcome.accepted).hasSize(2)
        assertThat(outcome.accepted.map { it.faceIndex }).containsExactly(0, 1)
        assertThat(outcome.reason).isEqualTo(SelectionReason.SPOT_OVERFLOW)
    }

    @Test
    fun twoArrowsPerSpotAreFineWhenTheEndHasSixShots() {
        // Six arrows on a three spot face: shot indices 0 and 3 share spot 0.
        val outcome = CandidateSelection.select(
            (0 until 6).map { i -> candidate(0.9 - 0.05 * i, faceIndex = i % 3) },
            expectedShots = 6,
            maxPerSpot = 2
        )
        assertThat(outcome.accepted).hasSize(6)
        assertThat(outcome.reason).isEqualTo(SelectionReason.COMPLETE)
    }

    @Test
    fun anOversubscribedSpotCanCrowdOutAnotherSpotsArrow() {
        // Known limitation of running the confidence selection before the per
        // spot cap: 0.95 and 0.90 take both places on raw confidence, then the
        // cap leaves only 0.95, and the uncontested arrow on spot 1 is lost.
        // Capping first would keep 0.95 and 0.30, but that order fails two of
        // this file's other tests. Pinned here so that changing it is a
        // decision, not an accident. See the KDoc on CandidateSelection.select.
        val outcome = CandidateSelection.select(
            listOf(
                candidate(0.95, faceIndex = 0),
                candidate(0.90, faceIndex = 0),
                candidate(0.30, faceIndex = 1),
                candidate(0.20, faceIndex = 1)
            ),
            expectedShots = 2,
            maxPerSpot = 1
        )
        assertThat(outcome.accepted).hasSize(1)
        assertThat(outcome.accepted[0].confidence).isWithin(1e-9).of(0.95)
        assertThat(outcome.reason).isEqualTo(SelectionReason.SPOT_OVERFLOW)
    }
}
