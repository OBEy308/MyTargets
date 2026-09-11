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

import de.dreier.mytargets.detection.geometry.Conic
import de.dreier.mytargets.detection.geometry.Vec2
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/** A conic fitted to boundary points after outliers were removed. */
class RobustFit(
    val conic: Conic,
    val inliers: List<Vec2>,
    /** Median Sampson distance of the inliers, in the units of the points. */
    val medianDistance: Double
)

/**
 * The robust fit of register.py: fit, measure every point's Sampson distance,
 * keep those within 2.5 sigma, four times over. Sigma comes from the median
 * distance and has a floor, because on a clean edge the median nearly
 * vanishes and the half-pixel steps of the ray sampling would then read as
 * outliers.
 */
object RobustConic {

    const val ITERATIONS = 4
    const val KEEP = 2.5
    private const val MEDIAN_TO_SIGMA = 1.4826
    private const val MIN_KEPT = 10

    /** First-order approximation of the geometric distance from [p] to [conic]. */
    fun sampsonDistance(conic: Conic, p: Vec2): Double {
        val h = p.homogeneous()
        val cp = conic.matrix * h
        return abs(h.dot(cp)) / max(2.0 * hypot(cp.x, cp.y), 1e-12)
    }

    /** The distance below which a point is kept. */
    fun threshold(medianDistance: Double, floorPx: Double): Double =
        KEEP * max(medianDistance * MEDIAN_TO_SIGMA + 1e-9, floorPx)

    /**
     * @param floorPx the smallest sigma in pixels: 0.5 px at 2000 px, times f
     * @return null for fewer than five points or when a fit degenerates
     */
    fun fit(points: List<Vec2>, floorPx: Double): RobustFit? {
        if (points.size < 5) return null
        var kept = points
        for (iteration in 0 until ITERATIONS) {
            val conic = Conic.fit(kept) ?: return null
            val limit = threshold(median(kept.map { sampsonDistance(conic, it) }), floorPx)
            val next = points.filter { sampsonDistance(conic, it) < limit }
            if (next.size < MIN_KEPT) break
            kept = next
        }
        val conic = Conic.fit(kept) ?: return null
        return RobustFit(conic, kept, median(kept.map { sampsonDistance(conic, it) }))
    }

    internal fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val n = sorted.size
        return if (n % 2 == 1) sorted[n / 2] else 0.5 * (sorted[n / 2 - 1] + sorted[n / 2])
    }
}
