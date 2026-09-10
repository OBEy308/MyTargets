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
import de.dreier.mytargets.detection.corpus.CorpusEntry
import de.dreier.mytargets.detection.corpus.PrintedScore
import de.dreier.mytargets.detection.corpus.SpotPosition
import de.dreier.mytargets.detection.corpus.TruthShot
import org.junit.Test

class ShotMatchingTest {

    // Arbitrary but distinct zone indices, chosen to read like the score they
    // stand for. Only equality between a truth shot and a detection matters
    // to matchByScore, so the exact values carry no other meaning.
    private val ringX = 10
    private val ring9 = 9
    private val ring8 = 8
    private val ring7 = 7

    private fun entry(vararg shots: TruthShot) = CorpusEntry(
        imageName = "t.jpg", image = null, camera = null, capture = null,
        target = null, shotsPerEnd = shots.size,
        shots = shots.toList(), unresolvedArrows = 0, registration = null
    )

    private fun truth(
        ring: Int,
        x: Double,
        y: Double,
        face: Int = 0,
        tolerance: Double? = null
    ) = TruthShot(
        scoringRing = ring,
        position = SpotPosition(face, x, y),
        positionTolerance = tolerance
    )

    private fun found(ring: Int, x: Double, y: Double, face: Int = 0, conf: Double = 0.9) =
        DetectedShotRecord(ring, null, SpotPosition(face, x, y), conf)

    @Test
    fun pairsNearestPositionsFirst() {
        val e = entry(truth(ringX, 0.0, 0.0), truth(ring9, 0.5, 0.0))
        val detected = listOf(found(ring9, 0.49, 0.0), found(ringX, 0.01, 0.0))

        val result = ShotMatching.match(e, detected)

        assertThat(result.pairs).hasSize(2)
        assertThat(result.unmatchedTruth).isEmpty()
        assertThat(result.unmatchedDetected).isEmpty()
        val byTruth = result.pairs.associateBy { it.truthIndex }
        assertThat(byTruth[0]!!.detectedIndex).isEqualTo(1)
        assertThat(byTruth[1]!!.detectedIndex).isEqualTo(0)
        assertThat(byTruth[0]!!.distance!!).isWithin(1e-9).of(0.01)
    }

    @Test
    fun aDetectionExactlyAtTheToleranceBoundaryStillMatches() {
        // The tolerance comparison is <=, not <: a detection sitting exactly
        // on the boundary is still within tolerance. 0.05 and 0.0 are both
        // exactly representable and Math.hypot(0.05, 0.0) returns exactly
        // 0.05, so this is a genuine boundary case, not one that only looks
        // like one due to floating point rounding.
        //
        // The truth shot carries no tolerance of its own, so the gate is
        // exactly DEFAULT_POSITION_TOLERANCE (0.05) -- the detector's budget
        // plus zero annotation slack. Giving the shot its own tolerance here
        // would widen the gate past 0.05 (the two add), which would leave
        // this detection sitting comfortably inside the gate rather than on
        // its boundary.
        val e = entry(truth(ringX, 0.0, 0.0))
        val detected = listOf(found(ringX, 0.05, 0.0))

        val result = ShotMatching.match(e, detected)

        assertThat(result.pairs).hasSize(1)
        assertThat(result.pairs[0].distance!!).isWithin(1e-9).of(0.05)
        assertThat(result.unmatchedTruth).isEmpty()
        assertThat(result.unmatchedDetected).isEmpty()
    }

    @Test
    fun aDetectionBeyondTheGateIsNotAMatch() {
        val e = entry(truth(ringX, 0.0, 0.0))
        val detected = listOf(found(ringX, 0.5, 0.0))

        val result = ShotMatching.match(e, detected, defaultPositionTolerance = 0.05)

        assertThat(result.pairs).isEmpty()
        assertThat(result.unmatchedTruth).containsExactly(0)
        assertThat(result.unmatchedDetected).containsExactly(0)
    }

    @Test
    fun positionsOnDifferentSpotsNeverPair() {
        val e = entry(truth(ringX, 0.0, 0.0, face = 0))
        val detected = listOf(found(ringX, 0.0, 0.0, face = 1))

        val result = ShotMatching.match(e, detected)

        assertThat(result.pairs).isEmpty()
        assertThat(result.unmatchedTruth).containsExactly(0)
    }

    @Test
    fun aSurplusDetectionStaysUnmatched() {
        val e = entry(truth(ringX, 0.0, 0.0))
        val detected = listOf(found(ringX, 0.0, 0.0), found(ring9, 0.4, 0.4))

        val result = ShotMatching.match(e, detected)

        assertThat(result.pairs).hasSize(1)
        assertThat(result.unmatchedDetected).containsExactly(1)
        // Strengthens the brief's assertion: the surplus detection must show
        // up as unmatched, not simply be absent from the matched pairs for
        // some other reason (e.g. an implementation that drops detections
        // instead of accounting for them would make both assertions above
        // pass vacuously if it also under-counted the pairs; pinning the
        // total keeps that impossible).
        assertThat(result.pairs.size + result.unmatchedDetected.size).isEqualTo(detected.size)
    }

    @Test
    fun ringOnlyTruthPairsByScore() {
        val e = entry(
            TruthShot(scoringRing = ring9),
            TruthShot(scoringRing = ring9),
            TruthShot(scoringRing = ring7)
        )
        val detected = listOf(
            found(ring7, 0.6, 0.0, conf = 0.5),
            found(ring9, 0.2, 0.0, conf = 0.9),
            found(ring8, 0.4, 0.0, conf = 0.7)
        )

        val result = ShotMatching.match(e, detected)

        // Two of the three detections carry a score the truth contains.
        assertThat(result.pairs).hasSize(2)
        assertThat(result.pairs.all { it.distance == null }).isTrue()
        // The eight matches nothing; one of the two nines stays unmatched.
        assertThat(result.unmatchedDetected).containsExactly(2)
        assertThat(result.unmatchedTruth).hasSize(1)
        // Strengthens the two assertions above: which truth index is left
        // over, and which detection each pair actually carries, are both
        // deterministic here (truth index 0 is the first ring-9 shot, so it
        // is the one `firstOrNull` picks). A wrong implementation could
        // satisfy "one nine unmatched" and "two pairs, no distance" while
        // still assigning the wrong detection to the wrong truth index.
        assertThat(result.unmatchedTruth).containsExactly(1)
        assertThat(result.pairs.first { it.truthIndex == 0 }.detectedIndex).isEqualTo(1)
        assertThat(result.pairs.first { it.truthIndex == 2 }.detectedIndex).isEqualTo(0)
    }

    @Test
    fun ringOnlyMatchingPrefersConfidentDetections() {
        val e = entry(TruthShot(scoringRing = ring9))
        val detected = listOf(
            found(ring9, 0.0, 0.0, conf = 0.2),
            found(ring9, 0.3, 0.0, conf = 0.8)
        )

        val result = ShotMatching.match(e, detected)

        assertThat(result.pairs).hasSize(1)
        assertThat(result.pairs[0].detectedIndex).isEqualTo(1)
        // Strengthens the assertion above: the less confident detection must
        // surface as unmatched rather than vanish. Checking only pairs.hasSize(1)
        // and the winning index would also pass an implementation that simply
        // discards every detection beyond the first match.
        assertThat(result.unmatchedDetected).containsExactly(0)
    }

    @Test
    fun noDetectionsLeavesEveryTruthUnmatched() {
        val e = entry(truth(ringX, 0.0, 0.0), truth(ring9, 0.5, 0.0))
        val result = ShotMatching.match(e, emptyList())
        assertThat(result.pairs).isEmpty()
        assertThat(result.unmatchedTruth).containsExactly(0, 1).inOrder()
    }

    @Test
    fun theGreedyChoiceIsGloballyNearestFirst() {
        // Truth A at 0.00, truth B at 0.03. One detection at 0.02.
        // Nearest first pairs it with B at 0.01, not with A at 0.02.
        val e = entry(truth(ring9, 0.0, 0.0), truth(ring9, 0.03, 0.0))
        val detected = listOf(found(ring9, 0.02, 0.0))

        val result = ShotMatching.match(e, detected)

        assertThat(result.pairs).hasSize(1)
        assertThat(result.pairs[0].truthIndex).isEqualTo(1)
        assertThat(result.pairs[0].distance!!).isWithin(1e-9).of(0.01)
    }

    @Test
    fun eachTruthShotBringsItsOwnTolerance() {
        // The annotator records a larger tolerance where shafts overlap and the
        // entry point had to be estimated. A single global gate would either
        // reject that hit or admit sloppiness everywhere else. The gate a shot
        // actually uses is DEFAULT_POSITION_TOLERANCE (0.05) plus its own
        // annotation tolerance, so truth 0's gate is 0.052 and truth 1's is
        // 0.09.
        val e = CorpusEntry(
            imageName = "t.jpg", image = null, camera = null, capture = null,
            target = null, shotsPerEnd = 2,
            shots = listOf(
                TruthShot(scoringRing = 0, position = SpotPosition(0, 0.0, 0.0),
                    positionTolerance = 0.002),
                TruthShot(scoringRing = 2, position = SpotPosition(0, 0.5, 0.0),
                    positionTolerance = 0.04)
            ),
            unresolvedArrows = 0, registration = null
        )
        val detected = listOf(
            DetectedShotRecord(0, null, SpotPosition(0, 0.06, 0.0), 0.9),
            DetectedShotRecord(2, null, SpotPosition(0, 0.585, 0.0), 0.9)
        )

        val result = ShotMatching.match(e, detected)

        // 0.06 exceeds truth 0's gate of 0.052; 0.085 fits inside truth 1's
        // gate of 0.09.
        assertThat(result.pairs).hasSize(1)
        assertThat(result.pairs[0].truthIndex).isEqualTo(1)
        assertThat(result.unmatchedTruth).containsExactly(0)
        // Strengthens the assertions above: pins which detection the surviving
        // pair uses and its distance. Without this, a bug that swapped which
        // truth shot receives which tolerance (using truth 1's 0.04 for truth
        // 0, and truth 0's 0.002 for truth 1) would also leave exactly one
        // pair by accident: truth 0's distance of 0.06 would fit inside the
        // swapped-in gate of 0.09, admitting truth 0 instead, and truth 1's
        // distance of 0.085 would exceed the swapped-in gate of 0.052,
        // rejecting truth 1 -- but then the surviving pair would carry
        // truthIndex 0 and detectedIndex 0, not 1, and unmatchedTruth would
        // contain 1, not 0. That mirror-image failure only shows up once the
        // detected index and distance are actually checked.
        assertThat(result.pairs[0].detectedIndex).isEqualTo(1)
        assertThat(result.pairs[0].distance!!).isWithin(1e-9).of(0.085)
    }

    @Test
    fun aShotWithoutItsOwnToleranceFallsBackToTheDefault() {
        val e = CorpusEntry(
            imageName = "t.jpg", image = null, camera = null, capture = null,
            target = null, shotsPerEnd = 1,
            shots = listOf(TruthShot(scoringRing = 0, position = SpotPosition(0, 0.0, 0.0))),
            unresolvedArrows = 0, registration = null
        )
        val detected = listOf(DetectedShotRecord(0, null, SpotPosition(0, 0.04, 0.0), 0.9))

        assertThat(ShotMatching.match(e, detected).pairs).hasSize(1)
        val rejected = ShotMatching.match(e, detected, defaultPositionTolerance = 0.01)
        assertThat(rejected.pairs).isEmpty()
        // Strengthens the second assertion above: the detection that no
        // longer matches must surface as unmatched, on both sides, rather
        // than vanish once the default tightens.
        assertThat(rejected.unmatchedTruth).containsExactly(0)
        assertThat(rejected.unmatchedDetected).containsExactly(0)
    }

    @Test
    fun aLargerPerShotToleranceAdmitsWhatTheDefaultAloneWouldReject() {
        // 0.06 exceeds ShotMatching.DEFAULT_POSITION_TOLERANCE (0.05), so the
        // default alone (no annotation tolerance) would reject this pair; the
        // shot's own annotation tolerance of 0.08 widens the gate to 0.13,
        // which admits it.
        val e = entry(truth(ringX, 0.0, 0.0, tolerance = 0.08))
        val detected = listOf(found(ringX, 0.06, 0.0))

        val result = ShotMatching.match(e, detected)

        assertThat(result.pairs).hasSize(1)
        assertThat(result.pairs[0].distance!!).isWithin(1e-9).of(0.06)
    }

    @Test
    fun aDetectionTheOldGateWouldHaveRejectedNowMatchesInsideTheWidenedGate() {
        // Real annotation tolerances are 0.01-0.02 -- a tenth of a ring,
        // narrower than an arrow shaft. Under the old rule the gate WAS the
        // annotation tolerance (0.01 here), so a detector accurate to 0.03
        // was rejected outright. The corrected gate is the detector's own
        // budget (DEFAULT_POSITION_TOLERANCE, 0.05) PLUS that annotation
        // tolerance -- 0.06 here -- because the tolerance is annotation
        // uncertainty, not a detector budget, and must not replace the
        // detector's budget rather than add to it. 0.03 now fits.
        val e = entry(truth(ringX, 0.0, 0.0, tolerance = 0.01))
        val detected = listOf(found(ringX, 0.03, 0.0))

        val result = ShotMatching.match(e, detected)

        assertThat(result.pairs).hasSize(1)
        assertThat(result.pairs[0].distance!!).isWithin(1e-9).of(0.03)
    }

    @Test
    fun aDetectionBeyondTheWidenedGateStillDoesNotMatch() {
        // Same annotation tolerance as above (0.01), so the same gate of
        // 0.06. 0.07 exceeds it, so this still does not match -- the widened
        // gate is not unlimited.
        val e = entry(truth(ringX, 0.0, 0.0, tolerance = 0.01))
        val detected = listOf(found(ringX, 0.07, 0.0))

        val result = ShotMatching.match(e, detected)

        assertThat(result.pairs).isEmpty()
        assertThat(result.unmatchedTruth).containsExactly(0)
        assertThat(result.unmatchedDetected).containsExactly(0)
    }

    @Test
    fun ringOnlyTruthPairsByPrintedScore() {
        val e = CorpusEntry(
            imageName = "t.jpg", image = null, camera = null, capture = null,
            target = null, shotsPerEnd = 2,
            shots = listOf(
                TruthShot(printedScore = PrintedScore.of("9")),
                TruthShot(printedScore = PrintedScore.of("7"))
            ),
            unresolvedArrows = 0, registration = null
        )
        val detected = listOf(
            DetectedShotRecord(null, PrintedScore.of("7"), SpotPosition(0, 0.6, 0.0), 0.5),
            DetectedShotRecord(null, PrintedScore.of("9"), SpotPosition(0, 0.2, 0.0), 0.9)
        )

        val result = ShotMatching.match(e, detected)

        assertThat(result.pairs).hasSize(2)
        assertThat(result.pairs.all { it.distance == null }).isTrue()
        // Strengthens the assertions above: pins which detection each truth
        // index actually receives, and that nothing is left over on either
        // side. "Two pairs, no distance" alone would also pass an
        // implementation that paired truth 0 with detection 0 (9 with 7) by
        // mistake, as long as it still emitted two pairs with a null distance.
        assertThat(result.unmatchedTruth).isEmpty()
        assertThat(result.unmatchedDetected).isEmpty()
        assertThat(result.pairs.first { it.truthIndex == 0 }.detectedIndex).isEqualTo(1)
        assertThat(result.pairs.first { it.truthIndex == 1 }.detectedIndex).isEqualTo(0)
    }

    @Test
    fun scoringRingTakesPrecedenceOverPrintedScoreWhenBothArePresent() {
        // The doc comment on scoresAgree claims the zone index wins whenever
        // both sides have one, and the printed value is only a fallback for
        // entries without one. None of the tests above exercise a shot that
        // carries both, so a fallback-first implementation -- one that tries
        // printedScore before scoringRing -- would pass every test above
        // while getting the precedence backwards. This pins it directly.
        val truthBothPresent = TruthShot(scoringRing = 5, printedScore = PrintedScore.of("9"))

        // Rings agree, printed values disagree: still counts as the same shot.
        assertThat(
            ShotMatching.scoresAgree(
                truthBothPresent,
                DetectedShotRecord(5, PrintedScore.of("7"), SpotPosition(0, 0.0, 0.0), 0.9)
            )
        ).isTrue()

        // Rings disagree, printed values agree: still counts as a different
        // shot -- this does not fall back to the printed value.
        assertThat(
            ShotMatching.scoresAgree(
                truthBothPresent,
                DetectedShotRecord(6, PrintedScore.of("9"), SpotPosition(0, 0.0, 0.0), 0.9)
            )
        ).isFalse()
    }

    @Test
    fun aMixedEntryMatchesPositionedShotsByPositionAndUnpositionedShotsByScore() {
        // Truth 0 carries a position and must be matched by distance; truth 1
        // carries none and must fall back to score. Per-shot, not per-entry --
        // this is exactly the split the whole-entry `hasPositions` switch used
        // to collapse into one all-or-nothing choice, which let one missing
        // position push an entire, otherwise fully positioned entry into
        // score-only matching.
        val e = CorpusEntry(
            imageName = "t.jpg", image = null, camera = null, capture = null,
            target = null, shotsPerEnd = 2,
            shots = listOf(
                truth(ringX, 0.0, 0.0),        // positioned
                TruthShot(scoringRing = ring7) // no position
            ),
            unresolvedArrows = 0, registration = null
        )
        val detected = listOf(
            found(ringX, 0.01, 0.0), // close to truth 0's position
            found(ring7, 9.0, 9.0)   // nowhere near anything, but the right score for truth 1
        )

        val result = ShotMatching.match(e, detected)

        assertThat(result.pairs).hasSize(2)
        val byTruth = result.pairs.associateBy { it.truthIndex }
        assertThat(byTruth[0]!!.detectedIndex).isEqualTo(0)
        assertThat(byTruth[0]!!.distance).isNotNull()
        assertThat(byTruth[1]!!.detectedIndex).isEqualTo(1)
        // Strengthens the assertion above: a pair made by score carries no
        // distance, so this also confirms truth 1 was matched by SCORE and
        // not accidentally admitted by a position gate wide enough to reach
        // clear across the photograph to (9.0, 9.0).
        assertThat(byTruth[1]!!.distance).isNull()
        assertThat(result.unmatchedTruth).isEmpty()
        assertThat(result.unmatchedDetected).isEmpty()
    }

    @Test
    fun aPositionedTruthShotThatFailsTheGateIsNotRescuedByScoreMatching() {
        // If a positioned shot's own gate rejects its nearest detection, it
        // must stay unmatched -- it must not fall through to score matching
        // just because a score-agreeing detection happens to be sitting
        // elsewhere in the same photograph. Only shots with NO position at
        // all use score; this is what stops one missing position elsewhere in
        // the entry from silently improving THIS shot's result.
        val e = entry(truth(ringX, 0.0, 0.0))
        val detected = listOf(found(ringX, 5.0, 5.0)) // same ring, nowhere near the position

        val result = ShotMatching.match(e, detected)

        assertThat(result.pairs).isEmpty()
        assertThat(result.unmatchedTruth).containsExactly(0)
        assertThat(result.unmatchedDetected).containsExactly(0)
    }

    @Test
    fun removingOnePositionFromAnEntryDoesNotRescueItsSiblingsByScore() {
        // The whole-branch review's reproduction, at the ShotMatching level:
        // an entry where every position is badly off (0.3, far outside any
        // gate) but every ring value is correct. Under the old, entry-level
        // `hasPositions` switch, dropping ONE shot's position flipped the
        // WHOLE entry to score-only matching -- which ignores position
        // entirely -- so all six shots would suddenly "match". Per-shot
        // matching must not let that happen: the five shots that still carry
        // a position must still fail to match by position.
        val shots = (0 until 6).map { i ->
            TruthShot(
                scoringRing = i,
                position = if (i == 0) null else SpotPosition(0, 0.1 * i, 0.0)
            )
        }
        val e = CorpusEntry(
            imageName = "t.jpg", image = null, camera = null, capture = null,
            target = null, shotsPerEnd = 6, shots = shots,
            unresolvedArrows = 0, registration = null
        )
        // Offset in y, not x: an x offset of 0.3 landing on a 0.1 grid would
        // put detection i exactly on top of truth i + 3 and accidentally
        // match it, which is not what this test is about.
        val detected = shots.mapIndexed { i, shot ->
            DetectedShotRecord(shot.scoringRing, null, SpotPosition(0, 0.1 * i, 0.3), 0.9)
        }

        val result = ShotMatching.match(e, detected)

        // Only truth 0 (the one with no position) can be found, by score.
        assertThat(result.pairs).hasSize(1)
        assertThat(result.pairs[0].truthIndex).isEqualTo(0)
        assertThat(result.pairs[0].distance).isNull()
        assertThat(result.unmatchedTruth).containsExactly(1, 2, 3, 4, 5).inOrder()
    }

    @Test
    fun anUnmatchedDetectionRecordsItsDistanceToTheNearestUnmatchedTruthOnItsFace() {
        // Truth A is close enough to be matched by position. Truth B is not:
        // no detection comes near enough, so it stays unmatched, and the
        // detection that almost found it is recorded as having missed only on
        // distance -- this is what makes the position error metric honest
        // about a detector that never gets close enough to be admitted at
        // all, rather than being silently bounded by the gate. A third,
        // unrelated detection sits on a DIFFERENT face: the one truth left
        // over (B) belongs to a different spot, so there is no comparable
        // candidate for it, and it must NOT be recorded -- recording it would
        // misattribute a distance across spots that were never comparable in
        // the first place.
        val e = CorpusEntry(
            imageName = "t.jpg", image = null, camera = null, capture = null,
            target = null, shotsPerEnd = 2,
            shots = listOf(
                truth(ring9, 0.0, 0.0),
                truth(ring7, 0.5, 0.0)
            ),
            unresolvedArrows = 0, registration = null
        )
        val detected = listOf(
            found(ring9, 0.01, 0.0),          // matches truth A by position
            found(ring7, 0.6, 0.0),           // 0.1 from truth B, beyond the 0.05 gate
            found(ring7, 0.0, 0.0, face = 1)  // different face: no comparable truth left
        )

        val result = ShotMatching.match(e, detected)

        assertThat(result.pairs).hasSize(1)
        assertThat(result.unmatchedTruth).containsExactly(1)
        assertThat(result.unmatchedDetected).containsExactly(1, 2).inOrder()
        assertThat(result.detectionsRejectedOnDistance).hasSize(1)
        assertThat(result.detectionsRejectedOnDistance[0]).isWithin(1e-9).of(0.1)
    }
}
