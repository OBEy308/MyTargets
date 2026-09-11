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

import de.dreier.mytargets.detection.geometry.Mat3
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** The working image: the original shrunk to a long edge of at most 1600 px, never enlarged. */
class WorkingScale(val originalWidth: Int, val originalHeight: Int) {

    private val factor =
        min(1.0, MAX_LONG_EDGE.toDouble() / max(originalWidth, originalHeight))

    val width: Int = max(1, (originalWidth * factor).roundToInt())
    val height: Int = max(1, (originalHeight * factor).roundToInt())

    val isOriginal: Boolean
        get() = width == originalWidth && height == originalHeight

    /** register.py's pixel thresholds hold at a long edge of 2000 px; multiply them by this. */
    val f: Double
        get() = max(width, height) / 2000.0

    /**
     * Working pixel from original pixel. cv::resize maps pixel centres, so
     * x_small = sx * x + (0.5 sx - 0.5), not sx * x; sx and sy differ slightly
     * because the working size is rounded.
     */
    val smallFromOriginal: Mat3
        get() {
            val sx = width.toDouble() / originalWidth
            val sy = height.toDouble() / originalHeight
            return Mat3.of(
                sx, 0.0, 0.5 * sx - 0.5,
                0.0, sy, 0.5 * sy - 0.5,
                0.0, 0.0, 1.0
            )
        }

    companion object {
        const val MAX_LONG_EDGE = 1600
    }
}
