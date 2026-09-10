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

import com.google.common.truth.Truth.assertThat
import de.dreier.mytargets.detection.corpus.CaptureInfo
import de.dreier.mytargets.detection.corpus.CorpusEntry
import de.dreier.mytargets.detection.corpus.PrintedScore
import de.dreier.mytargets.detection.corpus.SpotPosition
import de.dreier.mytargets.detection.corpus.TruthShot
import org.junit.Test

class MetricsTest {

    // Only ever exercised with a single-element tag set, so taking the first
    // element as the entry's lighting condition is enough to reproduce a tag.
    private fun entry(
        name: String,
        tags: Set<String> = emptySet(),
        vararg shots: TruthShot
    ) = CorpusEntry(
        imageName = name,
        image = null,
        camera = null,
        capture = tags.firstOrNull()?.let { CaptureInfo(lighting = it, angle = null, angleDegrees = null) },
        target = null,
        shotsPerEnd = shots.size,
        shots = shots.toList(),
        unresolvedArrows = 0,
        registration = null
    )

    private fun truth(ring: Int, x: Double, y: Double) =
        TruthShot(scoringRing = ring, position = SpotPosition(0, x, y))

    private fun found(ring: Int, x: Double, y: Double) =
        DetectedShotRecord(ring, null, SpotPosition(0, x, y), 0.9)

    private fun outcome(e: CorpusEntry, detected: List<DetectedShotRecord>) =
        EntryOutcome(e, ShotMatching.match(e, detected), detected)

    private fun entryWithUnresolved(unresolved: Int) = CorpusEntry(
        imageName = "u.jpg", image = null, camera = null, capture = null,
        target = null, shotsPerEnd = 6,
        shots = listOf(
            TruthShot(scoringRing = 0, position = SpotPosition(0, 0.0, 0.0)),
            TruthShot(scoringRing = 2, position = SpotPosition(0, 0.3, 0.0)),
            TruthShot(scoringRing = 3, position = SpotPosition(0, 0.6, 0.0)),
            TruthShot(scoringRing = 3, position = SpotPosition(0, 0.9, 0.0))
        ),
        unresolvedArrows = unresolved, registration = null
    )

    @Test
    fun aPerfectRunScoresOneEverywhere() {
        val e = entry("p.jpg", shots = arrayOf(truth(0, 0.0, 0.0), truth(2, 0.3, 0.0)))
        val m = Metrics.over(listOf(outcome(e, listOf(found(0, 0.0, 0.0), found(2, 0.3, 0.0)))))

        assertThat(m.detectionRate!!).isWithin(1e-9).of(1.0)
        assertThat(m.falsePositiveRate!!).isWithin(1e-9).of(0.0)
        assertThat(m.scoreAccuracy!!).isWithin(1e-9).of(1.0)
        assertThat(m.medianPositionError!!).isWithin(1e-9).of(0.0)
    }

    @Test
    fun aMissedArrowLowersDetectionRateButNotScoreAccuracy() {
        // Ring accuracy has its own denominator: the shots the detector never
        // found are not "wrong", they are simply absent from what can be
        // judged. The missed arrow drags detectionRate down; scoreAccuracy is
        // only ever computed over the shots that DID get matched, so it stays
        // perfect here -- that separation is the whole point of task 6.
        val e = entry("m.jpg", shots = arrayOf(truth(0, 0.0, 0.0), truth(2, 0.3, 0.0)))
        val m = Metrics.over(listOf(outcome(e, listOf(found(0, 0.0, 0.0)))))

        assertThat(m.detectionRate!!).isWithin(1e-9).of(0.5)
        assertThat(m.scoreComparableShots).isEqualTo(1)
        assertThat(m.scoreAccuracy!!).isWithin(1e-9).of(1.0)
        assertThat(m.falsePositiveRate!!).isWithin(1e-9).of(0.0)
    }

    @Test
    fun anInventedArrowRaisesTheFalsePositiveRate() {
        val e = entry("f.jpg", shots = arrayOf(truth(0, 0.0, 0.0)))
        val m = Metrics.over(listOf(outcome(e, listOf(found(0, 0.0, 0.0), found(2, 0.8, 0.0)))))

        assertThat(m.detectionRate!!).isWithin(1e-9).of(1.0)
        assertThat(m.falsePositiveRate!!).isWithin(1e-9).of(1.0)
    }

    @Test
    fun theFalsePositiveRateIsMeasuredAgainstExpectedNotAgainstFound() {
        // Two expected arrows, one found, two invented. Against expected that
        // is 2/2 = 1.0; against matched it would be 2/1 = 2.0.
        val e = entry("fp.jpg", shots = arrayOf(truth(2, 0.0, 0.0), truth(3, 0.5, 0.0)))
        val detected = listOf(
            found(2, 0.0, 0.0),
            found(5, 0.2, 0.0),
            found(5, 0.3, 0.0)
        )
        val m = Metrics.over(listOf(outcome(e, detected)))

        assertThat(m.matchedShots).isEqualTo(1)
        assertThat(m.falsePositives).isEqualTo(2)
        assertThat(m.falsePositiveRate!!).isWithin(1e-9).of(1.0)
    }

    @Test
    fun aMatchedArrowWithTheWrongScoreCountsAsFoundButNotAsCorrect() {
        val e = entry("w.jpg", shots = arrayOf(truth(0, 0.0, 0.0)))
        val m = Metrics.over(listOf(outcome(e, listOf(found(2, 0.01, 0.0)))))

        assertThat(m.detectionRate!!).isWithin(1e-9).of(1.0)
        assertThat(m.scoreAccuracy!!).isWithin(1e-9).of(0.0)
    }

    @Test
    fun ringAccuracyComparesEachTruthWithItsOwnDetection() {
        // Every other test in this file happens to pair index i with index i,
        // which cannot tell a correct comparison from one that swaps the two
        // indices. These pairings form a three-cycle, which can: the right
        // answer is 2/3 and the swapped one is 0.
        val shots = arrayOf(
            truth(2, 0.0, 0.0),
            truth(3, 0.3, 0.0),
            truth(4, 0.6, 0.0)
        )
        val e = entry("cycle.jpg", shots = shots)
        val detected = listOf(
            found(5, 0.6, 0.0),   // pairs with truth 2, and is wrong
            found(2, 0.0, 0.0),   // pairs with truth 0, correct
            found(3, 0.3, 0.0)    // pairs with truth 1, correct
        )
        val m = Metrics.over(listOf(outcome(e, detected)))

        assertThat(m.detectionRate!!).isWithin(1e-9).of(1.0)
        assertThat(m.scoreAccuracy!!).isWithin(1e-9).of(2.0 / 3.0)
    }

    @Test
    fun scoreAccuracyIsMeasuredAgainstComparableMatchesNotAgainstExpected() {
        // Five of six arrows missed; the one found is scored correctly. Ring
        // accuracy has its own denominator -- the scoreComparableShots that
        // were actually matched and could be judged -- so the five misses
        // that detectionRate already penalises do not also drag this number
        // down. Rewarding the detector with a full 1.0 here is not a lie: it
        // genuinely got the one comparison it could make right.
        val shots = Array(6) { truth(2, 0.1 * it, 0.0) }
        val e = entry("s.jpg", shots = shots)
        val m = Metrics.over(listOf(outcome(e, listOf(found(2, 0.0, 0.0)))))

        assertThat(m.detectionRate!!).isWithin(1e-9).of(1.0 / 6.0)
        assertThat(m.scoreComparableShots).isEqualTo(1)
        assertThat(m.scoreAccuracy!!).isWithin(1e-9).of(1.0)
    }

    @Test
    fun positionErrorsUseTheMedianAndThe95thPercentile() {
        val shots = arrayOf(
            truth(2, 0.0, 0.0), truth(2, 0.2, 0.0),
            truth(2, 0.4, 0.0), truth(2, 0.6, 0.0)
        )
        val e = entry("q.jpg", shots = shots)
        val detected = listOf(
            found(2, 0.000, 0.0),   // error 0.000
            found(2, 0.210, 0.0),   // error 0.010
            found(2, 0.420, 0.0),   // error 0.020
            found(2, 0.630, 0.0)    // error 0.030
        )
        val m = Metrics.over(listOf(outcome(e, detected)))

        // Errors are 0, 0.01, 0.02, 0.03. With linear interpolation between
        // order statistics the median sits at rank 0.5 * 3 = 1.5, so
        // 0.01 * 0.5 + 0.02 * 0.5 = 0.015.
        assertThat(m.medianPositionError!!).isWithin(1e-9).of(0.015)
        // The 95th percentile sits at rank 0.95 * 3 = 2.85, so
        // 0.02 * 0.15 + 0.03 * 0.85 = 0.0285.
        assertThat(m.p95PositionError!!).isWithin(1e-9).of(0.0285)
    }

    @Test
    fun positionErrorsIgnoreScoreOnlyMatchesRatherThanTreatingThemAsZero() {
        // A ring-only entry (no truth shot carries a position) matches by
        // score, and every one of its MatchedPair.distance values is null --
        // exactly what the inherited photographs produce. Folded into the
        // same aggregate as a real position error, those nulls must stay
        // excluded from positionErrors. An implementation that mapped a null
        // distance to 0.0 instead of dropping it would pull the median and
        // the 95th percentile down towards zero even though nothing was ever
        // measured in space for those five pairs.
        val positioned = entry("pos.jpg", shots = arrayOf(truth(1, 0.0, 0.0)))
        val detectedPositioned = listOf(found(1, 0.03, 0.0)) // real distance 0.03

        val ringOnly = CorpusEntry(
            imageName = "ring-only.jpg", image = null, camera = null, capture = null,
            target = null, shotsPerEnd = 5,
            shots = List(5) { TruthShot(scoringRing = 7) },
            unresolvedArrows = 0, registration = null
        )
        val detectedRingOnly = List(5) { found(7, 0.0, 0.0) }

        val m = Metrics.over(
            listOf(
                outcome(positioned, detectedPositioned),
                outcome(ringOnly, detectedRingOnly)
            )
        )

        assertThat(m.positionErrors).containsExactly(0.03)
        assertThat(m.medianPositionError!!).isWithin(1e-9).of(0.03)
        assertThat(m.p95PositionError!!).isWithin(1e-9).of(0.03)
    }

    @Test
    fun ringOnlyEntriesContributeEverythingButThePositionError() {
        val e = entry("r.jpg", shots = arrayOf(TruthShot(scoringRing = 2), TruthShot(scoringRing = 3)))
        val m = Metrics.over(listOf(outcome(e, listOf(found(2, 0.0, 0.0), found(3, 0.5, 0.0)))))

        assertThat(m.detectionRate!!).isWithin(1e-9).of(1.0)
        assertThat(m.scoreAccuracy!!).isWithin(1e-9).of(1.0)
        assertThat(m.entriesWithPositions).isEqualTo(0)
        assertThat(m.medianPositionError).isNull()
        assertThat(m.p95PositionError).isNull()
    }

    @Test
    fun anEmptyCorpusMeasuresNothingRatherThanReportingZero() {
        // A rate of 0.0 would read as a perfect run once printed as a
        // percentage, and would silently pass any regression bound placed on
        // it. An empty corpus must say it measured nothing instead.
        val m = Metrics.over(emptyList())
        assertThat(m.expectedShots).isEqualTo(0)
        assertThat(m.detectionRate).isNull()
        assertThat(m.falsePositiveRate).isNull()
        assertThat(m.scoreAccuracy).isNull()
        assertThat(m.medianPositionError).isNull()
    }

    @Test
    fun aZeroRateIsDistinctFromNoRateAtAll() {
        // A detector that finds nothing out of one expected shot has
        // legitimately measured a detection rate of zero -- a real result,
        // not an absence of one. An implementation that always returned 0.0
        // in place of null for an empty denominator would pass every other
        // null-check in this file yet still be indistinguishable from this
        // case; this test tells the two apart directly, side by side.
        val missedEverything = entry("miss.jpg", shots = arrayOf(truth(2, 0.0, 0.0)))
        val nothingAnnotated = CorpusEntry(
            imageName = "r.jpg", image = null, camera = null, capture = null,
            target = null, shotsPerEnd = 6, shots = emptyList(),
            unresolvedArrows = 0, registration = null
        )

        val measuredZero = Metrics.over(listOf(outcome(missedEverything, emptyList())))
        val measuredNothing = Metrics.over(
            listOf(
                EntryOutcome(
                    nothingAnnotated,
                    ShotMatching.match(nothingAnnotated, emptyList()),
                    emptyList()
                )
            )
        )

        assertThat(measuredZero.detectionRate).isNotNull()
        assertThat(measuredZero.detectionRate!!).isWithin(1e-9).of(0.0)
        assertThat(measuredNothing.detectionRate).isNull()
    }

    @Test
    fun aRegistrationOnlyEntryContributesToNothing() {
        val registrationOnly = CorpusEntry(
            imageName = "r.jpg", image = null, camera = null, capture = null,
            target = null, shotsPerEnd = 6, shots = emptyList(),
            unresolvedArrows = 0, registration = null
        )
        val m = Metrics.over(
            listOf(EntryOutcome(registrationOnly, ShotMatching.match(registrationOnly, emptyList()), emptyList()))
        )

        assertThat(m.expectedShots).isEqualTo(0)
        assertThat(m.annotatedEntries).isEqualTo(0)
        assertThat(m.detectionRate).isNull()
    }

    @Test
    fun unresolvedArrowsForgiveUnmatchedDetections() {
        // Six arrows are in the photograph, four could be annotated. A detector
        // finding all six must not be charged two inventions for the two the
        // annotator could not place: surplus (2) equals unresolvedArrows (2),
        // so the bounded rule forgives all of it, same as the literal rule
        // would have.
        val e = entryWithUnresolved(unresolved = 2)
        val detected = listOf(
            found(0, 0.0, 0.0), found(2, 0.3, 0.0),
            found(3, 0.6, 0.0), found(3, 0.9, 0.0),
            found(2, 0.05, 0.05), found(2, 0.06, 0.06)
        )
        val m = Metrics.over(listOf(outcome(e, detected)))

        assertThat(m.matchedShots).isEqualTo(4)
        assertThat(m.falsePositives).isEqualTo(0)
        // Under the bounded rule every annotated, in-scope entry is judged,
        // forgiven or not -- this entry's four listed hits sit in
        // expectedShots, the false positive rate's own denominator, giving a
        // real, measured rate of zero rather than an unmeasured null.
        assertThat(m.expectedShots).isEqualTo(4)
        assertThat(m.falsePositiveRate!!).isWithin(1e-9).of(0.0)
        assertThat(m.forgivenEntries).isEqualTo(1)
    }

    @Test
    fun withoutUnresolvedArrowsAnUnmatchedDetectionIsAnInvention() {
        val e = entryWithUnresolved(unresolved = 0)
        val detected = listOf(
            found(0, 0.0, 0.0), found(2, 0.3, 0.0),
            found(3, 0.6, 0.0), found(3, 0.9, 0.0),
            found(2, 0.05, 0.05)
        )
        val m = Metrics.over(listOf(outcome(e, detected)))

        assertThat(m.matchedShots).isEqualTo(4)
        assertThat(m.falsePositives).isEqualTo(1)
        assertThat(m.forgivenEntries).isEqualTo(0)
    }

    @Test
    fun forgivenessIsBoundedByUnresolvedArrowsNotUnlimited() {
        // This is the case that actually tells the bounded rule apart from
        // the literal, unbounded README reading: one unresolved arrow but
        // THREE surplus detections. The literal rule forgives all three,
        // charging zero false positives; the bounded rule forgives only one
        // -- the one hidden arrow can explain one unexplained detection, not
        // three -- and charges max(0, 3 - 1) = 2. The other two tests in this
        // file (unresolved = 2 with exactly 2 surplus detections) cannot
        // distinguish the two rules, because they happen to agree whenever
        // surplus <= unresolvedArrows.
        val e = entryWithUnresolved(unresolved = 1)
        val detected = listOf(
            found(0, 0.0, 0.0), found(2, 0.3, 0.0),
            found(3, 0.6, 0.0), found(3, 0.9, 0.0),
            found(2, 2.0, 0.0), found(2, 3.0, 0.0), found(2, 4.0, 0.0)
        )
        val m = Metrics.over(listOf(outcome(e, detected)))

        assertThat(m.matchedShots).isEqualTo(4)
        assertThat(m.falsePositives).isEqualTo(2)
        // Forgiveness still applied -- just not to the full surplus -- so the
        // entry still counts as forgiven.
        assertThat(m.forgivenEntries).isEqualTo(1)
    }

    @Test
    fun theFalsePositiveRateIsMeasuredAgainstExpectedShotsIncludingAForgivenEntry() {
        // Under the bounded rule every annotated, in-scope entry can
        // contribute a false positive -- a forgiven entry's listed hits are
        // no longer excluded from expectedShots, the false positive rate's
        // own denominator (item 3: falsePositiveDenominator was deleted
        // because it was provably identical to expectedShots), because
        // forgiveness is no longer all-or-nothing. A forgiven five-hit entry
        // takes 50 fabricated detections but its single unresolved arrow
        // forgives only one of them, charging 49; a strict one-hit entry
        // takes one fabrication and is charged for it. Against every listed
        // hit that was actually judged (5 + 1 = 6) that is 50 / 6 -- if the
        // forgiven entry's hits were still excluded from the denominator
        // (the old rule) it would read as 1 / 1 = 100% instead, hiding the
        // 49 charged false positives entirely.
        val forgiven = CorpusEntry(
            imageName = "forgiven.jpg", image = null, camera = null, capture = null,
            target = null, shotsPerEnd = 6,
            shots = (0 until 5).map { i -> truth(2, 0.1 * i, 0.0) },
            unresolvedArrows = 1, registration = null
        )
        val forgivenDetected = (0 until 5).map { i -> found(2, 0.1 * i, 0.0) } +
            (0 until 50).map { i -> found(2, 5.0 + i, 0.0) }
        val strict = entry("strict.jpg", shots = arrayOf(truth(2, 0.0, 0.0)))
        val strictDetected = listOf(found(2, 0.0, 0.0), found(2, 9.0, 0.0))

        val m = Metrics.over(
            listOf(outcome(forgiven, forgivenDetected), outcome(strict, strictDetected))
        )

        assertThat(m.falsePositives).isEqualTo(50)
        assertThat(m.expectedShots).isEqualTo(6)
        assertThat(m.falsePositiveRate!!).isWithin(1e-9).of(50.0 / 6.0)
        assertThat(m.forgivenEntries).isEqualTo(1)
    }

    @Test
    fun entriesWithPositionsCountsAnnotatedEntriesThatCarryPositions() {
        // Only ever asserted in the == 0 case elsewhere in this file (see
        // ringOnlyEntriesContributeEverythingButThePositionError); this pins
        // down the positive count too, so the field cannot be left
        // permanently at zero.
        val positioned = entry("pos.jpg", shots = arrayOf(truth(2, 0.0, 0.0)))
        val ringOnly = entry("ring.jpg", shots = arrayOf(TruthShot(scoringRing = 3)))
        val m = Metrics.over(
            listOf(
                outcome(positioned, listOf(found(2, 0.0, 0.0))),
                outcome(ringOnly, listOf(found(3, 0.0, 0.0)))
            )
        )

        assertThat(m.entriesWithPositions).isEqualTo(1)
    }

    @Test
    fun entriesWithPositionsCountsAnEntryThatHasSomePositionedShotsEvenIfNotAll() {
        // Under the old entry-level `hasPositions` flag, a single unpositioned
        // shot excluded the WHOLE entry from this count, even though it still
        // contributes a real, measured position error for its other shot.
        // entriesWithPositions must follow the same per-shot logic as
        // matching itself now does, not the old all-or-nothing flag.
        val mixed = entry(
            "mixed.jpg",
            shots = arrayOf(truth(2, 0.0, 0.0), TruthShot(scoringRing = 3))
        )
        val m = Metrics.over(
            listOf(outcome(mixed, listOf(found(2, 0.0, 0.0), found(3, 5.0, 5.0))))
        )

        assertThat(m.entriesWithPositions).isEqualTo(1)
    }

    @Test
    fun positionErrorInAMixedEntryOnlyCountsThePositionedPair() {
        // The unpositioned shot's pair is made by score and carries no
        // distance; only the positioned shot's real distance may enter
        // positionErrors.
        val mixed = entry(
            "mixed.jpg",
            shots = arrayOf(truth(2, 0.0, 0.0), TruthShot(scoringRing = 3))
        )
        val detected = listOf(found(2, 0.03, 0.0), found(3, 9.0, 9.0))
        val m = Metrics.over(listOf(outcome(mixed, detected)))

        assertThat(m.positionErrors).containsExactly(0.03)
    }

    @Test
    fun removingOnePositionFromAnEntryDoesNotInflateItsDetectionRate() {
        // The whole-branch review's reproduction: an entry where every
        // position is badly off (0.3, far outside any position gate) but
        // every ring value is correct. Fully positioned, every shot fails
        // position matching and the entry finds nothing. The old,
        // entry-level `hasPositions` switch fell back to matching every shot
        // by score -- which ignores position entirely -- the moment even ONE
        // shot lost its position, so removing a single position used to flip
        // this entry from a detection rate of 0.0 to 1.0. Per-shot matching
        // must not let that happen: the shots that still carry a position
        // must still fail to match by position, and must not be rescued by
        // score matching just because a sibling shot's position was dropped.
        fun shotsWithout(dropIndex: Int?) = (0 until 6).map { i ->
            TruthShot(
                scoringRing = i,
                position = if (i == dropIndex) null else SpotPosition(0, 0.1 * i, 0.0)
            )
        }
        fun entryWith(name: String, shots: List<TruthShot>) = CorpusEntry(
            imageName = name, image = null, camera = null, capture = null,
            target = null, shotsPerEnd = 6, shots = shots,
            unresolvedArrows = 0, registration = null
        )
        // Offset in y, not x: an x offset of 0.3 landing on a 0.1 grid would
        // put detection i exactly on top of truth i + 3 and accidentally
        // match it, which is not what this test is about.
        fun detectedFor(shots: List<TruthShot>) = shots.mapIndexed { i, shot ->
            DetectedShotRecord(shot.scoringRing, null, SpotPosition(0, 0.1 * i, 0.3), 0.9)
        }

        val fullyPositioned = entryWith("full.jpg", shotsWithout(dropIndex = null))
        val onePositionRemoved = entryWith("partial.jpg", shotsWithout(dropIndex = 0))

        val fullRate = Metrics.over(
            listOf(outcome(fullyPositioned, detectedFor(fullyPositioned.shots)))
        ).detectionRate!!
        val partialRate = Metrics.over(
            listOf(outcome(onePositionRemoved, detectedFor(onePositionRemoved.shots)))
        ).detectionRate!!

        assertThat(fullRate).isWithin(1e-9).of(0.0)
        // Only the one shot that lost its position can now be found -- by
        // score, since it no longer has a position to be judged by -- and
        // none of the other five are rescued.
        assertThat(partialRate).isWithin(1e-9).of(1.0 / 6.0)
        assertThat(partialRate).isLessThan(0.5)
    }

    @Test
    fun detectionsRejectedOnDistanceAggregatesTheCountAndTheMedian() {
        // Two entries, each with one detection that missed only on distance:
        // 0.10 and 0.20 away from the nearest unmatched truth. The metric
        // must count both and report their median, not silently drop them
        // because they never became a matched pair.
        val a = entry("a.jpg", shots = arrayOf(truth(2, 0.0, 0.0)))
        val detectedA = listOf(found(2, 0.10, 0.0))
        val b = entry("b.jpg", shots = arrayOf(truth(3, 0.0, 0.0)))
        val detectedB = listOf(found(3, 0.20, 0.0))

        val m = Metrics.over(listOf(outcome(a, detectedA), outcome(b, detectedB)))

        assertThat(m.detectionsRejectedOnDistance).isEqualTo(2)
        assertThat(m.medianRejectedDistance!!).isWithin(1e-9).of(0.15)
    }

    @Test
    fun detectionsRejectedOnDistanceIsZeroAndTheMedianIsNullWhenNothingWasRejectedOnDistance() {
        val e = entry("p.jpg", shots = arrayOf(truth(2, 0.0, 0.0)))
        val m = Metrics.over(listOf(outcome(e, listOf(found(2, 0.0, 0.0)))))

        assertThat(m.detectionsRejectedOnDistance).isEqualTo(0)
        assertThat(m.medianRejectedDistance).isNull()
    }

    @Test
    fun ringAccuracyCountsOnlyComparableTruth() {
        // Both truth shots carry a position, so ShotMatching pairs them by
        // distance regardless of what kind of score either side reports --
        // having a matched pair is not by itself enough for the pair to be
        // score-comparable. Shot A carries a zone index and is matched by a
        // detection that reports one too: comparable, and correct. Shot B
        // carries only a printed value and is matched by a detection that
        // reports only a zone index: matched, but NOT comparable, so it must
        // neither drag the ring accuracy down nor be counted as comparable --
        // an isScoreComparable that always returned true would get exactly
        // that wrong, inflating scoreComparableShots to 2 and scoreAccuracy
        // down to 0.5.
        val entry = CorpusEntry(
            imageName = "i.jpg", image = null, camera = null, capture = null,
            target = null, shotsPerEnd = 2,
            shots = listOf(
                TruthShot(scoringRing = 9, position = SpotPosition(0, 0.0, 0.0)),
                TruthShot(printedScore = PrintedScore.of("8"), position = SpotPosition(0, 0.5, 0.0))
            ),
            unresolvedArrows = 0, registration = null
        )
        val detected = listOf(
            DetectedShotRecord(9, null, SpotPosition(0, 0.0, 0.0), 0.9),
            DetectedShotRecord(3, null, SpotPosition(0, 0.5, 0.0), 0.9)
        )
        val m = Metrics.over(listOf(outcome(entry, detected)))

        assertThat(m.matchedShots).isEqualTo(2)
        assertThat(m.scoreComparableShots).isEqualTo(1)
        assertThat(m.scoreAccuracy!!).isWithin(1e-9).of(1.0)
    }

    @Test
    fun tagsSplitTheCorpusIntoComparableGroups() {
        val dark = entry("d.jpg", tags = setOf("dark"), shots = arrayOf(truth(2, 0.0, 0.0)))
        val plain = entry("p.jpg", shots = arrayOf(truth(2, 0.0, 0.0)))

        val byTag = Metrics.byTag(
            listOf(
                outcome(dark, emptyList()),
                outcome(plain, listOf(found(2, 0.0, 0.0)))
            )
        )

        assertThat(byTag.keys).containsExactly("dark", "untagged")
        assertThat(byTag["dark"]!!.detectionRate!!).isWithin(1e-9).of(0.0)
        assertThat(byTag["untagged"]!!.detectionRate!!).isWithin(1e-9).of(1.0)
    }

    @Test
    fun nearRingBoundaryHitsLeaveRingAccuracyEntirely() {
        // Both shots match by position. The boundary shot (scoringRing 5) is
        // matched by a detection that reports a DIFFERENT ring (9) -- wrong,
        // if it were counted. The plain shot (scoringRing 7) is matched
        // correctly. Including the boundary shot in ring accuracy would give
        // 1/2 = 50%; excluding it entirely -- out of the numerator AND the
        // denominator -- gives 1/1 = 100%. An implementation that counted it
        // as a normal comparable pair would get a materially different,
        // provably wrong number here, not just a differently-labelled one.
        val e = CorpusEntry(
            imageName = "boundary.jpg", image = null, camera = null, capture = null,
            target = null, shotsPerEnd = 2,
            shots = listOf(
                TruthShot(scoringRing = 5, position = SpotPosition(0, 0.0, 0.0), nearRingBoundary = true),
                TruthShot(scoringRing = 7, position = SpotPosition(0, 0.3, 0.0))
            ),
            unresolvedArrows = 0, registration = null
        )
        val detected = listOf(found(9, 0.0, 0.0), found(7, 0.3, 0.0))
        val m = Metrics.over(listOf(outcome(e, detected)))

        assertThat(m.matchedShots).isEqualTo(2)
        assertThat(m.boundaryShots).isEqualTo(1)
        assertThat(m.scoreComparableShots).isEqualTo(1)
        assertThat(m.scoreAccuracy!!).isWithin(1e-9).of(1.0)
    }

    @Test
    fun aNearRingBoundaryHitStillCountsForDetectionRate() {
        // Leaving ring accuracy is not the same as leaving detection: the
        // pair was still found, so it still counts as matched.
        val e = CorpusEntry(
            imageName = "boundary2.jpg", image = null, camera = null, capture = null,
            target = null, shotsPerEnd = 1,
            shots = listOf(
                TruthShot(scoringRing = 5, position = SpotPosition(0, 0.0, 0.0), nearRingBoundary = true)
            ),
            unresolvedArrows = 0, registration = null
        )
        val m = Metrics.over(listOf(outcome(e, listOf(found(5, 0.0, 0.0)))))

        assertThat(m.detectionRate!!).isWithin(1e-9).of(1.0)
        assertThat(m.boundaryShots).isEqualTo(1)
        assertThat(m.scoreComparableShots).isEqualTo(0)
    }

    @Test
    fun uncertainHitsAreExcludedFromThePositionErrorButStillCountAsMatched() {
        // Two matched, positioned pairs: the uncertain one at distance 0.01,
        // the plain one at 0.04. Excluding the uncertain one leaves
        // positionErrors = [0.04] -- median 0.04. Including it (the wrong
        // behaviour) would give [0.01, 0.04] -- median 0.025. The two answers
        // differ, so this actually discriminates the rule rather than
        // merely restating it.
        val e = CorpusEntry(
            imageName = "uncertain.jpg", image = null, camera = null, capture = null,
            target = null, shotsPerEnd = 2,
            shots = listOf(
                TruthShot(scoringRing = 2, position = SpotPosition(0, 0.0, 0.0), uncertain = true),
                TruthShot(scoringRing = 3, position = SpotPosition(0, 0.5, 0.0))
            ),
            unresolvedArrows = 0, registration = null
        )
        val detected = listOf(found(2, 0.01, 0.0), found(3, 0.54, 0.0))
        val m = Metrics.over(listOf(outcome(e, detected)))

        assertThat(m.matchedShots).isEqualTo(2)
        assertThat(m.detectionRate!!).isWithin(1e-9).of(1.0)
        assertThat(m.uncertainPositionsExcluded).isEqualTo(1)
        assertThat(m.positionErrors).hasSize(1)
        assertThat(m.positionErrors.single()).isWithin(1e-9).of(0.04)
        assertThat(m.medianPositionError!!).isWithin(1e-9).of(0.04)
    }

    @Test
    fun anUncertainHitWithNoComparableTruthStillCountsForScoreAccuracyNormally() {
        // uncertain only concerns position; it must not silently also
        // exclude the pair from ring accuracy.
        val e = CorpusEntry(
            imageName = "uncertain2.jpg", image = null, camera = null, capture = null,
            target = null, shotsPerEnd = 1,
            shots = listOf(
                TruthShot(scoringRing = 2, position = SpotPosition(0, 0.0, 0.0), uncertain = true)
            ),
            unresolvedArrows = 0, registration = null
        )
        val m = Metrics.over(listOf(outcome(e, listOf(found(2, 0.01, 0.0)))))

        assertThat(m.scoreComparableShots).isEqualTo(1)
        assertThat(m.scoreAccuracy!!).isWithin(1e-9).of(1.0)
    }

    @Test
    fun outOfScopeEntryContributesToNoMetric() {
        // The out-of-scope entry alone, if counted, would contribute 2
        // expected shots, up to 2 matches (its positions align with the
        // detections), a surplus detection, and 0 correct scores (every
        // detected ring is wrong) -- enough to move expectedShots,
        // matchedShots, falsePositives, scoreComparableShots and
        // annotatedEntries all at once, not just one number quietly.
        val inScope = entry("in.jpg", shots = arrayOf(truth(2, 0.0, 0.0)))
        val inScopeDetected = listOf(found(2, 0.0, 0.0))

        val outOfScope = CorpusEntry(
            imageName = "out.jpg", image = null, camera = null, capture = null,
            target = null, shotsPerEnd = 3,
            shots = listOf(truth(5, 0.0, 0.0), truth(6, 0.3, 0.0)),
            unresolvedArrows = 0, registration = null,
            outOfScope = "Drei Auflagen nebeneinander."
        )
        val outOfScopeDetected = listOf(found(9, 0.0, 0.0), found(9, 0.3, 0.0), found(9, 5.0, 0.0))

        val withOutOfScope = Metrics.over(
            listOf(outcome(inScope, inScopeDetected), outcome(outOfScope, outOfScopeDetected))
        )
        val withoutOutOfScope = Metrics.over(listOf(outcome(inScope, inScopeDetected)))

        assertThat(withOutOfScope.expectedShots).isEqualTo(withoutOutOfScope.expectedShots)
        assertThat(withOutOfScope.matchedShots).isEqualTo(withoutOutOfScope.matchedShots)
        assertThat(withOutOfScope.falsePositives).isEqualTo(withoutOutOfScope.falsePositives)
        assertThat(withOutOfScope.scoreComparableShots).isEqualTo(withoutOutOfScope.scoreComparableShots)
        assertThat(withOutOfScope.correctScores).isEqualTo(withoutOutOfScope.correctScores)
        assertThat(withOutOfScope.annotatedEntries).isEqualTo(withoutOutOfScope.annotatedEntries)
        assertThat(withOutOfScope.positionErrors).isEqualTo(withoutOutOfScope.positionErrors)

        // Pinned absolute values: if the out-of-scope entry leaked through,
        // expectedShots would read 3 (1 + 2) rather than 1, and
        // annotatedEntries would read 2 rather than 1.
        assertThat(withOutOfScope.expectedShots).isEqualTo(1)
        assertThat(withOutOfScope.annotatedEntries).isEqualTo(1)
    }

    @Test
    fun falsePositiveRateIsNullWhenTheOnlyAnnotatedEntryIsOutOfScope() {
        // A corpus with exactly one annotated entry, and it is out of scope:
        // nothing here can ever be judged, which must read as "not
        // measured", not as a rate of zero.
        val outOfScope = CorpusEntry(
            imageName = "out.jpg", image = null, camera = null, capture = null,
            target = null, shotsPerEnd = 1,
            shots = listOf(truth(2, 0.0, 0.0)),
            unresolvedArrows = 0, registration = null,
            outOfScope = "not covered by v1"
        )
        val m = Metrics.over(listOf(outcome(outOfScope, listOf(found(9, 9.0, 9.0)))))

        assertThat(m.falsePositiveRate).isNull()
        assertThat(m.expectedShots).isEqualTo(0)
        assertThat(m.annotatedEntries).isEqualTo(0)
    }

    // --- Item 2: surplus charged beyond forgiveness ------------------------

    @Test
    fun entriesChargedBeyondForgivenessCountsThePartiallyForgivenEntrySeparately() {
        // One unresolved arrow forgives one surplus detection; three are
        // fabricated, so two are still charged despite forgiveness applying.
        // forgivenEntries alone cannot tell this apart from a fully forgiven
        // entry (see forgivenessIsBoundedByUnresolvedArrowsNotUnlimited) -- it
        // counts the entry either way. entriesChargedBeyondForgiveness and
        // detectionsChargedDespiteForgiveness exist to surface the partial
        // case specifically.
        val e = entryWithUnresolved(unresolved = 1)
        val detected = listOf(
            found(0, 0.0, 0.0), found(2, 0.3, 0.0),
            found(3, 0.6, 0.0), found(3, 0.9, 0.0),
            found(2, 2.0, 0.0), found(2, 3.0, 0.0), found(2, 4.0, 0.0)
        )
        val m = Metrics.over(listOf(outcome(e, detected)))

        assertThat(m.forgivenEntries).isEqualTo(1)
        assertThat(m.entriesChargedBeyondForgiveness).isEqualTo(1)
        assertThat(m.detectionsChargedDespiteForgiveness).isEqualTo(2)
    }

    @Test
    fun entriesChargedBeyondForgivenessIsZeroWhenForgivenessCoversTheWholeSurplus() {
        val e = entryWithUnresolved(unresolved = 2)
        val detected = listOf(
            found(0, 0.0, 0.0), found(2, 0.3, 0.0),
            found(3, 0.6, 0.0), found(3, 0.9, 0.0),
            found(2, 0.05, 0.05), found(2, 0.06, 0.06)
        )
        val m = Metrics.over(listOf(outcome(e, detected)))

        assertThat(m.forgivenEntries).isEqualTo(1)
        assertThat(m.entriesChargedBeyondForgiveness).isEqualTo(0)
        assertThat(m.detectionsChargedDespiteForgiveness).isEqualTo(0)
    }

    // --- Item 6: byTag drops out-of-scope entries entirely -----------------

    @Test
    fun byTagOmitsATagThatOnlyAnOutOfScopeEntryCarries() {
        // Change 6: an out-of-scope entry contributes to no metric --
        // including the existence of a byTag row that only it would produce.
        // Metrics.over already zeroes such a row's numbers; this checks the
        // row itself is gone, not merely empty.
        val outOfScope = CorpusEntry(
            imageName = "out.jpg", image = null, camera = null,
            capture = CaptureInfo(lighting = "onlyOutOfScope", angle = null, angleDegrees = null),
            target = null, shotsPerEnd = 1,
            shots = listOf(truth(5, 0.0, 0.0)),
            unresolvedArrows = 0, registration = null,
            outOfScope = "not covered by v1"
        )
        val plain = entry("p.jpg", shots = arrayOf(truth(2, 0.0, 0.0)))

        val byTag = Metrics.byTag(
            listOf(outcome(outOfScope, emptyList()), outcome(plain, listOf(found(2, 0.0, 0.0))))
        )

        assertThat(byTag.keys).containsExactly("untagged")
    }

    @Test
    fun byTagKeepsATagSharedByAnInScopeAndAnOutOfScopeEntry() {
        // Dropping the out-of-scope entry from a group must not drop the
        // group when an in-scope entry still carries the same tag.
        val outOfScope = CorpusEntry(
            imageName = "out.jpg", image = null, camera = null,
            capture = CaptureInfo(lighting = "dark", angle = null, angleDegrees = null),
            target = null, shotsPerEnd = 1,
            shots = listOf(truth(5, 0.0, 0.0)),
            unresolvedArrows = 0, registration = null,
            outOfScope = "not covered by v1"
        )
        val dark = entry("d.jpg", tags = setOf("dark"), shots = arrayOf(truth(2, 0.0, 0.0)))

        val byTag = Metrics.byTag(
            listOf(outcome(outOfScope, emptyList()), outcome(dark, listOf(found(2, 0.0, 0.0))))
        )

        assertThat(byTag.keys).containsExactly("dark")
        assertThat(byTag["dark"]!!.detectionRate!!).isWithin(1e-9).of(1.0)
    }

    // --- Item 9: pairs matched by score rather than by position ------------

    @Test
    fun pairsMatchedByScoreCountsPairsWithoutAPositionOfTheirOwn() {
        // Truth 0 is positioned and matched by position (distance non-null);
        // truth 1 has no position and is matched by score (distance null) --
        // see ShotMatching. Only the second contributes here.
        val mixed = entry(
            "mixed.jpg",
            shots = arrayOf(truth(2, 0.0, 0.0), TruthShot(scoringRing = 3))
        )
        val m = Metrics.over(
            listOf(outcome(mixed, listOf(found(2, 0.0, 0.0), found(3, 5.0, 5.0))))
        )

        assertThat(m.pairsMatchedByScore).isEqualTo(1)
    }

    @Test
    fun pairsMatchedByScoreIsZeroWhenEveryMatchedPairCarriesAPosition() {
        val e = entry("p.jpg", shots = arrayOf(truth(2, 0.0, 0.0)))
        val m = Metrics.over(listOf(outcome(e, listOf(found(2, 0.0, 0.0)))))

        assertThat(m.pairsMatchedByScore).isEqualTo(0)
    }

    // --- Item 10: boundaryShots and detectionsRejectedOnDistance -----------

    @Test
    fun boundaryShotsCountsEvenWhenTheMatchedPairIsNotOtherwiseScoreComparable() {
        // The reviewer's caught mutation: restricting boundaryShots to pairs
        // that would also have been score-comparable. A boundary shot is
        // excluded from ring accuracy precisely BECAUSE its ring value cannot
        // be fairly judged -- whether the detection happens to carry a
        // comparable kind of score at all is beside the point, and must not
        // gate whether the pair is counted here.
        val e = CorpusEntry(
            imageName = "boundary3.jpg", image = null, camera = null, capture = null,
            target = null, shotsPerEnd = 1,
            shots = listOf(
                TruthShot(scoringRing = 5, position = SpotPosition(0, 0.0, 0.0), nearRingBoundary = true)
            ),
            unresolvedArrows = 0, registration = null
        )
        // Reports only a printed value, no zone index -- NOT score-comparable
        // with the truth's zone index at all (see Metrics.isScoreComparable).
        val detected = listOf(DetectedShotRecord(null, PrintedScore.of("5"), SpotPosition(0, 0.0, 0.0), 0.9))
        val m = Metrics.over(listOf(outcome(e, detected)))

        assertThat(m.matchedShots).isEqualTo(1)
        assertThat(m.boundaryShots).isEqualTo(1)
        assertThat(m.scoreComparableShots).isEqualTo(0)
    }

    @Test
    fun detectionsRejectedOnDistanceHasNoCap() {
        // The reviewer's caught mutation: capping rejected-on-distance
        // entries at 0.25 spot radii. A detection can be arbitrarily far from
        // the nearest unmatched, positioned truth shot and still be reported
        // here -- the metric's whole point is to show how bad the detector's
        // distances get, not to hide the worst of them behind an
        // undocumented cap.
        val e = entry("far.jpg", shots = arrayOf(truth(2, 0.0, 0.0)))
        val detected = listOf(found(2, 0.9, 0.0)) // 0.9 spot radii from the truth
        val m = Metrics.over(listOf(outcome(e, detected)))

        assertThat(m.detectionsRejectedOnDistance).isEqualTo(1)
        assertThat(m.rejectedDistances.single()).isWithin(1e-9).of(0.9)
    }
}
