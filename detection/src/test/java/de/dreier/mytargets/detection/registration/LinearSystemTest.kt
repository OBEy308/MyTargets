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
import org.junit.Test

class LinearSystemTest {

    @Test
    fun solvesAThreeByThreeSystem() {
        val a = arrayOf(
            doubleArrayOf(2.0, 1.0, -1.0),
            doubleArrayOf(-3.0, -1.0, 2.0),
            doubleArrayOf(-2.0, 1.0, 2.0)
        )

        val x = LinearSystem.solve(a, doubleArrayOf(8.0, -11.0, -3.0))!!

        assertThat(x[0]).isWithin(1e-12).of(2.0)
        assertThat(x[1]).isWithin(1e-12).of(3.0)
        assertThat(x[2]).isWithin(1e-12).of(-1.0)
    }

    @Test
    fun aSingularSystemHasNoSolution() {
        val a = arrayOf(doubleArrayOf(1.0, 2.0), doubleArrayOf(2.0, 4.0))

        assertThat(LinearSystem.solve(a, doubleArrayOf(1.0, 2.0))).isNull()
    }
}
