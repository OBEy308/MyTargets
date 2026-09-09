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
import de.dreier.mytargets.detection.corpus.Score
import de.dreier.mytargets.detection.corpus.SpotPosition
import de.dreier.mytargets.detection.corpus.TruthShot
import org.junit.Test

class MetricsReportTest {

    private fun outcome(
        name: String,
        tags: Set<String>,
        truthScores: List<String>,
        detectedScores: List<String>
    ): EntryOutcome {
        val entry = CorpusEntry(
            name, "WAFull",
            truthScores.mapIndexed { i, s ->
                TruthShot(Score.of(s), SpotPosition(0, 0.1 * i, 0.0))
            },
            tags
        )
        val detected = detectedScores.mapIndexed { i, s ->
            DetectedShotRecord(Score.of(s), SpotPosition(0, 0.1 * i, 0.0), 0.9)
        }
        return EntryOutcome(entry, ShotMatching.match(entry, detected), detected)
    }

    @Test
    fun theReportNamesTheFourMetricsAndTheCorpusSize() {
        val report = MetricsReport.render(
            listOf(outcome("a.jpg", emptySet(), listOf("9", "8"), listOf("9", "8"))),
            title = "Baseline"
        )

        assertThat(report).contains("Baseline")
        assertThat(report).contains("Detection rate")
        assertThat(report).contains("False positives")
        assertThat(report).contains("Ring accuracy")
        assertThat(report).contains("Position error")
        assertThat(report).contains("1 photograph")
        assertThat(report).contains("2 arrows")
    }

    @Test
    fun aTagBreakdownAppearsWhenThereAreTags() {
        val report = MetricsReport.render(
            listOf(
                outcome("d.jpg", setOf("dark"), listOf("9"), emptyList()),
                outcome("p.jpg", emptySet(), listOf("9"), listOf("9"))
            ),
            title = "Baseline"
        )

        assertThat(report).contains("dark")
        assertThat(report).contains("untagged")
    }

    @Test
    fun theWorstEntriesAreListedSoTheyCanBeLookedAt() {
        val report = MetricsReport.render(
            listOf(
                outcome("good.jpg", emptySet(), listOf("9"), listOf("9")),
                outcome("bad.jpg", emptySet(), listOf("9", "8", "7"), emptyList())
            ),
            title = "Baseline"
        )

        assertThat(report).contains("bad.jpg")
    }

    @Test
    fun anAbsentPositionErrorIsSaidPlainlyRatherThanShownAsZero() {
        val entry = CorpusEntry(
            "r.jpg", "WAFull", listOf(TruthShot(Score.of("9"))), emptySet()
        )
        val detected = listOf(
            DetectedShotRecord(Score.of("9"), SpotPosition(0, 0.0, 0.0), 0.9)
        )
        val report = MetricsReport.render(
            listOf(EntryOutcome(entry, ShotMatching.match(entry, detected), detected)),
            title = "Rings only"
        )

        assertThat(report).contains("not measured")
        assertThat(report).doesNotContain("0.0000 spot radii")
    }

    @Test
    fun theWorstEntriesTableShowsTheWorstAndNotTheBest() {
        // Six entries against a cap of five: the single good one must be the
        // one left out. A two entry corpus cannot tell a correct sort from a
        // reversed one, because both entries fit under the cap either way.
        val outcomes = (0 until 5).map { i ->
            outcome("bad$i.jpg", emptySet(), listOf("9", "8"), emptyList())
        } + outcome("perfect.jpg", emptySet(), listOf("9", "8"), listOf("9", "8"))

        val report = MetricsReport.render(outcomes, title = "Worst")

        assertThat(report).contains("bad0.jpg")
        assertThat(report).contains("bad4.jpg")
        assertThat(report).doesNotContain("perfect.jpg")
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
            val report = MetricsReport.render(
                listOf(outcome("a.jpg", emptySet(), listOf("9", "8"), listOf("9"))),
                title = "Locale"
            )
            assertThat(report).contains("50.0 %")
            assertThat(report).doesNotContain("50,0 %")
        } finally {
            java.util.Locale.setDefault(original)
        }
    }
}
