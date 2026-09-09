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

    /**
     * The centre of the conic. Mathematically this is the pole of the line at
     * infinity, but taking that route through Mat3.inverse() is numerically
     * poor at pixel scale: a circle far from the image origin has a huge
     * constant entry, so its determinant falls many orders of magnitude below
     * the cube of its largest entry while the conic stays well conditioned.
     * Solving the 2x2 block instead keeps every entry on one scale.
     *
     * Null when the conic has no unique centre, as for a parabola or a
     * degenerate conic.
     */
    fun centre(): Vec2? {
        val a = matrix[0, 0]
        val b = matrix[0, 1]
        val c = matrix[1, 1]
        val d = matrix[0, 2]
        val e = matrix[1, 2]
        val blockDet = a * c - b * b
        val scale = maxOf(abs(a), abs(b), abs(c))
        if (scale == 0.0 || abs(blockDet) < 1e-15 * scale * scale) return null
        return Vec2((b * e - c * d) / blockDet, (b * d - a * e) / blockDet)
    }

    /**
     * A similarity taking this conic's centre to the origin and its
     * geometric mean radius to one. Conic arithmetic at pixel scale mixes
     * a huge constant term with small quadratic ones; working in this frame
     * keeps every entry on one scale. Null for a conic with no unique
     * centre.
     */
    fun normalisingFrame(): Mat3? {
        val normalisedConic = normalized()
        val m = normalisedConic.matrix
        val centre = normalisedConic.centre() ?: return null
        // Constant term after moving the centre to the origin.
        val ch = centre.homogeneous()
        val f = ch.dot(m * ch)
        val blockDet = m[0, 0] * m[1, 1] - m[0, 1] * m[1, 0]

        // Both guards are scale relative, in the style of [centre]: each
        // quantity is a difference of terms much larger than itself once the
        // conic sits far from the image origin -- blockDet shrinks with the
        // square of that distance and f with the fourth power -- so an absolute
        // floor would read plain rounding noise as degeneracy.
        val blockScale = maxOf(
            abs(m[0, 0]), abs(m[0, 1]), abs(m[1, 0]), abs(m[1, 1])
        )
        if (blockScale == 0.0) return null
        if (abs(blockDet) < 1e-15 * blockScale * blockScale) return null

        // Magnitudes of the six monomials whose signed sum is f.
        val fScale = abs(m[0, 0] * centre.x * centre.x) +
            abs(2.0 * m[0, 1] * centre.x * centre.y) +
            abs(m[1, 1] * centre.y * centre.y) +
            abs(2.0 * m[0, 2] * centre.x) +
            abs(2.0 * m[1, 2] * centre.y) +
            abs(m[2, 2])
        if (fScale == 0.0 || abs(f) < 1e-15 * fScale) return null

        // Semi-axes squared are -f / eigenvalue, so the geometric mean radius is
        // sqrt(|f| / sqrt(|det block|)).
        val size = sqrt(abs(f) / sqrt(abs(blockDet)))
        if (size < 1e-12) return null
        val s = 1.0 / size
        return Mat3.of(
            s, 0.0, -s * centre.x,
            0.0, s, -s * centre.y,
            0.0, 0.0, 1.0
        )
    }

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

            // Check for degeneracy: reject rank-deficient conics (e.g. a doubled line
            // from collinear points). An isNaN() test does not work because SymmetricEigen
            // guards every division, so finite input produces finite output. Instead use
            // a scale-relative threshold like Mat3.inverse() does.
            val d = normalisedConic.det()
            // The scale comes from the unit eigenvector deliberately: its
            // entries are bounded by one, so in the Hartley frame -- where the
            // points sit at distance sqrt(2) -- the conic's entries are of order
            // one too, and scale^3 is the right order for a determinant.
            val scale = maxOf(
                abs(v[0]), abs(v[1]), abs(v[2]), abs(v[3]), abs(v[4]), abs(v[5])
            )
            if (scale == 0.0 || abs(d) < 1e-12 * scale * scale * scale) return null

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
