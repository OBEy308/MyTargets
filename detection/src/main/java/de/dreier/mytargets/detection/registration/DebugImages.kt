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

import de.dreier.mytargets.detection.geometry.Vec2
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import kotlin.math.PI

/**
 * The registrar's stage images (Haupt-Spec, Debug-Ansicht). Each call returns
 * a new Mat at the working size; the registrar releases it after the sink.
 */
object DebugImages {

    const val CLASSES = "1-farbklassen"
    const val DISCS = "2-scheiben"
    const val RINGS = "3-ringe"

    private val GREEN = Scalar(0.0, 200.0, 0.0)
    private val RED = Scalar(0.0, 0.0, 255.0)
    private val GREY = Scalar(128.0, 128.0, 128.0)
    private val YELLOW = Scalar(0.0, 255.0, 255.0)
    private val MAGENTA = Scalar(255.0, 0.0, 255.0)

    /** BGR per class, in the order of [ColourClass]. */
    private val PALETTE = arrayOf(
        byteArrayOf(0, 255.toByte(), 255.toByte()), // yellow
        byteArrayOf(0, 0, 255.toByte()), // red
        byteArrayOf(255.toByte(), 0, 0), // blue
        byteArrayOf(0, 0, 0), // black
        byteArrayOf(255.toByte(), 255.toByte(), 255.toByte()), // white
        byteArrayOf(128.toByte(), 128.toByte(), 128.toByte()) // other
    )

    fun classes(classes: ClassImage): Mat {
        val bytes = ByteArray(classes.width * classes.height * 3)
        for (y in 0 until classes.height) {
            for (x in 0 until classes.width) {
                val colour = PALETTE[classes[x, y].ordinal]
                val i = 3 * (y * classes.width + x)
                bytes[i] = colour[0]
                bytes[i + 1] = colour[1]
                bytes[i + 2] = colour[2]
            }
        }
        val mat = Mat(classes.height, classes.width, CvType.CV_8UC3)
        mat.put(0, 0, bytes)
        return mat
    }

    /** Every measured disc thin and grey, the counted ones thick and green. */
    fun discs(small: Mat, search: DiscSearch): Mat {
        val mat = small.clone()
        for (disc in search.measured) {
            Imgproc.circle(mat, point(disc.centre), disc.radius.toInt(), GREY, 1)
        }
        for (disc in search.counted) {
            Imgproc.circle(mat, point(disc.centre), disc.radius.toInt(), GREEN, 3)
            Imgproc.circle(mat, point(disc.centre), 3, GREEN, Imgproc.FILLED)
        }
        return mat
    }

    /**
     * Boundary points green when the fit kept them, red when it did not; the
     * fitted conic yellow for an accepted ring, magenta for a rejected one.
     */
    fun rings(small: Mat, attempts: List<RingAttempt>): Mat {
        val mat = small.clone()
        for (attempt in attempts) {
            val kept = attempt.fit?.inliers?.toHashSet() ?: emptySet()
            for (p in attempt.points) {
                Imgproc.circle(mat, point(p), 1, if (p in kept) GREEN else RED, Imgproc.FILLED)
            }
            val conic = attempt.fit?.conic ?: continue
            val centre = conic.centre() ?: continue
            val outline = (0 until 360).mapNotNull { k ->
                val angle = 2.0 * PI * k / 360
                RingSearch.radiusAlong(conic, centre, angle)?.let { r ->
                    Point(centre.x + r * kotlin.math.cos(angle), centre.y + r * kotlin.math.sin(angle))
                }
            }
            if (outline.size < 2) continue
            val polyline = MatOfPoint(*outline.toTypedArray())
            try {
                Imgproc.polylines(mat, listOf(polyline), true, if (attempt.accepted) YELLOW else MAGENTA, 2)
            } finally {
                polyline.release()
            }
        }
        return mat
    }

    private fun point(v: Vec2) = Point(v.x, v.y)
}
