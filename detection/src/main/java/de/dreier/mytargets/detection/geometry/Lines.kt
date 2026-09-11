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
import kotlin.math.sin

/** A line through [point] along [direction], which is stored as a unit vector. */
class Line2(val point: Vec2, direction: Vec2) {

    val direction: Vec2 = run {
        val length = direction.length
        require(length > 0.0) { "a line needs a direction" }
        direction * (1.0 / length)
    }

    /**
     * The distance of [p] from the line, with the sign of
     * cross(direction, p - point): positive on the side the direction turns to
     * when rotated by +90 degrees.
     */
    fun signedDistanceTo(p: Vec2): Double {
        val d = p - point
        return direction.x * d.y - direction.y * d.x
    }

    fun project(p: Vec2): Vec2 {
        val d = p - point
        return point + direction * (direction.x * d.x + direction.y * d.y)
    }

    companion object {
        fun through(from: Vec2, to: Vec2) = Line2(from, to - from)
    }
}

/**
 * The point with the least sum of squared distances to a set of lines: where
 * arrows leaning the same way meet in the rectified image (arrow design,
 * Kandidatendiagnose).
 */
object CommonPoint {

    /**
     * Null for fewer than two lines, or when no two of them cross at more
     * than [minAngleDegrees], where the point is not determined.
     */
    fun of(lines: List<Line2>, minAngleDegrees: Double = 2.0): Vec2? {
        if (lines.size < 2) return null
        val minSine = sin(Math.toRadians(minAngleDegrees))
        val crossing = lines.indices.any { i ->
            (i + 1 until lines.size).any { j ->
                val a = lines[i].direction
                val b = lines[j].direction
                abs(a.x * b.y - a.y * b.x) > minSine
            }
        }
        if (!crossing) return null
        // The normal equations of sum (n . x - n . p)^2 with n the unit normal of each line.
        var a11 = 0.0
        var a12 = 0.0
        var a22 = 0.0
        var b1 = 0.0
        var b2 = 0.0
        for (line in lines) {
            val nx = -line.direction.y
            val ny = line.direction.x
            val c = nx * line.point.x + ny * line.point.y
            a11 += nx * nx
            a12 += nx * ny
            a22 += ny * ny
            b1 += nx * c
            b2 += ny * c
        }
        val det = a11 * a22 - a12 * a12
        if (abs(det) < 1e-12) return null
        return Vec2((a22 * b1 - a12 * b2) / det, (a11 * b2 - a12 * b1) / det)
    }
}
