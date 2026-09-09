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
 * Eigen decomposition of a real symmetric matrix using the cyclic Jacobi
 * method.
 *
 * Jacobi is chosen over faster algorithms because the matrices here are tiny
 * (2x2 to 6x6), it needs no external library, and it is accurate for the
 * near-degenerate cases that matter: a conic pencil member that has dropped to
 * rank two, and a scatter matrix whose smallest eigenvalue carries the fit.
 */
object SymmetricEigen {

    class Result(val values: DoubleArray, val vectors: Array<DoubleArray>)

    /**
     * @param a symmetric matrix; not modified
     * @return eigenvalues and eigenvectors, sorted by descending magnitude of
     *         the eigenvalue. `vectors[i]` is the unit eigenvector for
     *         `values[i]`.
     */
    fun decompose(a: Array<DoubleArray>, maxSweeps: Int = 100): Result {
        val n = a.size
        require(n > 0) { "matrix must not be empty" }
        a.forEach { require(it.size == n) { "matrix must be square" } }

        val work = Array(n) { r -> a[r].copyOf() }
        // v holds eigenvectors as columns while sweeping.
        val v = Array(n) { r -> DoubleArray(n) { c -> if (r == c) 1.0 else 0.0 } }

        for (sweep in 0 until maxSweeps) {
            var off = 0.0
            for (p in 0 until n) {
                for (q in p + 1 until n) {
                    off += work[p][q] * work[p][q]
                }
            }
            if (off < 1e-30) break

            for (p in 0 until n) {
                for (q in p + 1 until n) {
                    if (abs(work[p][q]) < 1e-300) continue

                    val theta = (work[q][q] - work[p][p]) / (2.0 * work[p][q])
                    val t = sign(theta) / (abs(theta) + sqrt(theta * theta + 1.0))
                    val c = 1.0 / sqrt(t * t + 1.0)
                    val s = t * c

                    for (k in 0 until n) {
                        val akp = work[k][p]
                        val akq = work[k][q]
                        work[k][p] = c * akp - s * akq
                        work[k][q] = s * akp + c * akq
                    }
                    for (k in 0 until n) {
                        val apk = work[p][k]
                        val aqk = work[q][k]
                        work[p][k] = c * apk - s * aqk
                        work[q][k] = s * apk + c * aqk
                    }
                    for (k in 0 until n) {
                        val vkp = v[k][p]
                        val vkq = v[k][q]
                        v[k][p] = c * vkp - s * vkq
                        v[k][q] = s * vkp + c * vkq
                    }
                }
            }
        }

        val values = DoubleArray(n) { work[it][it] }
        val order = (0 until n).sortedByDescending { abs(values[it]) }
        val sortedValues = DoubleArray(n) { values[order[it]] }
        val sortedVectors = Array(n) { i -> DoubleArray(n) { k -> v[k][order[i]] } }
        return Result(sortedValues, sortedVectors)
    }

    private fun sign(x: Double) = if (x >= 0.0) 1.0 else -1.0
}
