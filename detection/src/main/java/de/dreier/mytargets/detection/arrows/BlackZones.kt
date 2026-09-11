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

import de.dreier.mytargets.detection.registration.ColourClass
import de.dreier.mytargets.detection.registration.RingTransition

/**
 * The rings of a face on which a dark shaft has no contrast (arrow design,
 * Verfahren step 4, Über den schwarzen Ring), as inner..outer radius about the
 * centre of the rectified face, which is the spot's centre on the single-spot
 * faces of v1.
 */
object BlackZones {

    /** From each transition into BLACK, going outwards, to the next transition out of it. */
    fun of(transitions: List<RingTransition>): List<ClosedFloatingPointRange<Double>> {
        val sorted = transitions.sortedBy { it.radius }
        return sorted.withIndex().mapNotNull { (k, into) ->
            if (into.outside != ColourClass.BLACK) return@mapNotNull null
            val out = sorted.drop(k + 1).firstOrNull { it.inside == ColourClass.BLACK } ?: return@mapNotNull null
            into.radius..out.radius
        }
    }
}
