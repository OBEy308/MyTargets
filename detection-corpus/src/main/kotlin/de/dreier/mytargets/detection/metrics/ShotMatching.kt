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
import de.dreier.mytargets.detection.corpus.Score
import de.dreier.mytargets.detection.corpus.SpotPosition

/**
 * One arrow a detector claims to have found. Unlike a [de.dreier.mytargets
 * .detection.corpus.TruthShot] it always carries a position — a detector that
 * cannot say where an arrow is has not detected it.
 */
data class DetectedShotRecord(
    val score: Score,
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
     * How far a detection may sit from its true position and still count as the
     * same arrow, in spot radii. 0.05 of an 80 cm face is 2 cm, roughly two
     * arrow diameters. This is one of the values the corpus is meant to settle;
     * until it has, it is an informed guess.
     */
    const val DEFAULT_POSITION_GATE = 0.05

    fun match(
        entry: CorpusEntry,
        detected: List<DetectedShotRecord>,
        positionGate: Double = DEFAULT_POSITION_GATE
    ): MatchResult = if (entry.hasPositions) {
        matchByPosition(entry, detected, positionGate)
    } else {
        matchByScore(entry, detected)
    }

    private fun matchByPosition(
        entry: CorpusEntry,
        detected: List<DetectedShotRecord>,
        positionGate: Double
    ): MatchResult {
        val candidates = mutableListOf<MatchedPair>()
        entry.shots.forEachIndexed { truthIndex, truth ->
            val truthPosition = truth.position ?: return@forEachIndexed
            detected.forEachIndexed { detectedIndex, record ->
                val distance = truthPosition.distanceTo(record.position)
                if (distance != null && distance <= positionGate) {
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
            val score = detected[detectedIndex].score
            val hit = remainingTruth.firstOrNull { entry.shots[it].score == score }
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
