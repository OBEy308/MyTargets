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

package de.dreier.mytargets.detection

import com.google.common.truth.Truth.assertThat
import de.dreier.mytargets.detection.geometry.Vec2
import org.junit.Test

class SpotMappingTest {

    /** Mirrors WAVegas3Spot: three spots, radius 0.48. */
    private val threeSpot = FaceLayout(
        facePositions = listOf(
            Vec2(-0.52, 0.5),
            Vec2(0.0, -0.5),
            Vec2(0.52, 0.5)
        ),
        faceRadius = 0.48
    )

    @Test
    fun singleSpotLayoutIsTheIdentity() {
        val located = SpotMapping.locate(Vec2(0.3, -0.7), FaceLayout.singleSpot())!!
        assertThat(located.faceIndex).isEqualTo(0)
        assertThat(located.local.x).isWithin(1e-9).of(0.3)
        assertThat(located.local.y).isWithin(1e-9).of(-0.7)
    }

    @Test
    fun centreOfASpotMapsToTheSpotOrigin() {
        val located = SpotMapping.locate(Vec2(0.0, -0.5), threeSpot)!!
        assertThat(located.faceIndex).isEqualTo(1)
        assertThat(located.local.x).isWithin(1e-9).of(0.0)
        assertThat(located.local.y).isWithin(1e-9).of(0.0)
    }

    @Test
    fun edgeOfASpotMapsToUnitRadius() {
        // 0.48 to the right of the centre of spot 2.
        val located = SpotMapping.locate(Vec2(0.52 + 0.48, 0.5), threeSpot)!!
        assertThat(located.faceIndex).isEqualTo(2)
        assertThat(located.local.x).isWithin(1e-9).of(1.0)
        assertThat(located.local.y).isWithin(1e-9).of(0.0)
    }

    @Test
    fun pointBetweenSpotsIsOffTheFace() {
        assertThat(SpotMapping.locate(Vec2(0.0, 0.5), threeSpot)).isNull()
    }

    @Test
    fun picksTheNearestSpotWhenSpotsWouldOverlap() {
        val overlapping = FaceLayout(
            facePositions = listOf(Vec2(-0.2, 0.0), Vec2(0.2, 0.0)),
            faceRadius = 0.5
        )
        val located = SpotMapping.locate(Vec2(0.15, 0.0), overlapping)!!
        assertThat(located.faceIndex).isEqualTo(1)
    }
}
