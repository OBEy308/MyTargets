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
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class ConicTest {

    private fun circlePoints(center: Vec2, radius: Double, count: Int): List<Vec2> =
        (0 until count).map { i ->
            val a = 2.0 * PI * i / count
            Vec2(center.x + radius * cos(a), center.y + radius * sin(a))
        }

    @Test
    fun circleEvaluatesToZeroOnItsOwnPoints() {
        val c = Conic.circle(Vec2(3.0, -1.0), 5.0)
        for (p in circlePoints(Vec2(3.0, -1.0), 5.0, 16)) {
            assertThat(abs(c.evaluate(p))).isLessThan(1e-9)
        }
    }

    @Test
    fun circleEvaluatesNegativeInsideAndPositiveOutside() {
        val c = Conic.circle(Vec2(0.0, 0.0), 2.0)
        assertThat(c.evaluate(Vec2(0.0, 0.0))).isLessThan(0.0)
        assertThat(c.evaluate(Vec2(10.0, 0.0))).isGreaterThan(0.0)
    }

    @Test
    fun fitRecoversACircle() {
        val center = Vec2(120.0, -40.0)
        val radius = 33.0
        val fitted = Conic.fit(circlePoints(center, radius, 24))!!
        for (p in circlePoints(center, radius, 37)) {
            // Scale-free residual: the conic is only defined up to scale.
            assertThat(abs(fitted.normalized().evaluate(p))).isLessThan(1e-6)
        }
    }

    @Test
    fun fitRecoversAnEllipse() {
        val points = (0 until 20).map { i ->
            val a = 2.0 * PI * i / 20
            Vec2(200.0 + 90.0 * cos(a), 150.0 + 30.0 * sin(a))
        }
        val fitted = Conic.fit(points)!!.normalized()
        for (p in points) {
            assertThat(abs(fitted.evaluate(p))).isLessThan(1e-6)
        }
    }

    @Test
    fun fitNeedsAtLeastFivePoints() {
        assertThat(Conic.fit(circlePoints(Vec2(0.0, 0.0), 1.0, 4))).isNull()
    }

    @Test
    fun transformedConicVanishesOnTransformedPoints() {
        val h = Mat3.of(
            1.3, 0.2, 40.0,
            -0.15, 0.95, -25.0,
            0.0004, 0.0009, 1.0
        )
        val center = Vec2(0.0, 0.0)
        val radius = 100.0
        val transformed = Conic.circle(center, radius).transformedBy(h).normalized()

        for (p in circlePoints(center, radius, 24)) {
            val image = h.mapPoint(p)!!
            assertThat(abs(transformed.evaluate(image))).isLessThan(1e-6)
        }
    }

    @Test
    fun poleOfPolarLineIsTheOriginalPoint() {
        val c = Conic.circle(Vec2(2.0, 3.0), 7.0)
        val point = Vec3(11.0, -4.0, 1.0)
        val recovered = c.pole(c.polarLine(point))!!.toVec2()!!
        assertThat(recovered.x).isWithin(1e-7).of(11.0)
        assertThat(recovered.y).isWithin(1e-7).of(-4.0)
    }
}
