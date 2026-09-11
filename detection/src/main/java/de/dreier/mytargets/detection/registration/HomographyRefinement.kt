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

package de.dreier.mytargets.detection.registration

import de.dreier.mytargets.detection.geometry.Mat3
import de.dreier.mytargets.detection.geometry.Vec2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt

/** Image points that should lie on the ring of nominal [radius] once mapped to the target. */
class RingPoints(val radius: Double, val points: List<Vec2>)

/** The refined homography and the rings that stayed in the fit. */
class Refined(val homography: Mat3, val rings: List<RingPoints>)

/**
 * Levenberg-Marquardt on the radial residual |H p| - r over all ring points,
 * as register.py's refine_homography. The residual does not change when the
 * target turns about its centre, so the Jacobian has rank 7 of 8: the damping
 * copes with that, plain Gauss-Newton would not, and the rotation may drift
 * during the iteration. Callers fix it afterwards with Orientation.orient.
 */
object HomographyRefinement {

    private const val MAX_ITERATIONS = 60
    private const val MAX_DAMPING_STEPS = 12

    /** register.py drops a ring whose radial RMS exceeds this, while more than two remain. */
    const val DROP_RMS = 0.012

    /** Refines [start], normalised so that H[2,2] = 1. */
    fun refine(start: Mat3, rings: List<RingPoints>): Mat3 {
        var h = parameters(start)
        var r = residuals(h, rings)
        var cost = sumOfSquares(r)
        var lambda = 1e-3

        for (iteration in 0 until MAX_ITERATIONS) {
            val j = jacobian(h, rings, r)
            val a = Array(8) { p -> DoubleArray(8) { q -> dot(j[p], j[q]) } }
            val g = DoubleArray(8) { p -> dot(j[p], r) }

            var improved = false
            var lastStep: DoubleArray? = null
            for (attempt in 0 until MAX_DAMPING_STEPS) {
                val damped = Array(8) { p ->
                    DoubleArray(8) { q ->
                        if (p == q) a[p][q] + lambda * (a[p][p] + 1e-12) else a[p][q]
                    }
                }
                val step = LinearSystem.solve(damped, DoubleArray(8) { -g[it] })
                if (step == null) {
                    lambda *= 10.0
                    continue
                }
                lastStep = step
                val candidate = DoubleArray(8) { h[it] + step[it] }
                val candidateResiduals = residuals(candidate, rings)
                val candidateCost = sumOfSquares(candidateResiduals)
                if (candidateCost < cost) {
                    h = candidate
                    r = candidateResiduals
                    cost = candidateCost
                    lambda = max(lambda * 0.3, 1e-12)
                    improved = true
                    break
                }
                lambda *= 10.0
            }
            if (!improved || lastStep == null || sqrt(sumOfSquares(lastStep)) < 1e-12) break
        }
        return matrix(h)
    }

    /**
     * [refine] over all [rings]; while the worst ring's radial RMS exceeds
     * [DROP_RMS] and more than two rings remain, that ring leaves and the fit
     * starts again from [start].
     */
    fun refineDroppingRings(start: Mat3, rings: List<RingPoints>): Refined {
        var kept = rings
        while (true) {
            val h = refine(start, kept)
            val worst = kept.maxBy { radialRms(h, it) }
            if (kept.size > 2 && radialRms(h, worst) > DROP_RMS) {
                kept = kept - worst
                continue
            }
            return Refined(h, kept)
        }
    }

    fun radialRms(h: Mat3, ring: RingPoints): Double {
        var sum = 0.0
        for (p in ring.points) {
            val q = h.mapPoint(p)
            val d = if (q == null) Double.MAX_VALUE else hypot(q.x, q.y) - ring.radius
            sum += d * d
        }
        return sqrt(sum / ring.points.size)
    }

    private fun parameters(h: Mat3): DoubleArray {
        val w = h[2, 2]
        require(w != 0.0) { "a homography with H[2,2] = 0 cannot be normalised" }
        return DoubleArray(8) { h[it / 3, it % 3] / w }
    }

    private fun matrix(h: DoubleArray) = Mat3.of(
        h[0], h[1], h[2],
        h[3], h[4], h[5],
        h[6], h[7], 1.0
    )

    private fun residuals(h: DoubleArray, rings: List<RingPoints>): DoubleArray {
        val out = DoubleArray(rings.sumOf { it.points.size })
        var i = 0
        for (ring in rings) {
            for (p in ring.points) {
                val x = h[0] * p.x + h[1] * p.y + h[2]
                val y = h[3] * p.x + h[4] * p.y + h[5]
                val w = h[6] * p.x + h[7] * p.y + 1.0
                out[i++] = hypot(x / w, y / w) - ring.radius
            }
        }
        return out
    }

    /** Forward differences, one column per parameter, as register.py. */
    private fun jacobian(h: DoubleArray, rings: List<RingPoints>, r: DoubleArray): Array<DoubleArray> =
        Array(8) { p ->
            val step = 1e-7 * max(1.0, kotlin.math.abs(h[p]))
            val shifted = h.copyOf()
            shifted[p] += step
            val rp = residuals(shifted, rings)
            DoubleArray(r.size) { (rp[it] - r[it]) / step }
        }

    private fun dot(a: DoubleArray, b: DoubleArray): Double {
        var sum = 0.0
        for (i in a.indices) {
            sum += a[i] * b[i]
        }
        return sum
    }

    private fun sumOfSquares(v: DoubleArray) = dot(v, v)
}
