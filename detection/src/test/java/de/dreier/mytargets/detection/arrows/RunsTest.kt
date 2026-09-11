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
import org.junit.Test

class RunsTest {

    private val rings = listOf(0.05, 0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9, 1.0)

    @Test
    fun gapsUpToTheLimitAreBridged() {
        val contrast = doubleArrayOf(0.0, 0.2, 0.2, 0.0, 0.2)

        val apart = Runs.find(contrast, 0.15, 1)
        assertThat(apart.map { it.first to it.last }).containsExactly(1 to 2, 4 to 4).inOrder()

        val joined = Runs.find(contrast, 0.15, 2).single()
        assertThat(joined.first to joined.last).isEqualTo(1 to 4)
        assertThat(joined.darkSamples).isEqualTo(3)
        assertThat(joined.fill).isWithin(1e-12).of(0.75)
    }

    @Test
    fun theThresholdIsStrict() {
        assertThat(Runs.find(doubleArrayOf(0.15, 0.15), 0.15, 10)).isEmpty()
    }

    @Test
    fun tenMissingStepsOfPointZeroZeroTwoAreStillOneRun() {
        // radial.py: a gap of 0.02 at steps of 0.002 is ten indices.
        val contrast = DoubleArray(30).also { it[0] = 0.5; it[10] = 0.5; it[22] = 0.5 }

        assertThat(Runs.find(contrast, 0.15, 10).map { it.first to it.last })
            .containsExactly(0 to 10, 22 to 22).inOrder()
    }

    private fun run(span: Int, dark: Int) = Run(0, span - 1, dark)

    @Test
    fun aLongFullRunAwayFromTheRingsIsKept() {
        assertThat(RunRules.keep(run(50, 45), 0.098, DoubleArray(50) { 0.35 }, rings)).isTrue()
    }

    @Test
    fun aShortOrSparseRunIsDropped() {
        assertThat(RunRules.keep(run(50, 45), 0.07, DoubleArray(50) { 0.35 }, rings)).isFalse()
        assertThat(RunRules.keep(run(50, 25), 0.098, DoubleArray(50) { 0.35 }, rings)).isFalse()
    }

    @Test
    fun aRunWhollyOffTheFaceIsDropped() {
        assertThat(RunRules.keep(run(50, 45), 0.098, DoubleArray(50) { 1.03 }, rings)).isFalse()
    }

    @Test
    fun aRunAlongARingLineIsDropped() {
        // More than 40 % of the samples within 0.009 of ring 0.4 is a ring line, 40 % is not yet.
        val half = DoubleArray(50) { if (it < 25) 0.405 else 0.35 }
        val forty = DoubleArray(50) { if (it < 20) 0.405 else 0.35 }

        assertThat(RunRules.keep(run(50, 45), 0.098, half, rings)).isFalse()
        assertThat(RunRules.keep(run(50, 45), 0.098, forty, rings)).isTrue()
    }
}
