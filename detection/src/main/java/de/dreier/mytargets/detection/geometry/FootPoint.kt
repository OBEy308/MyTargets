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

/**
 * The foot of the perpendicular from the camera onto the face plane, in target
 * coordinates (arrow design, Verfahren step 2). In the rectified image an arrow
 * standing perpendicular in the face lies on a line through this point, and
 * its entry point is the end nearer it.
 *
 * It is the image under the homography of the vanishing point of the face
 * normal: the vanishing line is l = H^T (0, 0, 1), the vanishing point
 * v = (K K^T) l, and Q = H v.
 */
object FootPoint {

    /**
     * @param imageToTarget pixels of the original to target coordinates, any scale
     * @return null when the vanishing point or its image lies at infinity, which
     *         a visible face does not produce
     */
    fun of(imageToTarget: Mat3, intrinsics: CameraIntrinsics): Vec2? {
        val vanishingLine = imageToTarget.transpose() * Vec3(0.0, 0.0, 1.0)
        val vanishingPoint = NormalVanishingPoint.compute(vanishingLine, intrinsics) ?: return null
        return imageToTarget.mapPoint(vanishingPoint)
    }
}
