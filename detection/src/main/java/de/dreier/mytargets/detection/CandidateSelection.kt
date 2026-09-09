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

package de.dreier.mytargets.detection

import de.dreier.mytargets.detection.geometry.Vec2

/** A possible arrow, already located on a spot. */
data class Candidate(
    val faceIndex: Int,
    val local: Vec2,
    val confidence: Double
)

enum class SelectionReason {
    /** As many arrows as the round expects, all of them unambiguous. */
    COMPLETE,

    /** Fewer arrows found than expected; the remaining places stay open. */
    FEWER_THAN_EXPECTED,

    /** More candidates than places, and the surplus is too close to call. */
    AMBIGUOUS_SURPLUS,

    /**
     * A spot had more candidates than the end can store on it. The data model
     * derives the spot from the shot index, so a spot holds at most
     * ceil(shotsPerEnd / faceCount) arrows.
     */
    SPOT_OVERFLOW
}

class SelectionOutcome(
    val accepted: List<Candidate>,
    val reason: SelectionReason
)

/**
 * Decides which candidates actually become shots.
 *
 * The guiding rule from the spec: when in doubt write nothing rather than
 * something wrong. A missed arrow costs the archer two seconds; an invented one
 * corrupts the statistics for good.
 */
object CandidateSelection {

    /**
     * A candidate is only accepted over the next one down when it is clearly
     * more confident. "Clearly" is this much of the confidence scale.
     */
    private const val REQUIRED_CONFIDENCE_GAP = 0.15

    /**
     * @param maxPerSpot how many arrows one spot can hold in this end,
     *        ceil(shotsPerEnd / faceCount); equal to [expectedShots] for a
     *        single spot face, where it has no effect
     */
    fun select(
        candidates: List<Candidate>,
        expectedShots: Int,
        maxPerSpot: Int
    ): SelectionOutcome {
        require(maxPerSpot > 0) { "a spot holds at least one arrow" }
        val ranked = candidates.sortedByDescending { it.confidence }

        // First, select by confidence to detect ambiguity on the full set.
        val confidenceOutcome = selectByConfidence(ranked, expectedShots)

        // Then apply per spot cap to the accepted candidates.
        val perSpotCount = mutableMapOf<Int, Int>()
        val cappedAccepted = mutableListOf<Candidate>()
        var overflow = false
        for (c in confidenceOutcome.accepted) {
            val n = perSpotCount.getOrDefault(c.faceIndex, 0)
            if (n >= maxPerSpot) {
                overflow = true
            } else {
                perSpotCount[c.faceIndex] = n + 1
                cappedAccepted += c
            }
        }

        return if (overflow) {
            // The overflow is what the user needs to hear about; it explains
            // why we couldn't accept all the candidates that confidence alone
            // would have selected.
            SelectionOutcome(cappedAccepted, SelectionReason.SPOT_OVERFLOW)
        } else {
            SelectionOutcome(cappedAccepted, confidenceOutcome.reason)
        }
    }

    private fun selectByConfidence(
        ranked: List<Candidate>,
        expectedShots: Int
    ): SelectionOutcome {
        if (ranked.size < expectedShots) {
            return SelectionOutcome(ranked, SelectionReason.FEWER_THAN_EXPECTED)
        }
        if (ranked.size == expectedShots) {
            return SelectionOutcome(ranked, SelectionReason.COMPLETE)
        }

        // Walk down from the last place: accept the largest k for which the
        // k-th candidate is clearly above the (k+1)-th. Every place below a
        // contested one is contested as well, so nothing above k is safe
        // either until a clear gap appears.
        var k = expectedShots
        while (k > 0 && ranked[k - 1].confidence - ranked[k].confidence < REQUIRED_CONFIDENCE_GAP) {
            k--
        }

        return if (k == expectedShots) {
            SelectionOutcome(ranked.take(k), SelectionReason.COMPLETE)
        } else {
            // Contested places stay open rather than being filled by a coin toss.
            SelectionOutcome(ranked.take(k), SelectionReason.AMBIGUOUS_SURPLUS)
        }
    }
}
