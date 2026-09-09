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

package de.dreier.mytargets.detection.metrics

import de.dreier.mytargets.detection.corpus.CorpusEntry
import de.dreier.mytargets.detection.corpus.PrintedScore
import de.dreier.mytargets.detection.corpus.SpotPosition
import de.dreier.mytargets.detection.corpus.TruthShot

/**
 * One arrow a detector claims to have found. It always carries a position — a
 * detector that cannot say where an arrow is has not found it — and whichever
 * of the two score forms it can produce.
 */
data class DetectedShotRecord(
    val scoringRing: Int?,
    val printedScore: PrintedScore?,
    val position: SpotPosition,
    val confidence: Double
)

/** A truth shot and the detection assigned to it. [distance] is null for
 *  ring only truth, where no position is known to compare against. */
data class MatchedPair(
    val truthIndex: Int,
    val detectedIndex: Int,
    val distance: Double?
)

class MatchResult(
    val pairs: List<MatchedPair>,
    val unmatchedTruth: List<Int>,
    val unmatchedDetected: List<Int>
)

/**
 * Assigns detected arrows to true ones, which is what makes "found" mean
 * anything at all.
 *
 * With positions the assignment is greedy over globally nearest pairs: sort
 * every admissible pair by distance and take them while both sides are still
 * free. That is the usual choice in detection benchmarks and it is
 * deterministic, which matters more here than optimality — an end holds a
 * handful of arrows, and a pathological case where greedy loses to an optimal
 * assignment needs two arrows closer to each other than to their own truth,
 * which is already a detection failure.
 *
 * Without positions, matching falls back to the score as a multiset, taking the
 * most confident detection for each true score. Such an entry can support the
 * detection rate, the false positive count and the ring accuracy, but not the
 * position error.
 */
object ShotMatching {

    /**
     * The tolerance used for a truth shot that states none of its own, in spot
     * radii. The corpus records a tolerance per hit, so this only applies to
     * entries that predate that.
     */
    const val DEFAULT_POSITION_TOLERANCE = 0.05

    fun match(
        entry: CorpusEntry,
        detected: List<DetectedShotRecord>,
        defaultPositionTolerance: Double = DEFAULT_POSITION_TOLERANCE
    ): MatchResult = if (entry.hasPositions) {
        matchByPosition(entry, detected, defaultPositionTolerance)
    } else {
        matchByScore(entry, detected)
    }

    private fun matchByPosition(
        entry: CorpusEntry,
        detected: List<DetectedShotRecord>,
        defaultPositionTolerance: Double
    ): MatchResult {
        val candidates = mutableListOf<MatchedPair>()
        entry.shots.forEachIndexed { truthIndex, truth ->
            // hasPositions guarantees this, so the elvis can never fire; kept
            // null safe rather than asserted.
            val truthPosition = truth.position ?: return@forEachIndexed
            val tolerance = truth.positionTolerance ?: defaultPositionTolerance
            detected.forEachIndexed { detectedIndex, record ->
                val distance = truthPosition.distanceTo(record.position)
                if (distance != null && distance <= tolerance) {
                    candidates.add(MatchedPair(truthIndex, detectedIndex, distance))
                }
            }
        }

        // Nearest first, with the indices as a tie break so the result does not
        // depend on the order the candidates happened to be built in.
        candidates.sortWith(
            compareBy({ it.distance }, { it.truthIndex }, { it.detectedIndex })
        )

        return takeGreedily(candidates, entry.shots.size, detected.size)
    }

    private fun matchByScore(
        entry: CorpusEntry,
        detected: List<DetectedShotRecord>
    ): MatchResult {
        val byConfidence = detected.indices.sortedWith(
            compareByDescending<Int> { detected[it].confidence }.thenBy { it }
        )

        val remainingTruth = entry.shots.indices.toMutableList()
        val pairs = mutableListOf<MatchedPair>()

        for (detectedIndex in byConfidence) {
            val record = detected[detectedIndex]
            val hit = remainingTruth.firstOrNull { scoresAgree(entry.shots[it], record) }
            if (hit != null) {
                remainingTruth.remove(hit)
                pairs.add(MatchedPair(hit, detectedIndex, null))
            }
        }

        val matchedDetected = pairs.map { it.detectedIndex }.toSet()
        return MatchResult(
            pairs = pairs.sortedBy { it.truthIndex },
            unmatchedTruth = remainingTruth.sorted(),
            unmatchedDetected = detected.indices.filterNot { it in matchedDetected }
        )
    }

    /**
     * Whether a truth shot and a detection carry the same score. The zone index
     * wins where both sides have one, because it is exact; the printed value is
     * the fallback for the inherited photographs, which have no target model
     * and therefore no index.
     */
    internal fun scoresAgree(truth: TruthShot, detected: DetectedShotRecord): Boolean = when {
        truth.scoringRing != null && detected.scoringRing != null ->
            truth.scoringRing == detected.scoringRing
        truth.printedScore != null && detected.printedScore != null ->
            truth.printedScore == detected.printedScore
        else -> false
    }

    private fun takeGreedily(
        candidates: List<MatchedPair>,
        truthCount: Int,
        detectedCount: Int
    ): MatchResult {
        val usedTruth = mutableSetOf<Int>()
        val usedDetected = mutableSetOf<Int>()
        val pairs = mutableListOf<MatchedPair>()

        for (candidate in candidates) {
            if (candidate.truthIndex in usedTruth) continue
            if (candidate.detectedIndex in usedDetected) continue
            usedTruth.add(candidate.truthIndex)
            usedDetected.add(candidate.detectedIndex)
            pairs.add(candidate)
        }

        return MatchResult(
            pairs = pairs.sortedBy { it.truthIndex },
            unmatchedTruth = (0 until truthCount).filterNot { it in usedTruth },
            unmatchedDetected = (0 until detectedCount).filterNot { it in usedDetected }
        )
    }
}
