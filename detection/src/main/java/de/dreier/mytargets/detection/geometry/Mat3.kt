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
import kotlin.math.cos
import kotlin.math.sin

/** A 3x3 matrix in row-major order, used for homographies and conics. */
class Mat3(private val m: DoubleArray) {

    init {
        require(m.size == 9) { "Mat3 needs exactly 9 values, got ${m.size}" }
    }

    operator fun get(row: Int, col: Int): Double = m[row * 3 + col]

    operator fun times(other: Mat3): Mat3 {
        val r = DoubleArray(9)
        for (i in 0..2) {
            for (j in 0..2) {
                var sum = 0.0
                for (k in 0..2) {
                    sum += this[i, k] * other[k, j]
                }
                r[i * 3 + j] = sum
            }
        }
        return Mat3(r)
    }

    operator fun times(v: Vec3) = Vec3(
        this[0, 0] * v.x + this[0, 1] * v.y + this[0, 2] * v.z,
        this[1, 0] * v.x + this[1, 1] * v.y + this[1, 2] * v.z,
        this[2, 0] * v.x + this[2, 1] * v.y + this[2, 2] * v.z
    )

    fun transpose(): Mat3 {
        val r = DoubleArray(9)
        for (i in 0..2) {
            for (j in 0..2) {
                r[i * 3 + j] = this[j, i]
            }
        }
        return Mat3(r)
    }

    fun scaled(factor: Double) = Mat3(DoubleArray(9) { m[it] * factor })

    fun det(): Double =
        this[0, 0] * (this[1, 1] * this[2, 2] - this[1, 2] * this[2, 1]) -
            this[0, 1] * (this[1, 0] * this[2, 2] - this[1, 2] * this[2, 0]) +
            this[0, 2] * (this[1, 0] * this[2, 1] - this[1, 1] * this[2, 0])

    /** Null when the matrix is singular. */
    fun inverse(): Mat3? {
        val d = det()
        // Scale-relative threshold: a matrix of small entries has a small
        // determinant without being singular.
        val scale = m.maxOf { abs(it) }
        if (scale == 0.0 || abs(d) < 1e-12 * scale * scale * scale) return null
        val r = DoubleArray(9)
        r[0] = (this[1, 1] * this[2, 2] - this[1, 2] * this[2, 1]) / d
        r[1] = (this[0, 2] * this[2, 1] - this[0, 1] * this[2, 2]) / d
        r[2] = (this[0, 1] * this[1, 2] - this[0, 2] * this[1, 1]) / d
        r[3] = (this[1, 2] * this[2, 0] - this[1, 0] * this[2, 2]) / d
        r[4] = (this[0, 0] * this[2, 2] - this[0, 2] * this[2, 0]) / d
        r[5] = (this[0, 2] * this[1, 0] - this[0, 0] * this[1, 2]) / d
        r[6] = (this[1, 0] * this[2, 1] - this[1, 1] * this[2, 0]) / d
        r[7] = (this[0, 1] * this[2, 0] - this[0, 0] * this[2, 1]) / d
        r[8] = (this[0, 0] * this[1, 1] - this[0, 1] * this[1, 0]) / d
        return Mat3(r)
    }

    /** Null when the point maps onto the vanishing line. */
    fun mapPoint(p: Vec2): Vec2? = (this * p.homogeneous()).toVec2()

    /**
     * The image of [direction] attached at the point [at]: the Jacobian of the
     * point mapping applied to the direction. For p' = (H p) / w the derivative
     * along d is (H d * w - (H p) * (H d).z) / w^2, with d taken homogeneous
     * with z = 0. Null when [at] maps to infinity.
     */
    fun mapDirection(at: Vec2, direction: Vec2): Vec2? {
        val u = this * at.homogeneous()
        if (abs(u.z) < 1e-12) return null
        val du = this * Vec3(direction.x, direction.y, 0.0)
        val w2 = u.z * u.z
        return Vec2(
            (du.x * u.z - u.x * du.z) / w2,
            (du.y * u.z - u.y * du.z) / w2
        )
    }

    companion object {
        fun of(vararg values: Double) = Mat3(values.copyOf())

        fun identity() = of(
            1.0, 0.0, 0.0,
            0.0, 1.0, 0.0,
            0.0, 0.0, 1.0
        )

        fun translation(t: Vec2) = of(
            1.0, 0.0, t.x,
            0.0, 1.0, t.y,
            0.0, 0.0, 1.0
        )

        fun rotation(radians: Double): Mat3 {
            val c = cos(radians)
            val s = sin(radians)
            return of(
                c, -s, 0.0,
                s, c, 0.0,
                0.0, 0.0, 1.0
            )
        }
    }
}
