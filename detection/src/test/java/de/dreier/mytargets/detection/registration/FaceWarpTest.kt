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
import de.dreier.mytargets.detection.geometry.Vec2
import org.junit.Rule
import org.junit.Test
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
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

    @Test
    fun targetOfInvertsPixelOf() {
        val edge = 768
        for (target in listOf(Vec2(0.0, 0.0), Vec2(-1.1, -1.1), Vec2(0.37, -0.82))) {
            val back = FaceWarp.targetOf(FaceWarp.pixelOf(target, edge), edge)
            assertThat(back.distanceTo(target)).isLessThan(1e-12)
        }
        // Pixel 383.5 of 768 is the face centre: k = 768 / 2.2, k * 1.1 - 0.5 = 383.5.
        assertThat(FaceWarp.targetOf(Vec2(383.5, 383.5), edge).length).isLessThan(1e-12)
    }

    @Test
    fun aWiderExtentPutsItsCornersAtTheOuterEdges() {
        assertThat(FaceWarp.pixelOf(Vec2(-1.6, -1.6), 640, 1.6).distanceTo(Vec2(-0.5, -0.5)))
            .isLessThan(1e-9)
        assertThat(FaceWarp.pixelOf(Vec2(1.6, 1.6), 640, 1.6).distanceTo(Vec2(639.5, 639.5)))
            .isLessThan(1e-9)
        assertThat(FaceWarp.targetOf(Vec2(319.5, 319.5), 640, 1.6).length).isLessThan(1e-9)
    }

    @Test
    fun aWiderExtentWarpsTheCentreToTheMiddle() {
        // 100 px per radius, centre at (500, 400); a white dot at the centre.
        val imageToTarget = Mat3.of(0.01, 0.0, -5.0, 0.0, 0.01, -4.0, 0.0, 0.0, 1.0)
        val image = Mat(800, 1000, CvType.CV_8UC1, Scalar(0.0))
        Imgproc.circle(image, Point(500.0, 400.0), 3, Scalar(255.0), Imgproc.FILLED)
        val warped = FaceWarp.warp(image, imageToTarget, 320, 1.6)
        try {
            assertThat(warped.get(159, 159)[0]).isGreaterThan(200.0)
            assertThat(warped.get(10, 10)[0]).isEqualTo(0.0)
        } finally {
            image.release()
            warped.release()
        }
    }

    private fun close(bgr: DoubleArray, colour: Scalar) =
        (0..2).all { abs(bgr[it] - colour.`val`[it]) < 30.0 }
}
