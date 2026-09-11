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
import kotlin.math.cos
import kotlin.math.sin

class LinesTest {

    private fun direction(degrees: Double) =
        Vec2(cos(Math.toRadians(degrees)), sin(Math.toRadians(degrees)))

    private fun normal(d: Vec2) = Vec2(-d.y, d.x)

    @Test
    fun theSignedDistanceHasTheSignOfTheCrossProduct() {
        val line = Line2(Vec2(0.0, 0.0), Vec2(2.0, 0.0))

        assertThat(line.signedDistanceTo(Vec2(0.5, 0.3))).isWithin(1e-12).of(0.3)
        assertThat(line.signedDistanceTo(Vec2(0.5, -0.3))).isWithin(1e-12).of(-0.3)
    }

    @Test
    fun aPointIsProjectedOntoTheLine() {
        val p = Line2.through(Vec2(0.0, 0.0), Vec2(1.0, 1.0)).project(Vec2(1.0, 0.0))

        assertThat(p.x).isWithin(1e-12).of(0.5)
        assertThat(p.y).isWithin(1e-12).of(0.5)
    }

    @Test
    fun threeLinesThroughOnePointMeetExactlyThere() {
        val p = Vec2(1.2, -0.1)
        val lines = listOf(10.0, 40.0, 75.0).map { direction(it) }.map { Line2(p + it * 0.7, it) }

        assertThat(CommonPoint.of(lines)!!.distanceTo(p)).isLessThan(1e-9)
    }

    @Test
    fun noisyLinesMeetNearThePoint() {
        val p = Vec2(1.2, -0.1)
        val shifts = listOf(0.002, -0.002, 0.001)
        val lines = listOf(10.0, 40.0, 75.0).mapIndexed { i, degrees ->
            val d = direction(degrees)
            Line2(p + d * 0.7 + normal(d) * shifts[i], d)
        }

        assertThat(CommonPoint.of(lines)!!.distanceTo(p)).isAtMost(0.005)
    }

    @Test
    fun linesCrossingAtTwoDegreesOrLessGiveNoPoint() {
        val a = Line2(Vec2(0.0, 0.0), direction(0.0))

        assertThat(CommonPoint.of(listOf(a, Line2(Vec2(0.0, 0.1), direction(1.5))))).isNull()
        assertThat(CommonPoint.of(listOf(a))).isNull()
        assertThat(CommonPoint.of(listOf(a, Line2(Vec2(0.0, 0.1), direction(3.0))))).isNotNull()
    }
}
