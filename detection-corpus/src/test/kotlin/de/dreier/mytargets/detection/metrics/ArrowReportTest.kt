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
import de.dreier.mytargets.detection.corpus.SpotPosition
import de.dreier.mytargets.detection.corpus.TruthShot
import org.junit.Test

class ArrowReportTest {

    private fun entry(name: String, angle: String?, vararg shots: TruthShot) = CorpusEntry(
        imageName = name,
        image = null,
        camera = null,
        capture = CaptureInfo(lighting = "bedeckt", angle = angle, angleDegrees = null),
        target = null,
        shotsPerEnd = 6,
        shots = shots.toList(),
        unresolvedArrows = 0,
        registration = null
    )

    private fun truth(x: Double, y: Double) =
        TruthShot(scoringRing = 3, position = SpotPosition(0, x, y), positionTolerance = 0.01)

    private fun found(x: Double, y: Double) = DetectedShotRecord(3, null, SpotPosition(0, x, y), 1.0)

    private fun outcome(entry: CorpusEntry, vararg detected: DetectedShotRecord) =
        EntryOutcome(entry, ShotMatching.match(entry, detected.toList()), detected.toList())

    private fun row(
        name: String,
        group: String,
        listed: Int,
        matched: Int,
        outcome: String = ArrowRow.REGISTERED,
        angleDegrees: Double? = null
    ) = ArrowRow(
        imageName = name,
        group = group,
        angleDegrees = angleDegrees,
        outcome = outcome,
        detail = if (outcome == ArrowRow.REGISTERED) null else "no yellow disc",
        footPointFromCentre = 1.2,
        registrationError = 0.001,
        footPointShift = 0.002,
        largestLineOffset = 0.01,
        commonPointFromFoot = null,
        listed = listed,
        unresolved = 0,
        candidates = 6,
        accepted = matched,
        matched = matched,
        falsePositives = 0,
        selection = "COMPLETE",
        largestPositionError = 0.004,
        noShaft = 0,
        ranOut = 0,
        millis = StageMillis(900, 60, 700, 80)
    )

    private fun render(
        rows: List<ArrowRow>,
        truths: List<TruthDiagnosis> = emptyList(),
        outcomes: List<EntryOutcome> = emptyList(),
        outOfScope: List<Pair<String, String>> = emptyList()
    ) = ArrowReport.render("Run", rows, truths, emptyList(), outcomes, outOfScope)

    @Test
    fun theGroupOfAPhotographFollowsItsAngle() {
        assertThat(ArrowGroups.of(entry("a", "stark-schraeg"))).isEqualTo(ArrowGroups.OBLIQUE)
        assertThat(ArrowGroups.of(entry("b", "leicht-schraeg"))).isEqualTo(ArrowGroups.OBLIQUE)
        assertThat(ArrowGroups.of(entry("c", "frontal"))).isEqualTo(ArrowGroups.FRONTAL)
        assertThat(ArrowGroups.of(entry("d", null))).isEqualTo(ArrowGroups.UNKNOWN)
    }

    @Test
    fun groupsAreSummedSeparately() {
        val oblique = entry("o.jpg", "leicht-schraeg", truth(0.1, 0.1), truth(0.3, 0.0))
        val frontal = entry("f.jpg", "frontal", truth(0.2, 0.2))

        val report = render(
            listOf(row("o.jpg", ArrowGroups.OBLIQUE, 2, 1), row("f.jpg", ArrowGroups.FRONTAL, 1, 0)),
            outcomes = listOf(outcome(oblique, found(0.1, 0.1)), outcome(frontal))
        )

        assertThat(report).contains("| schraeg | 1 | 2 | 1 | 50.0 % | 0 |")
        assertThat(report).contains("| frontal | 1 | 1 | 0 | 0.0 % | 0 |")
    }

    @Test
    fun anUnregisteredPhotographComesFirstThenTheOneMissingMost() {
        val report = render(
            listOf(
                row("fine.jpg", ArrowGroups.OBLIQUE, 6, 6, angleDegrees = 35.0),
                row("poor.jpg", ArrowGroups.OBLIQUE, 6, 2),
                row("lost.jpg", ArrowGroups.OBLIQUE, 6, 0, outcome = "FACE_NOT_FOUND")
            )
        )

        assertThat(report.indexOf("| lost.jpg |")).isLessThan(report.indexOf("| poor.jpg |"))
        assertThat(report.indexOf("| poor.jpg |")).isLessThan(report.indexOf("| fine.jpg |"))
        assertThat(report).contains("| lost.jpg | FACE_NOT_FOUND: no yellow disc |")
        assertThat(report).contains("| Photograph | Outcome | Angle (deg) | Q from centre |")
        assertThat(report).contains("| fine.jpg | registered | 35 | 1.2000 |")
    }

    @Test
    fun theDiagnosisSeparatesFindingFromSelecting() {
        val truths = listOf(
            TruthDiagnosis("o.jpg", ArrowGroups.OBLIQUE, 0, rank = 1, confidence = 1.0, lineOffset = 0.01, accepted = true),
            TruthDiagnosis("o.jpg", ArrowGroups.OBLIQUE, 1, rank = 4, confidence = 0.4, lineOffset = -0.035, accepted = false),
            TruthDiagnosis("o.jpg", ArrowGroups.OBLIQUE, 2, rank = null, confidence = null, lineOffset = null, accepted = false)
        )

        val report = render(listOf(row("o.jpg", ArrowGroups.OBLIQUE, 3, 1)), truths = truths)

        assertThat(report).contains(
            "**schraeg:** 2 of 3 listed hits had a candidate within the budget (66.7 %); " +
                "the selection lost 1 of them. Line offsets from Q of those candidates: " +
                "median 0.0225, above 0.03: 1 of 2."
        )
        assertThat(report).contains("| o.jpg | 1 | 4 | 0.4000 | -0.0350 | no |")
    }

    @Test
    fun theDiagnosisCountsFalseCandidatesOnRealShaftsApart() {
        val truths = listOf(
            TruthDiagnosis("o.jpg", ArrowGroups.OBLIQUE, 0, rank = 1, confidence = 1.0, lineOffset = 0.01, accepted = true)
        )
        val photos = listOf(
            PhotoDiagnosis("o.jpg", ArrowGroups.OBLIQUE, 0.8, falseOnShafts = 3, falseElsewhere = 2),
            PhotoDiagnosis("p.jpg", ArrowGroups.OBLIQUE, 0.5, falseOnShafts = 1, falseElsewhere = 0)
        )

        val report = ArrowReport.render(
            "Run", listOf(row("o.jpg", ArrowGroups.OBLIQUE, 1, 1)), truths, photos, emptyList(), emptyList()
        )

        assertThat(report).contains("Candidates matching no hit: 4 on the shaft of a listed hit, 2 elsewhere.")
    }

    @Test
    fun photographsOutOfScopeAreOnlyListed() {
        val report = render(emptyList(), outOfScope = listOf("a6_x.jpg" to "three faces"))

        assertThat(report).contains("| a6_x.jpg | three faces |")
    }
}
