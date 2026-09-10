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

class OrientationTest {

    /** Head on: face centre at (640, 480), 300 px per target unit. */
    private val frontal = Mat3.of(
        1.0 / 300, 0.0, -640.0 / 300,
        0.0, 1.0 / 300, -480.0 / 300,
        0.0, 0.0, 1.0
    )
    private val centre = Vec2(640.0, 480.0)
    private val right = Vec2(940.0, 480.0)
    private val up = Vec2(640.0, 180.0)

    @Test
    fun anUprightMappingStaysAsItIs() {
        val oriented = Orientation.orient(frontal, centre)!!

        assertThat(oriented.mapPoint(right)!!.distanceTo(Vec2(1.0, 0.0))).isLessThan(1e-12)
        assertThat(oriented.mapPoint(up)!!.distanceTo(Vec2(0.0, -1.0))).isLessThan(1e-12)
    }

    @Test
    fun aMappingMirroredInXIsRepaired() {
        // Needs both steps: the reflection repair leaves a half turn, which
        // the rotation then undoes.
        val mirrored = Mat3.of(-1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0) * frontal
        assertThat(Orientation.isMirrored(mirrored, centre)).isTrue()

        val oriented = Orientation.orient(mirrored, centre)!!

        assertThat(Orientation.isMirrored(oriented, centre)).isFalse()
        assertThat(oriented.mapPoint(right)!!.distanceTo(Vec2(1.0, 0.0))).isLessThan(1e-9)
        assertThat(oriented.mapPoint(up)!!.distanceTo(Vec2(0.0, -1.0))).isLessThan(1e-9)
    }

    @Test
    fun aRotatedMappingIsTurnedBackToImageUp() {
        val oriented = Orientation.orient(Mat3.rotation(0.7) * frontal, centre)!!

        assertThat(oriented.mapPoint(up)!!.distanceTo(Vec2(0.0, -1.0))).isLessThan(1e-9)
    }
}
