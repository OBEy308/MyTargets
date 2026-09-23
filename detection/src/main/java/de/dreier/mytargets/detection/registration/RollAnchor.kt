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

import de.dreier.mytargets.detection.DebugSink
import de.dreier.mytargets.detection.geometry.Mat3
import de.dreier.mytargets.detection.geometry.Vec2
import de.dreier.mytargets.detection.show
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot

/**
 * The roll anchor of the roll-anchor design (2026-09-23): the rotation about
 * the face centre, modulo 90 degrees, of the straight edges around the face.
 *
 * Concentric rings leave that rotation open; [Orientation] pins it with "up
 * in the image". The paper and the straw lie in the plane of the rings, so the
 * rectification maps their edges to straight lines whose angle is exactly the
 * roll of the camera. Only edges across the radius count: shafts run from
 * their hit outwards, almost along the radius, and would otherwise vote with
 * their common lean.
 */
object RollAnchor {

    const val EXTENT = 1.6
    const val EDGE = 640
    const val INNER_RADIUS = 1.02
    /** Sobel 3x3 after the blur on 8-bit grey; a sharp step of 10 grey levels gives 19. */
    const val MIN_GRADIENT = 19.0
    const val MAX_ANGLE_TO_RADIUS_DEGREES = 30.0
    const val MIN_PIXELS = 300
    const val BINS = 90
    /** Peak over mean of the smoothed histogram. Provisional; set from RollAnchorCorpusRun. */
    const val MIN_STRENGTH = 2.0
    const val STAGE = "4-rollanker"

    /**
     * A 1-px step, as the resampling leaves it, is a staircase to the 3x3
     * Sobel: its angles scatter by 15 degrees and lean towards the axes. The
     * blur makes the step a ramp the Sobel reads to a fraction of a degree.
     */
    private const val BLUR_SIGMA = 1.5
    private const val BLUR_RADIUS = 4
    /** The blur and the Sobel carry the warp's black border this far inwards. */
    private const val ERODE_ITERATIONS = BLUR_RADIUS + 1
    private val SMOOTHING = doubleArrayOf(1.0, 2.0, 3.0, 2.0, 1.0)

    /**
     * [radians] in (-pi/4, pi/4]: the edges lie at this angle in target
     * coordinates, and `Mat3.rotation(-radians)` turns them onto the axes.
     */
    class Measurement(
        val radians: Double,
        val strength: Double,
        val pixels: Int,
        val histogram: DoubleArray
    ) {
        val degrees: Double
            get() = Math.toDegrees(radians)

        val isAnchor: Boolean
            get() = pixels >= MIN_PIXELS && strength >= MIN_STRENGTH
    }

    /**
     * Measures the edges around the face in [workingImage] (8-bit BGR), seen
     * through [imageToTarget], a map from its pixels to target coordinates.
     * Null when not a single pixel counted.
     */
    fun measure(workingImage: Mat, imageToTarget: Mat3, debug: DebugSink = DebugSink.NONE): Measurement? {
        val grey = Mat()
        try {
            Imgproc.cvtColor(workingImage, grey, Imgproc.COLOR_BGR2GRAY)
            val warped = FaceWarp.warp(grey, imageToTarget, EDGE, EXTENT)
            try {
                val valid = validPixels(grey, imageToTarget)
                val (x, y) = gradients(warped)
                val counted = BooleanArray(EDGE * EDGE)
                val result = histogramOf(x, y, valid, counted)
                debug.show(STAGE) { RollAnchorImages.render(warped, counted, result) }
                return result
            } finally {
                warped.release()
            }
        } finally {
            grey.release()
        }
    }

    /** 255 where the warp saw the photograph, eroded: the warp's border is black and perfectly straight. */
    private fun validPixels(grey: Mat, imageToTarget: Mat3): ByteArray {
        val ones = Mat(grey.rows(), grey.cols(), CvType.CV_8UC1, Scalar(255.0))
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
        try {
            val mask = FaceWarp.warp(ones, imageToTarget, EDGE, EXTENT)
            try {
                Imgproc.erode(mask, mask, kernel, Point(-1.0, -1.0), ERODE_ITERATIONS)
                return ByteArray(EDGE * EDGE).also { mask.get(0, 0, it) }
            } finally {
                mask.release()
            }
        } finally {
            ones.release()
            kernel.release()
        }
    }

    private fun gradients(warped: Mat): Pair<FloatArray, FloatArray> {
        val soft = Mat()
        val gx = Mat()
        val gy = Mat()
        try {
            val side = 2.0 * BLUR_RADIUS + 1.0
            Imgproc.GaussianBlur(warped, soft, Size(side, side), BLUR_SIGMA)
            Imgproc.Sobel(soft, gx, CvType.CV_32F, 1, 0, 3)
            Imgproc.Sobel(soft, gy, CvType.CV_32F, 0, 1, 3)
            return FloatArray(EDGE * EDGE).also { gx.get(0, 0, it) } to
                FloatArray(EDGE * EDGE).also { gy.get(0, 0, it) }
        } finally {
            soft.release()
            gx.release()
            gy.release()
        }
    }

    private fun histogramOf(gx: FloatArray, gy: FloatArray, mask: ByteArray, counted: BooleanArray): Measurement? {
        val bins = DoubleArray(BINS)
        val across = cos(Math.toRadians(MAX_ANGLE_TO_RADIUS_DEGREES))
        var pixels = 0
        for (row in 0 until EDGE) {
            for (col in 0 until EDGE) {
                val i = row * EDGE + col
                if (mask[i].toInt() and 0xFF != 255) continue
                val t = FaceWarp.targetOf(Vec2(col.toDouble(), row.toDouble()), EDGE, EXTENT)
                val r = t.length
                if (r < INNER_RADIUS || r > EXTENT) continue
                val x = gx[i].toDouble()
                val y = gy[i].toDouble()
                val magnitude = hypot(x, y)
                if (magnitude < MIN_GRADIENT) continue
                // The gradient of an edge across the radius points along the radius.
                if (abs(x * t.x + y * t.y) < across * magnitude * r) continue
                // A gradient and its edge differ by 90 degrees, which the fold removes.
                var folded = Math.toDegrees(atan2(y, x)) % 90.0
                if (folded < 0.0) folded += 90.0
                bins[minOf(BINS - 1, (folded * BINS / 90.0).toInt())] += magnitude
                counted[i] = true
                pixels++
            }
        }
        if (pixels == 0) return null
        return peakOf(bins, pixels)
    }

    /** The smoothed, cyclic histogram's peak, refined by a parabola through its neighbours. */
    internal fun peakOf(bins: DoubleArray, pixels: Int): Measurement {
        val half = SMOOTHING.size / 2
        val weight = SMOOTHING.sum()
        val smooth = DoubleArray(BINS) { k ->
            (-half..half).sumOf { d -> SMOOTHING[d + half] * bins[Math.floorMod(k + d, BINS)] } / weight
        }
        val k = smooth.indices.maxBy { smooth[it] }
        val left = smooth[Math.floorMod(k - 1, BINS)]
        val centre = smooth[k]
        val right = smooth[Math.floorMod(k + 1, BINS)]
        val curvature = left - 2.0 * centre + right
        val offset = if (curvature < 0.0) 0.5 * (left - right) / curvature else 0.0
        var degrees = ((k + 0.5 + offset) * 90.0 / BINS) % 90.0
        if (degrees < 0.0) degrees += 90.0
        if (degrees > 45.0) degrees -= 90.0
        val mean = smooth.average()
        val strength = if (mean > 0.0) centre / mean else 0.0
        return Measurement(Math.toRadians(degrees), strength, pixels, smooth)
    }
}
