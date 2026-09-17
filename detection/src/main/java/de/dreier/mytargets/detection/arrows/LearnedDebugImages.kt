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

import de.dreier.mytargets.detection.registration.releaseIfThrows
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The learned finder's stage images (design 3d, Debug-Bilder), drawn on the
 * rectified input of the network. Each call returns a new Mat; the detector
 * releases it after the sink.
 */
object LearnedDebugImages {

    const val HEATMAP = "5-heatmap"
    const val PEAKS = "6-spitzen"

    private val WHITE = Scalar(255.0, 255.0, 255.0)
    private val GREEN = Scalar(0.0, 200.0, 0.0)
    private val YELLOW = Scalar(0.0, 255.0, 255.0)
    private val GREY = Scalar(128.0, 128.0, 128.0)

    /** The tip heatmap as a red glow: each pixel blends towards pure red by its probability. */
    fun heatmap(warped: Mat, heat: Heatmap, stride: Int): Mat =
        warped.clone().releaseIfThrows { mat ->
            require(warped.type() == CvType.CV_8UC3) { "the rectified input is 8-bit BGR, got type ${warped.type()}" }
            val width = mat.cols()
            val height = mat.rows()
            val bytes = ByteArray(width * height * 3)
            mat.get(0, 0, bytes)
            for (y in 0 until height) {
                val hy = min(y / stride, heat.height - 1)
                for (x in 0 until width) {
                    val p = heat[min(x / stride, heat.width - 1), hy].coerceIn(0f, 1f)
                    if (p <= 0f) continue
                    val i = 3 * (y * width + x)
                    val keep = 1f - p
                    bytes[i] = ((bytes[i].toInt() and 0xFF) * keep).roundToInt().toByte()
                    bytes[i + 1] = ((bytes[i + 1].toInt() and 0xFF) * keep).roundToInt().toByte()
                    bytes[i + 2] = ((bytes[i + 2].toInt() and 0xFF) * keep + 255f * p).roundToInt().toByte()
                }
            }
            mat.put(0, 0, bytes)
        }

    /**
     * Every peak at or above the threshold, numbered by rank with its value:
     * green when accepted, grey when outside every spot, yellow when it fell
     * under the count or the spot cap.
     */
    fun peaks(warped: Mat, peaks: List<Peak>, accepted: List<Peak>, outsideFace: List<Peak>): Mat =
        warped.clone().releaseIfThrows { mat ->
            peaks.forEachIndexed { rank, p ->
                val colour = when {
                    accepted.any { it === p } -> GREEN
                    outsideFace.any { it === p } -> GREY
                    else -> YELLOW
                }
                val at = Point(p.u, p.v)
                Imgproc.circle(mat, at, 10, colour, 2)
                Imgproc.putText(
                    mat, "${rank + 1} ${String.format(Locale.ROOT, "%.2f", p.value)}", Point(p.u + 12, p.v - 12),
                    Imgproc.FONT_HERSHEY_SIMPLEX, 0.6, WHITE, 2
                )
            }
        }
}
