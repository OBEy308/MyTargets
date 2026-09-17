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

import kotlin.math.max
import kotlin.math.min

/**
 * A local maximum of the tip heatmap (design 3d, step 5): [u], [v] in pixels of
 * the network input, pixel centres at integers; [value] the probability there.
 */
data class Peak(val u: Double, val v: Double, val value: Double)

/** The tip heatmap as the network returns it: channel 0, row-major, at the stride's resolution. */
class Heatmap(val width: Int, val height: Int, val values: FloatArray) {

    init {
        require(width > 0 && height > 0) { "a heatmap needs at least one pixel, got $width x $height" }
        require(values.size == width * height) { "expected ${width * height} values for $width x $height, got ${values.size}" }
    }

    operator fun get(x: Int, y: Int): Float = values[y * width + x]
}

/**
 * peaks() of train.py, line for line, so the app cuts the heatmap exactly as
 * the PoC was measured: a pixel is a peak when it equals the maximum of the
 * kernel x kernel window around it (clipped at the border) and reaches the
 * threshold; its position is the centroid of the 3 x 3 window weighted by the
 * raw values, scaled by the stride into input pixels.
 */
object HeatmapPeaks {

    /** Highest value first, stable for equal values; at most [maxCount] when given. */
    fun find(heat: Heatmap, stride: Int, kernel: Int, threshold: Double, maxCount: Int? = null): List<Peak> {
        require(stride >= 1) { "the stride is at least 1, got $stride" }
        require(kernel >= 1 && kernel % 2 == 1) { "the kernel is odd and at least 1, got $kernel" }
        require(maxCount == null || maxCount >= 0) { "maxCount is not negative, got $maxCount" }
        // torch compares the float32 heatmap against the threshold cast to float32.
        val limit = threshold.toFloat()
        val reach = kernel / 2
        val found = ArrayList<Peak>()
        for (y in 0 until heat.height) {
            for (x in 0 until heat.width) {
                val centre = heat[x, y]
                if (centre < limit) continue
                var windowMax = Float.NEGATIVE_INFINITY
                for (j in max(0, y - reach)..min(heat.height - 1, y + reach)) {
                    for (i in max(0, x - reach)..min(heat.width - 1, x + reach)) {
                        windowMax = max(windowMax, heat[i, j])
                    }
                }
                if (centre != windowMax) continue
                var weight = 0.0
                var sumX = 0.0
                var sumY = 0.0
                for (j in max(0, y - 1)..min(heat.height - 1, y + 1)) {
                    for (i in max(0, x - 1)..min(heat.width - 1, x + 1)) {
                        val w = heat[i, j].toDouble()
                        weight += w
                        sumX += i * w
                        sumY += j * w
                    }
                }
                found += Peak(sumX / weight * stride, sumY / weight * stride, centre.toDouble())
            }
        }
        val ranked = found.sortedByDescending { it.value }
        return if (maxCount == null) ranked else ranked.take(maxCount)
    }
}
