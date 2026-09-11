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

class SmallKMeansTest {

    /** 100 points symmetric about (0.1, 0.1) and 100 about (0.9, 0.9). */
    private val xs = DoubleArray(200) { i -> (if (i < 100) 0.1 else 0.9) + 0.002 * (i % 10 - 4.5) }
    private val ys = DoubleArray(200) { i -> (if (i < 100) 0.1 else 0.9) + 0.002 * (i / 10 % 10 - 4.5) }

    @Test
    fun eachCentreMovesToTheMeanOfItsCluster() {
        val centres = SmallKMeans.run(
            xs, ys, arrayOf(doubleArrayOf(0.3, 0.3), doubleArrayOf(0.7, 0.7))
        )

        assertThat(centres[0][0]).isWithin(1e-12).of(0.1)
        assertThat(centres[0][1]).isWithin(1e-12).of(0.1)
        assertThat(centres[1][0]).isWithin(1e-12).of(0.9)
        assertThat(centres[1][1]).isWithin(1e-12).of(0.9)
    }

    @Test
    fun aCentreWithTooFewMembersStaysWhereItStarted() {
        val centres = SmallKMeans.run(
            xs, ys,
            arrayOf(doubleArrayOf(0.3, 0.3), doubleArrayOf(0.7, 0.7), doubleArrayOf(5.0, 5.0))
        )

        assertThat(centres[2].toList()).containsExactly(5.0, 5.0).inOrder()
    }

    @Test
    fun theStartIsNotModifiedAndTheResultRepeats() {
        val start = arrayOf(doubleArrayOf(0.3, 0.3), doubleArrayOf(0.7, 0.7))

        val first = SmallKMeans.run(xs, ys, start)
        val second = SmallKMeans.run(xs, ys, start)

        assertThat(start[0].toList()).containsExactly(0.3, 0.3).inOrder()
        assertThat(first.map { it.toList() }).isEqualTo(second.map { it.toList() })
    }
}
