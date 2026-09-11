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
import de.dreier.mytargets.detection.geometry.Vec2
import org.junit.Test

class FlankContrastTest {

    private val along = doubleArrayOf(0.0, 0.1, 0.2)
    private val horizontal = Vec2(1.0, 0.0)
    private val vertical = Vec2(0.0, 1.0)

    @Test
    fun aDarkStripeIsDarkerThanBothFlanks() {
        val face = FacePainter(0.9f).stripe(Vec2(-0.5, 0.0), Vec2(0.5, 0.0), 0.012, 0.1f).build()

        for (profile in listOf(
            FlankContrast.SEARCH.profile(face, Vec2(-0.3, 0.0), horizontal, 0.0, along),
            FlankContrast.WALK.profile(face, Vec2(-0.3, 0.0), horizontal, 0.0, along)
        )) {
            for (v in profile) assertThat(v).isWithin(1e-5).of(0.8)
        }
    }

    @Test
    fun aGreyStripeOnBlackIsLighterThanBothFlanks() {
        val face = FacePainter(0.1f).stripe(Vec2(-0.5, 0.0), Vec2(0.5, 0.0), 0.012, 0.5f).build()

        for (v in FlankContrast.SEARCH.profile(face, Vec2(-0.3, 0.0), horizontal, 0.0, along)) {
            assertThat(v).isWithin(1e-5).of(0.4)
        }
    }

    @Test
    fun anEdgeBetweenTwoAreasHasNoContrast() {
        val face = FacePainter(0.9f).below(0.0, 0.3f).build()

        for (v in FlankContrast.SEARCH.profile(face, Vec2(-0.3, 0.0), horizontal, 0.0, along)) {
            assertThat(v).isWithin(1e-5).of(0.0)
        }
    }

    @Test
    fun anOffsetShiftsTheLineToTheLeftOfItsDirection() {
        // The left normal of (1, 0) is (0, 1): an offset of 0.05 puts the line at y = 0.05.
        val face = FacePainter(0.9f).stripe(Vec2(-0.5, 0.05), Vec2(0.5, 0.05), 0.012, 0.1f).build()

        assertThat(FlankContrast.SEARCH.profile(face, Vec2(-0.3, 0.0), horizontal, 0.05, along)[1])
            .isWithin(1e-5).of(0.8)
    }

    @Test
    fun aSampleTouchingAnInvalidPixelHasNoContrast() {
        val stripe = FacePainter(0.9f).stripe(Vec2(0.0, -0.5), Vec2(0.0, 0.5), 0.012, 0.1f)
        val withEdge = FacePainter(0.9f).stripe(Vec2(0.0, -0.5), Vec2(0.0, 0.5), 0.012, 0.1f).invalidLeftOf(-0.01)

        assertThat(FlankContrast.SEARCH.profile(stripe.build(), Vec2(0.0, -0.3), vertical, 0.0, along)[0])
            .isWithin(1e-5).of(0.8)
        // The flank on the side of x < 0 lies at x = -0.016, beyond the edge of the photograph.
        assertThat(FlankContrast.SEARCH.profile(withEdge.build(), Vec2(0.0, -0.3), vertical, 0.0, along)[0])
            .isWithin(1e-9).of(0.0)
    }
}
