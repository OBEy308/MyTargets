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

package de.dreier.mytargets.detection

import de.dreier.mytargets.detection.geometry.Vec2

/**
 * Assigns a point on the face to a spot and converts it to that spot's local
 * coordinates, where the spot centre is the origin and its outermost ring has
 * radius one. Those are the coordinates `Shot.x` and `Shot.y` carry.
 */
object SpotMapping {

    class Located(val faceIndex: Int, val local: Vec2)

    /**
     * @return the spot containing [pointOnFace] and the local coordinates
     *         within it, or null when the point lies outside every spot -- an
     *         arrow next to the face.
     */
    fun locate(pointOnFace: Vec2, layout: FaceLayout): Located? {
        var bestIndex = -1
        var bestDistance = Double.MAX_VALUE

        layout.facePositions.forEachIndexed { index, centre ->
            val distance = pointOnFace.distanceTo(centre)
            if (distance <= layout.faceRadius && distance < bestDistance) {
                bestDistance = distance
                bestIndex = index
            }
        }

        if (bestIndex < 0) return null

        val centre = layout.facePositions[bestIndex]
        val local = (pointOnFace - centre) * (1.0 / layout.faceRadius)
        return Located(bestIndex, local)
    }
}
