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

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.sqrt

/** Real roots of polynomials up to degree three, by closed form. */
object Cubic {

    private const val EPS = 1e-12

    /**
     * Real roots of a x^3 + b x^2 + c x + d = 0, ascending. Roots that
     * coincide are reported once; the caller cannot distinguish a double root
     * from two roots that happen to be close, which is the right behaviour for
     * noisy input.
     */
    fun realRoots(a: Double, b: Double, c: Double, d: Double): List<Double> {
        if (abs(a) < EPS) return quadraticRoots(b, c, d)

        // Depressed cubic t^3 + p t + q, with x = t - b / (3a)
        val shift = b / (3.0 * a)
        val p = (3.0 * a * c - b * b) / (3.0 * a * a)
        val q = (2.0 * b * b * b - 9.0 * a * b * c + 27.0 * a * a * d) /
            (27.0 * a * a * a)

        val discriminant = q * q / 4.0 + p * p * p / 27.0
        val roots = when {
            abs(discriminant) < EPS -> {
                // Multiple root.
                if (abs(q) < EPS) {
                    listOf(0.0)
                } else {
                    val u = cbrt(-q / 2.0)
                    listOf(2.0 * u, -u)
                }
            }
            discriminant > 0.0 -> {
                val sq = sqrt(discriminant)
                listOf(cbrt(-q / 2.0 + sq) + cbrt(-q / 2.0 - sq))
            }
            else -> {
                // Three distinct real roots, trigonometric form.
                val r = sqrt(-p * p * p / 27.0)
                val phi = acos(clamp(-q / (2.0 * r)))
                val m = 2.0 * sqrt(-p / 3.0)
                (0..2).map { k -> m * cos((phi + 2.0 * PI * k) / 3.0) }
            }
        }
        return roots.map { it - shift }.sorted()
    }

    private fun quadraticRoots(a: Double, b: Double, c: Double): List<Double> {
        if (abs(a) < EPS) {
            return if (abs(b) < EPS) emptyList() else listOf(-c / b)
        }
        val disc = b * b - 4.0 * a * c
        return when {
            disc < -EPS -> emptyList()
            abs(disc) <= EPS -> listOf(-b / (2.0 * a))
            else -> {
                val sq = sqrt(disc)
                listOf((-b - sq) / (2.0 * a), (-b + sq) / (2.0 * a)).sorted()
            }
        }
    }

    private fun clamp(x: Double) = when {
        x < -1.0 -> -1.0
        x > 1.0 -> 1.0
        else -> x
    }
}
