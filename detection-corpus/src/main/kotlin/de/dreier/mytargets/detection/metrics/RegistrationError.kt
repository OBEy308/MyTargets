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

package de.dreier.mytargets.detection.metrics

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * How far a predicted registration is from the reference, in spot radii.
 *
 * Both homographies map pixels of the EXIF-rotated original to spot-local
 * target coordinates, nine values row by row. Points on the rings 0.2 to 1.0
 * go to the image with the reference and come back with the prediction; the
 * distance to where they started is the error. Only points that land inside
 * the image count, so a cropped face is measured where it can be seen.
 */
data class RegistrationError(
    val median: Double,
    val max: Double,
    val visiblePoints: Int
) {
    companion object {
        val RING_RADII = listOf(0.2, 0.4, 0.6, 0.8, 1.0)
        const val ANGLES = 72

        /** Null when not a single ring point lies inside the image. */
        fun between(
            reference: List<Double>,
            predicted: List<Double>,
            imageWidth: Int,
            imageHeight: Int
        ): RegistrationError? {
            require(reference.size == 9 && predicted.size == 9) {
                "a homography has nine values"
            }
            val toImage = requireNotNull(Homography.inverse(reference.toDoubleArray())) {
                "the reference homography is singular"
            }
            val back = predicted.toDoubleArray()

            val errors = ArrayList<Double>(RING_RADII.size * ANGLES)
            for (radius in RING_RADII) {
                for (k in 0 until ANGLES) {
                    val angle = 2.0 * PI * k / ANGLES
                    val tx = radius * cos(angle)
                    val ty = radius * sin(angle)
                    val p = Homography.map(toImage, tx, ty) ?: continue
                    if (p[0] < 0.0 || p[0] > imageWidth - 1.0) continue
                    if (p[1] < 0.0 || p[1] > imageHeight - 1.0) continue
                    val q = Homography.map(back, p[0], p[1]) ?: continue
                    errors += hypot(q[0] - tx, q[1] - ty)
                }
            }
            if (errors.isEmpty()) return null

            errors.sort()
            val n = errors.size
            val median = if (n % 2 == 1) {
                errors[n / 2]
            } else {
                0.5 * (errors[n / 2 - 1] + errors[n / 2])
            }
            return RegistrationError(median, errors.last(), n)
        }
    }
}

/** 3x3 homographies as nine values, row by row. */
object Homography {

    /** Null when the point maps to infinity. */
    fun map(h: DoubleArray, x: Double, y: Double): DoubleArray? {
        val w = h[6] * x + h[7] * y + h[8]
        if (abs(w) < 1e-12) return null
        return doubleArrayOf(
            (h[0] * x + h[1] * y + h[2]) / w,
            (h[3] * x + h[4] * y + h[5]) / w
        )
    }

    /** Null when [h] is singular; the guard is relative to the largest entry. */
    fun inverse(h: DoubleArray): DoubleArray? {
        require(h.size == 9) { "a homography has nine values, got ${h.size}" }
        val c00 = h[4] * h[8] - h[5] * h[7]
        val c01 = h[5] * h[6] - h[3] * h[8]
        val c02 = h[3] * h[7] - h[4] * h[6]
        val det = h[0] * c00 + h[1] * c01 + h[2] * c02
        val scale = h.maxOf { abs(it) }
        if (scale == 0.0 || abs(det) < 1e-15 * scale * scale * scale) return null
        return doubleArrayOf(
            c00 / det, (h[2] * h[7] - h[1] * h[8]) / det, (h[1] * h[5] - h[2] * h[4]) / det,
            c01 / det, (h[0] * h[8] - h[2] * h[6]) / det, (h[2] * h[3] - h[0] * h[5]) / det,
            c02 / det, (h[1] * h[6] - h[0] * h[7]) / det, (h[0] * h[4] - h[1] * h[3]) / det
        )
    }

    /**
     * diag(factor, factor, 1) · h: the same registration with target
     * coordinates scaled by [factor]. A WA6Ring reference times 0.6 is in
     * WAFull units, because WA6Ring's radius 1.0 lies at 0.6 of the full face.
     */
    fun scaleTarget(h: List<Double>, factor: Double): List<Double> =
        h.mapIndexed { i, v -> if (i < 6) v * factor else v }
}
