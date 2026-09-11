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

import de.dreier.mytargets.detection.Candidate
import de.dreier.mytargets.detection.geometry.Vec2
import de.dreier.mytargets.detection.registration.FaceWarp
import de.dreier.mytargets.detection.registration.releaseIfThrows
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import kotlin.math.abs

/**
 * The arrow search's stage images (arrow design, Debug-Bilder), drawn on the
 * rectified face. Each call returns a new Mat; the detector releases it after
 * the sink.
 */
object ArrowDebugImages {

    const val CANDIDATES = "5-kandidaten"
    const val ENTRY_POINTS = "6-einschuesse"

    private val WHITE = Scalar(255.0, 255.0, 255.0)
    private val MAGENTA = Scalar(255.0, 0.0, 255.0)
    private val GREEN = Scalar(0.0, 200.0, 0.0)
    private val YELLOW = Scalar(0.0, 255.0, 255.0)
    private val RED = Scalar(0.0, 0.0, 255.0)
    private val ORANGE = Scalar(0.0, 140.0, 255.0)

    /** The foot point, or an arrow towards it when it lies off the square, and every candidate numbered by rank. */
    fun candidates(warped: Mat, foot: Vec2?, candidates: List<ArrowCandidate>): Mat =
        warped.clone().releaseIfThrows { mat ->
            val edge = mat.cols()
            if (foot != null) {
                if (abs(foot.x) < FaceWarp.EXTENT && abs(foot.y) < FaceWarp.EXTENT) {
                    Imgproc.circle(mat, point(foot, edge), 12, WHITE, 3)
                } else {
                    val towards = foot * (1.0 / foot.length)
                    Imgproc.arrowedLine(mat, point(towards * 0.9, edge), point(towards * 1.05, edge), WHITE, 3)
                }
            }
            candidates.forEachIndexed { rank, c ->
                Imgproc.line(mat, point(c.tip, edge), point(c.far, edge), MAGENTA, 2)
                Imgproc.putText(mat, "${rank + 1}", point(c.tip, edge), Imgproc.FONT_HERSHEY_SIMPLEX, 1.0, WHITE, 2)
            }
        }

    /**
     * Every entry point: green when the selection accepted it, yellow when not;
     * a red cross where the walk saw no shaft, an orange triangle where it ran out.
     */
    fun entryPoints(warped: Mat, candidates: List<ArrowCandidate>, accepted: List<Candidate>): Mat =
        warped.clone().releaseIfThrows { mat ->
            val edge = mat.cols()
            for (c in candidates) {
                val p = point(c.tip, edge)
                val taken = c.located != null && accepted.any { it === c.located }
                Imgproc.circle(mat, p, 10, if (taken) GREEN else YELLOW, 3)
                when (c.refinement) {
                    TipRefinement.NO_SHAFT -> Imgproc.drawMarker(mat, p, RED, Imgproc.MARKER_CROSS, 24, 2)
                    TipRefinement.RAN_OUT -> Imgproc.drawMarker(mat, p, ORANGE, Imgproc.MARKER_TRIANGLE_UP, 24, 2)
                    TipRefinement.REFINED -> Unit
                }
            }
        }

    private fun point(p: Vec2, edge: Int): Point {
        val px = FaceWarp.pixelOf(p, edge)
        return Point(px.x, px.y)
    }
}
