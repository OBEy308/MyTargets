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
import de.dreier.mytargets.detection.registration.OpenCvRule
import org.junit.Rule
import org.junit.Test
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar

class LearnedDebugImagesTest {

    @get:Rule
    val openCv = OpenCvRule()

    private fun grey(edge: Int) = Mat(edge, edge, CvType.CV_8UC3, Scalar(100.0, 100.0, 100.0))

    private fun pixel(mat: Mat, x: Int, y: Int): List<Int> {
        val bgr = ByteArray(3)
        mat.get(y, x, bgr)
        return bgr.map { it.toInt() and 0xFF }
    }

    @Test
    fun theHeatmapGlowsRedWhereItIsHotAndLeavesTheRestAlone() {
        val warped = grey(16)
        val values = FloatArray(8 * 8)
        values[3 * 8 + 2] = 1.0f   // heat pixel (2, 3) covers warped pixels x 4..5, y 6..7
        val image = LearnedDebugImages.heatmap(warped, Heatmap(8, 8, values), stride = 2)
        try {
            assertThat(image.cols()).isEqualTo(16)
            assertThat(image.rows()).isEqualTo(16)
            assertThat(image.nativeObj).isNotEqualTo(warped.nativeObj)
            assertThat(pixel(image, 5, 7)).containsExactly(0, 0, 255).inOrder()
            assertThat(pixel(image, 0, 0)).containsExactly(100, 100, 100).inOrder()
        } finally {
            image.release()
            warped.release()
        }
    }

    @Test
    fun halfHeatBlendsHalfway() {
        val warped = grey(4)
        val values = floatArrayOf(0.5f, 0f, 0f, 0f)
        val image = LearnedDebugImages.heatmap(warped, Heatmap(2, 2, values), stride = 2)
        try {
            // 100 * 0.5 for blue and green, 100 * 0.5 + 255 * 0.5 = 177.5 for red, rounded half up.
            assertThat(pixel(image, 0, 0)).containsExactly(50, 50, 178).inOrder()
        } finally {
            image.release()
            warped.release()
        }
    }

    @Test
    fun peaksAreDrawnInTheirColours() {
        val warped = grey(200)
        val accepted = Peak(50.0, 50.0, 0.9)
        val dropped = Peak(150.0, 50.0, 0.3)
        val outside = Peak(50.0, 150.0, 0.5)
        val image = LearnedDebugImages.peaks(warped, listOf(accepted, outside, dropped), listOf(accepted), listOf(outside))
        try {
            assertThat(image.cols()).isEqualTo(200)
            // The ring of radius 10 passes through (x + 10, y); its colour names the class.
            assertThat(pixel(image, 60, 50)).containsExactly(0, 200, 0).inOrder()      // green
            assertThat(pixel(image, 160, 50)).containsExactly(0, 255, 255).inOrder()   // yellow
            assertThat(pixel(image, 60, 150)).containsExactly(128, 128, 128).inOrder() // grey
        } finally {
            image.release()
            warped.release()
        }
    }
}
