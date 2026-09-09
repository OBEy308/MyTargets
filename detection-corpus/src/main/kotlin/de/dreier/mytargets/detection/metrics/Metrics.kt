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

/** What a detector produced for one photograph, and how it lined up. */
class EntryOutcome(
    val entry: CorpusEntry,
    val match: MatchResult,
    val detected: List<DetectedShotRecord>
)

/**
 * The four numbers the design spec measures the pipeline by.
 *
 * All rates are relative to the EXPECTED shots, never to the matched ones. A
 * detector that misses five of six arrows and scores the sixth correctly must
 * not come out at a hundred percent ring accuracy.
 */
class Metrics(
    val expectedShots: Int,
    val matchedShots: Int,
    val falsePositives: Int,
    val correctScores: Int,
    val positionErrors: List<Double>,
    val entriesWithPositions: Int
) {

    /** Share of true arrows that were found at all. Null when the corpus has
     *  no expected arrows to measure against -- an empty corpus must not read
     *  as a perfect run. */
    val detectionRate: Double?
        get() = ratio(matchedShots)

    /** Invented arrows per expected arrow. Can exceed one. Null when the
     *  corpus has no expected arrows to measure against. */
    val falsePositiveRate: Double?
        get() = ratio(falsePositives)

    /** Share of true arrows found AND given the right score. The number that
     *  matters to the archer. Null when the corpus has no expected arrows to
     *  measure against. */
    val scoreAccuracy: Double?
        get() = ratio(correctScores)

    /** Null when no entry in the corpus carried positions. */
    val medianPositionError: Double?
        get() = percentile(0.50)

    /** Null when no entry in the corpus carried positions. */
    val p95PositionError: Double?
        get() = percentile(0.95)

    private fun ratio(count: Int): Double? =
        if (expectedShots == 0) null else count.toDouble() / expectedShots

    /** Linear interpolation between order statistics, the common definition. */
    private fun percentile(fraction: Double): Double? {
        if (positionErrors.isEmpty()) return null
        val sorted = positionErrors.sorted()
        val rank = fraction * (sorted.size - 1)
        val lower = rank.toInt()
        val upper = minOf(lower + 1, sorted.size - 1)
        val weight = rank - lower
        return sorted[lower] * (1.0 - weight) + sorted[upper] * weight
    }

    companion object {

        fun over(outcomes: List<EntryOutcome>): Metrics {
            var expected = 0
            var matched = 0
            var falsePositives = 0
            var correct = 0
            var withPositions = 0
            val errors = mutableListOf<Double>()

            for (outcome in outcomes) {
                expected += outcome.entry.expectedShots
                matched += outcome.match.pairs.size
                falsePositives += outcome.match.unmatchedDetected.size
                if (outcome.entry.hasPositions) {
                    withPositions++
                }
                for (pair in outcome.match.pairs) {
                    val truthShot = outcome.entry.shots[pair.truthIndex]
                    val detectedShot = outcome.detected[pair.detectedIndex]
                    if (truthShot.score == detectedShot.score) {
                        correct++
                    }
                    pair.distance?.let { errors.add(it) }
                }
            }

            return Metrics(expected, matched, falsePositives, correct, errors, withPositions)
        }

        /**
         * The same numbers per tag, so "dark" and "overlap" can be compared
         * against the rest. An entry with several tags counts in each of them;
         * an entry with none lands under [UNTAGGED].
         */
        fun byTag(outcomes: List<EntryOutcome>): Map<String, Metrics> {
            val groups = mutableMapOf<String, MutableList<EntryOutcome>>()
            for (outcome in outcomes) {
                val keys = outcome.entry.tags.ifEmpty { setOf(UNTAGGED) }
                for (key in keys) {
                    groups.getOrPut(key) { mutableListOf() }.add(outcome)
                }
            }
            return groups.toSortedMap().mapValues { (_, group) -> over(group) }
        }

        const val UNTAGGED = "untagged"
    }
}
