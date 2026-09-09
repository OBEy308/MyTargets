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

    @Test
    fun scoreCharactersMapToPrintedValues() {
        assertThat(Score.parseFilenameChar('x')).isEqualTo(Score.X)
        assertThat(Score.parseFilenameChar('X')).isEqualTo(Score.X)
        assertThat(Score.parseFilenameChar('9')).isEqualTo(Score.of("9"))
        assertThat(Score.parseFilenameChar('1')).isEqualTo(Score.of("1"))
        assertThat(Score.parseFilenameChar('m')).isEqualTo(Score.MISS)
    }

    @Test
    fun zeroAndUnknownCharactersAreRejected() {
        // '0' would be ambiguous: a miss is written 'm', and a plain ten has no
        // character in the inherited scheme at all.
        assertThat(Score.parseFilenameChar('0')).isNull()
        assertThat(Score.parseFilenameChar('!')).isNull()
    }

    @Test
    fun missIsRecognised() {
        assertThat(Score.MISS.isMiss).isTrue()
        assertThat(Score.X.isMiss).isFalse()
        assertThat(Score.of("7").isMiss).isFalse()
    }

    @Test
    fun distanceIsOnlyDefinedWithinOneSpot() {
        val a = SpotPosition(faceIndex = 0, x = 0.0, y = 0.0)
        val b = SpotPosition(faceIndex = 0, x = 3.0, y = 4.0)
        val other = SpotPosition(faceIndex = 1, x = 3.0, y = 4.0)

        assertThat(a.distanceTo(b)!!).isWithin(1e-9).of(5.0)
        assertThat(a.distanceTo(other)).isNull()
    }

    @Test
    fun anEntryKnowsWhetherItCarriesPositions() {
        val ringsOnly = CorpusEntry(
            imageName = "a6_998877.jpg",
            targetModel = null,
            shots = listOf(TruthShot(Score.of("9")), TruthShot(Score.of("8"))),
            tags = emptySet()
        )
        assertThat(ringsOnly.hasPositions).isFalse()
        assertThat(ringsOnly.expectedShots).isEqualTo(2)

        val annotated = ringsOnly.copy(
            shots = listOf(
                TruthShot(Score.of("9"), SpotPosition(0, 0.1, 0.1)),
                TruthShot(Score.of("8"), SpotPosition(0, 0.3, 0.0))
            )
        )
        assertThat(annotated.hasPositions).isTrue()
    }

    @Test
    fun anEntryWithSomePositionsCountsAsRingsOnly() {
        // A half annotated entry would make the position error depend on which
        // arrows happened to be annotated. Either all of them or none.
        val half = CorpusEntry(
            imageName = "half.jpg",
            targetModel = null,
            shots = listOf(
                TruthShot(Score.of("9"), SpotPosition(0, 0.1, 0.1)),
                TruthShot(Score.of("8"))
            ),
            tags = emptySet()
        )
        assertThat(half.hasPositions).isFalse()
    }
}
