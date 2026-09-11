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
import de.dreier.mytargets.detection.registration.FaceWarp
import org.opencv.core.CvType
import org.opencv.core.Mat
import kotlin.math.floor
import kotlin.math.max

/**
 * The working image of the arrow search (arrow design, Verfahren step 1): the
 * brightness V = max(B, G, R) / 255 of the rectified face, one float per pixel,
 * and which pixels show the original at all. It spans [-EXTENT, EXTENT]^2 of
 * target coordinates like FaceWarp's square, with pixel centres where
 * FaceWarp.pixelOf puts them.
 */
class RectifiedFace(val edge: Int, private val value: FloatArray, private val valid: BooleanArray) {

    init {
        require(edge > 1) { "a face needs at least 2 x 2 pixels" }
        require(value.size == edge * edge && valid.size == edge * edge) {
            "expected ${edge * edge} pixels"
        }
    }

    /** Pixels per target unit. */
    val pxPerUnit: Double = edge / (2.0 * FaceWarp.EXTENT)

    private fun pixel(t: Double) = pxPerUnit * (t + FaceWarp.EXTENT) - 0.5

    /** V at the pixel nearest to (x, y); NaN off the square or on a pixel outside the original. */
    fun nearest(x: Double, y: Double): Float {
        val px = floor(pixel(x) + 0.5).toInt()
        val py = floor(pixel(y) + 0.5).toInt()
        if (px < 0 || py < 0 || px >= edge || py >= edge) return Float.NaN
        val i = py * edge + px
        return if (valid[i]) value[i] else Float.NaN
    }

    /** V between the four pixels around (x, y); NaN when any of them is off the square or invalid. */
    fun bilinear(x: Double, y: Double): Float {
        val fx = pixel(x)
        val fy = pixel(y)
        val x0 = floor(fx).toInt()
        val y0 = floor(fy).toInt()
        if (x0 < 0 || y0 < 0 || x0 + 1 >= edge || y0 + 1 >= edge) return Float.NaN
        val i = y0 * edge + x0
        if (!valid[i] || !valid[i + 1] || !valid[i + edge] || !valid[i + edge + 1]) return Float.NaN
        val ax = (fx - x0).toFloat()
        val ay = (fy - y0).toFloat()
        val top = value[i] * (1f - ax) + value[i + 1] * ax
        val bottom = value[i + edge] * (1f - ax) + value[i + edge + 1] * ax
        return top * (1f - ay) + bottom * ay
    }

    companion object {

        /**
         * From [warped], the original rectified by FaceWarp.warp with
         * [imageToTarget]. The original was [originalWidth] x [originalHeight].
         */
        fun fromWarped(warped: Mat, imageToTarget: Mat3, originalWidth: Int, originalHeight: Int): RectifiedFace {
            require(warped.type() == CvType.CV_8UC3) { "expected 8-bit BGR, got type ${warped.type()}" }
            require(warped.rows() == warped.cols()) {
                "the rectified face is square, got ${warped.cols()} x ${warped.rows()}"
            }
            val toImage = requireNotNull(imageToTarget.inverse()) { "the homography is singular" }
            val edge = warped.cols()
            val bytes = ByteArray(edge * edge * 3)
            warped.get(0, 0, bytes)
            val value = FloatArray(edge * edge) { i ->
                val b = bytes[3 * i].toInt() and 0xFF
                val g = bytes[3 * i + 1].toInt() and 0xFF
                val r = bytes[3 * i + 2].toInt() and 0xFF
                max(b, max(g, r)) / 255f
            }
            return RectifiedFace(edge, value, validity(toImage, edge, originalWidth, originalHeight))
        }

        /**
         * A pixel is valid when its pre-image under H^-1 lies in
         * [0, width - 1] x [0, height - 1], so the warp's bilinear sampling never
         * reached the black border, and when its homogeneous coordinate has the
         * sign of the face centre's: H^-1 is known only up to a factor, and a
         * point behind the camera has the other sign.
         */
        private fun validity(toImage: Mat3, edge: Int, width: Int, height: Int): BooleanArray {
            val k = edge / (2.0 * FaceWarp.EXTENT)
            val m00 = toImage[0, 0]
            val m01 = toImage[0, 1]
            val m02 = toImage[0, 2]
            val m10 = toImage[1, 0]
            val m11 = toImage[1, 1]
            val m12 = toImage[1, 2]
            val m20 = toImage[2, 0]
            val m21 = toImage[2, 1]
            val m22 = toImage[2, 2]
            // The face centre, target (0, 0), has the homogeneous coordinate m22.
            val sign = if (m22 >= 0.0) 1.0 else -1.0
            val valid = BooleanArray(edge * edge)
            for (j in 0 until edge) {
                val ty = (j + 0.5) / k - FaceWarp.EXTENT
                for (i in 0 until edge) {
                    val tx = (i + 0.5) / k - FaceWarp.EXTENT
                    val w = m20 * tx + m21 * ty + m22
                    if (w * sign <= 0.0) continue
                    val x = (m00 * tx + m01 * ty + m02) / w
                    val y = (m10 * tx + m11 * ty + m12) / w
                    valid[j * edge + i] = x >= 0.0 && x <= width - 1.0 && y >= 0.0 && y <= height - 1.0
                }
            }
            return valid
        }
    }
}
