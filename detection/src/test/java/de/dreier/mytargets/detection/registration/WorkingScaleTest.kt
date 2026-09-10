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

class WorkingScaleTest {

    @Test
    fun anInheritedPhotographShrinksTo1600() {
        val scale = WorkingScale(3120, 4160)

        assertThat(scale.width).isEqualTo(1200)
        assertThat(scale.height).isEqualTo(1600)
        assertThat(scale.f).isWithin(1e-12).of(0.8)
    }

    @Test
    fun aSmallPhotographIsNeverEnlarged() {
        val scale = WorkingScale(960, 1280)

        assertThat(scale.isOriginal).isTrue()
        assertThat(scale.f).isWithin(1e-12).of(0.64)
    }

    @Test
    fun pixelEdgesMapToPixelEdgesAsResizeMapsThem() {
        // cv::resize maps pixel centres: the outer edge of the original,
        // x = -0.5 and x = W - 0.5, lands on the outer edge of the working image.
        val scale = WorkingScale(3120, 4160)
        val s = scale.smallFromOriginal

        assertThat(s.mapPoint(Vec2(-0.5, -0.5))!!.distanceTo(Vec2(-0.5, -0.5))).isLessThan(1e-9)
        assertThat(s.mapPoint(Vec2(3119.5, 4159.5))!!.distanceTo(Vec2(1199.5, 1599.5)))
            .isLessThan(1e-9)
    }

    @Test
    fun withoutShrinkingTheMappingIsTheIdentity() {
        val s = WorkingScale(1600, 1200).smallFromOriginal

        assertThat(s.mapPoint(Vec2(10.0, 20.0))!!.distanceTo(Vec2(10.0, 20.0))).isLessThan(1e-12)
    }
}
