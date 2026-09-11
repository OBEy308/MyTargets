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

import de.dreier.mytargets.detection.geometry.CameraIntrinsics
import de.dreier.mytargets.detection.geometry.Mat3
import de.dreier.mytargets.detection.geometry.Vec2
import de.dreier.mytargets.detection.geometry.Vec3
import de.dreier.mytargets.detection.registration.SyntheticFace
import de.dreier.mytargets.detection.registration.releaseIfThrows
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import kotlin.math.cos
import kotlin.math.sin

/**
 * The pinhole camera SyntheticFace.view builds: focal length FOCAL px, the
 * principal point on the face centre at [centre], turned by [angleDegrees]
 * about the vertical axis, [pxPerRadius] across the face centre vertically.
 * Target coordinates run x right, y down, z into the face; the camera stands
 * at (d sin a, 0, -d cos a) with d = FOCAL / pxPerRadius.
 */
class SyntheticCamera(angleDegrees: Double, pxPerRadius: Double, val centre: Vec2) {

    private val c = cos(Math.toRadians(angleDegrees))
    private val s = sin(Math.toRadians(angleDegrees))
    private val distance = FOCAL / pxPerRadius

    /** Height of the camera above the face plane, in spot radii. */
    val height = distance * c

    /** The foot of the perpendicular from the camera onto the face. */
    val footPoint = Vec2(distance * s, 0.0)

    val targetToPhoto: Mat3 = SyntheticFace.view(angleDegrees, pxPerRadius, centre)

    val intrinsics = CameraIntrinsics(FOCAL, centre)

    /** Depth of [p] in front of the camera. */
    fun depth(p: Vec3) = -p.x * s + p.z * c + distance

    /** Where [p] appears in the photograph. */
    fun project(p: Vec3): Vec2 {
        val z = depth(p)
        require(z > 1e-9) { "$p is not in front of the camera" }
        return Vec2(FOCAL * (p.x * c + p.z * s) / z + centre.x, FOCAL * p.y / z + centre.y)
    }

    /** Where the ray from the camera through [p] meets the face: what the rectified image shows at [p]. */
    fun onFace(p: Vec3): Vec2 {
        val k = -height / (-height - p.z)
        return Vec2(footPoint.x + (p.x - footPoint.x) * k, footPoint.y + (p.y - footPoint.y) * k)
    }

    companion object {
        /** SyntheticFace.view's focal length. */
        const val FOCAL = 1500.0
    }
}

/**
 * An arrow in the face: [entry] on the face, the nock [height] out of it
 * towards the camera and displaced by [tilt] per unit of height. Zero tilt
 * stands perpendicular; |tilt| is the tangent of the lean against the normal.
 */
class SyntheticArrow(
    val entry: Vec2,
    val tilt: Vec2 = Vec2(0.0, 0.0),
    val shaft: Scalar = DARK,
    val height: Double = 0.8,
    val width: Double = 0.014
) {
    val base: Vec3
        get() = Vec3(entry.x, entry.y, 0.0)

    val nock: Vec3
        get() = Vec3(entry.x + tilt.x * height, entry.y + tilt.y * height, -height)

    companion object {
        val DARK = Scalar(35.0, 35.0, 40.0)
        val GREY = Scalar(150.0, 150.0, 150.0)
        val FLETCHING = Scalar(60.0, 180.0, 60.0)
    }
}

object SyntheticArrows {

    private const val FLETCHING_WIDTH = 0.05
    private const val FLETCHING_FROM = 0.8
    private const val SHIFT = 4

    /** The face with [arrows] in it, seen by [camera]; the caller releases it. */
    fun photograph(camera: SyntheticCamera, width: Int, height: Int, arrows: List<SyntheticArrow>): Mat =
        SyntheticFace.photograph(width, height, camera.targetToPhoto).releaseIfThrows { photo ->
            for (arrow in arrows) draw(photo, camera, arrow)
        }

    /** The shaft from the entry to the nock, cut square at the entry, with fletching over its last fifth. */
    fun draw(photo: Mat, camera: SyntheticCamera, arrow: SyntheticArrow) {
        bar(photo, camera, arrow.base, arrow.nock, arrow.width, arrow.shaft)
        bar(photo, camera, lerp(arrow.base, arrow.nock, FLETCHING_FROM), arrow.nock, FLETCHING_WIDTH, SyntheticArrow.FLETCHING)
    }

    /** A stripe lying on the face from [from] to [to]: a shadow, or a mark. */
    fun stripeOnFace(photo: Mat, camera: SyntheticCamera, from: Vec2, to: Vec2, width: Double, colour: Scalar) {
        bar(photo, camera, Vec3(from.x, from.y, 0.0), Vec3(to.x, to.y, 0.0), width, colour)
    }

    /** A bar of [width] from [from] to [to] in space, its width shrinking with depth, anti-aliased. */
    private fun bar(photo: Mat, camera: SyntheticCamera, from: Vec3, to: Vec3, width: Double, colour: Scalar) {
        val a = camera.project(from)
        val b = camera.project(to)
        val along = (b - a) * (1.0 / (b - a).length)
        val across = Vec2(-along.y, along.x)
        val halfA = width * SyntheticCamera.FOCAL / camera.depth(from) / 2
        val halfB = width * SyntheticCamera.FOCAL / camera.depth(to) / 2
        val corners = listOf(a + across * halfA, b + across * halfB, b - across * halfB, a - across * halfA)
        val scale = (1 shl SHIFT).toDouble()
        val polygon = MatOfPoint(*corners.map { Point(Math.round(it.x * scale).toDouble(), Math.round(it.y * scale).toDouble()) }.toTypedArray())
        try {
            Imgproc.fillConvexPoly(photo, polygon, colour, Imgproc.LINE_AA, SHIFT)
        } finally {
            polygon.release()
        }
    }

    private fun lerp(p: Vec3, q: Vec3, t: Double) =
        Vec3(p.x + (q.x - p.x) * t, p.y + (q.y - p.y) * t, p.z + (q.z - p.z) * t)
}
