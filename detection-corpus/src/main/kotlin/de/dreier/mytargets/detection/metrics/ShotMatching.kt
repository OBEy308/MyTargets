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

/** A truth shot and the detection assigned to it. [distance] stays null for a
 *  pair made by score match, where no position comparison applies. */
data class MatchedPair(
    val truthIndex: Int,
    val detectedIndex: Int,
    val distance: Double?
)

/**
 * @param detectionsRejectedOnDistance The distance from each detection left
 *        unmatched to the nearest truth shot that also went unmatched, has a
 *        position, and sits on the same face -- where such a truth shot
 *        exists. This is not part of matching; it exists so the position
 *        error is not silently bounded by the gate that produced [pairs]. In
 *        detection order.
 */
class MatchResult(
    val pairs: List<MatchedPair>,
    val unmatchedTruth: List<Int>,
    val unmatchedDetected: List<Int>,
    val detectionsRejectedOnDistance: List<Double> = emptyList()
)

/**
 * Assigns detected arrows to true ones, which is what makes "found" mean
 * anything at all.
 *
 * The decision between the two strategies below is made per SHOT, not per
 * entry: a truth shot that carries a position is always matched by position,
 * and a truth shot with no position is matched by score against whatever
 * detections position matching left unclaimed. Position matching runs first,
 * globally nearest pair first, so the tightest pairs claim their partners
 * before score matching sees the remainder. A positioned shot that fails its
 * own gate stays unmatched; it is never retried against score, because a
 * shot the corpus placed in space is not the same claim as one it did not,
 * and rescuing it by score would let a bare ring number stand in for a
 * position the annotator could have recorded but did not.
 *
 * With positions the assignment is greedy over globally nearest pairs: sort
 * every admissible pair by distance and take them while both sides are still
 * free. That is the usual choice in detection benchmarks and it is
 * deterministic, which matters more here than optimality — an end holds a
 * handful of arrows, and a pathological case where greedy loses to an optimal
 * assignment needs two arrows closer to each other than to their own truth,
 * which is already a detection failure.
 *
 * Without a position, matching falls back to the score as a multiset, taking
 * the most confident detection for each true score. Such a shot can support
 * the detection rate, the false positive count and the ring accuracy, but not
 * the position error.
 */
object ShotMatching {

    /**
     * The detector's own position budget, in spot radii: how far a detection
     * may sit from a hit's annotated position and still count as the same
     * arrow, before accounting for how precisely the annotator could place
     * that hit. The gate actually used adds [TruthShot.positionTolerance] to
     * this (zero when the shot carries none) — the detector's budget plus
     * whatever slack the annotation itself carries.
     */
    const val DEFAULT_POSITION_TOLERANCE = 0.05

    fun match(
        entry: CorpusEntry,
        detected: List<DetectedShotRecord>,
        defaultPositionTolerance: Double = DEFAULT_POSITION_TOLERANCE
    ): MatchResult {
        val positioned = matchByPosition(entry, detected, defaultPositionTolerance)

        // Only a truth shot with NO position of its own falls back to score;
        // a positioned shot that failed its gate stays unmatched rather than
        // being retried here.
        val scoreTruth = positioned.unmatchedTruth.filter { entry.shots[it].position == null }
        val scored = matchByScore(entry, scoreTruth, positioned.unmatchedDetected, detected)

        val pairs = (positioned.pairs + scored.pairs).sortedBy { it.truthIndex }
        val unmatchedTruth = (positioned.unmatchedTruth - scoreTruth.toSet() + scored.unmatchedTruth)
            .sorted()
        val unmatchedDetected = scored.unmatchedDetected.sorted()

        return MatchResult(
            pairs = pairs,
            unmatchedTruth = unmatchedTruth,
            unmatchedDetected = unmatchedDetected,
            detectionsRejectedOnDistance =
                rejectedOnDistance(entry, detected, unmatchedTruth, unmatchedDetected)
        )
    }

    /**
     * For every detection matching found no home for, the distance to the
     * nearest truth shot that also found no home, carries a position, and
     * sits on the same face — where one exists. Nothing here influences
     * [pairs][MatchResult.pairs]; it exists purely so a position error metric
     * built on matched pairs is not silently bounded by the matching gate no
     * matter how inaccurate the detector actually is.
     */
    private fun rejectedOnDistance(
        entry: CorpusEntry,
        detected: List<DetectedShotRecord>,
        unmatchedTruth: List<Int>,
        unmatchedDetected: List<Int>
    ): List<Double> {
        val candidatePositions = unmatchedTruth.mapNotNull { entry.shots[it].position }
        return unmatchedDetected.mapNotNull { detectedIndex ->
            val position = detected[detectedIndex].position
            candidatePositions.mapNotNull { position.distanceTo(it) }.minOrNull()
        }
    }

    private fun matchByPosition(
        entry: CorpusEntry,
        detected: List<DetectedShotRecord>,
        defaultPositionTolerance: Double
    ): MatchResult {
        val candidates = mutableListOf<MatchedPair>()
        entry.shots.forEachIndexed { truthIndex, truth ->
            // A shot with no position of its own contributes no candidate
            // here at all; it is matched by score instead, in match() above.
            val truthPosition = truth.position ?: return@forEachIndexed
            val tolerance = defaultPositionTolerance + (truth.positionTolerance ?: 0.0)
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

    /**
     * Matches by score as a multiset, over only [truthIndices] and
     * [detectedIndices] — the truth shots with no position, and the
     * detections position matching left unclaimed.
     */
    private fun matchByScore(
        entry: CorpusEntry,
        truthIndices: List<Int>,
        detectedIndices: List<Int>,
        detected: List<DetectedShotRecord>
    ): MatchResult {
        val byConfidence = detectedIndices.sortedWith(
            compareByDescending<Int> { detected[it].confidence }.thenBy { it }
        )

        val remainingTruth = truthIndices.toMutableList()
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
            unmatchedDetected = detectedIndices.filterNot { it in matchedDetected }
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
