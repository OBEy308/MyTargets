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
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.cos
import kotlin.math.sin

/**
 * Photographs of a WAFull face with an exactly known homography, in print-like
 * colours -- the model's colours are display colours. The face is drawn head
 * on at 1000 px per spot radius and warped into the photograph, so its edges
 * are anti-aliased much as a camera blurs them.
 */
object SyntheticFace {

    val YELLOW = Scalar(40.0, 220.0, 245.0)
    val RED = Scalar(50.0, 50.0, 220.0)
    val BLUE = Scalar(220.0, 150.0, 40.0)
    val BLACK = Scalar(30.0, 30.0, 30.0)
    val WHITE = Scalar(235.0, 235.0, 235.0)

    /** A light grey boss: next to the white ring it reads as white or other, never as black. */
    val BACKGROUND = Scalar(185.0, 190.0, 195.0)

    private const val CANONICAL = 1000.0
    private val RINGS = listOf(1.0 to WHITE, 0.8 to BLACK, 0.6 to BLUE, 0.4 to RED, 0.2 to YELLOW)

    /**
     * Target to photograph for a pinhole camera with a focal length of 1500 px,
     * turned by [angleDegrees] about the vertical axis, [pxPerRadius] across
     * the face centre vertically, and the face centre on [centre]. That is
     * K [r1 r2 t] with r1 = (cos a, 0, -sin a), r2 = (0, 1, 0), t = (0, 0, d),
     * divided by d.
     */
    fun view(angleDegrees: Double, pxPerRadius: Double, centre: Vec2): Mat3 {
        val focal = 1500.0
        val d = focal / pxPerRadius
        val a = Math.toRadians(angleDegrees)
        val c = cos(a)
        val s = sin(a)
        return Mat3.of(
            (focal * c - centre.x * s) / d, 0.0, centre.x,
            -centre.y * s / d, focal / d, centre.y,
            -s / d, 0.0, 1.0
        )
    }

    /** The face seen through [targetToPhoto] on a [width] x [height] photograph. The caller releases it. */
    fun photograph(width: Int, height: Int, targetToPhoto: Mat3): Mat {
        val side = (2.2 * CANONICAL).toInt() + 1
        val canvas = Mat(side, side, CvType.CV_8UC3, BACKGROUND)
        val middle = Point(1.1 * CANONICAL, 1.1 * CANONICAL)
        val canvasFromTarget = Mat3.of(
            CANONICAL, 0.0, 1.1 * CANONICAL,
            0.0, CANONICAL, 1.1 * CANONICAL,
            0.0, 0.0, 1.0
        )
        val map = OpenCvMats.of(canvasFromTarget * targetToPhoto.inverse()!!)
        val photo = Mat()
        try {
            for ((r, colour) in RINGS) {
                Imgproc.circle(canvas, middle, (r * CANONICAL).toInt(), colour, Imgproc.FILLED, Imgproc.LINE_AA)
            }
            // WARP_INVERSE_MAP: the matrix takes a photograph pixel to the canvas.
            Imgproc.warpPerspective(
                canvas, photo, map, Size(width.toDouble(), height.toDouble()),
                Imgproc.INTER_LINEAR or Imgproc.WARP_INVERSE_MAP, Core.BORDER_CONSTANT, BACKGROUND
            )
            return photo
        } finally {
            canvas.release()
            map.release()
        }
    }

    /** Three faces head on, 200 px per spot radius, side by side on 1600 x 600. */
    fun threeFaces(): Mat {
        val photo = Mat(600, 1600, CvType.CV_8UC3, BACKGROUND)
        for (k in 0 until 3) {
            val middle = Point(300.0 + 500.0 * k, 300.0)
            for ((r, colour) in RINGS) {
                Imgproc.circle(photo, middle, (r * 200).toInt(), colour, Imgproc.FILLED, Imgproc.LINE_AA)
            }
        }
        return photo
    }

    fun blank(width: Int, height: Int): Mat = Mat(height, width, CvType.CV_8UC3, BACKGROUND)

    /** The nine values of [m], row by row, as RegistrationError takes them. */
    fun values(m: Mat3): List<Double> = List(9) { m[it / 3, it % 3] }
}
