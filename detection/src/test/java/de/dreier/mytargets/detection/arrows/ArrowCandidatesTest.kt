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
import de.dreier.mytargets.detection.geometry.Line2
import de.dreier.mytargets.detection.geometry.Vec2
import org.junit.Test
import kotlin.math.hypot

class ArrowCandidatesTest {

    private val foot = Vec2(1.3, 0.0)
    private val layout = FaceLayout.singleSpot()

    private class Shaft(val run: SearchRun, val walk: WalkResult)

    private fun shaft(
        tip: Vec2,
        far: Vec2,
        refinement: TipRefinement = TipRefinement.REFINED,
        score: Double = 0.3,
        shaftContrast: Double = 0.6
    ) = Shaft(SearchRun(tip, far, 0.6, score), WalkResult(tip, Line2.through(tip, far), shaftContrast, refinement))

    private fun build(vararg shafts: Shaft) =
        ArrowCandidates.build(shafts.map { it.run }, shafts.map { it.walk }, foot, layout)

    @Test
    fun twoChordsOfOneShaftBecomeOneAndTheRefinedStays() {
        // A leaning shaft: its line does not pass through the foot point.
        val tip = Vec2(0.2, 0.1)
        val far = Vec2(-0.3, 0.2)
        val chordTip = tip + (far - tip) * (0.1 / (far - tip).length)

        val candidates = build(
            shaft(tip, far, TipRefinement.REFINED, score = 0.2),
            shaft(chordTip, far, TipRefinement.RAN_OUT, score = 0.4)
        )

        assertThat(candidates).hasSize(1)
        assertThat(candidates[0].refinement).isEqualTo(TipRefinement.REFINED)
        assertThat(candidates[0].tip).isEqualTo(tip)
    }

    @Test
    fun twoRefinedArrowsOnOneLineThroughTheFootPointStayTwo() {
        // Seen from Q, one arrow stands behind the other.
        val candidates = build(
            shaft(Vec2(0.3, 0.0), Vec2(0.2, 0.0)),
            shaft(Vec2(0.1, 0.0), Vec2(-0.3, 0.0))
        )

        assertThat(candidates).hasSize(2)
    }

    @Test
    fun tipsWithinAHundredthAreOneArrowAndTheBetterScoredStays() {
        val candidates = build(
            shaft(Vec2(0.2, 0.1), Vec2(-0.3, 0.2), score = 0.2),
            shaft(Vec2(0.205, 0.1), Vec2(-0.3, 0.1), score = 0.4)
        )

        assertThat(candidates.single().score).isWithin(1e-12).of(0.4)
    }

    @Test
    fun confidenceSaturatesInLengthAndHalvesForAWalkThatRanOut() {
        val s = ArrowCandidates.LENGTH_SATURATION
        val candidates = build(
            shaft(Vec2(0.2, 0.3), Vec2(0.2 - s / 2, 0.3), score = 0.1),
            shaft(Vec2(0.2, -0.3), Vec2(0.2 - 2 * s, -0.3), score = 0.3),
            shaft(Vec2(0.2, 0.0), Vec2(0.2 - 2 * s, 0.0), TipRefinement.RAN_OUT, score = 0.2)
        )

        // Highest score first: the long refined one, the one that ran out, the short one.
        assertThat(candidates[0].confidence).isWithin(1e-9).of(1.0)
        assertThat(candidates[1].confidence).isWithin(1e-9).of(ArrowCandidates.UNREFINED_FACTOR)
        assertThat(candidates[2].confidence).isWithin(1e-9).of(0.5)
    }

    @Test
    fun aNegativeShaftContrastGivesNoConfidence() {
        val candidates = build(
            shaft(Vec2(0.2, 0.3), Vec2(-0.3, 0.3)),
            shaft(Vec2(0.2, -0.3), Vec2(-0.3, -0.3), TipRefinement.NO_SHAFT, score = 0.1, shaftContrast = -0.02)
        )

        assertThat(candidates.first { it.refinement == TipRefinement.NO_SHAFT }.confidence).isEqualTo(0.0)
    }

    @Test
    fun theOffsetFromTheFootPointHasTheSignOfTheCrossProduct() {
        // cross(far - tip, Q - tip) / |far - tip| = ((-0.5)(-0.1) - (0.1)(1.1)) / |(-0.5, 0.1)|
        val candidate = build(shaft(Vec2(0.2, 0.1), Vec2(-0.3, 0.2))).single()

        assertThat(candidate.offsetFromFoot).isWithin(1e-9).of(-0.06 / hypot(0.5, 0.1))
    }

    @Test
    fun aTipNextToTheFaceIsNotLocated() {
        val candidates = build(
            shaft(Vec2(0.2, 0.1), Vec2(-0.3, 0.2)),
            shaft(Vec2(-1.02, 0.3), Vec2(-1.3, 0.35))
        )

        val inside = candidates.first { it.tip == Vec2(0.2, 0.1) }
        val located = checkNotNull(inside.located)
        assertThat(located.faceIndex).isEqualTo(0)
        assertThat(located.local).isEqualTo(Vec2(0.2, 0.1))
        assertThat(located.confidence).isEqualTo(inside.confidence)
        assertThat(candidates.first { it.tip == Vec2(-1.02, 0.3) }.located).isNull()
    }
}
