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
import de.dreier.mytargets.detection.corpus.TruthShot

/** What a detector produced for one photograph, and how it lined up. */
class EntryOutcome(
    val entry: CorpusEntry,
    val match: MatchResult,
    val detected: List<DetectedShotRecord>
)

/**
 * The numbers the design spec measures the pipeline by.
 *
 * The corpus is only partly annotated: a registration only entry has no
 * hits at all, some entries carry hits with no position, and ring accuracy
 * can only be judged where the truth and the detection carry a comparable
 * kind of score. Mixing all of that into one shared denominator would hide
 * exactly what was measured, so each rate here has its own denominator, and
 * the two are not the same shot count.
 *
 * [detectionRate] is against every listed truth shot: a missed arrow always
 * lowers it. [scoreAccuracy] is against the shots that were actually MATCHED
 * and whose truth is comparable with what the detector reported; a missed
 * arrow has nothing to compare, so it lowers [detectionRate] and leaves
 * [scoreAccuracy] untouched. A detector that misses five of six arrows and
 * scores the sixth correctly is not perfectly accurate on the whole end --
 * [detectionRate] says so -- but it did get the one comparison it could make
 * right, and [scoreAccuracy] says that too.
 *
 * A rate is meaningless without knowing what it was measured against, which
 * is why every rate here is nullable rather than reporting a zero for a
 * denominator of zero, and why the report prints the denominator alongside
 * the rate.
 */
class Metrics(
    val expectedShots: Int,
    val matchedShots: Int,
    val falsePositives: Int,
    /**
     * The expected shots of the entries that were NOT forgiven -- the only
     * ones whose surplus detections could ever become a false positive. A
     * forgiven entry's listed hits are excluded here even though they are
     * counted in [expectedShots], because they can never move
     * [falsePositives]; leaving them in would dilute [falsePositiveRate]
     * with a denominator that the numerator can never grow into.
     */
    val falsePositiveDenominator: Int,
    val correctScores: Int,
    val scoreComparableShots: Int,
    val positionErrors: List<Double>,
    /**
     * For every detection that ended up unmatched, its distance to the
     * nearest unmatched, positioned truth shot on the same face -- where one
     * existed. Kept so [detectionsRejectedOnDistance] and
     * [medianRejectedDistance] can be derived from the same numbers rather
     * than recomputed.
     */
    val rejectedDistances: List<Double>,
    val entriesWithPositions: Int,
    val annotatedEntries: Int,
    val forgivenEntries: Int
) {

    /** Null when nothing was measured, never zero -- a zero reads as a result. */
    val detectionRate: Double?
        get() = ratio(matchedShots, expectedShots)

    /**
     * Invented arrows per expected arrow. Can exceed one. Null when
     * [falsePositiveDenominator] is zero.
     *
     * Measured against [falsePositiveDenominator], not [expectedShots]: a
     * forgiven entry's surplus detections can never become a false positive,
     * so its listed hits must not sit in this rate's denominator either --
     * doing so would only dilute it with shots that can never move the
     * numerator.
     */
    val falsePositiveRate: Double?
        get() = ratio(falsePositives, falsePositiveDenominator)

    /**
     * Measured against the shots whose truth is comparable with what the
     * detector reports, not against every expected shot. An inherited entry
     * carries printed values and no target model; scoring a zone index
     * against it is not possible, and counting it as wrong would be a lie.
     */
    val scoreAccuracy: Double?
        get() = ratio(correctScores, scoreComparableShots)

    /** Null when no entry in the corpus carried positions. */
    val medianPositionError: Double?
        get() = percentile(positionErrors, 0.50)

    /** Null when no entry in the corpus carried positions. */
    val p95PositionError: Double?
        get() = percentile(positionErrors, 0.95)

    /**
     * How many detections missed ONLY on distance: close enough to a truth
     * shot's score, perhaps, or with no comparable truth at all, but ruled
     * out purely because nothing unmatched and positioned was near enough.
     * Without this, [medianPositionError] and [p95PositionError] are bounded
     * by the matching gate no matter how inaccurate the detector actually
     * is -- a detector that never gets within the gate reports a flattering,
     * empty [positionErrors] rather than a visibly bad one.
     */
    val detectionsRejectedOnDistance: Int
        get() = rejectedDistances.size

    /** Null when nothing was ever rejected on distance alone. */
    val medianRejectedDistance: Double?
        get() = percentile(rejectedDistances, 0.50)

    private fun ratio(count: Int, total: Int): Double? =
        if (total == 0) null else count.toDouble() / total

    /** Linear interpolation between order statistics, the common definition. */
    private fun percentile(values: List<Double>, fraction: Double): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
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
            var falsePositiveDenominator = 0
            var correct = 0
            var comparable = 0
            var withPositions = 0
            var annotated = 0
            var forgiven = 0
            val errors = mutableListOf<Double>()
            val rejectedOnDistance = mutableListOf<Double>()

            for (outcome in outcomes) {
                val entry = outcome.entry
                if (!entry.isAnnotated) {
                    // A registration only entry has no hits to be right or
                    // wrong about. Counting it would dilute every rate.
                    continue
                }
                annotated++
                expected += entry.expectedShots
                matched += outcome.match.pairs.size

                if (entry.unresolvedArrows > 0) {
                    // The corpus README, verbatim: a detection matching no
                    // listed hit counts as a false positive only when
                    // unresolvedArrows is zero. The arrows are actually in
                    // the photograph; finding them is not an invention. This
                    // is the literal, generous reading -- a single
                    // unresolved arrow forgives any number of surplus
                    // detections for that entry, not just as many as are
                    // unresolved.
                    //
                    // The tighter alternative -- forgive only min(surplus,
                    // unresolvedArrows) detections and charge the rest as
                    // false positives -- was considered and rejected here.
                    // It is not what the README states, and the README is
                    // the authority for its own corpus format; inventing a
                    // stricter rule than the format documents would make
                    // this code second-guess the corpus rather than measure
                    // against it.
                    forgiven++
                } else {
                    falsePositives += outcome.match.unmatchedDetected.size
                    falsePositiveDenominator += entry.expectedShots
                }

                // Per-shot, matching ShotMatching's own decision: an entry
                // counts here as soon as ANY of its shots carries a position,
                // not only when EVERY shot does. The old all-or-nothing
                // `entry.hasPositions` flag would silently exclude an entry
                // that still contributes a real, measured position error for
                // its positioned shots.
                if (entry.shots.any { it.position != null }) {
                    withPositions++
                }

                for (pair in outcome.match.pairs) {
                    val truthShot = entry.shots[pair.truthIndex]
                    val detectedShot = outcome.detected[pair.detectedIndex]
                    if (isScoreComparable(truthShot, detectedShot)) {
                        comparable++
                        if (ShotMatching.scoresAgree(truthShot, detectedShot)) {
                            correct++
                        }
                    }
                    pair.distance?.let { errors.add(it) }
                }
                rejectedOnDistance.addAll(outcome.match.detectionsRejectedOnDistance)
            }

            return Metrics(
                expectedShots = expected,
                matchedShots = matched,
                falsePositives = falsePositives,
                falsePositiveDenominator = falsePositiveDenominator,
                correctScores = correct,
                scoreComparableShots = comparable,
                positionErrors = errors,
                rejectedDistances = rejectedOnDistance,
                entriesWithPositions = withPositions,
                annotatedEntries = annotated,
                forgivenEntries = forgiven
            )
        }

        /** Whether the two carry the same KIND of score and can be compared. */
        private fun isScoreComparable(
            truth: TruthShot,
            detected: DetectedShotRecord
        ): Boolean =
            (truth.scoringRing != null && detected.scoringRing != null) ||
                (truth.printedScore != null && detected.printedScore != null)

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
