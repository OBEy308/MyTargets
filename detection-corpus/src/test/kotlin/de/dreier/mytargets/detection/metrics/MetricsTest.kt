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
        // annotator could not place.
        val e = entryWithUnresolved(unresolved = 2)
        val detected = listOf(
            found(0, 0.0, 0.0), found(2, 0.3, 0.0),
            found(3, 0.6, 0.0), found(3, 0.9, 0.0),
            found(2, 0.05, 0.05), found(2, 0.06, 0.06)
        )
        val m = Metrics.over(listOf(outcome(e, detected)))

        assertThat(m.matchedShots).isEqualTo(4)
        assertThat(m.falsePositives).isEqualTo(0)
        // The only entry here is forgiven, so falsePositiveDenominator is
        // zero -- there is nothing left in the corpus that could ever be
        // charged a false positive -- and the rate must read as unmeasured,
        // not as a real zero.
        assertThat(m.falsePositiveDenominator).isEqualTo(0)
        assertThat(m.falsePositiveRate).isNull()
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
    fun unresolvedArrowsForgiveEveryUnmatchedDetectionNotJustAsManyAsAreUnresolved() {
        // This is the case that actually tells the literal README rule apart
        // from the tighter "forgive only min(surplus, unresolvedArrows)"
        // reading: one unresolved arrow but THREE surplus detections. The
        // literal rule forgives all three; the tighter rule would still
        // charge two of them as false positives. The other two tests in this
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
        assertThat(m.falsePositives).isEqualTo(0)
        assertThat(m.forgivenEntries).isEqualTo(1)
    }

    @Test
    fun theFalsePositiveDenominatorExcludesForgivenEntries() {
        // A forgiven entry's surplus detections can never become a false
        // positive, so its listed hits must not sit in the rate's
        // denominator either. A forgiven five-hit entry takes 50 fabricated
        // detections at no cost; a strict one-hit entry takes one
        // fabrication and is charged for it. Against the entries that were
        // actually judged (the strict one, one listed hit) that is 100%; if
        // the denominator reverted to expectedShots (5 + 1 = 6) it would
        // read as 1/6 = 16.7% instead.
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

        assertThat(m.falsePositives).isEqualTo(1)
        assertThat(m.falsePositiveDenominator).isEqualTo(1)
        assertThat(m.falsePositiveRate!!).isWithin(1e-9).of(1.0)
    }

    @Test
    fun falsePositiveRateIsNullWhenEveryEntryIsForgiven() {
        // If every entry in the corpus is forgiven, the false positive
        // denominator is zero -- not because nothing was found, but because
        // nothing here could ever be charged. That must read as "not
        // measured", never as a rate of zero.
        val forgiven = CorpusEntry(
            imageName = "forgiven.jpg", image = null, camera = null, capture = null,
            target = null, shotsPerEnd = 6,
            shots = listOf(truth(2, 0.0, 0.0)),
            unresolvedArrows = 1, registration = null
        )
        val detected = listOf(found(2, 0.0, 0.0), found(2, 9.0, 0.0))
        val m = Metrics.over(listOf(outcome(forgiven, detected)))

        assertThat(m.falsePositiveDenominator).isEqualTo(0)
        assertThat(m.falsePositiveRate).isNull()
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
}
