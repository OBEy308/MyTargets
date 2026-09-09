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
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Recovers the imaged centre and the vanishing line of the target plane from
 * the images of two concentric circles.
 *
 * Why this works. In the plane of the target two concentric circles are
 *
 *     C1 = diag(1, 1, -r1^2)      C2 = diag(1, 1, -r2^2)
 *
 * and the pencil C1 - lambda C2 has two degenerate members:
 *
 *     lambda = 1            -> diag(0, 0, r2^2 - r1^2), rank one, the line at
 *                              infinity counted twice; a DOUBLE root of the
 *                              determinant
 *     lambda = r1^2 / r2^2  -> diag(1 - lambda, 1 - lambda, 0), rank two, whose
 *                              null vector (0, 0, 1) is the common centre; a
 *                              SIMPLE root
 *
 * A homography preserves all of that. We use the simple root: under noise a
 * double root splits into either two real or two complex roots, and in the
 * complex case a real root finder does not see it at all. The simple root is
 * well separated and its null vector is the imaged centre c. The vanishing line
 * is then the polar of c with respect to either conic, l = C1' c, because in
 * the target plane C1 (0,0,1)^T is proportional to (0,0,1), the line at
 * infinity, and pole/polar relations survive homographies.
 *
 * The right root is recognised by where its null vector lies: the imaged centre
 * is inside both ellipses. The null vectors of the (near) rank one members lie
 * on the vanishing line, far outside. That test is scale free and needs no
 * tuned tolerance.
 *
 * Everything is computed in a normalised frame in which the first conic is
 * roughly the unit circle about the origin. At pixel scale the determinant
 * cubic has coefficients spanning many orders of magnitude and the eigenvalue
 * ratios lose their meaning.
 */
object VanishingLine {

    class Result(val line: Vec3, val imagedCentre: Vec2)

    /**
     * @return the vanishing line as a unit vector and the imaged centre, or null
     *         when the inputs were not the images of two distinct concentric
     *         circles.
     */
    fun fromConcentricCircles(c1: Conic, c2: Conic): Result? {
        val frame = normalisingFrame(c1) ?: return null
        val a = c1.transformedBy(frame).normalized().matrix
        val b = c2.transformedBy(frame).normalized().matrix
        if (areTheSameConic(a, b)) return null

        val roots = Cubic.realRoots(
            cubicA(b), cubicB(a, b), cubicC(a, b), a.det()
        )

        var best: Vec3? = null
        var bestResidual = Double.MAX_VALUE

        for (lambda in roots) {
            val m = arrayOf(
                doubleArrayOf(a[0, 0] - lambda * b[0, 0], a[0, 1] - lambda * b[0, 1], a[0, 2] - lambda * b[0, 2]),
                doubleArrayOf(a[1, 0] - lambda * b[1, 0], a[1, 1] - lambda * b[1, 1], a[1, 2] - lambda * b[1, 2]),
                doubleArrayOf(a[2, 0] - lambda * b[2, 0], a[2, 1] - lambda * b[2, 1], a[2, 2] - lambda * b[2, 2])
            )
            val eigen = SymmetricEigen.decompose(m)
            if (abs(eigen.values[0]) < 1e-12) continue

            // Null vector: eigenvector of the eigenvalue smallest in magnitude.
            val candidate = Vec3(
                eigen.vectors[2][0],
                eigen.vectors[2][1],
                eigen.vectors[2][2]
            )
            if (!isInside(candidate, a) || !isInside(candidate, b)) continue

            // Among admissible members prefer the one closest to rank two.
            val residual = abs(eigen.values[2]) / abs(eigen.values[1])
            if (residual < bestResidual) {
                bestResidual = residual
                best = candidate
            }
        }

        val centre = best ?: return null
        val lineInFrame = a * centre

        // Back to image coordinates: points go through the inverse frame,
        // lines through the transpose.
        val imagedCentre = frame.inverse()?.times(centre)?.toVec2() ?: return null
        val line = (frame.transpose() * lineInFrame).normalized()
        return Result(line, imagedCentre)
    }

    /**
     * Similarity that moves the centre of [conic] to the origin and scales its
     * geometric mean radius to one. Null for a degenerate conic.
     */
    private fun normalisingFrame(conic: Conic): Mat3? {
        val normalisedConic = conic.normalized()
        val m = normalisedConic.matrix
        val centre = normalisedConic.centre() ?: return null
        // Constant term after moving the centre to the origin.
        val ch = centre.homogeneous()
        val f = ch.dot(m * ch)
        val blockDet = m[0, 0] * m[1, 1] - m[0, 1] * m[1, 0]
        if (abs(blockDet) < 1e-18 || abs(f) < 1e-18) return null
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

    /** Both inputs are normalised to unit Frobenius norm, up to sign. */
    private fun areTheSameConic(a: Mat3, b: Mat3): Boolean {
        var minus = 0.0
        var plus = 0.0
        for (i in 0..2) {
            for (j in 0..2) {
                val d = a[i, j] - b[i, j]
                val s = a[i, j] + b[i, j]
                minus += d * d
                plus += s * s
            }
        }
        return min(minus, plus) < 1e-18
    }

    /**
     * Whether [p] lies inside the ellipse [conic]: its quadratic form there has
     * the same sign as at the ellipse's own centre. Scale of [p] and of the
     * conic do not matter.
     */
    private fun isInside(p: Vec3, conic: Mat3): Boolean {
        val centre = Conic(conic).centre()?.homogeneous() ?: return false
        val interior = centre.dot(conic * centre)
        val value = p.dot(conic * p)
        return interior != 0.0 && value * interior > 0.0
    }

    /*
     * Coefficients of det(A - lambda B), expanded by multilinearity in the
     * columns. Choosing j of the three columns from -lambda B contributes
     * (-lambda)^j times the sum of the corresponding mixed determinants:
     *
     *   lambda^3 : -det(B)
     *   lambda^2 : sum_k det(B with column k taken from A)
     *   lambda^1 : -sum_k det(A with column k taken from B)
     *   lambda^0 : det(A)
     */
    private fun cubicA(b: Mat3) = -b.det()

    private fun cubicB(a: Mat3, b: Mat3): Double {
        var sum = 0.0
        for (k in 0..2) {
            sum += cofactorMixed(base = b, replacement = a, k = k)
        }
        return sum
    }

    private fun cubicC(a: Mat3, b: Mat3): Double {
        var sum = 0.0
        for (k in 0..2) {
            sum -= cofactorMixed(base = a, replacement = b, k = k)
        }
        return sum
    }

    /**
     * Determinant of the matrix built from [replacement]'s column k and
     * [base]'s other columns.
     */
    private fun cofactorMixed(base: Mat3, replacement: Mat3, k: Int): Double {
        val values = DoubleArray(9)
        for (row in 0..2) {
            for (col in 0..2) {
                values[row * 3 + col] =
                    if (col == k) replacement[row, col] else base[row, col]
            }
        }
        return Mat3(values).det()
    }
}
