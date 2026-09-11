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
import de.dreier.mytargets.detection.geometry.Vec2
import org.junit.Test
import kotlin.math.PI
import kotlin.math.hypot

class RayTransitionsTest {

    private val centre = Vec2(100.0, 100.0)

    private fun distance(x: Int, y: Int) = hypot(x - centre.x, y - centre.y)

    /** Yellow to radius 30, red to 60, white beyond. */
    private val face = ClassImage.of(200, 200) { x, y ->
        val d = distance(x, y)
        when {
            d < 30 -> ColourClass.YELLOW
            d < 60 -> ColourClass.RED
            else -> ColourClass.WHITE
        }
    }

    private fun yellowToRed(classes: ClassImage, inner: (Double) -> Double?) =
        RayTransitions.find(
            classes, centre, inner, ColourClass.YELLOW, ColourClass.RED,
            rays = 72, lo = 0.6, hi = 1.7, maxGapPx = 12.0
        )

    @Test
    fun findsTheYellowRedBoundaryOnEveryRay() {
        val points = yellowToRed(face) { 30.0 }

        assertThat(points).hasSize(72)
        for (p in points) {
            assertThat(p.distanceTo(centre)).isWithin(1.0).of(30.0)
        }
    }

    @Test
    fun aRayWithTooWideAGapCountsAsOccluded() {
        // A band of OTHER from radius 30 to 45 where x >= 100 and y >= 100:
        // 15 px between yellow and red, more than 12. The rays at 0, 5, ...,
        // 90 degrees run through it -- 19 of 72.
        val occluded = ClassImage.of(200, 200) { x, y ->
            val d = distance(x, y)
            when {
                d < 30 -> ColourClass.YELLOW
                x >= 100 && y >= 100 && d < 45 -> ColourClass.OTHER
                d < 60 -> ColourClass.RED
                else -> ColourClass.WHITE
            }
        }

        val points = yellowToRed(occluded) { 30.0 }

        assertThat(points).hasSize(72 - 19)
    }

    @Test
    fun aRayWithoutAnInnerRadiusIsSkipped() {
        val points = yellowToRed(face) { angle -> if (angle < PI) 30.0 else null }

        assertThat(points).hasSize(36)
    }
}
