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

import de.dreier.mytargets.detection.geometry.Vec2
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * register.py's boundary_points: along each ray from a centre, the transition
 * from one colour class to the next. It lies midway between the last sample
 * of the inner class and the first sample of the outer class after it.
 * Samples are one pixel apart and taken at the nearest pixel -- register.py
 * truncates, which shifts every ring by half a pixel.
 */
object RayTransitions {

    private const val MIN_SAMPLES = 10

    /**
     * @param innerRadius for a ray angle, the radius the window is relative to;
     *        null skips the ray
     * @param lo start of the window, as a multiple of the inner radius
     * @param hi end of the window, exclusive, as a multiple of the inner radius
     * @param maxGapPx a ray with more than this between the two classes counts
     *        as occluded and gives no point
     */
    fun find(
        classes: ClassImage,
        centre: Vec2,
        innerRadius: (angle: Double) -> Double?,
        inside: ColourClass,
        outside: ColourClass,
        rays: Int,
        lo: Double,
        hi: Double,
        maxGapPx: Double
    ): List<Vec2> {
        val points = ArrayList<Vec2>(rays)
        for (k in 0 until rays) {
            val angle = 2.0 * PI * k / rays
            val inner = innerRadius(angle) ?: continue
            val c = cos(angle)
            val s = sin(angle)
            val first = max((inner * lo).toInt(), 1)
            val last = (inner * hi).toInt()

            val sampleRadii = ArrayList<Int>()
            val sampleClasses = ArrayList<ColourClass>()
            for (r in first until last) {
                val x = (centre.x + r * c).roundToInt()
                val y = (centre.y + r * s).roundToInt()
                if (classes.isInside(x, y)) {
                    sampleRadii += r
                    sampleClasses += classes[x, y]
                }
            }
            if (sampleRadii.size < MIN_SAMPLES) continue

            val lastIn = sampleClasses.lastIndexOf(inside)
            if (lastIn < 0) continue
            var firstOut = -1
            for (j in lastIn + 1 until sampleClasses.size) {
                if (sampleClasses[j] == outside) {
                    firstOut = j
                    break
                }
            }
            if (firstOut < 0 || firstOut - lastIn > maxGapPx) continue

            val r = 0.5 * (sampleRadii[lastIn] + sampleRadii[firstOut])
            points += Vec2(centre.x + r * c, centre.y + r * s)
        }
        return points
    }
}
