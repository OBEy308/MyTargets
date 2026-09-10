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
import de.dreier.mytargets.detection.geometry.Conic
import de.dreier.mytargets.detection.geometry.Vec2
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class RobustConicTest {

    private val centre = Vec2(800.0, 600.0)

    private fun onRing(radius: Double, angle: Double) =
        Vec2(centre.x + radius * cos(angle), centre.y + radius * sin(angle))

    private fun ring(radius: Double, count: Int) =
        (0 until count).map { onRing(radius, 2.0 * PI * it / count) }

    /** Most points exactly on radius 150, every tenth one ray step (0.5 px) out. */
    private val quantised = (0 until 400).map { i ->
        onRing(if (i % 10 == 0) 150.5 else 150.0, 2.0 * PI * i / 400)
    }

    @Test
    fun theSampsonDistanceOfACircleIsExact() {
        // For a circle it is (r^2 - R^2) / 2r.
        val d = RobustConic.sampsonDistance(
            Conic.circle(centre, 100.0), Vec2(centre.x + 103.0, centre.y)
        )

        assertThat(d).isWithin(1e-9).of((103.0 * 103.0 - 100.0 * 100.0) / (2 * 103.0))
    }

    @Test
    fun recoversACircleDespiteShaftLikeOutliers() {
        val clean = ring(200.0, 360)
        // Three shafts crossing the ring: runs of points along a ray, outside it.
        val shafts = listOf(0.3, 2.1, 4.4).flatMap { a ->
            (1..20).map { k -> onRing(205.0 + 3.0 * k, a) }
        }

        val fit = RobustConic.fit(clean + shafts, floorPx = 0.4)!!

        assertThat(fit.conic.centre()!!.distanceTo(centre)).isLessThan(0.01)
        assertThat(fit.inliers).containsNoneIn(shafts)
        assertThat(fit.inliers).hasSize(clean.size)
    }

    @Test
    fun theFloorKeepsPointsOneRayStepOff() {
        // With 0.4 px (0.5 px at 2000 px, times f = 0.8) the threshold is 1 px.
        val fit = RobustConic.fit(quantised, floorPx = 0.4)!!

        assertThat(fit.inliers).hasSize(quantised.size)
    }

    @Test
    fun withoutTheFloorThoseSamePointsWouldBeDropped() {
        // Why the floor exists: the median distance nearly vanishes on a clean
        // edge, and the half-pixel points look like outliers.
        val fit = RobustConic.fit(quantised, floorPx = 0.0)!!

        assertThat(fit.inliers.size).isLessThan(quantised.size)
    }

    @Test
    fun theThresholdIsTwoAndAHalfSigmaButNeverBelowTheFloor() {
        assertThat(RobustConic.threshold(0.0, 0.4)).isWithin(1e-6).of(1.0)
        assertThat(RobustConic.threshold(1.0, 0.4)).isWithin(1e-6).of(2.5 * 1.4826)
    }

    @Test
    fun fewerThanFivePointsGiveNoFit() {
        assertThat(RobustConic.fit(ring(100.0, 4), floorPx = 0.4)).isNull()
    }
}
