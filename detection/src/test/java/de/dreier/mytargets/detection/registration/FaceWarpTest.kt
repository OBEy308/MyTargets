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
import org.junit.Rule
import org.junit.Test
import org.opencv.core.Scalar
import kotlin.math.abs
import kotlin.math.roundToInt

class FaceWarpTest {

    @get:Rule
    val openCv = OpenCvRule()

    @Test
    fun theEdgeFollowsThePixelDensityAtTheFaceCentre() {
        val imageToTarget = SyntheticFace.view(0.0, 400.0, Vec2(800.0, 600.0)).inverse()!!

        assertThat(FaceWarp.edgeFor(imageToTarget)).isEqualTo(880)
    }

    @Test
    fun theEdgeIsCappedAt3000() {
        val imageToTarget = SyntheticFace.view(0.0, 2000.0, Vec2(800.0, 600.0)).inverse()!!

        assertThat(FaceWarp.edgeFor(imageToTarget)).isEqualTo(FaceWarp.MAX_EDGE)
    }

    @Test
    fun theCornersOfTheSquareAreTheOuterEdgesOfTheImage() {
        assertThat(FaceWarp.pixelOf(Vec2(-1.1, -1.1), 880).distanceTo(Vec2(-0.5, -0.5)))
            .isLessThan(1e-9)
        assertThat(FaceWarp.pixelOf(Vec2(1.1, 1.1), 880).distanceTo(Vec2(879.5, 879.5)))
            .isLessThan(1e-9)
    }

    @Test
    fun theRectifiedFaceHasEachColourAtItsRadius() {
        val view = SyntheticFace.view(30.0, 400.0, Vec2(800.0, 600.0))
        val photo = SyntheticFace.photograph(1600, 1200, view)
        val warped = FaceWarp.warp(photo, view.inverse()!!)
        try {
            val expected = listOf(
                0.1 to SyntheticFace.YELLOW, 0.3 to SyntheticFace.RED, 0.5 to SyntheticFace.BLUE,
                0.7 to SyntheticFace.BLACK, 0.9 to SyntheticFace.WHITE
            )
            for ((radius, colour) in expected) {
                // Sampled below the centre, where the 30 degree turn does not foreshorten.
                val p = FaceWarp.pixelOf(Vec2(0.0, radius), warped.cols())
                val bgr = warped.get(p.y.roundToInt(), p.x.roundToInt())
                assertThat(close(bgr, colour)).isTrue()
            }
        } finally {
            photo.release()
            warped.release()
        }
    }

    private fun close(bgr: DoubleArray, colour: Scalar) =
        (0..2).all { abs(bgr[it] - colour.`val`[it]) < 30.0 }
}
