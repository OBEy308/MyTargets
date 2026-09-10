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
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

class SidecarTruthTest {

    private val full = """
        {
          "image": { "file": "a.jpg", "width": 4000, "height": 2252, "exifOrientation": 6 },
          "camera": { "model": "Galaxy S25", "focalLengthMm": 5.4, "focalLength35mm": 23 },
          "capture": { "lighting": "sonne", "angle": "leicht-schraeg", "angleDegrees": null },
          "target": { "model": "WAFull", "diameterCm": 80, "faceCount": 1 },
          "end": { "shotsPerEnd": 6 },
          "shots": [
            { "faceIndex": 0, "x": -0.195, "y": -0.201, "scoringRing": 3, "points": 8,
              "positionTolerance": 0.015 },
            { "faceIndex": 0, "x": 0.028, "y": 0.039, "scoringRing": 0, "points": 10,
              "positionTolerance": 0.01, "nearRingBoundary": true }
          ],
          "annotation": { "unresolvedArrows": 2 },
          "registration": {
            "imageToTarget": [[0.0009, 0.0, -1.03], [0.0, 0.00087, -1.5], [0.00012, -7.1e-05, 1.0]],
            "imagedCentre": [1145.77, 1775.66]
          }
        }
    """.trimIndent()

    @Test
    fun readsEveryPartOfARealSidecar() {
        val e = SidecarTruth.parse("a.jpg", full)

        assertThat(e.imageName).isEqualTo("a.jpg")
        assertThat(e.image!!.width).isEqualTo(4000)
        assertThat(e.image!!.longEdge).isEqualTo(4000)
        assertThat(e.camera!!.focalLength35mm!!).isWithin(1e-9).of(23.0)
        assertThat(e.target!!.model).isEqualTo("WAFull")
        assertThat(e.target!!.faceCount).isEqualTo(1)
        assertThat(e.shotsPerEnd).isEqualTo(6)
        assertThat(e.unresolvedArrows).isEqualTo(2)
        assertThat(e.tags).containsExactly("sonne", "leicht-schraeg")
    }

    @Test
    fun aPrintedScoreIsReadWhenGivenAndAbsentOtherwise() {
        val json = """
            { "shots": [
                { "x": 0.1, "y": 0.2, "scoringRing": 0, "printedScore": "x" },
                { "x": 0.1, "y": 0.2, "scoringRing": 1 }
            ] }
        """.trimIndent()

        val e = SidecarTruth.parse("a.jpg", json)

        assertThat(e.shots[0].printedScore).isEqualTo(PrintedScore.X)
        assertThat(e.shots[1].printedScore).isNull()
    }

    @Test
    fun readsTheShotsWithRingToleranceAndFlags() {
        val e = SidecarTruth.parse("a.jpg", full)

        assertThat(e.expectedShots).isEqualTo(2)
        assertThat(e.shots.all { it.position != null }).isTrue()

        val first = e.shots[0]
        assertThat(first.scoringRing).isEqualTo(3)
        assertThat(first.position!!.x).isWithin(1e-9).of(-0.195)
        assertThat(first.positionTolerance!!).isWithin(1e-9).of(0.015)
        assertThat(first.nearRingBoundary).isFalse()

        assertThat(e.shots[1].scoringRing).isEqualTo(0)
        assertThat(e.shots[1].nearRingBoundary).isTrue()
    }

    @Test
    fun readsTheRegistrationAsNineValuesRowByRow() {
        val r = SidecarTruth.parse("a.jpg", full).registration!!

        assertThat(r.imageToTarget).hasSize(9)
        assertThat(r.imageToTarget[0]).isWithin(1e-12).of(0.0009)
        assertThat(r.imageToTarget[2]).isWithin(1e-12).of(-1.03)
        assertThat(r.imageToTarget[3]).isWithin(1e-12).of(0.0)
        assertThat(r.imageToTarget[6]).isWithin(1e-12).of(0.00012)
        assertThat(r.imageToTarget[8]).isWithin(1e-12).of(1.0)
        // Image pixels, not spot-local coordinates: a type of its own, so it
        // cannot be compared with a hit by accident.
        assertThat(r.imagedCentre).isEqualTo(ImagePoint(1145.77, 1775.66))
    }

    @Test
    fun anEmptyShotListIsARegistrationOnlyEntry() {
        val json = """
            {
              "image": { "file": "r.jpg", "width": 100, "height": 100 },
              "target": { "model": "WAFull", "faceCount": 1 },
              "shots": []
            }
        """.trimIndent()

        val e = SidecarTruth.parse("r.jpg", json)

        assertThat(e.isAnnotated).isFalse()
        assertThat(e.expectedShots).isEqualTo(0)
        assertThat(e.unresolvedArrows).isEqualTo(0)
    }

    @Test
    fun unknownFieldsAreIgnoredSoTheCorpusCanGrow() {
        val json = """
            {
              "image": { "file": "u.jpg", "width": 100, "height": 100, "somethingNew": 7 },
              "target": { "model": "WAFull", "faceCount": 1 },
              "shots": [ { "faceIndex": 0, "x": 0.0, "y": 0.0, "scoringRing": 0 } ],
              "aFieldFromNextYear": { "nested": true }
            }
        """.trimIndent()

        val e = SidecarTruth.parse("u.jpg", json)
        assertThat(e.expectedShots).isEqualTo(1)
    }

    @Test
    fun aShotWithoutAScoringRingIsRejected() {
        val json = """
            {
              "image": { "file": "n.jpg", "width": 100, "height": 100 },
              "target": { "model": "WAFull", "faceCount": 1 },
              "shots": [ { "faceIndex": 0, "x": 0.0, "y": 0.0 } ]
            }
        """.trimIndent()

        val error = runCatching { SidecarTruth.parse("n.jpg", json) }.exceptionOrNull()
        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(error!!).hasMessageThat().contains("n.jpg")
        assertThat(error!!).hasMessageThat().contains("shot 0")
    }

    @Test
    fun aHalfGivenPositionIsRejected() {
        val json = """
            {
              "image": { "file": "h.jpg", "width": 100, "height": 100 },
              "target": { "model": "WAFull", "faceCount": 1 },
              "shots": [ { "faceIndex": 0, "x": 0.1, "scoringRing": 0 } ]
            }
        """.trimIndent()

        val error = runCatching { SidecarTruth.parse("h.jpg", json) }.exceptionOrNull()
        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(error!!).hasMessageThat().contains("h.jpg")
    }

    @Test
    fun aFaceIndexWithoutXAndYIsRejected() {
        val json = """
            {
              "image": { "file": "f.jpg", "width": 100, "height": 100 },
              "target": { "model": "WAFull", "faceCount": 1 },
              "shots": [ { "faceIndex": 1, "scoringRing": 0 } ]
            }
        """.trimIndent()

        val error = runCatching { SidecarTruth.parse("f.jpg", json) }.exceptionOrNull()
        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(error!!).hasMessageThat().contains("f.jpg")
        assertThat(error!!).hasMessageThat().contains("faceIndex")
    }

    @Test
    fun anImagedCentreOfTheWrongSizeNamesTheImage() {
        val json = """
            {
              "image": { "file": "z.jpg", "width": 100, "height": 100 },
              "target": { "model": "WAFull", "faceCount": 1 },
              "shots": [],
              "registration": {
                "imageToTarget": [[1.0, 0.0, 0.0], [0.0, 1.0, 0.0], [0.0, 0.0, 1.0]],
                "imagedCentre": [1.0]
              }
            }
        """.trimIndent()

        val error = runCatching { SidecarTruth.parse("z.jpg", json) }.exceptionOrNull()
        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(error!!).hasMessageThat().contains("z.jpg")
        assertThat(error!!).hasMessageThat().contains("imagedCentre")
    }

    @Test
    fun aDecimalCommaNamesTheImageRatherThanThrowingBare() {
        val json = """
            {
              "image": { "file": "c.jpg", "width": 100, "height": 100 },
              "target": { "model": "WAFull", "faceCount": 1 },
              "shots": [ { "faceIndex": 0, "x": "0,031", "y": 0.1, "scoringRing": 0 } ]
            }
        """.trimIndent()

        val error = runCatching { SidecarTruth.parse("c.jpg", json) }.exceptionOrNull()
        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(error!!).hasMessageThat().contains("c.jpg")
    }

    @Test
    fun malformedJsonNamesTheImage() {
        val error = runCatching { SidecarTruth.parse("b.jpg", "{ not json") }.exceptionOrNull()
        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(error!!).hasMessageThat().contains("b.jpg")
    }

    @Test
    fun aMissingShotsArrayIsRejected() {
        val json = """{ "target": { "model": "WAFull", "faceCount": 1 } }"""
        val error = runCatching { SidecarTruth.parse("m.jpg", json) }.exceptionOrNull()
        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(error!!).hasMessageThat().contains("m.jpg")
    }

    @Test
    fun aRegistrationMatrixOfTheWrongShapeNamesTheImage() {
        val json = """
            {
              "image": { "file": "w.jpg", "width": 100, "height": 100 },
              "target": { "model": "WAFull", "faceCount": 1 },
              "shots": [],
              "registration": { "imageToTarget": [[1.0, 2.0], [3.0, 4.0]] }
            }
        """.trimIndent()

        val error = runCatching { SidecarTruth.parse("w.jpg", json) }.exceptionOrNull()
        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(error!!).hasMessageThat().contains("w.jpg")
    }

    @Test
    fun aValueTheDataTypesRejectNamesTheImage() {
        // The data types check their own invariants, and their messages cannot
        // know which photograph the value came from. One mistyped value in one
        // of twenty sidecars must still say which file it is in.
        val cases = mapOf(
            "zero positionTolerance" to
                """{ "shots": [ { "x": 0.0, "y": 0.0, "scoringRing": 0, "positionTolerance": 0 } ] }""",
            "blank printedScore" to
                """{ "shots": [ { "x": 0.0, "y": 0.0, "scoringRing": 0, "printedScore": "" } ] }""",
            "negative scoringRing" to
                """{ "shots": [ { "x": 0.0, "y": 0.0, "scoringRing": -1 } ] }""",
            "zero width" to
                """{ "image": { "width": 0, "height": 100 }, "shots": [] }""",
            "zero faceCount" to
                """{ "target": { "model": "WAFull", "faceCount": 0 }, "shots": [] }""",
            "negative unresolvedArrows" to
                """{ "shots": [], "annotation": { "unresolvedArrows": -1 } }"""
        )

        for ((case, json) in cases) {
            val error = runCatching { SidecarTruth.parse("t.jpg", json) }.exceptionOrNull()
            assertWithMessage(case).that(error).isInstanceOf(IllegalArgumentException::class.java)
            assertWithMessage(case).that(error!!.message).contains("t.jpg")
        }
    }

    @Test
    fun aTrailingCommaNamesTheImageRatherThanPassingANull() {
        // Gson reads leniently: [a, b,] comes back as [a, b, null]. A hand
        // edited list with a trailing comma must be rejected with the image
        // name, not end in a bare NullPointerException or a null smuggled
        // into the registration.
        val identityRows = "[1.0, 0.0, 0.0], [0.0, 1.0, 0.0], [0.0, 0.0, 1.0]"
        val cases = mapOf(
            "shots" to
                """{ "shots": [ { "x": 0.0, "y": 0.0, "scoringRing": 0 }, ] }""",
            "imageToTarget" to
                """{ "shots": [], "registration": { "imageToTarget": [$identityRows,] } }""",
            "imageToTarget row" to
                """{ "shots": [], "registration": { "imageToTarget": [[1.0, 0.0,], [0.0, 1.0, 0.0], [0.0, 0.0, 1.0]] } }""",
            "imagedCentre" to
                """{ "shots": [], "registration": { "imageToTarget": [$identityRows], "imagedCentre": [1.0,] } }"""
        )

        for ((case, json) in cases) {
            val error = runCatching { SidecarTruth.parse("t.jpg", json) }.exceptionOrNull()
            assertWithMessage(case).that(error).isInstanceOf(IllegalArgumentException::class.java)
            assertWithMessage(case).that(error!!.message).contains("t.jpg")
        }
    }
}
