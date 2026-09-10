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
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

class RectificationTest {

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

    private fun circlePoints(radius: Double, count: Int): List<Vec2> =
        (0 until count).map { i ->
            val a = 2.0 * PI * i / count
            Vec2(radius * cos(a), radius * sin(a))
        }

    @Test
    fun mapsImagedRingsBackToConcentricCirclesOfNominalRadius() {
        val outer = Conic.circle(Vec2(0.0, 0.0), 1.0).transformedBy(h)
        val inner = Conic.circle(Vec2(0.0, 0.0), 0.4).transformedBy(h)

        val result = Rectification.fromConcentricCircles(outer, 1.0, inner, 0.4)!!

        for (p in circlePoints(1.0, 24)) {
            val recovered = result.imageToTarget.mapPoint(h.mapPoint(p)!!)!!
            assertThat(hypot(recovered.x, recovered.y)).isWithin(1e-5).of(1.0)
        }
        for (p in circlePoints(0.4, 24)) {
            val recovered = result.imageToTarget.mapPoint(h.mapPoint(p)!!)!!
            assertThat(hypot(recovered.x, recovered.y)).isWithin(1e-5).of(0.4)
        }
    }

    @Test
    fun putsTheCommonCentreAtTheOrigin() {
        val outer = Conic.circle(Vec2(0.0, 0.0), 1.0).transformedBy(h)
        val inner = Conic.circle(Vec2(0.0, 0.0), 0.4).transformedBy(h)

        val result = Rectification.fromConcentricCircles(outer, 1.0, inner, 0.4)!!
        val centre = result.imageToTarget.mapPoint(h.mapPoint(Vec2(0.0, 0.0))!!)!!

        assertThat(abs(centre.x)).isLessThan(1e-6)
        assertThat(abs(centre.y)).isLessThan(1e-6)
    }

    @Test
    fun preservesAnglesAroundTheCentre() {
        // Rotation is fixed by the image up direction, but the angle between two
        // target directions must survive regardless of which rotation was chosen.
        val outer = Conic.circle(Vec2(0.0, 0.0), 1.0).transformedBy(h)
        val inner = Conic.circle(Vec2(0.0, 0.0), 0.4).transformedBy(h)
        val result = Rectification.fromConcentricCircles(outer, 1.0, inner, 0.4)!!

        val a = result.imageToTarget.mapPoint(h.mapPoint(Vec2(0.7, 0.0))!!)!!
        val b = result.imageToTarget.mapPoint(h.mapPoint(Vec2(0.0, 0.7))!!)!!
        val dot = a.x * b.x + a.y * b.y
        assertThat(abs(dot)).isLessThan(1e-5)
    }

    @Test
    fun frontalViewIsRecoveredAsAPureSimilarity() {
        val outer = Conic.circle(Vec2(640.0, 480.0), 300.0)
        val inner = Conic.circle(Vec2(640.0, 480.0), 120.0)

        val result = Rectification.fromConcentricCircles(outer, 1.0, inner, 0.4)!!
        val recovered = result.imageToTarget.mapPoint(Vec2(640.0 + 300.0, 480.0))!!

        assertThat(hypot(recovered.x, recovered.y)).isWithin(1e-6).of(1.0)
    }

    @Test
    fun rejectsConicsThatAreNotConcentricCircles() {
        val notACircle = Conic.fit(
            (0 until 12).map { i ->
                val a = 2.0 * PI * i / 12
                Vec2(300.0 * cos(a), 40.0 * sin(a) + 900.0 * cos(a))
            }
        )!!
        val other = Conic.circle(Vec2(0.0, 0.0), 0.4).transformedBy(h)

        assertThat(Rectification.attempt(notACircle, 1.0, other, 0.4))
            .isInstanceOf(Rectification.Attempt.Rejected::class.java)
        assertThat(Rectification.fromConcentricCircles(notACircle, 1.0, other, 0.4)).isNull()
    }

    @Test
    fun alignsImageUpAtTheFaceCentreWithTargetUp() {
        // Directions are not preserved globally by a projective map, so "image
        // up" has to be taken at the imaged centre of the face. A probe at the
        // image corner gives a rotation that is off by several degrees here.
        val outer = Conic.circle(Vec2(0.0, 0.0), 1.0).transformedBy(hPixels)
        val inner = Conic.circle(Vec2(0.0, 0.0), 0.4).transformedBy(hPixels)
        val result = Rectification.fromConcentricCircles(outer, 1.0, inner, 0.4)!!

        val imagedCentre = hPixels.mapPoint(Vec2(0.0, 0.0))!!
        val up = result.imageToTarget.mapDirection(imagedCentre, Vec2(0.0, -1.0))!!

        // Straight up on the face is the negative y axis.
        assertThat(abs(up.x)).isLessThan(1e-6 * up.length)
        assertThat(up.y).isLessThan(0.0)
    }

    @Test
    fun doesNotMirrorTheFace() {
        // Handedness is not constrained by the rest of the chain, and every
        // other assertion in this file -- radii, dot products, distances --
        // survives a reflection unchanged.
        val outer = Conic.circle(Vec2(640.0, 480.0), 300.0)
        val inner = Conic.circle(Vec2(640.0, 480.0), 120.0)
        val result = Rectification.fromConcentricCircles(outer, 1.0, inner, 0.4)!!

        val right = result.imageToTarget.mapPoint(Vec2(640.0 + 300.0, 480.0))!!
        val up = result.imageToTarget.mapPoint(Vec2(640.0, 480.0 - 300.0))!!

        assertThat(right.x).isWithin(1e-6).of(1.0)
        assertThat(abs(right.y)).isLessThan(1e-6)
        assertThat(up.y).isWithin(1e-6).of(-1.0)
        assertThat(abs(up.x)).isLessThan(1e-6)
    }

    @Test
    fun rejectsRingsWhoseRadiusRatioDisagreesWithTheDeclaredOne() {
        // True ratio 0.4, declared 0.9. This has to reach the ratio guard rather
        // than short-circuiting in the pencil.
        val outer = Conic.circle(Vec2(0.0, 0.0), 1.0).transformedBy(h)
        val inner = Conic.circle(Vec2(0.0, 0.0), 0.4).transformedBy(h)

        val attempt = Rectification.attempt(outer, 1.0, inner, 0.9)

        assertThat(attempt).isInstanceOf(Rectification.Attempt.Rejected::class.java)
        val rejected = attempt as Rectification.Attempt.Rejected
        assertThat(rejected.reason).isEqualTo(Rectification.Reason.RATIO_MISMATCH)
        assertThat(rejected.value!!).isWithin(1e-6).of(0.4)
        assertThat(rejected.limit!!).isWithin(1e-12).of(0.9)
        assertThat(rejected.describe()).isEqualTo("radius ratio 0.400, expected 0.900")
    }

    @Test
    fun aSuccessfulAttemptCarriesTheResult() {
        val outer = Conic.circle(Vec2(640.0, 480.0), 300.0)
        val inner = Conic.circle(Vec2(640.0, 480.0), 120.0)

        val attempt = Rectification.attempt(outer, 1.0, inner, 0.4)

        assertThat(attempt).isInstanceOf(Rectification.Attempt.Rectified::class.java)
        val result = (attempt as Rectification.Attempt.Rectified).result
        val recovered = result.imageToTarget.mapPoint(Vec2(940.0, 480.0))!!
        assertThat(recovered.distanceTo(Vec2(1.0, 0.0))).isLessThan(1e-6)
    }

    @Test
    fun worksForASmallRingFarFromTheImageOrigin() {
        // A 40 cm face at 3 m: a small ring, nowhere near the image origin.
        // The conic arithmetic has to be done in a normalised frame or the
        // guards see noise that is not there.
        val centre = Vec2(2000.0, 1500.0)
        val outer = Conic.circle(centre, 200.0)
        val inner = Conic.circle(centre, 80.0)

        val result = Rectification.fromConcentricCircles(outer, 1.0, inner, 0.4)!!

        val recovered = result.imageToTarget.mapPoint(Vec2(centre.x + 200.0, centre.y))!!
        assertThat(hypot(recovered.x, recovered.y)).isWithin(1e-4).of(1.0)
        assertThat(result.imagedCentre.x).isWithin(1e-3).of(centre.x)
        assertThat(result.imagedCentre.y).isWithin(1e-3).of(centre.y)
    }

    @Test
    fun worksForANoisySmallRingFarFromTheImageOrigin() {
        // The same 40 cm face at 3 m, with the jitter of the noise test above.
        // Before the normalised frame this returned null for the great majority
        // of seeds.
        val centre = Vec2(2000.0, 1500.0)
        val random = Random(42)
        fun noisyRing(radius: Double): Conic = Conic.fit(
            circlePoints(radius, 60).map { p ->
                Vec2(
                    centre.x + p.x + random.nextDouble() - 0.5,
                    centre.y + p.y + random.nextDouble() - 0.5
                )
            }
        )!!

        val result = Rectification.fromConcentricCircles(
            noisyRing(200.0), 1.0, noisyRing(80.0), 0.4
        )!!

        for ((pixelRadius, targetRadius) in listOf(200.0 to 1.0, 80.0 to 0.4)) {
            for (p in circlePoints(pixelRadius, 37)) {
                val recovered = result.imageToTarget
                    .mapPoint(Vec2(centre.x + p.x, centre.y + p.y))!!
                assertThat(hypot(recovered.x, recovered.y))
                    .isWithin(0.02).of(targetRadius)
            }
        }
    }

    @Test
    fun toleratesHalfPixelNoiseOnFittedRings() {
        val random = Random(42)
        fun noisyRing(radius: Double): Conic = Conic.fit(
            circlePoints(radius, 60).map { p ->
                val q = hPixels.mapPoint(p)!!
                Vec2(q.x + random.nextDouble() - 0.5, q.y + random.nextDouble() - 0.5)
            }
        )!!

        val result = Rectification.fromConcentricCircles(
            noisyRing(1.0), 1.0, noisyRing(0.4), 0.4
        )!!

        // Prototype over 500 seeds: radius error below 0.002 target radii.
        for (radius in listOf(1.0, 0.4)) {
            for (p in circlePoints(radius, 37)) {
                val recovered = result.imageToTarget.mapPoint(hPixels.mapPoint(p)!!)!!
                assertThat(hypot(recovered.x, recovered.y)).isWithin(0.01).of(radius)
            }
        }
    }
}
