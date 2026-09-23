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

import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import kotlin.math.cos
import kotlin.math.sin

/**
 * Stage [RollAnchor.STAGE]: the warped surroundings, the counted pixels
 * between the two circles, the two edge directions in red, the averaged axis
 * and its perpendicular in yellow, the histogram over 180 degrees.
 */
internal object RollAnchorImages {

    private const val HISTOGRAM_HEIGHT = 120
    private val COUNTED = Scalar(0.0, 200.0, 0.0)
    private val EDGES = Scalar(0.0, 0.0, 255.0)
    private val AXIS = Scalar(0.0, 255.0, 255.0)
    private val BAR = Scalar(200.0, 200.0, 200.0)

    fun render(warped: Mat, counted: BooleanArray, measurement: RollAnchor.Measurement?): Mat {
        val edge = RollAnchor.EDGE
        return Mat(edge + HISTOGRAM_HEIGHT, edge, CvType.CV_8UC3, Scalar(0.0, 0.0, 0.0)).releaseIfThrows { out ->
            val top = out.submat(0, edge, 0, edge)
            try {
                Imgproc.cvtColor(warped, top, Imgproc.COLOR_GRAY2BGR)
                val green = byteArrayOf(0, 200.toByte(), 0)
                for (i in counted.indices) {
                    if (counted[i]) top.put(i / edge, i % edge, green)
                }
            } finally {
                top.release()
            }
            val centre = Point(edge / 2.0, edge / 2.0)
            for (radius in listOf(RollAnchor.INNER_RADIUS, RollAnchor.OUTER_RADIUS)) {
                Imgproc.circle(out, centre, (edge / 2.0 * radius / RollAnchor.EXTENT).toInt(), COUNTED, 1)
            }
            if (measurement != null) {
                // A gradient peak at g is an edge running along g + 90 degrees.
                for (g in listOf(measurement.edgeDegrees.first, measurement.edgeDegrees.second)) {
                    line(out, centre, Math.toRadians(g + 90.0), EDGES, 1)
                }
                for (quarter in 0 until 2) {
                    line(out, centre, measurement.radians + quarter * Math.PI / 2.0, AXIS, 2)
                }
                val peak = measurement.histogram.maxOrNull() ?: 0.0
                val width = edge.toDouble() / measurement.histogram.size
                for ((k, value) in measurement.histogram.withIndex()) {
                    val h = if (peak > 0.0) value / peak * (HISTOGRAM_HEIGHT - 10) else 0.0
                    Imgproc.rectangle(
                        out,
                        Point(k * width, (edge + HISTOGRAM_HEIGHT).toDouble()),
                        Point((k + 1) * width - 1, edge + HISTOGRAM_HEIGHT - h),
                        BAR, Imgproc.FILLED
                    )
                }
                Imgproc.putText(
                    out,
                    String.format(
                        java.util.Locale.ROOT, "axis %.1f deg  strength %.2f  %d px  orthogonality %.1f deg",
                        measurement.degrees, measurement.strength, measurement.pixels, measurement.orthogonality
                    ),
                    Point(8.0, edge + 20.0), Imgproc.FONT_HERSHEY_SIMPLEX, 0.6, AXIS, 1
                )
            }
        }
    }

    private fun line(out: Mat, centre: Point, radians: Double, colour: Scalar, thickness: Int) {
        val half = RollAnchor.EDGE / 2.0
        val d = Point(cos(radians) * half, sin(radians) * half)
        Imgproc.line(out, Point(centre.x - d.x, centre.y - d.y), Point(centre.x + d.x, centre.y + d.y), colour, thickness)
    }
}
