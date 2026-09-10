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

class MetricsReportTest {

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
        capture = tags.firstOrNull()?.let {
            CaptureInfo(lighting = it, angle = null, angleDegrees = null)
        },
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

    private fun outcomeWithRings(
        name: String,
        truthRings: List<Int>,
        detectedRings: List<Int>
    ): EntryOutcome {
        val entry = CorpusEntry(
            imageName = name, image = null, camera = null, capture = null,
            target = null, shotsPerEnd = truthRings.size,
            shots = truthRings.mapIndexed { i, r ->
                TruthShot(scoringRing = r, position = SpotPosition(0, 0.1 * i, 0.0))
            },
            unresolvedArrows = 0, registration = null
        )
        val detected = detectedRings.mapIndexed { i, r ->
            DetectedShotRecord(r, null, SpotPosition(0, 0.1 * i, 0.0), 0.9)
        }
        return EntryOutcome(entry, ShotMatching.match(entry, detected), detected)
    }

    @Test
    fun theReportNamesTheFourMetricsAndTheCorpusSize() {
        val e = entry("a.jpg", shots = arrayOf(truth(9, 0.0, 0.0), truth(8, 0.1, 0.0)))
        val report = MetricsReport.render(
            listOf(outcome(e, listOf(found(9, 0.0, 0.0), found(8, 0.1, 0.0)))),
            title = "Baseline"
        )

        assertThat(report).contains("Baseline")
        assertThat(report).contains("Detection rate")
        assertThat(report).contains("False positives")
        assertThat(report).contains("Ring accuracy")
        assertThat(report).contains("Position error")
        assertThat(report).contains("1 photograph")
        assertThat(report).contains("2 listed hits")
        // A perfect run over two comparable hits: the rate and its denominator
        // must appear together, not just as two substrings that could belong
        // to different rows or a different run.
        assertThat(report).contains("Detection rate | 100.0 % | of 2 listed hits")
        assertThat(report).contains("Ring accuracy | 100.0 % | of 2 comparable hits")
    }

    @Test
    fun theHeaderReportsAnnotatedEntriesNotExpectedShots() {
        // One entry with two listed hits plus one registration-only entry
        // with none: annotatedEntries is 1, expectedShots is 2. If the
        // header's "annotated" slot were fed expectedShots instead, it would
        // read "2 annotated" -- a number that happens to already appear
        // elsewhere in this report as the hit count, so a test that only
        // checked for the digit "1" or "2" somewhere in the text would miss
        // the swap. This pins the exact phrase.
        val annotated = entry("a.jpg", shots = arrayOf(truth(9, 0.0, 0.0), truth(8, 0.1, 0.0)))
        val registrationOnly = CorpusEntry(
            imageName = "r.jpg", image = null, camera = null, capture = null,
            target = null, shotsPerEnd = 6, shots = emptyList(),
            unresolvedArrows = 0, registration = null
        )
        val report = MetricsReport.render(
            listOf(
                outcome(annotated, listOf(found(9, 0.0, 0.0), found(8, 0.1, 0.0))),
                outcome(registrationOnly, emptyList())
            ),
            title = "Header"
        )

        assertThat(report).contains("1 annotated")
        assertThat(report).doesNotContain("2 annotated")
    }

    @Test
    fun aTagBreakdownAppearsWhenThereAreTags() {
        val dark = entry("d.jpg", tags = setOf("dark"), shots = arrayOf(truth(9, 0.0, 0.0)))
        val plain = entry("p.jpg", shots = arrayOf(truth(9, 0.0, 0.0)))
        val report = MetricsReport.render(
            listOf(
                outcome(dark, emptyList()),
                outcome(plain, listOf(found(9, 0.0, 0.0)))
            ),
            title = "Baseline"
        )

        assertThat(report).contains("dark")
        assertThat(report).contains("untagged")
        // The two groups must not be interchangeable: "dark" found nothing of
        // its one hit, "untagged" found all of its one hit. A test that only
        // checked the tag names would pass even if the rows were swapped.
        assertThat(report).contains("| dark | 1 | 0.0 % | not measured |")
        assertThat(report).contains("| untagged | 1 | 100.0 % | 100.0 % |")
    }

    @Test
    fun theWorstEntriesAreListedSoTheyCanBeLookedAt() {
        val good = entry("good.jpg", shots = arrayOf(truth(9, 0.0, 0.0)))
        val bad = entry(
            "bad.jpg",
            shots = arrayOf(truth(9, 0.0, 0.0), truth(8, 0.1, 0.0), truth(7, 0.2, 0.0))
        )
        val report = MetricsReport.render(
            listOf(
                outcome(good, listOf(found(9, 0.0, 0.0))),
                outcome(bad, emptyList())
            ),
            title = "Baseline"
        )

        assertThat(report).contains("bad.jpg")
        // "bad.jpg" appearing anywhere is not enough -- its row must show
        // that all three of its hits were missed, not some other count that
        // would still make it sort as the worst entry.
        assertThat(report).contains("| bad.jpg | 3 | 0 | 0 | 0 |")
    }

    @Test
    fun anAbsentPositionErrorIsSaidPlainlyRatherThanShownAsZero() {
        val e = CorpusEntry(
            imageName = "r.jpg", image = null, camera = null, capture = null,
            target = null, shotsPerEnd = 1,
            shots = listOf(TruthShot(scoringRing = 9)),
            unresolvedArrows = 0, registration = null
        )
        val detected = listOf(DetectedShotRecord(9, null, SpotPosition(0, 0.0, 0.0), 0.9))
        val report = MetricsReport.render(
            listOf(outcome(e, detected)),
            title = "Rings only"
        )

        assertThat(report).contains("not measured")
        assertThat(report).doesNotContain("0.0000 spot radii")
        // The denominator for the position error rows is zero placed hits --
        // that must be stated next to "not measured", not left implicit.
        assertThat(report).contains("Position error, median | not measured | of 0 placed hits")
        assertThat(report).contains("Position error, 95th pct | not measured | of 0 placed hits")
    }

    @Test
    fun anEmptyCorpusReportsNotMeasuredRatherThanZeroPercent() {
        // A rate of 0.0 % reads as a perfect run. An empty corpus must say
        // plainly that nothing was measured, so it cannot silently pass a
        // regression bound such as falsePositiveRate <= 0.05.
        val report = MetricsReport.render(emptyList(), title = "Empty")

        assertThat(report).contains("not measured")
        assertThat(report).doesNotContain("0.0 %")
        // Every rate's row must pair "not measured" with its own zero
        // denominator, not with some other metric's count.
        assertThat(report).contains("Detection rate | not measured | of 0 listed hits")
        assertThat(report).contains("False positives | not measured | of 0 listed hits")
        assertThat(report).contains("Ring accuracy | not measured | of 0 comparable hits")
    }

    @Test
    fun theWorstEntriesTableShowsTheWorstAndNotTheBest() {
        // Six entries against a cap of five: the single good one must be the
        // one left out. A two entry corpus cannot tell a correct sort from a
        // reversed one, because both entries fit under the cap either way.
        //
        // The "bad" entries are matched -- positions align -- but scored
        // wrong, so scoreAccuracy is a real, measured 0.0, not null. Nothing
        // was detected here would instead give a null scoreAccuracy (no
        // comparable pair at all), which is a different case entirely --
        // see aRegistrationOnlyEntryDoesNotDisplaceABadlyMeasuredEntry.
        val badShots = arrayOf(truth(9, 0.0, 0.0), truth(8, 0.1, 0.0))
        val wrongScores = listOf(found(2, 0.0, 0.0), found(3, 0.1, 0.0))
        val outcomes = (0 until 5).map { i ->
            outcome(entry("bad$i.jpg", shots = badShots), wrongScores)
        } + outcome(
            entry("perfect.jpg", shots = badShots),
            listOf(found(9, 0.0, 0.0), found(8, 0.1, 0.0))
        )

        val report = MetricsReport.render(outcomes, title = "Worst")

        assertThat(report).contains("bad0.jpg")
        assertThat(report).contains("bad4.jpg")
        assertThat(report).doesNotContain("perfect.jpg")
    }

    @Test
    fun aRegistrationOnlyEntryDoesNotDisplaceABadlyMeasuredEntry() {
        // Five registration-only entries -- scoreAccuracy null, because they
        // have no comparable matched pair at all -- plus one entry that got
        // none of its six rings right (scoreAccuracy 0.0, a real measured
        // result) plus one perfect entry, against a cap of five. Sorting
        // nulls first (the old behaviour) fills the whole table with the
        // five registration-only entries and pushes the badly measured one
        // off entirely, even though it is exactly the kind of entry this
        // table exists to surface. Sorting nulls last keeps it visible.
        val registrationOnly = (0 until 5).map { i ->
            outcome(
                CorpusEntry(
                    imageName = "reg$i.jpg", image = null, camera = null, capture = null,
                    target = null, shotsPerEnd = 6, shots = emptyList(),
                    unresolvedArrows = 0, registration = null
                ),
                emptyList()
            )
        }
        val badShots = (0 until 6).map { i -> truth(1, 0.1 * i, 0.0) }.toTypedArray()
        val bad = outcome(
            entry("bad.jpg", shots = badShots),
            (0 until 6).map { i -> found(2, 0.1 * i, 0.0) }
        )
        val perfect = outcome(
            entry("perfect.jpg", shots = arrayOf(truth(9, 0.0, 0.0))),
            listOf(found(9, 0.0, 0.0))
        )

        val report = MetricsReport.render(
            registrationOnly + listOf(bad, perfect),
            title = "Worst"
        )

        assertThat(report).contains("bad.jpg")
    }

    @Test
    fun numbersAreFormattedIndependentlyOfTheDefaultLocale() {
        // A report that says "50,0 %" on one machine and "50.0 %" on another
        // cannot be diffed between runs, which is most of why it is written to
        // a file at all. Forcing a comma decimal locale here makes this test
        // discriminate on every machine, not only on a German one.
        val original = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            val e = entry("a.jpg", shots = arrayOf(truth(9, 0.0, 0.0), truth(8, 0.1, 0.0)))
            val report = MetricsReport.render(
                listOf(outcome(e, listOf(found(9, 0.0, 0.0)))),
                title = "Locale"
            )
            assertThat(report).contains("50.0 %")
            assertThat(report).doesNotContain("50,0 %")
        } finally {
            java.util.Locale.setDefault(original)
        }
    }

    @Test
    fun eachMetricStatesWhatItRestsOn() {
        val report = MetricsReport.render(
            listOf(outcomeWithRings("a.jpg", listOf(0, 2), listOf(0, 2))),
            title = "Denominators"
        )

        assertThat(report).contains("Detection rate")
        assertThat(report).contains("of 2 listed hits")
        assertThat(report).contains("Ring accuracy")
        assertThat(report).contains("of 2 comparable hits")
    }

    @Test
    fun forgivenEntriesAreCalledOutSoTheFalsePositiveRateIsReadable() {
        // A forgiven entry (f.jpg) sits alongside a strict one (s.jpg) with a
        // genuine fabrication. Under the bounded rule every annotated,
        // in-scope entry is judged, so f.jpg's one listed hit DOES sit in the
        // false positive rate's denominator alongside s.jpg's two -- the
        // rate here is 1 / 3, not 1 / 2.
        val forgiven = CorpusEntry(
            imageName = "f.jpg", image = null, camera = null, capture = null,
            target = null, shotsPerEnd = 6,
            shots = listOf(TruthShot(scoringRing = 0, position = SpotPosition(0, 0.0, 0.0))),
            unresolvedArrows = 2, registration = null
        )
        val forgivenDetected = listOf(
            DetectedShotRecord(0, null, SpotPosition(0, 0.0, 0.0), 0.9),
            DetectedShotRecord(2, null, SpotPosition(0, 0.5, 0.0), 0.9)
        )
        val strict = CorpusEntry(
            imageName = "s.jpg", image = null, camera = null, capture = null,
            target = null, shotsPerEnd = 2,
            shots = listOf(
                TruthShot(scoringRing = 1, position = SpotPosition(0, 0.0, 0.0)),
                TruthShot(scoringRing = 2, position = SpotPosition(0, 0.3, 0.0))
            ),
            unresolvedArrows = 0, registration = null
        )
        val strictDetected = listOf(
            DetectedShotRecord(1, null, SpotPosition(0, 0.0, 0.0), 0.9),
            DetectedShotRecord(2, null, SpotPosition(0, 0.3, 0.0), 0.9),
            DetectedShotRecord(3, null, SpotPosition(0, 0.9, 0.0), 0.9)
        )
        val report = MetricsReport.render(
            listOf(
                EntryOutcome(forgiven, ShotMatching.match(forgiven, forgivenDetected), forgivenDetected),
                EntryOutcome(strict, ShotMatching.match(strict, strictDetected), strictDetected)
            ),
            title = "Forgiven"
        )

        assertThat(report).contains("unresolved arrows")
        // "unresolved arrows" alone would pass even if the callout sentence
        // named the wrong count of entries, or if the false positive rate it
        // is explaining still read as though the forgiven entry's listed hit
        // were part of its denominator. Pin both down.
        assertThat(report).contains(
            "1 entry declare unresolved arrows, so surplus detections there " +
                "are not counted as false positives."
        )
        assertThat(report).contains("False positives | 33.3 % | of 3 listed hits")
    }

    @Test
    fun detectionRateAndRingAccuracyAreNotInterchangeableDenominators() {
        // Three listed truth shots: one matched and score-comparable, one
        // matched but NOT score-comparable (the detection reports a printed
        // score where the truth carries a zone index, so the two cannot be
        // compared), and one missed entirely. That makes expectedShots = 3
        // and scoreComparableShots = 1 genuinely different -- in every other
        // test in this file the two happen to coincide (2 and 2, 1 and 1, 0
        // and 0), so a report that swapped the two denominators between the
        // "Detection rate" and "Ring accuracy" rows would still pass every
        // other assertion in this class. This one catches exactly that swap.
        val entry = CorpusEntry(
            imageName = "mixed.jpg", image = null, camera = null, capture = null,
            target = null, shotsPerEnd = 3,
            shots = listOf(
                TruthShot(scoringRing = 9, position = SpotPosition(0, 0.0, 0.0)),
                TruthShot(scoringRing = 8, position = SpotPosition(0, 0.2, 0.0)),
                TruthShot(scoringRing = 7, position = SpotPosition(0, 0.4, 0.0))
            ),
            unresolvedArrows = 0, registration = null
        )
        val detected = listOf(
            // Matches shot 0 by position, and is score-comparable (both carry
            // a zone index) and correct.
            DetectedShotRecord(9, null, SpotPosition(0, 0.0, 0.0), 0.9),
            // Matches shot 1 by position, but reports a printed score where
            // the truth carries a zone index -- matched, not comparable.
            DetectedShotRecord(null, PrintedScore.of("8"), SpotPosition(0, 0.2, 0.0), 0.9)
            // Nothing detected near shot 2: it is missed entirely.
        )
        val report = MetricsReport.render(
            listOf(EntryOutcome(entry, ShotMatching.match(entry, detected), detected)),
            title = "Mixed"
        )

        // 2 of 3 listed hits matched: detectionRate = 2/3 = 66.7 %.
        assertThat(report).contains("Detection rate | 66.7 % | of 3 listed hits")
        // Only 1 of those 2 matches is score-comparable, and it is correct:
        // scoreAccuracy = 1/1 = 100.0 %.
        assertThat(report).contains("Ring accuracy | 100.0 % | of 1 comparable hits")
    }

    @Test
    fun theReportNamesDetectionsThatMissedOnlyOnDistance() {
        val e = entry("a.jpg", shots = arrayOf(truth(9, 0.0, 0.0)))
        val detected = listOf(found(9, 0.10, 0.0)) // 0.10 from truth, beyond the 0.05 gate
        val report = MetricsReport.render(listOf(outcome(e, detected)), title = "Distance")

        assertThat(report).contains("missed only on distance")
        assertThat(report).contains("1 detection")
        assertThat(report).contains("0.1000 spot radii")
    }

    @Test
    fun theReportOmitsTheMissedOnDistanceLineWhenThereIsNothingToReport() {
        val e = entry("p.jpg", shots = arrayOf(truth(9, 0.0, 0.0)))
        val report = MetricsReport.render(
            listOf(outcome(e, listOf(found(9, 0.0, 0.0)))),
            title = "Perfect"
        )

        assertThat(report).doesNotContain("missed only on distance")
    }

    @Test
    fun theReportNamesHitsExcludedForSittingNearARingBoundary() {
        val e = CorpusEntry(
            imageName = "boundary.jpg", image = null, camera = null, capture = null,
            target = null, shotsPerEnd = 1,
            shots = listOf(
                TruthShot(scoringRing = 5, position = SpotPosition(0, 0.0, 0.0), nearRingBoundary = true)
            ),
            unresolvedArrows = 0, registration = null
        )
        val report = MetricsReport.render(
            listOf(outcome(e, listOf(found(5, 0.0, 0.0)))),
            title = "Boundary"
        )

        assertThat(report).contains("ring boundary")
        assertThat(report).contains("1 hit")
    }

    @Test
    fun theReportOmitsTheRingBoundaryLineWhenThereIsNothingToReport() {
        val report = MetricsReport.render(
            listOf(outcome(entry("p.jpg", shots = arrayOf(truth(9, 0.0, 0.0))), listOf(found(9, 0.0, 0.0)))),
            title = "Perfect"
        )

        assertThat(report).doesNotContain("ring boundary")
    }

    @Test
    fun theReportNamesPositionsExcludedForBeingUncertain() {
        val e = CorpusEntry(
            imageName = "uncertain.jpg", image = null, camera = null, capture = null,
            target = null, shotsPerEnd = 1,
            shots = listOf(
                TruthShot(scoringRing = 2, position = SpotPosition(0, 0.0, 0.0), uncertain = true)
            ),
            unresolvedArrows = 0, registration = null
        )
        val report = MetricsReport.render(
            listOf(outcome(e, listOf(found(2, 0.0, 0.0)))),
            title = "Uncertain"
        )

        assertThat(report).contains("uncertain")
        assertThat(report).contains("1 position")
    }

    @Test
    fun theReportOmitsTheUncertainLineWhenThereIsNothingToReport() {
        val report = MetricsReport.render(
            listOf(outcome(entry("p.jpg", shots = arrayOf(truth(9, 0.0, 0.0))), listOf(found(9, 0.0, 0.0)))),
            title = "Perfect"
        )

        assertThat(report).doesNotContain("uncertain")
    }

    @Test
    fun theReportNamesOutOfScopePhotographsAndWhy() {
        // Change 6: an out-of-scope photograph stays in the corpus but must
        // never move a hit metric silently -- the report has to say which
        // photographs were left out and why, not just quietly compute a
        // smaller corpus.
        val inScope = entry("in.jpg", shots = arrayOf(truth(9, 0.0, 0.0)))
        val outOfScope = CorpusEntry(
            imageName = "out.jpg", image = null, camera = null, capture = null,
            target = null, shotsPerEnd = 3,
            shots = listOf(truth(5, 0.0, 0.0), truth(6, 0.3, 0.0)),
            unresolvedArrows = 0, registration = null,
            outOfScope = "Drei Auflagen nebeneinander."
        )
        val report = MetricsReport.render(
            listOf(
                outcome(inScope, listOf(found(9, 0.0, 0.0))),
                outcome(outOfScope, listOf(found(9, 0.0, 0.0), found(9, 0.3, 0.0)))
            ),
            title = "OutOfScope"
        )

        assertThat(report).contains("out.jpg")
        assertThat(report).contains("Drei Auflagen nebeneinander.")
    }

    @Test
    fun theWorstEntriesTableExcludesOutOfScopePhotographs() {
        // Metrics.over reports zero for everything about an out-of-scope
        // entry -- if it were not filtered out before the sort, it would
        // read as a flawless zero-arrow entry and could even displace a
        // genuinely badly-measured one from the five-row cap.
        val outOfScope = CorpusEntry(
            imageName = "aaa-out.jpg", image = null, camera = null, capture = null,
            target = null, shotsPerEnd = 3,
            shots = listOf(truth(5, 0.0, 0.0), truth(6, 0.3, 0.0)),
            unresolvedArrows = 0, registration = null,
            outOfScope = "not covered by v1"
        )
        val bad = entry(
            "bad.jpg",
            shots = arrayOf(truth(9, 0.0, 0.0), truth(8, 0.1, 0.0), truth(7, 0.2, 0.0))
        )
        val report = MetricsReport.render(
            listOf(
                outcome(outOfScope, listOf(found(9, 0.0, 0.0), found(9, 0.3, 0.0))),
                outcome(bad, emptyList())
            ),
            title = "Worst"
        )

        assertThat(report).contains("| bad.jpg | 3 | 0 | 0 | 0 |")
        assertThat(report).doesNotContain("aaa-out.jpg |")
    }

    @Test
    fun theReportOmitsTheOutOfScopeSectionWhenNothingIsOutOfScope() {
        val e = entry("p.jpg", shots = arrayOf(truth(9, 0.0, 0.0)))
        val report = MetricsReport.render(listOf(outcome(e, listOf(found(9, 0.0, 0.0)))), title = "Perfect")

        assertThat(report).doesNotContain("Out of scope")
    }
}
