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

import kotlin.math.ceil
import kotlin.math.max

internal object Numbers {

    /**
     * from, from + step, ... below [until], like numpy's arange. The count is
     * rounded against floating noise, so steps(-0.1, 0.035, 0.0005) has 270
     * values as in tips.py.
     */
    fun steps(from: Double, until: Double, step: Double): DoubleArray {
        val n = ceil((until - from) / step - 1e-9).toInt()
        return DoubleArray(max(0, n)) { from + it * step }
    }

    /** The median as numpy computes it: the mean of the two middle values for an even count; NaN for none. */
    fun median(values: DoubleArray): Double {
        if (values.isEmpty()) return Double.NaN
        val sorted = values.sortedArray()
        val n = sorted.size
        return if (n % 2 == 1) sorted[n / 2] else 0.5 * (sorted[n / 2 - 1] + sorted[n / 2])
    }
}
