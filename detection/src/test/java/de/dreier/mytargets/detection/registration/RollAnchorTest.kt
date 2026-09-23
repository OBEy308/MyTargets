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

package de.dreier.mytargets.detection.registration

import com.google.common.truth.Truth.assertThat
import de.dreier.mytargets.detection.geometry.Vec2
import org.junit.Rule
import org.junit.Test
import org.opencv.core.Mat
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class RollAnchorTest {

    @get:Rule
    val openCv = OpenCvRule()

    private val tilted = SyntheticFace.view(30.0, 300.0, Vec2(800.0, 600.0))

    private fun measure(photo: Mat): RollAnchor.Measurement? =
        try {
            RollAnchor.measure(photo, tilted.inverse()!!)
        } finally {
            photo.release()
        }

    private fun from(radius: Double, degrees: Double) =
        Vec2(radius * cos(Math.toRadians(degrees)), radius * sin(Math.toRadians(degrees)))

    private fun spikes(vararg at: Int): DoubleArray {
        val bins = DoubleArray(RollAnchor.BINS) { 1.0 }
        for (k in at) bins[k] = 50.0
        return bins
    }

    @Test
    fun twoPerpendicularSpikesAreTheirBinCentre() {
        val peak = RollAnchor.peakOf(spikes(30, 120), 1000)
        assertThat(peak.degrees).isWithin(0.05).of(30.5)
        assertThat(peak.strength).isGreaterThan(3.0)
        assertThat(peak.orthogonality).isWithin(0.05).of(0.0)
    }

    @Test
    fun twoShearedSpikesAreAnchoredOnTheirMean() {
        // Edges at 20.5 and 104.5 degrees, 84 apart: modulo 90 at 20.5 and 14.5, the mean 17.5.
        val peak = RollAnchor.peakOf(spikes(20, 104), 1000)
        assertThat(peak.degrees).isWithin(0.1).of(17.5)
        assertThat(peak.orthogonality).isWithin(0.1).of(-6.0)
    }

    @Test
    fun aSingleSpikeWithoutPartnerIsWeak() {
        assertThat(RollAnchor.peakOf(spikes(30), 1000).strength).isLessThan(1.5)
    }

    @Test
    fun spikesAbove45DegreesFoldToTheNearestAxis() {
        assertThat(RollAnchor.peakOf(spikes(80, 170), 1000).degrees).isWithin(0.05).of(-9.5)
    }

    @Test
    fun aFlatHistogramHasStrengthOne() {
        val peak = RollAnchor.peakOf(DoubleArray(RollAnchor.BINS) { 1.0 }, 1000)
        assertThat(peak.strength).isWithin(1e-9).of(1.0)
    }

    @Test
    fun theHistogramHasOneBinPerDegreeOverHalfATurn() {
        assertThat(RollAnchor.BINS).isEqualTo(180)
    }

    @Test
    fun findsTheTurnOfThePaper() {
        for (paper in listOf(-30.0, 0.0, 12.0, 30.0)) {
            val m = measure(SyntheticFace.photograph(1600, 1200, tilted, paperDegrees = paper))!!
            assertThat(m.degrees).isWithin(1.0).of(paper)
            assertThat(m.isAnchor).isTrue()
        }
    }

    @Test
    fun aPaperTurnedBy60DegreesIsCorrectedToTheNearestAxis() {
        val m = measure(SyntheticFace.photograph(1600, 1200, tilted, paperDegrees = 60.0))!!
        assertThat(m.degrees).isWithin(1.0).of(-30.0)
    }

    @Test
    fun radialShaftsDoNotMoveTheAngle() {
        val shafts = (0 until 6).map { k -> from(0.1, 200.0 + 12.0 * k) to from(1.55, 200.0 + 12.0 * k) }
        val m = measure(SyntheticFace.photograph(1600, 1200, tilted, paperDegrees = 12.0, shafts = shafts))!!
        assertThat(m.degrees).isWithin(1.0).of(12.0)
    }

    @Test
    fun parallelShaftsFromTheGoldDoNotMoveTheAngle() {
        // Six shafts leaning the same way, as in 2026-09-14_sonne_stark-schraeg_08.
        val lean = from(1.5, -65.0)
        val shafts = (0 until 6).map { k ->
            val hit = Vec2(-0.15 + 0.06 * k, 0.05 * (k % 2))
            hit to hit + lean
        }
        val m = measure(SyntheticFace.photograph(1600, 1200, tilted, paperDegrees = -8.0, shafts = shafts))!!
        assertThat(m.degrees).isWithin(1.0).of(-8.0)
    }

    @Test
    fun aShearedPaperIsAnchoredOnTheMeanOfItsEdges() {
        // x' = x + tan(8 deg) y keeps the edge along (1, 0) at 0 degrees and lays the edge
        // along (0, 1) on (t, 1), 82 degrees: modulo 90 at 0 and -8, the mean -4; turned by 12
        // degrees the axis lies at 8.
        val m = measure(
            SyntheticFace.photograph(1600, 1200, tilted, paperDegrees = 12.0, paperShearDegrees = 8.0)
        )!!
        assertThat(m.degrees).isWithin(1.0).of(8.0)
        assertThat(abs(m.orthogonality)).isWithin(1.5).of(8.0)
        assertThat(m.isAnchor).isTrue()
    }

    @Test
    fun aBareBackgroundIsNoAnchor() {
        val m = measure(SyntheticFace.photograph(1600, 1200, tilted))
        assertThat(m?.isAnchor ?: false).isFalse()
    }

    @Test
    fun theEdgeOfTheWarpIsNoAnchor() {
        // Face in the corner of the photograph: most of the ring 1.02..1.3
        // (RollAnchor.INNER_RADIUS..OUTER_RADIUS) lies outside, and the black
        // border of the warp would be a perfect straight line.
        val corner = SyntheticFace.view(0.0, 300.0, Vec2(250.0, 250.0))
        val photo = SyntheticFace.photograph(1600, 1200, corner)
        val m = try {
            RollAnchor.measure(photo, corner.inverse()!!)
        } finally {
            photo.release()
        }
        assertThat(m?.isAnchor ?: false).isFalse()
    }
}
