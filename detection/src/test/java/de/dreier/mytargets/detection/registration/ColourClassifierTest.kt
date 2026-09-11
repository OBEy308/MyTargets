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
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc

class ColourClassifierTest {

    @get:Rule
    val openCv = OpenCvRule()

    private val yellow = Scalar(40.0, 220.0, 245.0)
    private val red = Scalar(50.0, 50.0, 220.0)
    private val white = Scalar(235.0, 235.0, 235.0)

    private fun classify(mat: Mat, reference: DiscReference? = null): ClassImage =
        try {
            ColourClassifier.classify(HsvPixels.fromBgr(mat), reference)
        } finally {
            mat.release()
        }

    @Test
    fun hsvComesBackInDegreesAndFractions() {
        val mat = Mat(1, 2, CvType.CV_8UC3)
        try {
            mat.put(0, 0, byteArrayOf(0, 0, 255.toByte(), 255.toByte(), 0, 0))
            val hsv = HsvPixels.fromBgr(mat)

            assertThat(hsv.hue(0)).isEqualTo(0.0)
            assertThat(hsv.hue(1)).isEqualTo(240.0)
            assertThat(hsv.saturation(0)).isEqualTo(1.0)
            assertThat(hsv.value(1)).isEqualTo(1.0)
        } finally {
            mat.release()
        }
    }

    @Test
    fun classifiesSixPatches() {
        val colours = listOf(
            yellow, red,
            Scalar(220.0, 150.0, 40.0), // blue
            Scalar(30.0, 30.0, 30.0), // black
            white,
            Scalar(60.0, 160.0, 60.0) // green: none of the face colours
        )
        val mat = Mat(20, 20 * colours.size, CvType.CV_8UC3)
        colours.forEachIndexed { k, c ->
            val patch = mat.submat(0, 20, 20 * k, 20 * (k + 1))
            patch.setTo(c)
            patch.release()
        }

        val classes = classify(mat)

        assertThat((0 until colours.size).map { classes[20 * it + 10, 10] }).containsExactly(
            ColourClass.YELLOW, ColourClass.RED, ColourClass.BLUE,
            ColourClass.BLACK, ColourClass.WHITE, ColourClass.OTHER
        ).inOrder()
    }

    @Test
    fun theSecondPassTakesItsFloorsFromTheDisc() {
        // A pale yellow (saturation 0.23) passes the generous first-pass floor of
        // 0.15, but not half of a strongly saturated disc's 0.8.
        val pale = { Mat(10, 10, CvType.CV_8UC3, Scalar(180.0, 225.0, 235.0)) }

        assertThat(classify(pale())[5, 5]).isEqualTo(ColourClass.YELLOW)
        assertThat(classify(pale(), DiscReference(0.8, 1.0, 0.8, 0.9))[5, 5])
            .isNotEqualTo(ColourClass.YELLOW)
    }

    @Test
    fun theDiscReferenceIsTheMedianOfTheDiscAndOfTheRedRingAroundIt() {
        val mat = Mat(120, 120, CvType.CV_8UC3, white)
        Imgproc.circle(mat, Point(60.0, 60.0), 45, red, -1)
        Imgproc.circle(mat, Point(60.0, 60.0), 20, yellow, -1)
        try {
            val hsv = HsvPixels.fromBgr(mat)
            val classes = ColourClassifier.classify(hsv)

            val reference = ColourClassifier.discReference(hsv, classes, Vec2(60.0, 60.0), 20.0)!!

            // Saturation (max - min) / max: yellow 205/245, red 170/220. Value is
            // normalised by the 98th percentile, which the yellow disc sets.
            assertThat(reference.yellowSaturation).isWithin(0.01).of(205.0 / 245)
            assertThat(reference.yellowValue).isWithin(0.01).of(1.0)
            assertThat(reference.redSaturation).isWithin(0.01).of(170.0 / 220)
            assertThat(reference.redValue).isWithin(0.01).of(220.0 / 245)
        } finally {
            mat.release()
        }
    }

    @Test
    fun noDiscReferenceWithoutEnoughYellowAndRed() {
        val mat = Mat(60, 60, CvType.CV_8UC3, white)
        try {
            val hsv = HsvPixels.fromBgr(mat)

            assertThat(
                ColourClassifier.discReference(hsv, ColourClassifier.classify(hsv), Vec2(30.0, 30.0), 10.0)
            ).isNull()
        } finally {
            mat.release()
        }
    }
}
