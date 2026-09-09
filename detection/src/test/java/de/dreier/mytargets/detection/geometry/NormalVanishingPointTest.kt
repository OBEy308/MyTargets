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
    fun entryPointIsTheEndNearerTheVanishingPoint() {
        val vanishing = Vec2(0.0, 0.0)
        val streaks = listOf(
            Streak(endA = Vec2(10.0, 0.0), endB = Vec2(30.0, 0.0)),
            Streak(endA = Vec2(-40.0, 0.0), endB = Vec2(-15.0, 0.0))
        )
        val entries = NormalVanishingPoint.entryPoints(streaks, vanishing)

        assertThat(entries[0].x).isWithin(1e-9).of(10.0)
        assertThat(entries[1].x).isWithin(1e-9).of(-15.0)
    }

    @Test
    fun aShaftStandingTowardsTheCameraHasItsNockFurtherFromTheVanishingPoint() {
        // The physics the rule rests on, rather than a restatement of the rule.
        // Pinhole camera at the origin looking along +z, frontal target plane at
        // z = 5. An arrow enters at (1, 0.5, 5); its nock stands 0.3 out of the
        // face towards the camera, at z = 4.7. The plane's vanishing line is the
        // image line at infinity, so the normal's vanishing point is the
        // principal point.
        val k = CameraIntrinsics(3000.0, Vec2(2000.0, 1500.0))
        val v = NormalVanishingPoint.compute(Vec3(0.0, 0.0, 1.0), k)!!

        fun project(x: Double, y: Double, z: Double) =
            Vec2(3000.0 * x / z + 2000.0, 3000.0 * y / z + 1500.0)

        val entry = project(1.0, 0.5, 5.0)
        val nock = project(1.0, 0.5, 4.7)

        // Moving towards the camera walks the image away from v.
        assertThat(entry.distanceTo(v)).isLessThan(nock.distanceTo(v))

        val picked = NormalVanishingPoint.entryPoints(listOf(Streak(nock, entry)), v)
        assertThat(picked[0].x).isWithin(1e-9).of(entry.x)
        assertThat(picked[0].y).isWithin(1e-9).of(entry.y)
    }
}
