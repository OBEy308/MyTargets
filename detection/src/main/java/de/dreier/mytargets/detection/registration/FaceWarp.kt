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
import de.dreier.mytargets.detection.geometry.Vec2
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Stage 3 of the Haupt-Spec: the original resampled into the square
 * [-EXTENT, EXTENT]^2 of target coordinates. The edge length follows the
 * original's pixel density at the face centre rather than a fixed value, and
 * is capped to bound the memory a 12-MP photograph can take.
 */
object FaceWarp {

    const val EXTENT = 1.1
    const val MAX_EDGE = 3000

    fun edgeFor(imageToTarget: Mat3): Int {
        val targetToImage = requireNotNull(imageToTarget.inverse()) { "the homography is singular" }
        val centre = Vec2(0.0, 0.0)
        val alongX = targetToImage.mapDirection(centre, Vec2(1.0, 0.0))?.length ?: 0.0
        val alongY = targetToImage.mapDirection(centre, Vec2(0.0, 1.0))?.length ?: 0.0
        val pxPerUnit = max(alongX, alongY)
        return min(MAX_EDGE, ceil(2.0 * EXTENT * pxPerUnit).toInt()).coerceAtLeast(1)
    }

    /** Pixel of [target] in a warped image of [edge] px; pixel centres at integers. */
    fun pixelOf(target: Vec2, edge: Int): Vec2 {
        val k = edge / (2.0 * EXTENT)
        return Vec2(k * (target.x + EXTENT) - 0.5, k * (target.y + EXTENT) - 0.5)
    }

    /** A new [edge] x [edge] image; the caller releases it. */
    fun warp(original: Mat, imageToTarget: Mat3, edge: Int = edgeFor(imageToTarget)): Mat {
        val k = edge / (2.0 * EXTENT)
        val warpedFromTarget = Mat3.of(
            k, 0.0, k * EXTENT - 0.5,
            0.0, k, k * EXTENT - 0.5,
            0.0, 0.0, 1.0
        )
        val map = OpenCvMats.of(warpedFromTarget * imageToTarget)
        val warped = Mat()
        try {
            Imgproc.warpPerspective(
                original, warped, map, Size(edge.toDouble(), edge.toDouble()),
                Imgproc.INTER_LINEAR, Core.BORDER_CONSTANT, Scalar(0.0, 0.0, 0.0)
            )
            return warped
        } finally {
            map.release()
        }
    }
}
