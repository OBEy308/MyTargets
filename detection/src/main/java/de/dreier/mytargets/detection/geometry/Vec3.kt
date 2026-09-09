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

/** A homogeneous point, or equivalently a line, in the projective plane. */
data class Vec3(val x: Double, val y: Double, val z: Double) {

    fun dot(other: Vec3): Double = x * other.x + y * other.y + z * other.z

    fun cross(other: Vec3) = Vec3(
        y * other.z - z * other.y,
        z * other.x - x * other.z,
        x * other.y - y * other.x
    )

    val norm: Double
        get() = sqrt(x * x + y * y + z * z)

    fun normalized(): Vec3 {
        val n = norm
        return if (n == 0.0) this else Vec3(x / n, y / n, z / n)
    }

    /**
     * The inhomogeneous point, or null when this is a point at infinity and
     * therefore has no image in the affine plane.
     */
    fun toVec2(): Vec2? = if (abs(z) < 1e-12) null else Vec2(x / z, y / z)
}
