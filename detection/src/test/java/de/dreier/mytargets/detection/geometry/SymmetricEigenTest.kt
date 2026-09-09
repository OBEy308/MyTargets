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

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.abs

class SymmetricEigenTest {

    @Test
    fun diagonalMatrixReturnsItsDiagonalSortedByMagnitude() {
        val a = arrayOf(
            doubleArrayOf(2.0, 0.0, 0.0),
            doubleArrayOf(0.0, -5.0, 0.0),
            doubleArrayOf(0.0, 0.0, 1.0)
        )
        val result = SymmetricEigen.decompose(a)
        assertThat(result.values[0]).isWithin(1e-9).of(-5.0)
        assertThat(abs(result.values[1])).isWithin(1e-9).of(2.0)
        assertThat(abs(result.values[2])).isWithin(1e-9).of(1.0)
    }

    @Test
    fun eigenvectorsSatisfyTheDefiningEquation() {
        val a = arrayOf(
            doubleArrayOf(4.0, 1.0, -2.0),
            doubleArrayOf(1.0, 2.0, 0.0),
            doubleArrayOf(-2.0, 0.0, 3.0)
        )
        val result = SymmetricEigen.decompose(a)
        for (i in 0..2) {
            val v = result.vectors[i]
            val lambda = result.values[i]
            for (row in 0..2) {
                val av = (0..2).sumOf { col -> a[row][col] * v[col] }
                assertThat(av).isWithin(1e-8).of(lambda * v[row])
            }
        }
    }

    @Test
    fun rankOneMatrixYieldsItsGeneratingVector() {
        // a = l * l^T with l = (1, 2, -1), so the dominant eigenvector is l
        // normalised and the dominant eigenvalue is |l|^2 = 6.
        val l = doubleArrayOf(1.0, 2.0, -1.0)
        val a = Array(3) { r -> DoubleArray(3) { c -> l[r] * l[c] } }
        val result = SymmetricEigen.decompose(a)

        assertThat(result.values[0]).isWithin(1e-8).of(6.0)
        assertThat(abs(result.values[1])).isLessThan(1e-8)
        assertThat(abs(result.values[2])).isLessThan(1e-8)

        // Eigenvectors are unique only up to sign.
        val v = result.vectors[0]
        val sign = if (v[0] < 0) -1.0 else 1.0
        val norm = kotlin.math.sqrt(6.0)
        assertThat(v[0] * sign).isWithin(1e-8).of(1.0 / norm)
        assertThat(v[1] * sign).isWithin(1e-8).of(2.0 / norm)
        assertThat(v[2] * sign).isWithin(1e-8).of(-1.0 / norm)
    }

    @Test
    fun worksForSixBySixMatrices() {
        val n = 6
        val a = Array(n) { r -> DoubleArray(n) { c -> 1.0 / (r + c + 1.0) } }
        val result = SymmetricEigen.decompose(a)
        for (i in 0 until n) {
            val v = result.vectors[i]
            val lambda = result.values[i]
            for (row in 0 until n) {
                val av = (0 until n).sumOf { col -> a[row][col] * v[col] }
                assertThat(av).isWithin(1e-8).of(lambda * v[row])
            }
        }
    }
}
