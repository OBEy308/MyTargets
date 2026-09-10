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
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class RegistrationErrorTest {

    /** Face centre at (1000, 900), 400 px per spot radius, seen head on. */
    private val frontal = listOf(
        1.0 / 400, 0.0, -1000.0 / 400,
        0.0, 1.0 / 400, -900.0 / 400,
        0.0, 0.0, 1.0
    )

    /** A view with perspective: the inverse of a target-to-image map. */
    private val tilted = Homography.inverse(
        doubleArrayOf(400.0, 30.0, 1000.0, -20.0, 380.0, 900.0, 0.05, 0.08, 1.0)
    )!!.toList()

    private fun times(a: List<Double>, b: List<Double>): List<Double> =
        List(9) { i -> (0..2).sumOf { k -> a[i / 3 * 3 + k] * b[k * 3 + i % 3] } }

    @Test
    fun identicalHomographiesHaveNoError() {
        val e = RegistrationError.between(tilted, tilted, 2400, 2000)!!

        assertThat(e.max).isLessThan(1e-9)
        assertThat(e.visiblePoints).isEqualTo(5 * 72)
    }

    @Test
    fun aShiftOfOneHundredthIsAnErrorOfOneHundredthEverywhere() {
        val shift = listOf(1.0, 0.0, 0.01, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)

        val e = RegistrationError.between(tilted, times(shift, tilted), 2400, 2000)!!

        assertThat(e.median).isWithin(1e-9).of(0.01)
        assertThat(e.max).isWithin(1e-9).of(0.01)
    }

    @Test
    fun aRotationOfFiveDegreesIsTheChordOfEachRing() {
        val a = 5.0 * PI / 180
        val rotation = listOf(cos(a), -sin(a), 0.0, sin(a), cos(a), 0.0, 0.0, 0.0, 1.0)

        val e = RegistrationError.between(tilted, times(rotation, tilted), 2400, 2000)!!

        // The chord 2 r sin(a/2) is largest on the rim. Five rings of 72
        // points each put the median on ring 0.6.
        assertThat(e.max).isWithin(1e-9).of(2.0 * sin(a / 2))
        assertThat(e.median).isWithin(1e-9).of(2.0 * 0.6 * sin(a / 2))
    }

    @Test
    fun onlyPointsInsideTheImageCount() {
        // The image ends at x = 999, one pixel left of the face centre. Of the
        // 72 angles of each ring, 95 to 265 degrees lie left of it: 35.
        val e = RegistrationError.between(frontal, frontal, 1000, 2000)!!

        assertThat(e.visiblePoints).isEqualTo(5 * 35)
    }

    @Test
    fun noVisiblePointGivesNull() {
        assertThat(RegistrationError.between(frontal, frontal, 100, 100)).isNull()
    }

    @Test
    fun aWa6RingReferenceScaledToWaFullUnitsMatchesTheWaFullRegistration() {
        // register.py stores WA6Ring references in WA6Ring units, where radius
        // 1.0 sits at 0.6 of the full face: H6 = diag(1/0.6, 1/0.6, 1) · H.
        val wa6 = Homography.scaleTarget(frontal, 1.0 / 0.6)

        val e = RegistrationError.between(
            Homography.scaleTarget(wa6, 0.6), frontal, 2400, 2000
        )!!

        assertThat(e.max).isLessThan(1e-9)
    }
}
