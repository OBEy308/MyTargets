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

class NormalVanishingPointTest {

    @Test
    fun approximateIntrinsicsPutThePrincipalPointAtTheImageCentre() {
        val k = CameraIntrinsics.approximate(4000, 3000)
        assertThat(k.principalPoint.x).isWithin(1e-9).of(2000.0)
        assertThat(k.principalPoint.y).isWithin(1e-9).of(1500.0)
        // Fallback focal length: 0.75 times the long edge, about a 27 mm lens
        // in 35 mm terms, which is where phone main cameras sit.
        assertThat(k.focalLengthPx).isWithin(1e-9).of(3000.0)
    }

    @Test
    fun focalLengthFollowsFromThe35mmEquivalent() {
        // A 36 mm wide film frame spans the long edge of the image.
        val k = CameraIntrinsics.from35mmEquivalent(4000, 3000, focalLength35mm = 26.0)
        assertThat(k.focalLengthPx).isWithin(1e-6).of(26.0 / 36.0 * 4000.0)
        assertThat(k.principalPoint.x).isWithin(1e-9).of(2000.0)
    }

    @Test
    fun frontalViewHasItsNormalVanishingPointAtThePrincipalPoint() {
        val k = CameraIntrinsics.approximate(4000, 3000)
        // A frontal plane has the line at infinity as its vanishing line.
        val v = NormalVanishingPoint.compute(Vec3(0.0, 0.0, 1.0), k)!!
        assertThat(v.x).isWithin(1e-6).of(2000.0)
        assertThat(v.y).isWithin(1e-6).of(1500.0)
    }

    @Test
    fun aPlaneTiltedAboutTheHorizontalAxisMovesTheVanishingPointVertically() {
        val k = CameraIntrinsics.approximate(4000, 3000)
        // The line (0, 1, 1000) is y = -1000: horizontal, above the image
        // (image y grows downwards). The normal then vanishes on the opposite
        // side, below the principal point. With f = 3000 the value is
        // v = (2000, 5100).
        val v = NormalVanishingPoint.compute(Vec3(0.0, 1.0, 1000.0), k)!!
        assertThat(v.x).isWithin(1e-6).of(2000.0)
        assertThat(v.y).isWithin(1e-6).of(5100.0)
    }

    @Test
    fun entryPointIsTheEndFurtherFromTheVanishingPoint() {
        val vanishing = Vec2(0.0, 0.0)
        val streaks = listOf(
            Streak(endA = Vec2(10.0, 0.0), endB = Vec2(30.0, 0.0)),
            Streak(endA = Vec2(-40.0, 0.0), endB = Vec2(-15.0, 0.0))
        )
        val entries = NormalVanishingPoint.entryPoints(streaks, vanishing)

        assertThat(entries[0].x).isWithin(1e-9).of(30.0)
        assertThat(entries[1].x).isWithin(1e-9).of(-40.0)
    }
}
