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

import kotlin.math.hypot

/** A point or direction in the plane. */
data class Vec2(val x: Double, val y: Double) {

    operator fun plus(other: Vec2) = Vec2(x + other.x, y + other.y)

    operator fun minus(other: Vec2) = Vec2(x - other.x, y - other.y)

    operator fun times(scale: Double) = Vec2(x * scale, y * scale)

    val length: Double
        get() = hypot(x, y)

    fun distanceTo(other: Vec2): Double = (this - other).length

    /** Homogeneous representation with w = 1. */
    fun homogeneous() = Vec3(x, y, 1.0)
}
