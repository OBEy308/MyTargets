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

import de.dreier.mytargets.detection.Candidate
import de.dreier.mytargets.detection.FaceLayout
import de.dreier.mytargets.detection.SelectionReason
import de.dreier.mytargets.detection.SpotMapping
import de.dreier.mytargets.detection.geometry.Vec2
import de.dreier.mytargets.detection.registration.FaceWarp

/** A peak the detector placed on a spot: the candidate carries the spot-local position and the heatmap value. */
class LearnedFind(val peak: Peak, val candidate: Candidate)

/**
 * Step 6 of design 3d. The peaks arrive cut to expectedShots by value (decision
 * 4: no gap rule, so AMBIGUOUS_SURPLUS never occurs). A peak outside every spot
 * took a place and counts for nothing; a spot over its cap drops its lowest
 * peaks and the reason says so.
 */
object LearnedSelection {

    class Placement(
        /** Of the kept peaks, those on a spot, in the order they came. */
        val onFace: List<LearnedFind>,
        /** Of the kept peaks, those in the band beyond radius one or beside the face. */
        val outsideFace: List<Peak>,
        /** [onFace] after the per-spot cap: the shots; the same instances. */
        val accepted: List<LearnedFind>,
        val reason: SelectionReason
    )

    fun place(kept: List<Peak>, inputSize: Int, layout: FaceLayout, expectedShots: Int, maxPerSpot: Int): Placement {
        require(maxPerSpot > 0) { "a spot holds at least one arrow" }
        val onFace = ArrayList<LearnedFind>()
        val outside = ArrayList<Peak>()
        for (peak in kept) {
            val target = FaceWarp.targetOf(Vec2(peak.u, peak.v), inputSize)
            val located = SpotMapping.locate(target, layout)
            if (located == null) {
                outside += peak
            } else {
                onFace += LearnedFind(peak, Candidate(located.faceIndex, located.local, peak.value))
            }
        }
        val perSpot = HashMap<Int, Int>()
        val accepted = ArrayList<LearnedFind>()
        var overflow = false
        for (find in onFace) {
            val n = perSpot.getOrDefault(find.candidate.faceIndex, 0)
            if (n >= maxPerSpot) {
                overflow = true
            } else {
                perSpot[find.candidate.faceIndex] = n + 1
                accepted += find
            }
        }
        val reason = when {
            overflow -> SelectionReason.SPOT_OVERFLOW
            accepted.size == expectedShots -> SelectionReason.COMPLETE
            else -> SelectionReason.FEWER_THAN_EXPECTED
        }
        return Placement(onFace, outside, accepted, reason)
    }
}
