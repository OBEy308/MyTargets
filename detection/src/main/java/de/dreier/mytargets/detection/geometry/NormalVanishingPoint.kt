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

import kotlin.math.max

/**
 * A pinhole camera reduced to what this pipeline needs: square pixels, no skew.
 */
class CameraIntrinsics(val focalLengthPx: Double, val principalPoint: Vec2) {

    /** K K^T, the dual image of the absolute conic for square pixels. */
    fun dualAbsoluteConic(): Mat3 {
        val f = focalLengthPx
        val cx = principalPoint.x
        val cy = principalPoint.y
        return Mat3.of(
            f * f + cx * cx, cx * cy, cx,
            cx * cy, f * f + cy * cy, cy,
            cx, cy, 1.0
        )
    }

    companion object {
        /**
         * Fallback focal length as a fraction of the long image edge. 0.75
         * corresponds to a 27 mm lens in 35 mm terms, which is where phone main
         * cameras sit (24 to 28 mm). The image diagonal, a common default, would
         * be a 45 mm lens and off by a factor of 1.7.
         */
        private const val FALLBACK_FOCAL_FRACTION = 0.75

        /** Width of a 35 mm film frame, the reference for EXIF's
         *  FocalLengthIn35mmFilm. */
        private const val FILM_WIDTH_MM = 36.0

        /**
         * Principal point at the image centre. [focalLengthPx] should come from
         * EXIF where available, see [from35mmEquivalent]; without it the long
         * edge times [FALLBACK_FOCAL_FRACTION] is used.
         */
        fun approximate(
            imageWidth: Int,
            imageHeight: Int,
            focalLengthPx: Double? = null
        ): CameraIntrinsics = CameraIntrinsics(
            focalLengthPx
                ?: FALLBACK_FOCAL_FRACTION * max(imageWidth, imageHeight),
            Vec2(imageWidth / 2.0, imageHeight / 2.0)
        )

        /**
         * From EXIF's FocalLengthIn35mmFilm: the film frame's 36 mm span the
         * long edge of the image.
         */
        fun from35mmEquivalent(
            imageWidth: Int,
            imageHeight: Int,
            focalLength35mm: Double
        ): CameraIntrinsics {
            require(focalLength35mm > 0.0) { "focal length must be positive" }
            val longEdge = max(imageWidth, imageHeight).toDouble()
            return approximate(
                imageWidth, imageHeight,
                focalLengthPx = focalLength35mm / FILM_WIDTH_MM * longEdge
            )
        }
    }
}

/** The two ends of an imaged arrow shaft, in no particular order. */
data class Streak(val endA: Vec2, val endB: Vec2)

/**
 * The vanishing point of the target plane's normal, and what it is for.
 *
 * An arrow sticks out of the face towards the camera. A point X + t d images as
 * K X + t K d, so walking along the normal away from the camera -- into the
 * face, with increasing depth -- moves the image *towards* the vanishing point
 * of that normal, reaching it at infinite depth. Walking the other way, out of
 * the face towards the camera, moves the image *away* from the vanishing point,
 * off to infinity at the principal plane.
 *
 * The nock is the end raised out of the face towards the camera, so along an
 * imaged shaft the nock is the end FARTHER from the vanishing point and the
 * entry hole is the end NEARER it.
 *
 * Note that the homography alone does not give this point. It gives the
 * vanishing line l of the plane; the vanishing point of the normal is
 * v = (K K^T) l, so the camera intrinsics are required. The spec's phrasing
 * "follows from the homography" is a shortcut.
 *
 * Given l, v is unique -- there is no sign to choose. What is uncertain for a
 * nearly frontal view is how far away l is; v then sits close to the principal
 * point and its exact position is poorly determined. Checking the streaks
 * against an uncertain v is the perception pipeline's job.
 */
object NormalVanishingPoint {

    /** Null when the normal is parallel to the image plane, which cannot occur
     *  for a face that is visible at all. */
    fun compute(vanishingLine: Vec3, intrinsics: CameraIntrinsics): Vec2? =
        (intrinsics.dualAbsoluteConic() * vanishingLine).toVec2()

    /**
     * For each streak the end nearer [vanishingPoint], which is the entry point
     * of the arrow. The other end is the nock, which stands out of the face
     * towards the camera and is therefore imaged farther from the vanishing
     * point.
     */
    fun entryPoints(streaks: List<Streak>, vanishingPoint: Vec2): List<Vec2> =
        streaks.map { streak ->
            if (streak.endA.distanceTo(vanishingPoint) <
                streak.endB.distanceTo(vanishingPoint)
            ) {
                streak.endA
            } else {
                streak.endB
            }
        }
}
