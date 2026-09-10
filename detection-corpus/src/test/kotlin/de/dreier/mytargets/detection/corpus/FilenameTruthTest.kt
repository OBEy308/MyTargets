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

class FilenameTruthTest {

    private fun scoresOf(entry: CorpusEntry) =
        entry.shots.map { it.printedScore!!.text }

    @Test
    fun parsesSixArrowsWithoutATag() {
        val entry = FilenameTruth.parse("a6_998877.jpg")!!
        assertThat(entry.imageName).isEqualTo("a6_998877.jpg")
        assertThat(entry.expectedShots).isEqualTo(6)
        assertThat(scoresOf(entry)).containsExactly("9", "9", "8", "8", "7", "7").inOrder()
        assertThat(entry.tags).isEmpty()
        assertThat(entry.target).isNull()
    }

    @Test
    fun anInheritedEntryCarriesNoRingIndexAndNoMetadata() {
        // The file name gives printed values and no target model, so these
        // entries cannot support ring accuracy against a zone index, and they
        // have no capture conditions to group by.
        val entry = FilenameTruth.parse("a6_998877.jpg")!!

        assertThat(entry.shots.all { it.scoringRing == null }).isTrue()
        assertThat(entry.shots.all { it.printedScore != null }).isTrue()
        assertThat(entry.target).isNull()
        assertThat(entry.image).isNull()
        assertThat(entry.tags).isEmpty()
        assertThat(entry.unresolvedArrows).isEqualTo(0)
    }

    @Test
    fun parsesXRingsAndATag() {
        val entry = FilenameTruth.parse("a6_x99765_noise.jpg")!!
        assertThat(scoresOf(entry)).containsExactly("X", "9", "9", "7", "6", "5").inOrder()
        assertThat(FilenameTruth.tagOf("a6_x99765_noise.jpg")).isEqualTo("noise")
        // Stronger than the brief's own assertion: a real inherited tag must
        // NOT leak into CorpusEntry.tags, which is derived from capture
        // conditions only. Both "no tag present" (see
        // parsesSixArrowsWithoutATag) and "tag present" must land here empty,
        // otherwise the "no tag" case alone can't tell a correct
        // implementation from one that always returns an empty set.
        assertThat(entry.tags).isEmpty()
    }

    @Test
    fun theInheritedMarkerIsAvailableSeparately() {
        assertThat(FilenameTruth.tagOf("a6_x99765_noise.jpg")).isEqualTo("noise")
        assertThat(FilenameTruth.tagOf("a6_998877.jpg")).isNull()
        assertThat(FilenameTruth.tagOf("holiday.jpg")).isNull()
    }

    @Test
    fun parsesEightArrowsAndAMultiWordTag() {
        val entry = FilenameTruth.parse("a8_xxx99988_front.jpg")!!
        assertThat(entry.expectedShots).isEqualTo(8)
        assertThat(scoresOf(entry))
            .containsExactly("X", "X", "X", "9", "9", "9", "8", "8").inOrder()
        assertThat(FilenameTruth.tagOf("a8_xxx99988_front.jpg")).isEqualTo("front")
    }

    @Test
    fun parsesAnUnderscoredTag() {
        val entry = FilenameTruth.parse("a6_x99999_multiple_targets.jpg")!!
        assertThat(entry.expectedShots).isEqualTo(6)
        assertThat(FilenameTruth.tagOf("a6_x99999_multiple_targets.jpg"))
            .isEqualTo("multiple_targets")
    }

    @Test
    fun rejectsAMismatchBetweenTheCountAndTheScores() {
        // Says six arrows, spells five scores.
        assertThat(FilenameTruth.parse("a6_99887.jpg")).isNull()
    }

    @Test
    fun rejectsNamesOutsideTheScheme() {
        assertThat(FilenameTruth.parse("IMG_20260909.jpg")).isNull()
        assertThat(FilenameTruth.parse("a6.jpg")).isNull()
        assertThat(FilenameTruth.parse("b6_998877.jpg")).isNull()
        assertThat(FilenameTruth.parse("a6_9908877.jpg")).isNull()
    }

    @Test
    fun acceptsAnyImageExtensionAndIsCaseInsensitiveOnIt() {
        assertThat(FilenameTruth.parse("a6_998877.JPG")).isNotNull()
        assertThat(FilenameTruth.parse("a6_998877.png")).isNotNull()
    }
}
