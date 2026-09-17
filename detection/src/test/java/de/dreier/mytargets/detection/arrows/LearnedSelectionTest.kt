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

package de.dreier.mytargets.detection.arrows

import com.google.common.truth.Truth.assertThat
import de.dreier.mytargets.detection.FaceLayout
import de.dreier.mytargets.detection.SelectionReason
import de.dreier.mytargets.detection.geometry.Vec2
import de.dreier.mytargets.detection.registration.FaceWarp
import org.junit.Test

class LearnedSelectionTest {

    private val edge = 768

    /** A peak at target coordinates (x, y) with [value]. */
    private fun peakAt(x: Double, y: Double, value: Double): Peak {
        val px = FaceWarp.pixelOf(Vec2(x, y), edge)
        return Peak(px.x, px.y, value)
    }

    private val threeSpot = FaceLayout(
        facePositions = listOf(Vec2(-0.52, 0.5), Vec2(0.0, -0.5), Vec2(0.52, 0.5)),
        faceRadius = 0.48
    )

    @Test
    fun peaksOnTheFaceBecomeCandidatesInSpotLocalCoordinates() {
        val kept = listOf(peakAt(0.3, -0.4, 0.9), peakAt(-0.1, 0.2, 0.5))

        val placement = LearnedSelection.place(kept, edge, FaceLayout.singleSpot(), expectedShots = 2, maxPerSpot = 2)

        assertThat(placement.reason).isEqualTo(SelectionReason.COMPLETE)
        assertThat(placement.outsideFace).isEmpty()
        assertThat(placement.accepted).hasSize(2)
        val first = placement.accepted[0].candidate
        assertThat(first.faceIndex).isEqualTo(0)
        assertThat(first.local.distanceTo(Vec2(0.3, -0.4))).isLessThan(1e-9)
        assertThat(first.confidence).isWithin(1e-12).of(0.9)
        assertThat(placement.accepted[0].peak).isSameInstanceAs(kept[0])
    }

    @Test
    fun aPeakInTheBandBeyondRadiusOneIsOutsideAndTakesItsPlace() {
        // Design step 6: it took one of the expectedShots places in step 5 and counts for nothing now.
        val kept = listOf(peakAt(0.0, 1.05, 0.8), peakAt(0.2, 0.2, 0.4))

        val placement = LearnedSelection.place(kept, edge, FaceLayout.singleSpot(), expectedShots = 2, maxPerSpot = 2)

        assertThat(placement.outsideFace).containsExactly(kept[0])
        assertThat(placement.onFace.map { it.peak }).containsExactly(kept[1])
        assertThat(placement.accepted).hasSize(1)
        assertThat(placement.reason).isEqualTo(SelectionReason.FEWER_THAN_EXPECTED)
    }

    @Test
    fun noPeaksMeansFewerThanExpected() {
        val placement = LearnedSelection.place(emptyList(), edge, FaceLayout.singleSpot(), expectedShots = 6, maxPerSpot = 6)

        assertThat(placement.accepted).isEmpty()
        assertThat(placement.reason).isEqualTo(SelectionReason.FEWER_THAN_EXPECTED)
    }

    @Test
    fun aSpotOverItsCapDropsItsLowestPeaksAndSaysSo() {
        // Three shots on three spots, one per spot; two peaks land on spot 1.
        val kept = listOf(peakAt(0.0, -0.5, 0.9), peakAt(0.1, -0.4, 0.7), peakAt(0.52, 0.5, 0.6))

        val placement = LearnedSelection.place(kept, edge, threeSpot, expectedShots = 3, maxPerSpot = 1)

        assertThat(placement.onFace).hasSize(3)
        assertThat(placement.accepted.map { it.peak.value }).containsExactly(0.9, 0.6).inOrder()
        assertThat(placement.accepted.map { it.candidate.faceIndex }).containsExactly(1, 2).inOrder()
        assertThat(placement.reason).isEqualTo(SelectionReason.SPOT_OVERFLOW)
    }

    @Test
    fun theSpotLocalCoordinatesScaleWithTheSpotRadius() {
        val kept = listOf(peakAt(0.52 + 0.24, 0.5, 0.9))

        val placement = LearnedSelection.place(kept, edge, threeSpot, expectedShots = 1, maxPerSpot = 1)

        val c = placement.accepted.single().candidate
        assertThat(c.faceIndex).isEqualTo(2)
        assertThat(c.local.distanceTo(Vec2(0.5, 0.0))).isLessThan(1e-9)
    }
}
