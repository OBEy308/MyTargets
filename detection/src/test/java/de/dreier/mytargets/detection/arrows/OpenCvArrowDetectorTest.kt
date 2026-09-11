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

package de.dreier.mytargets.detection.arrows

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import de.dreier.mytargets.detection.DetectionFailure
import de.dreier.mytargets.detection.DetectionRequest
import de.dreier.mytargets.detection.FaceLayout
import de.dreier.mytargets.detection.SelectionReason
import de.dreier.mytargets.detection.geometry.Vec2
import de.dreier.mytargets.detection.registration.OpenCvRule
import de.dreier.mytargets.detection.registration.RingTransitions
import de.dreier.mytargets.detection.registration.SyntheticFace
import org.junit.Rule
import org.junit.Test
import kotlin.math.hypot

/**
 * The detector against arrows drawn with a real pinhole projection. The entry
 * point is where the arrow was put into the face in space, so the end of the
 * streak that counts follows from the projection and not from an assumption of
 * the test: a detector with the rule of stage 6 reversed would report the far
 * end of each visible shaft and fail here (Haupt-Spec, Stufe 6, correction of
 * 2026-09-09).
 */
class OpenCvArrowDetectorTest {

    @get:Rule
    val openCv = OpenCvRule()

    private val detector = OpenCvArrowDetector()

    /**
     * Entry points off the black ring, where a dark shaft has no contrast, whose
     * shafts stay apart as seen from either foot point: at 45 degrees two pairs
     * lie under the duplicate rule's 6 degrees, but their ends are at least
     * 0.17 from each other's lines, far beyond its 0.025.
     */
    private val entries = listOf(
        Vec2(0.3, 0.35), Vec2(0.1, -0.05), Vec2(-0.3, -0.3), Vec2(0.25, -0.42), Vec2(-0.25, 0.15)
    )

    /** Both cameras stand 2.0 radii above the face. */
    private val at30 = SyntheticCamera(30.0, 650.0, Vec2(1000.0, 750.0))
    private val at45 = SyntheticCamera(45.0, 530.0, Vec2(1000.0, 750.0))

    private fun request(camera: SyntheticCamera, shots: Int) = DetectionRequest(
        FaceLayout.singleSpot(), WaFullZones.RADII, RingTransitions.WA_FULL, shots, camera.intrinsics
    )

    private fun analyse(camera: SyntheticCamera): ArrowAnalysis.Analysed {
        val photo = SyntheticArrows.photograph(camera, 2000, 1500, entries.map { SyntheticArrow(it) })
        val analysis = try {
            detector.analyse(photo, request(camera, entries.size))
        } finally {
            photo.release()
        }
        assertWithMessage("outcome of the registration").that(analysis).isInstanceOf(ArrowAnalysis.Analysed::class.java)
        return analysis as ArrowAnalysis.Analysed
    }

    private fun assertFindsEveryArrow(camera: SyntheticCamera) {
        val photo = SyntheticArrows.photograph(camera, 2000, 1500, entries.map { SyntheticArrow(it) })
        val result = try {
            detector.detect(photo, request(camera, entries.size))
        } finally {
            photo.release()
        }

        assertThat(result.failure).isNull()
        assertThat(result.reason).isEqualTo(SelectionReason.COMPLETE)
        assertThat(result.shots).hasSize(entries.size)
        for (entry in entries) {
            val nearest = result.shots.minOf { hypot(it.x - entry.x, it.y - entry.y) }
            assertWithMessage("entry point of the arrow at $entry").that(nearest).isAtMost(0.01)
        }
    }

    @Test
    fun findsEveryArrowAt30Degrees() = assertFindsEveryArrow(at30)

    @Test
    fun findsEveryArrowAt45Degrees() = assertFindsEveryArrow(at45)

    @Test
    fun theFootPointComesOutWhereTheCameraStands() {
        // The rings barely pin the third row of the homography, which Q hangs on;
        // 0.03 stays inside the search's window of 0.04.
        assertThat(analyse(at30).footPoint!!.distanceTo(at30.footPoint)).isAtMost(0.03)
    }

    @Test
    fun theFaceConfidenceFollowsTheWorstRing() {
        val registration = analyse(at30).registration
        val worst = registration.rings.maxOf { it.radialRms }

        assertThat(OpenCvArrowDetector.faceConfidence(registration))
            .isWithin(1e-12).of((1.0 - worst / 0.012).coerceIn(0.0, 1.0))
        assertThat(OpenCvArrowDetector.faceConfidence(registration)).isGreaterThan(0.0)
    }

    @Test
    fun aBlankPhotographIsNotRegistered() {
        val photo = SyntheticFace.blank(2000, 1500)
        val result = try {
            detector.detect(photo, request(at30, 5))
        } finally {
            photo.release()
        }

        assertThat(result.failure).isEqualTo(DetectionFailure.FACE_NOT_FOUND)
        assertThat(result.shots).isEmpty()
    }
}
