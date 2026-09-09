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

class CubicTest {

    private fun assertRootsAre(actual: List<Double>, vararg expected: Double) {
        assertThat(actual).hasSize(expected.size)
        expected.sorted().forEachIndexed { i, e ->
            assertThat(actual[i]).isWithin(1e-7).of(e)
        }
    }

    @Test
    fun threeDistinctRoots() {
        // (x - 1)(x - 2)(x + 3) = x^3 - 7x + 6
        assertRootsAre(Cubic.realRoots(1.0, 0.0, -7.0, 6.0), -3.0, 1.0, 2.0)
    }

    @Test
    fun oneRealRootAndTwoComplex() {
        // (x - 2)(x^2 + 1) = x^3 - 2x^2 + x - 2
        assertRootsAre(Cubic.realRoots(1.0, -2.0, 1.0, -2.0), 2.0)
    }

    @Test
    fun doubleRootIsReportedOnce() {
        // (x - 4)^2 (x + 1) = x^3 - 7x^2 + 8x + 16
        val roots = Cubic.realRoots(1.0, -7.0, 8.0, 16.0)
        assertThat(roots).hasSize(2)
        assertThat(roots[0]).isWithin(1e-6).of(-1.0)
        assertThat(roots[1]).isWithin(1e-6).of(4.0)
    }

    @Test
    fun degeneratesToQuadratic() {
        // 0*x^3 + x^2 - 5x + 6 = 0 has roots 2 and 3
        assertRootsAre(Cubic.realRoots(0.0, 1.0, -5.0, 6.0), 2.0, 3.0)
    }

    @Test
    fun degeneratesToLinear() {
        assertRootsAre(Cubic.realRoots(0.0, 0.0, 2.0, -8.0), 4.0)
    }
}
