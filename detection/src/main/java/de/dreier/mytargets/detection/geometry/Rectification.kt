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
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Turns the images of two concentric target rings into the homography that maps
 * the photograph to target coordinates.
 *
 * The chain is: imaged centre and vanishing line from the pencil, affine
 * rectification from that line, metric rectification from the outer ellipse,
 * then scale and translation from the known radii and the centre. Rotation
 * about the target axis is not observable from concentric circles -- the face
 * is rotationally symmetric -- and is fixed by [imageUp], measured at the
 * imaged centre.
 */
object Rectification {

    class Result(
        val imageToTarget: Mat3,
        val vanishingLine: Vec3,
        val imagedCentre: Vec2
    )

    /**
     * @param outer image of the ring with the larger nominal [outerRadius]
     * @param inner image of the ring with the smaller nominal [innerRadius]
     * @param imageUp the direction that should end up pointing up in target
     *        coordinates; the default assumes the phone was held upright, with
     *        image y growing downwards
     */
    fun fromConcentricCircles(
        outer: Conic,
        outerRadius: Double,
        inner: Conic,
        innerRadius: Double,
        imageUp: Vec2 = Vec2(0.0, -1.0)
    ): Result? {
        require(outerRadius > innerRadius) { "outer radius must be the larger one" }

        // Every step below is conic arithmetic, and at pixel scale that mixes a
        // constant term of order 10^7 with quadratic ones of order one: the
        // guards in metricFromEllipse, centreOf and radiusOf then see rounding
        // noise rather than the conic. Work in a frame where the outer ring is
        // roughly the unit circle about the origin -- the same treatment
        // VanishingLine already gives itself -- and convert back at the end.
        val frame = outer.normalisingFrame() ?: return null
        val outerInFrame = outer.transformedBy(frame)
        val innerInFrame = inner.transformedBy(frame)

        val pencil = VanishingLine.fromConcentricCircles(outerInFrame, innerInFrame)
            ?: return null
        // Both of these are in frame coordinates, not image coordinates.
        val lineInFrame = pencil.line
        val centreInFrame = pencil.imagedCentre

        // Affine rectification: send the vanishing line to (0, 0, 1).
        val affine = Mat3.of(
            1.0, 0.0, 0.0,
            0.0, 1.0, 0.0,
            lineInFrame.x, lineInFrame.y, lineInFrame.z
        )
        if (affine.inverse() == null) return null

        val affineOuter = outerInFrame.transformedBy(affine)
        val affineInner = innerInFrame.transformedBy(affine)

        // After affine rectification the conics are ellipses that differ from
        // circles by one common linear map. Recover it from the outer one.
        val metric = metricFromEllipse(affineOuter) ?: return null

        val metricOuter = affineOuter.transformedBy(metric)
        val metricInner = affineInner.transformedBy(metric)

        val centre = centreOf(metricOuter) ?: return null
        val centreInner = centreOf(metricInner) ?: return null
        val radius = radiusOf(metricOuter, centre)
        if (centre.distanceTo(centreInner) > CONCENTRIC_TOLERANCE * radius) {
            return null
        }
        if (radius < 1e-9) return null

        // Check the radius ratio; if it is wrong these were not the rings we
        // were told they were.
        val expectedRatio = innerRadius / outerRadius
        val actualRatio = radiusOf(metricInner, centreInner) / radius
        if (abs(actualRatio - expectedRatio) > RATIO_TOLERANCE * expectedRatio) return null

        val scale = outerRadius / radius
        val toOrigin = Mat3.translation(Vec2(-centre.x, -centre.y))
        val scaling = Mat3.of(
            scale, 0.0, 0.0,
            0.0, scale, 0.0,
            0.0, 0.0, 1.0
        )

        // This chain starts in frame coordinates, so it must be probed there.
        val withoutRotation = scaling * toOrigin * metric * affine

        val orientationFixed = if (isMirrored(withoutRotation, centreInFrame)) {
            MIRROR_Y * withoutRotation
        } else {
            withoutRotation
        }

        // Fix the remaining rotation so that imageUp, taken at the imaged
        // centre, points along -y in target coordinates: upwards on the face.
        // [imageUp] is a direction and [frame] is a similarity, so it passes
        // through unchanged up to a positive scale factor, which leaves the
        // angle -- and hence the rotation -- the same. Only the point the
        // direction is attached to has to be moved into the frame.
        val rotation = rotationAligning(orientationFixed, centreInFrame, imageUp)
            ?: return null
        val frameToTarget = rotation * orientationFixed

        // Out of the frame again: image points pass through the frame first,
        // lines transform contragrediently, and the centre needs the inverse.
        val imagedCentre = frame.inverse()?.mapPoint(centreInFrame) ?: return null
        return Result(
            imageToTarget = frameToTarget * frame,
            vanishingLine = (frame.transpose() * lineInFrame).normalized(),
            imagedCentre = imagedCentre
        )
    }

    private const val CONCENTRIC_TOLERANCE = 0.05
    // Relative tolerance on the dimensionless radius ratio; an absolute
    // tolerance would weaken as the rings diverge -- at a ratio of 0.1 it
    // would accept 80 percent relative error.
    private const val RATIO_TOLERANCE = 0.08

    /** Reflection in the x axis, used to repair a mirrored rectification. */
    private val MIRROR_Y = Mat3.of(
        1.0, 0.0, 0.0,
        0.0, -1.0, 0.0,
        0.0, 0.0, 1.0
    )

    /**
     * A linear map taking the given ellipse to a circle. The ellipse matrix
     * restricted to its upper 2x2 block is symmetric definite (positive or
     * negative, depending on the conic's overall sign); its square root is
     * the map we want.
     */
    private fun metricFromEllipse(conic: Conic): Mat3? {
        val c = conic.normalized().matrix
        val block = arrayOf(
            doubleArrayOf(c[0, 0], c[0, 1]),
            doubleArrayOf(c[1, 0], c[1, 1])
        )
        val eigen = SymmetricEigen.decompose(block)
        val l0 = eigen.values[0]
        val l1 = eigen.values[1]
        // Both eigenvalues must share a sign for the conic to be an ellipse.
        if (l0 * l1 <= 0.0) return null
        val s = if (l0 > 0) 1.0 else -1.0
        val a0 = sqrt(s * l0)
        val a1 = sqrt(s * l1)
        if (a0 < 1e-12 || a1 < 1e-12) return null

        // block = V diag(l) V^T, so the map is diag(sqrt(l)) V^T.
        val v = eigen.vectors
        return Mat3.of(
            a0 * v[0][0], a0 * v[0][1], 0.0,
            a1 * v[1][0], a1 * v[1][1], 0.0,
            0.0, 0.0, 1.0
        )
    }

    /**
     * Centre of a conic: the pole of the line at infinity. Delegates to
     * [Conic.centre] rather than routing through the 3x3 inverse, because
     * that route is numerically unusable at pixel scale -- do not simplify
     * this back.
     */
    private fun centreOf(conic: Conic): Vec2? = conic.centre()

    /** Radius of a circle-shaped conic, measured from its centre. */
    private fun radiusOf(conic: Conic, centre: Vec2): Double {
        val c = conic.normalized().matrix
        // For a x^2 + a y^2 + ... the radius follows from the constant term
        // after translating the centre to the origin.
        val a = c[0, 0]
        if (abs(a) < 1e-15) return 0.0
        val f = c[2, 2] + a * (centre.x * centre.x + centre.y * centre.y) +
            2.0 * (c[0, 2] * centre.x + c[1, 2] * centre.y)
        val r2 = -f / a
        return if (r2 <= 0.0) 0.0 else sqrt(r2)
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
    private fun rotationAligning(mapping: Mat3, imagedCentre: Vec2, imageUp: Vec2): Mat3? {
        val direction = mapping.mapDirection(imagedCentre, imageUp) ?: return null
        if (direction.length < 1e-12) return null
        // We want direction to end up pointing at -90 degrees.
        val current = atan2(direction.y, direction.x)
        return Mat3.rotation(-PI / 2.0 - current)
    }

    /**
     * Whether [mapping] reverses orientation at [at].
     *
     * The chain above constrains rotation but not handedness: the metric step's
     * 2x2 block is diag(sqrt(lambda)) V^T, and the sign of det(V) is whatever
     * the Jacobi sweeps left on the eigenvectors. Without this check the face
     * comes out mirrored in about half of all views, and no assertion phrased in
     * radii, dot products or distances can see it, because all of those are
     * reflection invariant.
     *
     * Target coordinates share the image's handedness -- x right, y downwards,
     * up on the face being negative y -- so a correct map has a positive
     * Jacobian determinant.
     */
    private fun isMirrored(mapping: Mat3, at: Vec2): Boolean {
        val jx = mapping.mapDirection(at, Vec2(1.0, 0.0)) ?: return false
        val jy = mapping.mapDirection(at, Vec2(0.0, 1.0)) ?: return false
        return jx.x * jy.y - jx.y * jy.x < 0.0
    }
}
