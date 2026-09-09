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

package de.dreier.mytargets.detection.corpus

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CorpusEntryTest {

    private fun entry(
        shots: List<TruthShot>,
        unresolved: Int = 0,
        shotsPerEnd: Int = 6,
        lighting: String? = "sonne",
        angle: String? = "leicht-schraeg"
    ) = CorpusEntry(
        imageName = "t.jpg",
        image = ImageInfo("t.jpg", 4000, 2252, 6),
        camera = CameraInfo("Galaxy S25", 23.0),
        capture = CaptureInfo(lighting, angle, null),
        target = TargetInfo("WAFull", 80.0, 1),
        shotsPerEnd = shotsPerEnd,
        shots = shots,
        unresolvedArrows = unresolved,
        registration = null
    )

    private fun ringShot(ring: Int) = TruthShot(scoringRing = ring)

    private fun placedShot(ring: Int, x: Double, y: Double, tolerance: Double? = null) =
        TruthShot(
            scoringRing = ring,
            position = SpotPosition(0, x, y),
            positionTolerance = tolerance
        )

    @Test
    fun printedScoreCharactersMapToPrintedValues() {
        assertThat(PrintedScore.parseFilenameChar('x')).isEqualTo(PrintedScore.X)
        assertThat(PrintedScore.parseFilenameChar('X')).isEqualTo(PrintedScore.X)
        assertThat(PrintedScore.parseFilenameChar('9')).isEqualTo(PrintedScore.of("9"))
        assertThat(PrintedScore.parseFilenameChar('m')).isEqualTo(PrintedScore.MISS)
        assertThat(PrintedScore.parseFilenameChar('0')).isNull()
        assertThat(PrintedScore.parseFilenameChar('!')).isNull()
    }

    @Test
    fun printedScoreNormalisesCaseAndWhitespace() {
        assertThat(PrintedScore.of("x")).isEqualTo(PrintedScore.X)
        assertThat(PrintedScore.of(" 9 ")).isEqualTo(PrintedScore.of("9"))
        assertThat(PrintedScore.MISS.isMiss).isTrue()
        assertThat(PrintedScore.X.isMiss).isFalse()
    }

    @Test
    fun aShotNeedsAtLeastOneWayToBeScored() {
        try {
            TruthShot()
            throw AssertionError("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // A shot with neither a zone index nor a printed value cannot be
            // compared with anything a detector produces.
        }
    }

    @Test
    fun distanceIsOnlyDefinedWithinOneSpot() {
        val a = SpotPosition(0, 0.0, 0.0)
        assertThat(a.distanceTo(SpotPosition(0, 3.0, 4.0))!!).isWithin(1e-9).of(5.0)
        assertThat(a.distanceTo(SpotPosition(1, 3.0, 4.0))).isNull()
    }

    @Test
    fun expectedShotsIsTheListedTruthNotTheEndSize() {
        // The corpus README: the detection rate and the position error refer to
        // the LISTED hits. Four listed of six shot means four expected.
        val e = entry(listOf(ringShot(0), ringShot(2), ringShot(3), ringShot(3)), unresolved = 2)
        assertThat(e.expectedShots).isEqualTo(4)
        assertThat(e.shotsPerEnd).isEqualTo(6)
        assertThat(e.unresolvedArrows).isEqualTo(2)
    }

    @Test
    fun anEmptyShotListIsARegistrationOnlyEntryRatherThanAnError() {
        val e = entry(emptyList())
        assertThat(e.isAnnotated).isFalse()
        assertThat(e.expectedShots).isEqualTo(0)
        assertThat(e.hasPositions).isFalse()
    }

    @Test
    fun anEntryKnowsWhetherEveryShotCarriesAPosition() {
        assertThat(entry(listOf(placedShot(0, 0.1, 0.1), placedShot(2, 0.3, 0.0))).hasPositions)
            .isTrue()
        assertThat(entry(listOf(placedShot(0, 0.1, 0.1), ringShot(2))).hasPositions)
            .isFalse()
        assertThat(entry(listOf(ringShot(0))).hasPositions).isFalse()
    }

    @Test
    fun tagsComeFromTheCaptureConditions() {
        assertThat(entry(listOf(ringShot(0))).tags)
            .containsExactly("sonne", "leicht-schraeg")
        assertThat(entry(listOf(ringShot(0)), lighting = null, angle = null).tags).isEmpty()
    }

    @Test
    fun aRegistrationHoldsNineValuesRowByRow() {
        val r = Registration(
            imageToTarget = listOf(
                0.00089867, -5e-08, -1.02884717,
                -3.186e-05, 0.00086714, -1.50279171,
                0.00011846, -7.073e-05, 1.0
            ),
            imagedCentre = SpotPosition(0, 1145.77, 1775.66)
        )
        assertThat(r.imageToTarget).hasSize(9)
        assertThat(r.imageToTarget[8]).isWithin(1e-12).of(1.0)
    }

    @Test
    fun aRegistrationRejectsAMatrixOfTheWrongSize() {
        try {
            Registration(imageToTarget = listOf(1.0, 2.0, 3.0), imagedCentre = null)
            throw AssertionError("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // A homography has nine values.
        }
    }
}
