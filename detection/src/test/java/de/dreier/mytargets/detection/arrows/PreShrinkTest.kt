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
import de.dreier.mytargets.detection.geometry.Vec2
import de.dreier.mytargets.detection.registration.OpenCvRule
import org.junit.Rule
import org.junit.Test
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar

class PreShrinkTest {

    @get:Rule
    val openCv = OpenCvRule()

    @Test
    fun thePhotographOfTheCorpusShrinksToHalf() {
        // 4000 x 2252, the S25's photographs: pre = 2000 / 4000.
        val shrink = PreShrink.of(4000, 2252, 2000)

        assertThat(shrink.factor).isWithin(1e-12).of(0.5)
        assertThat(shrink.width).isEqualTo(2000)
        assertThat(shrink.height).isEqualTo(1126)
        assertThat(shrink.isIdentity).isFalse()
    }

    @Test
    fun aSmallImageStaysAsItIs() {
        val shrink = PreShrink.of(1600, 1200, 2000)

        assertThat(shrink.isIdentity).isTrue()
        assertThat(shrink.width).isEqualTo(1600)
        assertThat(shrink.height).isEqualTo(1200)
    }

    @Test
    fun theSizeRoundsHalfToEvenLikePython() {
        // Python's round(2.5) is 2 and round(3.5) is 4; Math.rint agrees, roundToInt would not.
        assertThat(PreShrink.of(10, 5, 5).height).isEqualTo(2)   // 5 * 0.5 = 2.5 -> 2
        assertThat(PreShrink.of(10, 7, 5).height).isEqualTo(4)   // 7 * 0.5 = 3.5 -> 4
    }

    @Test
    fun theShrunkHomographyMapsTheShrunkPixelWhereTheOriginalMappedTheOriginalPixel() {
        val shrink = PreShrink.of(4000, 2252, 2000)
        val original = Mat3.of(
            0.00236109, 7e-08, -4.19406437,
            0.00016237, 0.00213358, -3.48990962,
            0.0006076, -5.421e-05, 1.0
        )
        val p = Vec2(1775.17, 1501.02)

        val fromOriginal = original.mapPoint(p)!!
        val fromShrunk = shrink.imageToTarget(original).mapPoint(p * shrink.factor)!!

        assertThat(fromShrunk.distanceTo(fromOriginal)).isLessThan(1e-9)
    }

    @Test
    fun shrinkResamplesToTheComputedSize() {
        val image = Mat(2252, 4000, CvType.CV_8UC3, Scalar(10.0, 20.0, 30.0))
        val small = PreShrink.of(4000, 2252, 2000).shrink(image)
        try {
            assertThat(small.cols()).isEqualTo(2000)
            assertThat(small.rows()).isEqualTo(1126)
            assertThat(small.type()).isEqualTo(CvType.CV_8UC3)
        } finally {
            small.release()
            image.release()
        }
    }

    @Test
    fun anIdentityShrinkReturnsACopy() {
        val image = Mat(1200, 1600, CvType.CV_8UC3, Scalar(10.0, 20.0, 30.0))
        val small = PreShrink.of(1600, 1200, 2000).shrink(image)
        try {
            assertThat(small.nativeObj).isNotEqualTo(image.nativeObj)
            assertThat(small.cols()).isEqualTo(1600)
        } finally {
            small.release()
            image.release()
        }
    }
}
