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

/**
 * Lloyd's k-means in two dimensions from fixed starting centres, as the colour
 * split of register.py. No random start and no random sample, so a run
 * repeats exactly.
 */
object SmallKMeans {

    /** A centre moves only when more than [minMembers] points chose it. [start] is not modified. */
    fun run(
        xs: DoubleArray,
        ys: DoubleArray,
        start: Array<DoubleArray>,
        iterations: Int = 20,
        minMembers: Int = 50
    ): Array<DoubleArray> {
        require(xs.size == ys.size) { "xs and ys differ in length" }
        val centres = Array(start.size) { start[it].copyOf() }
        repeat(iterations) {
            val sumX = DoubleArray(centres.size)
            val sumY = DoubleArray(centres.size)
            val members = IntArray(centres.size)
            for (i in xs.indices) {
                val k = nearest(xs[i], ys[i], centres)
                sumX[k] += xs[i]
                sumY[k] += ys[i]
                members[k]++
            }
            for (k in centres.indices) {
                if (members[k] > minMembers) {
                    centres[k][0] = sumX[k] / members[k]
                    centres[k][1] = sumY[k] / members[k]
                }
            }
        }
        return centres
    }

    /** Index of the nearest centre; the lower index wins a tie. */
    fun nearest(x: Double, y: Double, centres: Array<DoubleArray>): Int {
        var best = 0
        var bestDistance = Double.MAX_VALUE
        for (k in centres.indices) {
            val dx = x - centres[k][0]
            val dy = y - centres[k][1]
            val d = dx * dx + dy * dy
            if (d < bestDistance) {
                bestDistance = d
                best = k
            }
        }
        return best
    }
}
