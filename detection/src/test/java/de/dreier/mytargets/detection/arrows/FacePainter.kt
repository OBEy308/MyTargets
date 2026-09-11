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

package de.dreier.mytargets.detection.arrows

import de.dreier.mytargets.detection.geometry.Vec2
import de.dreier.mytargets.detection.registration.FaceWarp
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Rectified faces painted pixel by pixel, for the tests that need no OpenCV.
 * [edge] px span [-1.1, 1.1]^2 like FaceWarp's square; the default gives
 * 1000 px per spot radius, the resolution radial.py and tips.py worked at.
 */
class FacePainter(background: Float, private val edge: Int = 2200) {

    private val value = FloatArray(edge * edge) { background }
    private val valid = BooleanArray(edge * edge) { true }
    private val k = edge / (2.0 * FaceWarp.EXTENT)

    private fun centre(i: Int) = (i + 0.5) / k - FaceWarp.EXTENT

    private fun pixel(t: Double) = k * (t + FaceWarp.EXTENT) - 0.5

    /** Calls [action] for every pixel in the box whose centre satisfies [inside]. */
    private fun each(
        xMin: Double,
        xMax: Double,
        yMin: Double,
        yMax: Double,
        inside: (Double, Double) -> Boolean,
        action: (Int) -> Unit
    ) {
        val i0 = max(0, floor(pixel(xMin)).toInt())
        val i1 = min(edge - 1, ceil(pixel(xMax)).toInt())
        val j0 = max(0, floor(pixel(yMin)).toInt())
        val j1 = min(edge - 1, ceil(pixel(yMax)).toInt())
        for (j in j0..j1) {
            val y = centre(j)
            for (i in i0..i1) {
                if (inside(centre(i), y)) action(j * edge + i)
            }
        }
    }

    /** Paints the ring between [inner] and [outer]. */
    fun ring(inner: Double, outer: Double, v: Float) = apply {
        each(-outer, outer, -outer, outer, { x, y -> hypot(x, y).let { it >= inner && it < outer } }) {
            value[it] = v
        }
    }

    /** Paints a straight stripe of [width] from [from] to [to], cut square at both ends. */
    fun stripe(from: Vec2, to: Vec2, width: Double, v: Float) = apply {
        val half = width / 2
        val dx = to.x - from.x
        val dy = to.y - from.y
        val length = sqrt(dx * dx + dy * dy)
        each(
            min(from.x, to.x) - half, max(from.x, to.x) + half,
            min(from.y, to.y) - half, max(from.y, to.y) + half,
            { x, y ->
                val along = ((x - from.x) * dx + (y - from.y) * dy) / (length * length)
                val across = ((x - from.x) * dy - (y - from.y) * dx) / length
                along in 0.0..1.0 && abs(across) <= half
            }
        ) { value[it] = v }
    }

    /** Paints everything with y greater than [y]. */
    fun below(y: Double, v: Float) = apply {
        each(-FaceWarp.EXTENT, FaceWarp.EXTENT, y, FaceWarp.EXTENT, { _, py -> py > y }) { value[it] = v }
    }

    /** Marks every pixel with x less than [x] as showing no part of the photograph. */
    fun invalidLeftOf(x: Double) = apply {
        each(-FaceWarp.EXTENT, x, -FaceWarp.EXTENT, FaceWarp.EXTENT, { px, _ -> px < x }) { valid[it] = false }
    }

    fun build() = RectifiedFace(edge, value.copyOf(), valid.copyOf())
}
