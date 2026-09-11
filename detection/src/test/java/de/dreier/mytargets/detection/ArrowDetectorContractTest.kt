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

package de.dreier.mytargets.detection

import com.google.common.truth.Truth.assertThat
import de.dreier.mytargets.detection.geometry.CameraIntrinsics
import de.dreier.mytargets.detection.geometry.Vec2
import de.dreier.mytargets.detection.registration.OpenCvRule
import de.dreier.mytargets.detection.registration.RingTransitions
import org.junit.Rule
import org.junit.Test
import org.opencv.core.Mat

class ArrowDetectorContractTest {

    // The detector takes a Mat, and even an empty Mat is created natively.
    @get:Rule
    val openCv = OpenCvRule()

    private val request = DetectionRequest(
        layout = FaceLayout.singleSpot(),
        zoneRadii = listOf(0.2, 0.4, 0.6, 0.8, 1.0),
        transitions = RingTransitions.WA_FULL,
        expectedShots = 3,
        intrinsics = CameraIntrinsics.approximate(4000, 3000)
    )

    private val threeSpot = FaceLayout(
        facePositions = listOf(Vec2(-0.52, 0.5), Vec2(0.0, -0.5), Vec2(0.52, 0.5)),
        faceRadius = 0.48
    )

    @Test
    fun capsArrowsPerSpotFromTheEndSize() {
        // Single spot: the cap equals the end size and never bites.
        assertThat(request.maxArrowsPerSpot).isEqualTo(3)
        // Three spots, three arrows: one per spot.
        assertThat(request.copy(layout = threeSpot).maxArrowsPerSpot).isEqualTo(1)
        // Three spots, six arrows: indices 0 and 3 share a spot.
        assertThat(request.copy(layout = threeSpot, expectedShots = 6).maxArrowsPerSpot).isEqualTo(2)
        // Rounds up: four arrows on three spots need two on one of them.
        assertThat(request.copy(layout = threeSpot, expectedShots = 4).maxArrowsPerSpot).isEqualTo(2)
    }

    @Test
    fun failedResultCarriesNoShots() {
        val result = DetectionResult.failed(DetectionFailure.FACE_NOT_FOUND)
        assertThat(result.shots).isEmpty()
        assertThat(result.failure).isEqualTo(DetectionFailure.FACE_NOT_FOUND)
        assertThat(result.faceConfidence).isWithin(1e-9f).of(0f)
    }

    @Test
    fun aDetectorCanBeSubstituted() {
        // The whole point of the interface: plan 3 swaps the implementation and
        // nothing else changes.
        val stub = object : ArrowDetector {
            override fun detect(image: Mat, request: DetectionRequest, debug: DebugSink) = DetectionResult(
                shots = listOf(DetectedShot(0, 0.1f, -0.2f, 0.9f)),
                faceConfidence = 0.8f,
                reason = SelectionReason.FEWER_THAN_EXPECTED,
                failure = null
            )
        }
        val image = Mat()
        val result = try {
            stub.detect(image, request)
        } finally {
            image.release()
        }
        assertThat(result.shots).hasSize(1)
        assertThat(result.shots[0].faceIndex).isEqualTo(0)
        assertThat(result.failure).isNull()
    }

    @Test
    fun requestRejectsAnEmptyZoneList() {
        try {
            request.copy(zoneRadii = emptyList())
            throw AssertionError("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // The radii drive the ring fitting; without them there is nothing to fit.
        }
    }

    @Test
    fun requestRejectsAnEmptyTransitionTable() {
        try {
            request.copy(transitions = emptyList())
            throw AssertionError("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // The registration finds the face from these transitions; without them there is no face.
        }
    }
}
