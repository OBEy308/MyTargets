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
     * The expected shots of the annotated, in-scope entries that were
     * actually judged for a false positive -- which, since the bounded
     * forgiveness rule lets every such entry contribute one (see
     * [falsePositives]), is every annotated entry that Change 6 does not
     * pull out of scope entirely. An out-of-scope entry's listed hits are
     * excluded here exactly as they are from [expectedShots], because
     * [CorpusEntry.outOfScope] takes it out of every hit metric, this rate
     * included.
     *
     * This used to also exclude wholly-forgiven entries, back when
     * `unresolvedArrows > 0` forgave a surplus without bound and such an
     * entry's numerator could never move. Under the bounded rule a forgiven
     * entry can still be charged for the surplus beyond what its unresolved
     * arrows explain, so it is judged like any other and belongs in this
     * denominator too.
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
    /**
     * Entries where [CorpusEntry.unresolvedArrows] forgave at least one
     * surplus detection -- full or partial. An entry whose surplus never
     * exceeded its unresolved arrows is forgiven completely; one whose
     * surplus went beyond that is forgiven only partially and still counts
     * here, because forgiveness applied to it too, just not to all of it.
     */
    val forgivenEntries: Int,
    /**
     * Matched pairs whose truth shot is [TruthShot.nearRingBoundary]: the
     * ring value there depends on the arrow's diameter, which is not
     * something a detector can be faulted for disagreeing with. Such a pair
     * leaves ring accuracy entirely -- out of [scoreComparableShots] and out
     * of [correctScores] -- and is counted only here, so a corpus where many
     * hits sit on a ring boundary is visible rather than silently folded into
     * a rate it cannot fairly move.
     */
    val boundaryShots: Int,
    /**
     * Matched, positioned pairs whose truth shot is [TruthShot.uncertain]:
     * the annotator estimated the position because the shaft was occluded at
     * the entry point, so any error there is the annotation's, not the
     * detector's. Such a pair still counts as matched and still counts for
     * [detectionRate]; only its distance is left out of [positionErrors],
     * and therefore out of [medianPositionError] and [p95PositionError].
     */
    val uncertainPositionsExcluded: Int
) {

    /** Null when nothing was measured, never zero -- a zero reads as a result. */
    val detectionRate: Double?
        get() = ratio(matchedShots, expectedShots)

    /**
     * Invented arrows per expected arrow. Can exceed one. Null when
     * [falsePositiveDenominator] is zero -- which, under the bounded
     * forgiveness rule, happens only when there is no annotated, in-scope
     * entry at all, not merely because every entry happened to be forgiven.
     *
     * Measured against [falsePositiveDenominator] rather than [expectedShots]
     * so this rate and [falsePositiveDenominator] always describe the same
     * entries; see [falsePositiveDenominator] for which those are.
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
            var boundaryShots = 0
            var uncertainExcluded = 0
            val errors = mutableListOf<Double>()
            val rejectedOnDistance = mutableListOf<Double>()

            for (outcome in outcomes) {
                val entry = outcome.entry
                if (!entry.isAnnotated || entry.outOfScope != null) {
                    // A registration only entry has no hits to be right or
                    // wrong about, and an out-of-scope entry belongs in the
                    // corpus but not in the metrics (Change 6). Counting
                    // either would dilute every rate.
                    continue
                }
                annotated++
                expected += entry.expectedShots
                matched += outcome.match.pairs.size

                // Bounded forgiveness: unresolvedArrows explains that many
                // arrows are genuinely in the photograph with an
                // indeterminate entry point, so that many surplus detections
                // are not inventions. On
                // 2026-08-15_bedeckt_frontal_02.jpg six arrows are in the
                // face and the sixth shaft disappears behind another -- one
                // hidden arrow, which justifies one unexplained detection,
                // not fifty. Any surplus beyond unresolvedArrows is still
                // charged as a false positive.
                //
                // The literal, unbounded alternative -- treat
                // `unresolvedArrows > 0` as blanket amnesty for every
                // surplus detection on the entry, however many -- was
                // considered and rejected here. It reads the README's wording
                // literally, but it lets one hidden arrow excuse an
                // unlimited number of fabricated detections, which is not
                // what the field means in practice.
                val surplus = outcome.match.unmatchedDetected.size
                val entryForgiven = minOf(surplus, entry.unresolvedArrows)
                if (entryForgiven > 0) {
                    forgiven++
                }
                falsePositives += surplus - entryForgiven
                falsePositiveDenominator += entry.expectedShots

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
                    if (truthShot.nearRingBoundary) {
                        // The ring value itself depends on the arrow's
                        // diameter here; whether the detector agrees says
                        // nothing about it, so this pair leaves ring
                        // accuracy's numerator AND denominator entirely.
                        boundaryShots++
                    } else if (isScoreComparable(truthShot, detectedShot)) {
                        comparable++
                        if (ShotMatching.scoresAgree(truthShot, detectedShot)) {
                            correct++
                        }
                    }
                    val distance = pair.distance
                    if (distance != null) {
                        if (truthShot.uncertain) {
                            // The annotator estimated this position because
                            // the shaft was occluded at the entry point, so
                            // any error here is the annotation's, not the
                            // detector's. The pair still counts as matched
                            // above; only its distance stays out of the
                            // position error.
                            uncertainExcluded++
                        } else {
                            errors.add(distance)
                        }
                    }
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
                forgivenEntries = forgiven,
                boundaryShots = boundaryShots,
                uncertainPositionsExcluded = uncertainExcluded
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
