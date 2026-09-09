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

package de.dreier.mytargets.detection.geometry

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * A conic section as a symmetric 3x3 matrix C, so that a homogeneous point p
 * lies on the conic exactly when p^T C p = 0.
 *
 * For the coefficients of a x^2 + b xy + c y^2 + d x + e y + f = 0 the matrix is
 *
 *     [ a    b/2  d/2 ]
 *     [ b/2  c    e/2 ]
 *     [ d/2  e/2  f   ]
 *
 * A conic is only defined up to a non-zero scale factor. Anything that compares
 * conics must normalise first.
 */
class Conic(val matrix: Mat3) {

    fun evaluate(p: Vec2): Double {
        val h = p.homogeneous()
        return h.dot(matrix * h)
    }

    /**
     * The image of this conic under the point mapping [h], which is
     * H^-T C H^-1.
     */
    fun transformedBy(h: Mat3): Conic {
        val inv = requireNotNull(h.inverse()) { "homography must be invertible" }
        return Conic(inv.transpose() * matrix * inv)
    }

    /** The polar line of [point] with respect to this conic: C p. */
    fun polarLine(point: Vec3): Vec3 = matrix * point

    /** The pole of [line]: C^-1 l. Null for a degenerate conic. */
    fun pole(line: Vec3): Vec3? = matrix.inverse()?.times(line)

    /** Scaled so the Frobenius norm is one, which makes residuals comparable. */
    fun normalized(): Conic {
        var sum = 0.0
        for (i in 0..2) {
            for (j in 0..2) {
                sum += matrix[i, j] * matrix[i, j]
            }
        }
        val n = sqrt(sum)
        return if (n == 0.0) this else Conic(matrix.scaled(1.0 / n))
    }

    companion object {

        fun circle(center: Vec2, radius: Double): Conic = Conic(
            Mat3.of(
                1.0, 0.0, -center.x,
                0.0, 1.0, -center.y,
                -center.x, -center.y,
                center.x * center.x + center.y * center.y - radius * radius
            )
        )

        /**
         * Least squares fit through [points], at least five of them.
         *
         * The points are first normalised the way Hartley recommends -- centroid
         * to the origin, mean distance sqrt(2) -- because the design matrix mixes
         * squared pixel coordinates with linear ones. Without that the smallest
         * eigenvalue drowns in rounding error at image scale.
         */
        fun fit(points: List<Vec2>): Conic? {
            if (points.size < 5) return null

            val t = normalisation(points) ?: return null
            val normalisedPoints = points.map { t.mapPoint(it) ?: return null }

            // Rows of [x^2, xy, y^2, x, y, 1]; solve A v = 0 in the least
            // squares sense, which is the eigenvector of A^T A for the smallest
            // eigenvalue.
            val ata = Array(6) { DoubleArray(6) }
            for (p in normalisedPoints) {
                val row = doubleArrayOf(
                    p.x * p.x, p.x * p.y, p.y * p.y, p.x, p.y, 1.0
                )
                for (i in 0..5) {
                    for (j in 0..5) {
                        ata[i][j] += row[i] * row[j]
                    }
                }
            }

            val eigen = SymmetricEigen.decompose(ata)
            // Sorted by descending magnitude, so the last one is the smallest:
            // the least squares solution of A v = 0.
            val v = eigen.vectors[5]
            val normalisedConic = Mat3.of(
                v[0], v[1] / 2.0, v[3] / 2.0,
                v[1] / 2.0, v[2], v[4] / 2.0,
                v[3] / 2.0, v[4] / 2.0, v[5]
            )
            if (normalisedConic.det().isNaN()) return null

            // Undo the normalisation: C = T^T C_norm T
            return Conic(t.transpose() * normalisedConic * t)
        }

        /** Similarity that centres [points] and scales mean distance to sqrt(2). */
        private fun normalisation(points: List<Vec2>): Mat3? {
            val n = points.size.toDouble()
            val cx = points.sumOf { it.x } / n
            val cy = points.sumOf { it.y } / n
            val centre = Vec2(cx, cy)
            val meanDistance = points.sumOf { it.distanceTo(centre) } / n
            if (meanDistance < 1e-12) return null
            val s = sqrt(2.0) / meanDistance
            return Mat3.of(
                s, 0.0, -s * cx,
                0.0, s, -s * cy,
                0.0, 0.0, 1.0
            )
        }
    }
}
