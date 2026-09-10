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
import kotlin.math.atan2

/**
 * Handedness and rotation of an image-to-target mapping. Concentric circles fix
 * neither: a reflection and every rotation about the face centre map them onto
 * themselves. Shared by [Rectification] and by the registration, which needs
 * both again after refining the homography.
 */
object Orientation {

    /** Reflection in the x axis of target coordinates. */
    val MIRROR_Y: Mat3 = Mat3.of(
        1.0, 0.0, 0.0,
        0.0, -1.0, 0.0,
        0.0, 0.0, 1.0
    )

    /**
     * [mapping] with its handedness repaired at [at] and [imageUp], taken at
     * [at], pointing along -y: upwards on the face. Null when a direction
     * cannot be mapped at [at].
     */
    fun orient(mapping: Mat3, at: Vec2, imageUp: Vec2 = Vec2(0.0, -1.0)): Mat3? {
        val handed = if (isMirrored(mapping, at)) MIRROR_Y * mapping else mapping
        val rotation = rotationAligning(handed, at, imageUp) ?: return null
        return rotation * handed
    }

    /**
     * Whether [mapping] reverses orientation at [at].
     *
     * The rest of a rectification constrains rotation but not handedness, and
     * no assertion phrased in radii, dot products or distances can see a
     * reflection, because all of those are reflection invariant.
     *
     * Target coordinates share the image's handedness -- x right, y downwards,
     * up on the face being negative y -- so a correct map has a positive
     * Jacobian determinant.
     */
    fun isMirrored(mapping: Mat3, at: Vec2): Boolean {
        val jx = mapping.mapDirection(at, Vec2(1.0, 0.0)) ?: return false
        val jy = mapping.mapDirection(at, Vec2(0.0, 1.0)) ?: return false
        return jx.x * jy.y - jx.y * jy.x < 0.0
    }

    /**
     * Rotation that makes [imageUp], taken at [imagedCentre] and seen through
     * [mapping], point along the negative y axis of target coordinates.
     *
     * The direction has to be taken at the centre of the face: [mapping] is
     * projective, and the image of a direction depends on where it is attached.
     * At the image corner the answer would differ by several degrees for a
     * moderately tilted view.
     */
    fun rotationAligning(mapping: Mat3, imagedCentre: Vec2, imageUp: Vec2): Mat3? {
        val direction = mapping.mapDirection(imagedCentre, imageUp) ?: return null
        if (direction.length < 1e-12) return null
        // We want direction to end up pointing at -90 degrees.
        val current = atan2(direction.y, direction.x)
        return Mat3.rotation(-PI / 2.0 - current)
    }
}
