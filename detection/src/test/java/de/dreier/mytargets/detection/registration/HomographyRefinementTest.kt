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
import de.dreier.mytargets.detection.geometry.Mat3
import de.dreier.mytargets.detection.geometry.Orientation
import de.dreier.mytargets.detection.geometry.Vec2
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class HomographyRefinementTest {

    /** Target to image: a tilted view, about 300 px per unit. */
    private val targetToImage = Mat3.of(
        300.0, 40.0, 700.0,
        -25.0, 280.0, 500.0,
        0.06, 0.09, 1.0
    )
    private val truth = targetToImage.inverse()!!
    private val imagedCentre = targetToImage.mapPoint(Vec2(0.0, 0.0))!!

    private fun ring(radius: Double, count: Int = 180) = RingPoints(
        radius,
        (0 until count).map { i ->
            val a = 2.0 * PI * i / count
            targetToImage.mapPoint(Vec2(radius * cos(a), radius * sin(a)))!!
        }
    )

    private val rings = listOf(0.2, 0.4, 0.6, 0.8).map { ring(it) }

    /** A few percent off the truth, as a two-ring rectification leaves it. */
    private val start = Mat3.of(
        1.03, 0.02, 0.01,
        -0.015, 0.98, -0.02,
        0.0, 0.0, 1.0
    ) * truth

    @Test
    fun theRadialResidualVanishesOnExactPoints() {
        val refined = HomographyRefinement.refine(start, rings)

        for (ring in rings) {
            assertThat(HomographyRefinement.radialRms(refined, ring)).isLessThan(1e-7)
        }
    }

    @Test
    fun afterAligningImageUpTheRefinedHomographyIsTheTruth() {
        // The radial residual leaves the rotation free, so the raw result may be
        // turned about the face centre; only the oriented ones are comparable.
        val refined = Orientation.orient(HomographyRefinement.refine(start, rings), imagedCentre)!!
        val expected = Orientation.orient(truth, imagedCentre)!!

        for (ring in rings) {
            for (p in ring.points) {
                assertThat(refined.mapPoint(p)!!.distanceTo(expected.mapPoint(p)!!))
                    .isLessThan(1e-6)
            }
        }
    }

    @Test
    fun aRingOnTheWrongRadiusIsDropped() {
        // Points of radius 0.66 claimed to lie on 0.6: no homography fits them
        // together with the others, so that ring goes.
        val wrong = RingPoints(0.6, ring(0.66).points)

        val result = HomographyRefinement.refineDroppingRings(
            start, listOf(ring(0.2), ring(0.4), wrong, ring(0.8))
        )

        assertThat(result.rings.map { it.radius }).containsExactly(0.2, 0.4, 0.8).inOrder()
    }

    @Test
    fun twoRingsAreNeverDropped() {
        val wrong = RingPoints(0.4, ring(0.44).points)

        val result = HomographyRefinement.refineDroppingRings(start, listOf(ring(0.2), wrong))

        assertThat(result.rings).hasSize(2)
    }
}
