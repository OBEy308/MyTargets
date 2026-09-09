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

class SidecarTruthTest {

    @Test
    fun parsesAFullyAnnotatedEntry() {
        val json = """
            {
              "targetModel": "WAFull",
              "tags": ["oblique", "sun"],
              "shots": [
                { "score": "X", "faceIndex": 0, "x": 0.031, "y": -0.017 },
                { "score": "9", "faceIndex": 0, "x": -0.142, "y": 0.088 }
              ]
            }
        """.trimIndent()

        val entry = SidecarTruth.parse("shot.jpg", json)

        assertThat(entry.imageName).isEqualTo("shot.jpg")
        assertThat(entry.targetModel).isEqualTo("WAFull")
        assertThat(entry.tags).containsExactly("oblique", "sun")
        assertThat(entry.hasPositions).isTrue()
        assertThat(entry.shots[0].score.text).isEqualTo("X")
        assertThat(entry.shots[0].position!!.x).isWithin(1e-9).of(0.031)
        assertThat(entry.shots[1].position!!.y).isWithin(1e-9).of(0.088)
    }

    @Test
    fun parsesAnEntryWithScoresOnly() {
        val json = """{ "shots": [ { "score": "9" }, { "score": "M" } ] }"""
        val entry = SidecarTruth.parse("rings.jpg", json)

        assertThat(entry.hasPositions).isFalse()
        assertThat(entry.targetModel).isNull()
        assertThat(entry.tags).isEmpty()
        assertThat(entry.shots[1].score.isMiss).isTrue()
    }

    @Test
    fun defaultsFaceIndexToZeroWhenOnlyCoordinatesAreGiven() {
        val json = """{ "shots": [ { "score": "9", "x": 0.2, "y": 0.1 } ] }"""
        val entry = SidecarTruth.parse("single.jpg", json)
        assertThat(entry.shots[0].position!!.faceIndex).isEqualTo(0)
    }

    @Test
    fun rejectsAHalfGivenPosition() {
        val json = """{ "shots": [ { "score": "9", "x": 0.2 } ] }"""
        val error = runCatching { SidecarTruth.parse("bad.jpg", json) }.exceptionOrNull()
        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(error!!).hasMessageThat().contains("bad.jpg")
        assertThat(error!!).hasMessageThat().contains("x")
    }

    @Test
    fun rejectsAnEmptyShotList() {
        val error = runCatching {
            SidecarTruth.parse("empty.jpg", """{ "shots": [] }""")
        }.exceptionOrNull()
        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(error!!).hasMessageThat().contains("empty.jpg")
    }

    @Test
    fun rejectsAMissingScore() {
        val error = runCatching {
            SidecarTruth.parse("noscore.jpg", """{ "shots": [ { "x": 0.1, "y": 0.1 } ] }""")
        }.exceptionOrNull()
        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(error!!).hasMessageThat().contains("score")
    }

    @Test
    fun rejectsMalformedJson() {
        val error = runCatching { SidecarTruth.parse("broken.jpg", "{ not json") }
            .exceptionOrNull()
        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(error!!).hasMessageThat().contains("broken.jpg")
    }

    @Test
    fun readsTheDirectoryDefault() {
        assertThat(SidecarTruth.defaultsTargetModel("""{ "targetModel": "WA6Ring" }"""))
            .isEqualTo("WA6Ring")
        assertThat(SidecarTruth.defaultsTargetModel("{}")).isNull()
    }

    @Test
    fun rejectsABlankScore() {
        val json = """{ "shots": [ { "score": "", "x": 0.1, "y": 0.1 } ] }"""
        val error = runCatching { SidecarTruth.parse("blank.jpg", json) }.exceptionOrNull()
        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(error!!).hasMessageThat().contains("blank.jpg")
        assertThat(error!!).hasMessageThat().contains("shot 0")
    }

    @Test
    fun rejectsAPositionGivenAsYWithoutX() {
        val json = """{ "shots": [ { "score": "9", "y": 0.2 } ] }"""
        val error = runCatching { SidecarTruth.parse("bad.jpg", json) }.exceptionOrNull()
        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(error!!).hasMessageThat().contains("bad.jpg")
    }

    @Test
    fun rejectsAFaceIndexWithoutCoordinates() {
        // A face index alone says which spot but not where on it, which would
        // otherwise become a silent position at the spot centre.
        val json = """{ "shots": [ { "score": "9", "faceIndex": 1 } ] }"""
        val error = runCatching { SidecarTruth.parse("bad.jpg", json) }.exceptionOrNull()
        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(error!!).hasMessageThat().contains("bad.jpg")
    }
}
