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
    /**
     * Matched pairs whose truth shot carried no position of its own and were
     * therefore matched by score rather than by position -- see
     * [ShotMatching]. Printed beside [entriesWithPositions] so a corpus
     * drifting towards score-only annotation shows up in the numbers: each
     * deleted position turns a hard position test into a much easier score
     * test, which is the faithful consequence of matching per shot rather
     * than per entry, not a bug -- but it should not go unnoticed.
     */
    val pairsMatchedByScore: Int,
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
     * Entries counted in [forgivenEntries] whose surplus went beyond what
     * [CorpusEntry.unresolvedArrows] could explain -- the partially forgiven
     * ones, where forgiveness applied but did not cover everything.
     */
    val entriesChargedBeyondForgiveness: Int,
    /**
     * Detections charged as false positives on an entry counted in
     * [entriesChargedBeyondForgiveness] -- the surplus left over once that
     * entry's unresolved arrows had forgiven as much as they could. Printed
     * so a partially forgiven entry's charged surplus is visible rather than
     * silently folded into [falsePositives].
     */
    val detectionsChargedDespiteForgiveness: Int,
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
     * [expectedShots] is zero -- which, under the bounded forgiveness rule,
     * happens only when there is no annotated, in-scope entry at all, not
     * merely because every entry happened to be forgiven.
     *
     * Measured against the same [expectedShots] as [detectionRate]: the
     * bounded forgiveness rule (change 3) and out-of-scope exclusion
     * (change 6) both apply to the same set of entries for every hit metric,
     * so there is no longer a separate denominator to keep in step with this
     * one.
     */
    val falsePositiveRate: Double?
        get() = ratio(falsePositives, expectedShots)

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
        get() = Percentiles.of(positionErrors, 0.50)

    /** Null when no entry in the corpus carried positions. */
    val p95PositionError: Double?
        get() = Percentiles.of(positionErrors, 0.95)

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
        get() = Percentiles.of(rejectedDistances, 0.50)

    private fun ratio(count: Int, total: Int): Double? =
        if (total == 0) null else count.toDouble() / total

    companion object {

        fun over(outcomes: List<EntryOutcome>): Metrics {
            var expected = 0
            var matched = 0
            var falsePositives = 0
            var correct = 0
            var comparable = 0
            var withPositions = 0
            var matchedByScore = 0
            var annotated = 0
            var forgiven = 0
            var chargedBeyondForgiveness = 0
            var chargedDespiteForgiveness = 0
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
                if (entry.unresolvedArrows > 0 && surplus > entry.unresolvedArrows) {
                    // Forgiveness applied -- entryForgiven equals
                    // unresolvedArrows here -- but did not cover the whole
                    // surplus, so the rest is charged despite it.
                    chargedBeyondForgiveness++
                    chargedDespiteForgiveness += surplus - entry.unresolvedArrows
                }
                falsePositives += surplus - entryForgiven

                // Per-shot, matching ShotMatching's own decision: an entry
                // counts here as soon as ANY of its shots carries a position,
                // not only when EVERY shot does. The old, all-or-nothing
                // entry-level flag this replaced would silently exclude an
                // entry that still contributes a real, measured position
                // error for its positioned shots.
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
                    } else {
                        // A pair made by score carries no distance -- see
                        // MatchedPair.distance.
                        matchedByScore++
                    }
                }
                rejectedOnDistance.addAll(outcome.match.detectionsRejectedOnDistance)
            }

            return Metrics(
                expectedShots = expected,
                matchedShots = matched,
                falsePositives = falsePositives,
                correctScores = correct,
                scoreComparableShots = comparable,
                positionErrors = errors,
                rejectedDistances = rejectedOnDistance,
                entriesWithPositions = withPositions,
                pairsMatchedByScore = matchedByScore,
                annotatedEntries = annotated,
                forgivenEntries = forgiven,
                entriesChargedBeyondForgiveness = chargedBeyondForgiveness,
                detectionsChargedDespiteForgiveness = chargedDespiteForgiveness,
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
         *
         * Out-of-scope entries are dropped before grouping, not merely zeroed
         * afterwards: [over] already excludes them from every number, but an
         * out-of-scope entry that is the only one under some tag would still
         * leave that tag's row in the map with nothing measured for it.
         * Change 6 says such an entry contributes to no metric -- including
         * the existence of a row it alone would produce.
         */
        fun byTag(outcomes: List<EntryOutcome>): Map<String, Metrics> {
            val inScope = outcomes.filter { it.entry.outOfScope == null }
            val groups = mutableMapOf<String, MutableList<EntryOutcome>>()
            for (outcome in inScope) {
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
