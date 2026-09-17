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

import de.dreier.mytargets.detection.geometry.Mat3
import de.dreier.mytargets.detection.registration.releaseIfThrows
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/**
 * The pre-shrink of prepare.py (design 3d, step 2): the longest side of the
 * original down to [maxSide] pixels with INTER_AREA before the warp, so the
 * warp samples the pixels the model was trained on. The size rounds half to
 * even like Python's round(); kotlin.math.round does the same (it is
 * Math.rint on the JVM), and roundToInt would not.
 */
class PreShrink private constructor(val factor: Double, val width: Int, val height: Int) {

    val isIdentity: Boolean
        get() = factor == 1.0

    /** A new image of [width] x [height]; a copy when nothing shrinks. The caller releases it. */
    fun shrink(image: Mat): Mat =
        if (isIdentity) {
            image.clone()
        } else {
            Mat().releaseIfThrows { small ->
                Imgproc.resize(image, small, Size(width.toDouble(), height.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
            }
        }

    /** The homography from pixels of the shrunk image to target coordinates: H times S_pre of prepare.py. */
    fun imageToTarget(fromOriginal: Mat3): Mat3 =
        fromOriginal * Mat3.of(
            1.0 / factor, 0.0, 0.0,
            0.0, 1.0 / factor, 0.0,
            0.0, 0.0, 1.0
        )

    companion object {
        fun of(width: Int, height: Int, maxSide: Int): PreShrink {
            require(width > 0 && height > 0) { "an image has positive size, got $width x $height" }
            require(maxSide > 0) { "maxSide is positive, got $maxSide" }
            val factor = min(1.0, maxSide.toDouble() / max(width, height))
            return PreShrink(factor, round(width * factor).toInt(), round(height * factor).toInt())
        }
    }
}
