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

import kotlin.math.abs

/** Small dense linear systems: enough for the eight unknowns of a homography. */
internal object LinearSystem {

    /**
     * x with a x = b, by Gaussian elimination with partial pivoting. Null
     * when [a] is singular relative to its largest entry. Neither argument is
     * modified.
     */
    fun solve(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
        val n = b.size
        require(a.size == n && a.all { it.size == n }) { "a must be $n x $n" }
        val scale = a.maxOf { row -> row.maxOf { abs(it) } }
        if (scale == 0.0) return null

        val m = Array(n) { r -> DoubleArray(n + 1) { c -> if (c < n) a[r][c] else b[r] } }
        for (col in 0 until n) {
            val pivot = (col until n).maxBy { abs(m[it][col]) }
            if (abs(m[pivot][col]) < 1e-14 * scale) return null
            val swap = m[col]
            m[col] = m[pivot]
            m[pivot] = swap
            for (r in col + 1 until n) {
                val factor = m[r][col] / m[col][col]
                if (factor == 0.0) continue
                for (c in col..n) {
                    m[r][c] -= factor * m[col][c]
                }
            }
        }

        val x = DoubleArray(n)
        for (r in n - 1 downTo 0) {
            var sum = m[r][n]
            for (c in r + 1 until n) {
                sum -= m[r][c] * x[c]
            }
            x[r] = sum / m[r][r]
        }
        return x
    }
}
