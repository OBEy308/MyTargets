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

package de.dreier.mytargets.detection.geometry

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class Mat3Test {

    private val tolerance = 1e-9

    @Test
    fun identityMapsPointToItself() {
        val p = Vec2(3.0, -7.0)
        val mapped = Mat3.identity().mapPoint(p)!!
        assertThat(mapped.x).isWithin(tolerance).of(3.0)
        assertThat(mapped.y).isWithin(tolerance).of(-7.0)
    }

    @Test
    fun translationMovesPoint() {
        val mapped = Mat3.translation(Vec2(2.0, 5.0)).mapPoint(Vec2(1.0, 1.0))!!
        assertThat(mapped.x).isWithin(tolerance).of(3.0)
        assertThat(mapped.y).isWithin(tolerance).of(6.0)
    }

    @Test
    fun inverseUndoesMapping() {
        val h = Mat3.of(
            1.4, 0.3, 12.0,
            -0.2, 1.1, -4.0,
            0.0007, 0.0011, 1.0
        )
        val p = Vec2(37.0, -12.5)
        val roundTrip = h.inverse()!!.mapPoint(h.mapPoint(p)!!)!!
        assertThat(roundTrip.x).isWithin(1e-6).of(p.x)
        assertThat(roundTrip.y).isWithin(1e-6).of(p.y)
    }

    @Test
    fun singularMatrixHasNoInverse() {
        val singular = Mat3.of(
            1.0, 2.0, 3.0,
            2.0, 4.0, 6.0,
            1.0, 1.0, 1.0
        )
        assertThat(singular.inverse()).isNull()
    }

    @Test
    fun pointOnVanishingLineHasNoAffineImage() {
        // Third row maps (1, 0) to w = 0.
        val h = Mat3.of(
            1.0, 0.0, 0.0,
            0.0, 1.0, 0.0,
            -1.0, 0.0, 1.0
        )
        assertThat(h.mapPoint(Vec2(1.0, 0.0))).isNull()
    }

    @Test
    fun directionFollowsTheJacobianOfThePointMapping() {
        // A projective map does not preserve directions globally, so a
        // direction only makes sense together with the point it is taken at.
        val h = Mat3.of(
            1.4, 0.3, 12.0,
            -0.2, 1.1, -4.0,
            0.0007, 0.0011, 1.0
        )
        val at = Vec2(37.0, -12.5)
        val direction = Vec2(0.0, -1.0)
        val eps = 1e-5
        val finiteDifference =
            (h.mapPoint(at + direction * eps)!! - h.mapPoint(at)!!) * (1.0 / eps)
        val analytic = h.mapDirection(at, direction)!!
        assertThat(analytic.x).isWithin(1e-6).of(finiteDifference.x)
        assertThat(analytic.y).isWithin(1e-6).of(finiteDifference.y)
    }
}
