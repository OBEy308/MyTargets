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
import de.dreier.mytargets.detection.geometry.Mat3
import de.dreier.mytargets.detection.registration.FaceWarp
import de.dreier.mytargets.detection.registration.OpenCvRule
import org.junit.Rule
import org.junit.Test
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc

class RectifiedFaceTest {

    @get:Rule
    val openCv = OpenCvRule()

    /**
     * A 200 x 150 photograph maps to target coordinates by (p - (100, 75)) / 100,
     * so it covers x in [-1, 0.99] and y in [-0.75, 0.74] of the square
     * [-1.1, 1.1]^2. FaceWarp gives it 220 px, 100 per unit.
     */
    private val imageToTarget = Mat3.of(0.01, 0.0, -1.0, 0.0, 0.01, -0.75, 0.0, 0.0, 1.0)

    /** BGR (100, 150, 200) with a black 21 x 21 square around the face centre. */
    private fun face(): RectifiedFace {
        val photo = Mat(150, 200, CvType.CV_8UC3, Scalar(100.0, 150.0, 200.0))
        try {
            Imgproc.rectangle(photo, Point(90.0, 65.0), Point(110.0, 85.0), Scalar(0.0, 0.0, 0.0), Imgproc.FILLED)
            val warped = FaceWarp.warp(photo, imageToTarget)
            try {
                return RectifiedFace.fromWarped(warped, imageToTarget, 200, 150)
            } finally {
                warped.release()
            }
        } finally {
            photo.release()
        }
    }

    @Test
    fun theBrightnessIsTheLargestChannel() {
        assertThat(face().nearest(0.5, 0.0).toDouble()).isWithin(1e-6).of(200.0 / 255.0)
    }

    @Test
    fun aBlackPixelOfThePhotographIsValid() {
        val v = face().nearest(0.0, 0.0)

        assertThat(v.isNaN()).isFalse()
        assertThat(v.toDouble()).isWithin(1e-6).of(0.0)
    }

    @Test
    fun aPixelOutsideThePhotographIsNotValid() {
        val face = face()

        assertThat(face.nearest(0.0, 0.9).isNaN()).isTrue()
        assertThat(face.nearest(-1.05, 0.0).isNaN()).isTrue()
    }

    @Test
    fun thePreImageMustLieWithinTheLastPixelCentre() {
        // Pixel centres sit at target (i + 0.5) / 100 - 1.1: pixel 208 at 0.985 has
        // its pre-image at x = 198.5, pixel 209 at 0.995 at x = 199.5 > 199.
        val face = face()

        assertThat(face.nearest(0.985, 0.0).isNaN()).isFalse()
        assertThat(face.nearest(0.995, 0.0).isNaN()).isTrue()
    }

    private fun centreOf(i: Int, edge: Int) = (i + 0.5) * (2.0 * FaceWarp.EXTENT / edge) - FaceWarp.EXTENT

    @Test
    fun bilinearReturnsThePixelAtItsCentreAndInterpolatesBetween() {
        val face = RectifiedFace(4, FloatArray(16) { it.toFloat() }, BooleanArray(16) { true })
        val y = centreOf(1, 4)

        assertThat(face.bilinear(centreOf(1, 4), y).toDouble()).isWithin(1e-5).of(5.0)
        assertThat(face.bilinear((centreOf(1, 4) + centreOf(2, 4)) / 2, y).toDouble()).isWithin(1e-5).of(5.5)
        assertThat(face.bilinear(1.2, 0.0).isNaN()).isTrue()
    }

    @Test
    fun bilinearIsNotANumberNextToAnInvalidPixel() {
        val valid = BooleanArray(16) { true }.also { it[6] = false }
        val face = RectifiedFace(4, FloatArray(16) { it.toFloat() }, valid)

        assertThat(face.bilinear((centreOf(1, 4) + centreOf(2, 4)) / 2, centreOf(1, 4)).isNaN()).isTrue()
        assertThat(face.nearest(centreOf(1, 4), centreOf(1, 4)).isNaN()).isFalse()
    }
}
