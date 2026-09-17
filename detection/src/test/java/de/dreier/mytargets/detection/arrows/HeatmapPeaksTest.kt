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
import org.junit.Test

class HeatmapPeaksTest {

    private fun heat(width: Int, height: Int, vararg cells: Triple<Int, Int, Float>): Heatmap {
        val values = FloatArray(width * height)
        for ((x, y, v) in cells) values[y * width + x] = v
        return Heatmap(width, height, values)
    }

    private fun find(heat: Heatmap, threshold: Double = 0.12, maxCount: Int? = null) =
        HeatmapPeaks.find(heat, stride = 2, kernel = 5, threshold = threshold, maxCount = maxCount)

    @Test
    fun aLonePixelIsAPeakAtTwiceItsIndex() {
        val peaks = find(heat(8, 8, Triple(3, 4, 0.5f)))

        assertThat(peaks).containsExactly(Peak(6.0, 8.0, 0.5))
    }

    @Test
    fun belowTheThresholdNothingIsAPeak() {
        assertThat(find(heat(8, 8, Triple(3, 4, 0.11f)))).isEmpty()
    }

    @Test
    fun aValueExactlyOnTheThresholdIsKeptLikeTorchDoes() {
        // torch casts the threshold to float32 before comparing; 0.12f is a hair below 0.12.
        assertThat(find(heat(8, 8, Triple(3, 4, 0.12f)))).hasSize(1)
    }

    @Test
    fun thePositionIsTheCentroidOfTheThreeByThreeWindow() {
        val peaks = find(heat(8, 8, Triple(3, 4, 0.5f), Triple(4, 4, 0.25f)))

        // (3 * 0.5 + 4 * 0.25) / 0.75 = 3.333.. in heat pixels, times the stride.
        assertThat(peaks).hasSize(1)
        assertThat(peaks[0].u).isWithin(1e-9).of(2.0 * (3.0 * 0.5 + 4.0 * 0.25) / 0.75)
        assertThat(peaks[0].v).isWithin(1e-9).of(8.0)
        assertThat(peaks[0].value).isWithin(1e-9).of(0.5)
    }

    @Test
    fun theWindowIsClippedAtTheBorder() {
        val peaks = find(heat(8, 8, Triple(0, 0, 0.5f), Triple(1, 0, 0.1f)))

        assertThat(peaks).hasSize(1)
        assertThat(peaks[0].u).isWithin(1e-6).of(2.0 * (0.0 * 0.5 + 1.0 * 0.1) / 0.6)
        assertThat(peaks[0].v).isWithin(1e-9).of(0.0)
    }

    @Test
    fun twoPeaksCloserThanTheKernelMergeIntoTheHigherOne() {
        // Two pixels apart lies inside the 5 x 5 window; three apart does not.
        val close = find(heat(12, 12, Triple(3, 3, 0.5f), Triple(5, 3, 0.4f)))
        val apart = find(heat(12, 12, Triple(3, 3, 0.5f), Triple(6, 3, 0.4f)))

        // Values come from float32 pixels; compare as Float so 0.4f does not have to equal the Double 0.4.
        assertThat(close.map { it.value.toFloat() }).containsExactly(0.5f)
        assertThat(apart.map { it.value.toFloat() }).containsExactly(0.5f, 0.4f).inOrder()
    }

    @Test
    fun equalNeighboursAreBothPeaks() {
        // The behaviour of peaks(): a plateau keeps every pixel equal to the window maximum.
        assertThat(find(heat(8, 8, Triple(3, 3, 0.5f), Triple(4, 3, 0.5f)))).hasSize(2)
    }

    @Test
    fun peaksComeHighestFirstAndAreCutToMaxCount() {
        val heat = heat(20, 20, Triple(2, 2, 0.3f), Triple(10, 2, 0.9f), Triple(2, 10, 0.6f), Triple(10, 10, 0.2f))

        // Compared as Float for the same reason as above.
        assertThat(find(heat).map { it.value.toFloat() }).containsExactly(0.9f, 0.6f, 0.3f, 0.2f).inOrder()
        assertThat(find(heat, maxCount = 2).map { it.value.toFloat() }).containsExactly(0.9f, 0.6f).inOrder()
        assertThat(find(heat, maxCount = 0)).isEmpty()
    }

    @Test
    fun aHeatmapChecksItsSize() {
        try {
            Heatmap(4, 4, FloatArray(15))
            throw AssertionError("expected an IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertThat(e).hasMessageThat().contains("16")
        }
    }
}
