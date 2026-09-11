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

import kotlin.math.abs

/** Samples [first] to [last] along a line, of which [darkSamples] lie above the threshold. */
class Run(val first: Int, val last: Int, val darkSamples: Int) {
    val span: Int
        get() = last - first + 1

    val fill: Double
        get() = darkSamples.toDouble() / span
}

object Runs {

    /**
     * radial.py: the samples whose contrast exceeds [threshold], joined into one
     * run while consecutive ones are at most [maxIndexGap] apart.
     */
    fun find(contrast: DoubleArray, threshold: Double, maxIndexGap: Int): List<Run> {
        val runs = ArrayList<Run>()
        var first = -1
        var last = -1
        var dark = 0
        for (i in contrast.indices) {
            if (contrast[i] <= threshold) continue
            if (first >= 0 && i - last <= maxIndexGap) {
                last = i
                dark++
            } else {
                if (first >= 0) runs += Run(first, last, dark)
                first = i
                last = i
                dark = 1
            }
        }
        if (first >= 0) runs += Run(first, last, dark)
        return runs
    }
}

/** radial.py's rules for one run (arrow design, Verfahren step 3). */
object RunRules {
    const val MIN_LENGTH = 0.08
    const val MIN_FILL = 0.6
    const val OFF_FACE = 1.02
    const val NEAR_RING = 0.009
    const val MAX_RING_SHARE = 0.4

    /**
     * Keeps a run at least MIN_LENGTH long and MIN_FILL full, not wholly beyond
     * radius OFF_FACE, and with at most MAX_RING_SHARE of its samples closer than
     * NEAR_RING to a ring line. [radii] holds the distance from the face centre of
     * every sample of the line the run lies on.
     */
    fun keep(run: Run, length: Double, radii: DoubleArray, ringRadii: List<Double>): Boolean {
        if (length < MIN_LENGTH || run.fill < MIN_FILL) return false
        var nearest = Double.MAX_VALUE
        var onRing = 0
        for (i in run.first..run.last) {
            val r = radii[i]
            if (r < nearest) nearest = r
            if (ringRadii.any { abs(r - it) < NEAR_RING }) onRing++
        }
        if (nearest > OFF_FACE) return false
        return onRing.toDouble() / run.span <= MAX_RING_SHARE
    }
}
