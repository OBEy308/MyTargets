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

import java.util.Locale
import kotlin.math.abs
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
 * imaged centre. [attempt] says why a pair was rejected; the registration
 * reports that per pair.
 */
object Rectification {

    class Result(
        val imageToTarget: Mat3,
        val vanishingLine: Vec3,
        val imagedCentre: Vec2
    )

    /** Why a pair of conics gave no rectification. */
    enum class Reason {
        /** The outer conic has no unique centre, so there is no working frame. */
        DEGENERATE_OUTER,

        /** The pencil of the two conics has no usable degenerate member. */
        NO_PENCIL,

        /** The vanishing line passes through the origin of the frame. */
        SINGULAR_AFFINE,

        /** After affine rectification the outer conic is not an ellipse. */
        NOT_AN_ELLIPSE,

        /** A rectified conic has no unique centre. */
        NO_CENTRE,

        /** The rectified outer circle has no radius. */
        ZERO_RADIUS,

        /** Centre distance in outer radii, against [CONCENTRIC_TOLERANCE]. */
        NOT_CONCENTRIC,

        /** Measured radius ratio, against the declared one. */
        RATIO_MISMATCH,

        /** Image up cannot be mapped at the imaged centre. */
        NO_ORIENTATION
    }

    sealed interface Attempt {
        class Rectified(val result: Result) : Attempt

        /**
         * [value] and [limit] are set for [Reason.NOT_CONCENTRIC] (centre
         * distance in outer radii, tolerance) and [Reason.RATIO_MISMATCH]
         * (measured ratio, declared ratio).
         */
        class Rejected(
            val reason: Reason,
            val value: Double? = null,
            val limit: Double? = null
        ) : Attempt {
            fun describe(): String = when (reason) {
                Reason.NOT_CONCENTRIC ->
                    "centres ${format(value)} outer radii apart, limit ${format(limit)}"
                Reason.RATIO_MISMATCH ->
                    "radius ratio ${format(value)}, expected ${format(limit)}"
                else -> reason.name.lowercase().replace('_', ' ')
            }

            private fun format(v: Double?) =
                if (v == null) "?" else String.format(Locale.ROOT, "%.3f", v)
        }
    }

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
    ): Result? =
        (attempt(outer, outerRadius, inner, innerRadius, imageUp) as? Attempt.Rectified)?.result

    /** As [fromConcentricCircles], but says why when there is no result. */
    fun attempt(
        outer: Conic,
        outerRadius: Double,
        inner: Conic,
        innerRadius: Double,
        imageUp: Vec2 = Vec2(0.0, -1.0)
    ): Attempt {
        require(outerRadius > innerRadius) { "outer radius must be the larger one" }

        // Every step below is conic arithmetic, and at pixel scale that mixes a
        // constant term of order 10^7 with quadratic ones of order one: the
        // guards in metricFromEllipse, centreOf and radiusOf then see rounding
        // noise rather than the conic. Work in a frame where the outer ring is
        // roughly the unit circle about the origin -- the same treatment
        // VanishingLine already gives itself -- and convert back at the end.
        val frame = outer.normalisingFrame() ?: return Attempt.Rejected(Reason.DEGENERATE_OUTER)
        val outerInFrame = outer.transformedBy(frame)
        val innerInFrame = inner.transformedBy(frame)

        val pencil = VanishingLine.fromConcentricCircles(outerInFrame, innerInFrame)
            ?: return Attempt.Rejected(Reason.NO_PENCIL)
        // Both of these are in frame coordinates, not image coordinates.
        val lineInFrame = pencil.line
        val centreInFrame = pencil.imagedCentre

        // Affine rectification: send the vanishing line to (0, 0, 1).
        val affine = Mat3.of(
            1.0, 0.0, 0.0,
            0.0, 1.0, 0.0,
            lineInFrame.x, lineInFrame.y, lineInFrame.z
        )
        if (affine.inverse() == null) return Attempt.Rejected(Reason.SINGULAR_AFFINE)

        val affineOuter = outerInFrame.transformedBy(affine)
        val affineInner = innerInFrame.transformedBy(affine)

        // After affine rectification the conics are ellipses that differ from
        // circles by one common linear map. Recover it from the outer one.
        val metric = metricFromEllipse(affineOuter) ?: return Attempt.Rejected(Reason.NOT_AN_ELLIPSE)

        val metricOuter = affineOuter.transformedBy(metric)
        val metricInner = affineInner.transformedBy(metric)

        val centre = centreOf(metricOuter) ?: return Attempt.Rejected(Reason.NO_CENTRE)
        val centreInner = centreOf(metricInner) ?: return Attempt.Rejected(Reason.NO_CENTRE)
        val radius = radiusOf(metricOuter, centre)
        if (radius < 1e-9) return Attempt.Rejected(Reason.ZERO_RADIUS)
        val offset = centre.distanceTo(centreInner) / radius
        if (offset > CONCENTRIC_TOLERANCE) {
            return Attempt.Rejected(Reason.NOT_CONCENTRIC, offset, CONCENTRIC_TOLERANCE)
        }

        // Check the radius ratio; if it is wrong these were not the rings we
        // were told they were.
        val expectedRatio = innerRadius / outerRadius
        val actualRatio = radiusOf(metricInner, centreInner) / radius
        if (abs(actualRatio - expectedRatio) > RATIO_TOLERANCE * expectedRatio) {
            return Attempt.Rejected(Reason.RATIO_MISMATCH, actualRatio, expectedRatio)
        }

        val scale = outerRadius / radius
        val toOrigin = Mat3.translation(Vec2(-centre.x, -centre.y))
        val scaling = Mat3.of(
            scale, 0.0, 0.0,
            0.0, scale, 0.0,
            0.0, 0.0, 1.0
        )

        // This chain starts in frame coordinates, so it must be probed there.
        val withoutRotation = scaling * toOrigin * metric * affine

        // [imageUp] is a direction and [frame] is a similarity, so it passes
        // through unchanged up to a positive scale factor, which leaves the
        // angle -- and hence the rotation -- the same. Only the point the
        // direction is attached to has to be moved into the frame.
        val frameToTarget = Orientation.orient(withoutRotation, centreInFrame, imageUp)
            ?: return Attempt.Rejected(Reason.NO_ORIENTATION)

        // Out of the frame again: image points pass through the frame first,
        // lines transform contragrediently, and the centre needs the inverse.
        val imagedCentre = frame.inverse()?.mapPoint(centreInFrame)
            ?: return Attempt.Rejected(Reason.NO_CENTRE)
        return Attempt.Rectified(
            Result(
                imageToTarget = frameToTarget * frame,
                vanishingLine = (frame.transpose() * lineInFrame).normalized(),
                imagedCentre = imagedCentre
            )
        )
    }

    private const val CONCENTRIC_TOLERANCE = 0.05
    // Relative tolerance on the dimensionless radius ratio; an absolute
    // tolerance would weaken as the rings diverge -- at a ratio of 0.1 it
    // would accept 80 percent relative error.
    private const val RATIO_TOLERANCE = 0.08

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
}
