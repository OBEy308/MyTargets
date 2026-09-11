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

/**
 * The zone radii of WAFull, spot local: X, 10, 9, ... 1. The app will
 * translate them from TargetModelBase; tests and the corpus run use these.
 */
object WaFullZones {
    val RADII = listOf(0.05, 0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9, 1.0)

    /**
     * The zone index of a hit [radius] from the centre, like zone() in tips.py:
     * the first zone reaching beyond it, from the pure radius as the sidecars
     * count. Null beyond the last ring.
     */
    fun zoneOf(radius: Double): Int? = RADII.indexOfFirst { radius < it }.takeIf { it >= 0 }
}
