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
import kotlin.random.Random

class VanishingLineTest {

    /** A homography with a genuine perspective part. */
    private val h = Mat3.of(
        1.20, 0.18, 300.0,
        -0.10, 0.94, 220.0,
        0.00035, 0.00062, 1.0
    )

    /** The same view at pixel scale: a unit circle becomes a ring of ~480 px. */
    private val hPixels = h * Mat3.of(
        400.0, 0.0, 0.0,
        0.0, 400.0, 0.0,
        0.0, 0.0, 1.0
    )

    /** The image of the line at infinity under [m] is M^-T * (0, 0, 1). */
    private fun expectedLine(m: Mat3): Vec3 =
        (m.inverse()!!.transpose() * Vec3(0.0, 0.0, 1.0)).normalized()

    private fun assertParallel(a: Vec3, b: Vec3, tolerance: Double = 1e-6) {
        // Lines are defined up to scale and sign.
        val cross = a.normalized().cross(b.normalized())
        assertThat(cross.norm).isLessThan(tolerance)
    }

    private fun circlePoints(radius: Double, count: Int): List<Vec2> =
        (0 until count).map { i ->
            val a = 2.0 * PI * i / count
            Vec2(radius * cos(a), radius * sin(a))
        }

    @Test
    fun recoversTheVanishingLineFromTwoCircles() {
        val c1 = Conic.circle(Vec2(0.0, 0.0), 40.0).transformedBy(h)
        val c2 = Conic.circle(Vec2(0.0, 0.0), 80.0).transformedBy(h)

        val result = VanishingLine.fromConcentricCircles(c1, c2)!!
        assertParallel(result.line, expectedLine(h))
    }

    @Test
    fun recoversTheImagedCentre() {
        val c1 = Conic.circle(Vec2(0.0, 0.0), 40.0).transformedBy(h)
        val c2 = Conic.circle(Vec2(0.0, 0.0), 80.0).transformedBy(h)

        val result = VanishingLine.fromConcentricCircles(c1, c2)!!
        // The common centre is the origin, whose image is the last column of h.
        assertThat(result.imagedCentre.x).isWithin(1e-6).of(300.0)
        assertThat(result.imagedCentre.y).isWithin(1e-6).of(220.0)
    }

    @Test
    fun isIndependentOfTheScaleOfTheFittedConics() {
        // Conic fits return an arbitrary scale; the result must not depend on it.
        val c1 = Conic(Conic.circle(Vec2(0.0, 0.0), 40.0).transformedBy(h).matrix.scaled(7.3))
        val c2 = Conic(Conic.circle(Vec2(0.0, 0.0), 80.0).transformedBy(h).matrix.scaled(-0.4))

        val result = VanishingLine.fromConcentricCircles(c1, c2)!!
        assertParallel(result.line, expectedLine(h))
        assertThat(result.imagedCentre.x).isWithin(1e-6).of(300.0)
        assertThat(result.imagedCentre.y).isWithin(1e-6).of(220.0)
    }

    @Test
    fun frontalViewYieldsTheLineAtInfinity() {
        val c1 = Conic.circle(Vec2(500.0, 400.0), 100.0)
        val c2 = Conic.circle(Vec2(500.0, 400.0), 200.0)

        val result = VanishingLine.fromConcentricCircles(c1, c2)!!
        assertThat(abs(result.line.x)).isLessThan(1e-6)
        assertThat(abs(result.line.y)).isLessThan(1e-6)
        assertThat(abs(result.line.z)).isGreaterThan(0.9)
        assertThat(result.imagedCentre.x).isWithin(1e-6).of(500.0)
        assertThat(result.imagedCentre.y).isWithin(1e-6).of(400.0)
    }

    @Test
    fun identicalCirclesHaveNoSolution() {
        val c = Conic.circle(Vec2(0.0, 0.0), 40.0).transformedBy(h)
        assertThat(VanishingLine.fromConcentricCircles(c, c)).isNull()
    }

    @Test
    fun survivesHalfPixelNoiseOnFittedRings() {
        // The exact tests above cannot tell a numerically fragile method from a
        // robust one. This one can: fitted conics from jittered points.
        val random = Random(42)
        fun noisyRing(radius: Double): Conic = Conic.fit(
            circlePoints(radius, 60).map { p ->
                val q = hPixels.mapPoint(p)!!
                Vec2(q.x + random.nextDouble() - 0.5, q.y + random.nextDouble() - 0.5)
            }
        )!!

        val result = VanishingLine.fromConcentricCircles(noisyRing(1.0), noisyRing(0.4))!!

        // Prototype over 500 seeds: centre error below 0.3 px, line cross norm
        // below 2e-6. Tolerances leave a wide margin.
        assertThat(result.imagedCentre.x).isWithin(2.0).of(300.0)
        assertThat(result.imagedCentre.y).isWithin(2.0).of(220.0)
        assertParallel(result.line, expectedLine(hPixels), tolerance = 1e-4)
    }
}
