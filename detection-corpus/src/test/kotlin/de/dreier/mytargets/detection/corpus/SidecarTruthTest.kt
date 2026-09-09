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
    fun readsTheShotsWithRingToleranceAndFlags() {
        val e = SidecarTruth.parse("a.jpg", full)

        assertThat(e.expectedShots).isEqualTo(2)
        assertThat(e.hasPositions).isTrue()

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
        assertThat(r.imageToTarget[3]).isWithin(1e-12).of(0.0)
        assertThat(r.imageToTarget[8]).isWithin(1e-12).of(1.0)
        assertThat(r.imagedCentre!!.x).isWithin(1e-6).of(1145.77)
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
}
