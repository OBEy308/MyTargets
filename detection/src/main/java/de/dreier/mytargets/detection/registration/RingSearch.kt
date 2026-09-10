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
import de.dreier.mytargets.detection.geometry.Vec3
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** One transition's search: the points found, the fit, and why the ring is not used, if it is not. */
class RingAttempt(
    val transition: RingTransition,
    val points: List<Vec2>,
    val fit: RobustFit?,
    val rejection: String?
) {
    val accepted: Boolean
        get() = rejection == null
}

/**
 * register.py's ring loop: each transition in turn, outward from the disc,
 * the search window following the conic of the last accepted ring.
 */
object RingSearch {

    const val RAYS = 720
    const val MIN_POINTS = 40
    const val MIN_INLIERS = 60
    private const val MAX_MEDIAN_AT_2000 = 3.0
    private const val FLOOR_AT_2000 = 0.5
    private const val GAP_AT_2000 = 12.0

    fun find(
        classes: ClassImage,
        disc: Disc,
        transitions: List<RingTransition>,
        f: Double
    ): List<RingAttempt> {
        var centre = disc.centre
        var innerRadius: (Double) -> Double? = { disc.radius }
        var lo = 0.6
        var hi = 1.7
        var lastAccepted: RingAttempt? = null
        val attempts = ArrayList<RingAttempt>()

        for (transition in transitions) {
            lastAccepted?.let { previous ->
                val conic = previous.fit!!.conic
                val conicCentre = conic.centre() ?: centre
                innerRadius = { angle -> radiusAlong(conic, conicCentre, angle) }
                lo = 1.02
                // Relative to the last accepted ring, not to the table's
                // neighbour: with a ring missing, the neighbour's window would
                // not reach this one.
                hi = transition.radius / previous.transition.radius * 1.3
            }
            val points = RayTransitions.find(
                classes, centre, innerRadius, transition.inside, transition.outside,
                RAYS, lo, hi, GAP_AT_2000 * f
            )
            val attempt = assess(transition, points, f)
            attempts += attempt
            if (attempt.accepted) {
                lastAccepted = attempt
                centre = attempt.fit!!.conic.centre() ?: centre
            }
        }
        return attempts
    }

    private fun assess(transition: RingTransition, points: List<Vec2>, f: Double): RingAttempt {
        if (points.size < MIN_POINTS) {
            return RingAttempt(transition, points, null, "${points.size} points")
        }
        val fit = RobustConic.fit(points, FLOOR_AT_2000 * f)
            ?: return RingAttempt(transition, points, null, "degenerate fit")
        val maxMedian = MAX_MEDIAN_AT_2000 * f
        val rejection = when {
            fit.inliers.size < MIN_INLIERS -> "${fit.inliers.size} inliers"
            fit.medianDistance > maxMedian ->
                "median distance ${format(fit.medianDistance)} px, limit ${format(maxMedian)}"
            else -> null
        }
        return RingAttempt(transition, points, fit, rejection)
    }

    /**
     * Distance from [centre] along [angle] to [conic], the nearer positive
     * root; register.py's ellipse_radius_at.
     */
    internal fun radiusAlong(conic: Conic, centre: Vec2, angle: Double): Double? {
        val m = conic.normalized().matrix
        val c = Vec3(centre.x, centre.y, 1.0)
        val d = Vec3(cos(angle), sin(angle), 0.0)
        val a = d.dot(m * d)
        val b = 2.0 * c.dot(m * d)
        val c0 = c.dot(m * c)
        val discriminant = b * b - 4.0 * a * c0
        if (discriminant < 0.0 || a == 0.0) return null
        val root = sqrt(discriminant)
        return listOf((-b + root) / (2.0 * a), (-b - root) / (2.0 * a))
            .filter { it > 0.0 }
            .minOrNull()
    }

    private fun format(v: Double) = String.format(Locale.ROOT, "%.2f", v)
}
