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
import kotlin.math.max
import kotlin.math.min

/**
 * How much darker a line is than both its flanks, or how much lighter, per
 * sample along it (arrow design, Verfahren steps 3 and 4). A dark shaft on a
 * coloured ring is darker than both flanks; a grey shaft on the black ring is
 * lighter than both. An edge between two areas is neither, because one flank is
 * as dark as the line. The contrast is local, so it needs no correction of the
 * lighting.
 */
class FlankContrast private constructor(
    private val centreOffsets: DoubleArray,
    /** How far the flanks lie from the line on either side. */
    val flank: Double,
    private val bilinear: Boolean
) {

    /**
     * The contrast at origin + s * [direction] for each s in [along], on the
     * line shifted by [offset] along the left normal (-direction.y, direction.x).
     * [direction] must be a unit vector. A sample that touches a pixel outside
     * the photograph has contrast 0.
     */
    fun profile(
        face: RectifiedFace,
        origin: Vec2,
        direction: Vec2,
        offset: Double,
        along: DoubleArray
    ): DoubleArray {
        val ux = direction.x
        val uy = direction.y
        val nx = -uy
        val ny = ux
        val out = DoubleArray(along.size)
        for (k in along.indices) {
            val bx = origin.x + offset * nx + along[k] * ux
            val by = origin.y + offset * ny + along[k] * uy
            var lowest = Double.MAX_VALUE
            var highest = -Double.MAX_VALUE
            var inside = true
            for (c in centreOffsets) {
                val v = sample(face, bx + c * nx, by + c * ny)
                if (v.isNaN()) {
                    inside = false
                    break
                }
                lowest = min(lowest, v)
                highest = max(highest, v)
            }
            if (!inside) continue
            val left = sample(face, bx - flank * nx, by - flank * ny)
            val right = sample(face, bx + flank * nx, by + flank * ny)
            if (left.isNaN() || right.isNaN()) continue
            val darker = min(left, right) - lowest
            val lighter = highest - max(left, right)
            out[k] = max(darker, lighter)
        }
        return out
    }

    private fun sample(face: RectifiedFace, x: Double, y: Double): Double =
        (if (bilinear) face.bilinear(x, y) else face.nearest(x, y)).toDouble()

    companion object {
        /** radial.py: three lines at -0.003, 0, +0.003, flanks at 0.016, the nearest pixel. */
        val SEARCH = FlankContrast(doubleArrayOf(-0.003, 0.0, 0.003), 0.016, bilinear = false)

        /** tips.py: seven lines from -0.003 to +0.003, flanks at 0.012, bilinear. */
        val WALK = FlankContrast(DoubleArray(7) { -0.003 + 0.001 * it }, 0.012, bilinear = true)
    }
}
