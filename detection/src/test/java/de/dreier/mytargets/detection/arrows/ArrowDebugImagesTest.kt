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
import de.dreier.mytargets.detection.DebugSink
import de.dreier.mytargets.detection.DetectionRequest
import de.dreier.mytargets.detection.FaceLayout
import de.dreier.mytargets.detection.geometry.Vec2
import de.dreier.mytargets.detection.registration.DebugImages
import de.dreier.mytargets.detection.registration.FaceWarp
import de.dreier.mytargets.detection.registration.OpenCvRule
import de.dreier.mytargets.detection.registration.RingTransitions
import org.junit.Rule
import org.junit.Test

class ArrowDebugImagesTest {

    @get:Rule
    val openCv = OpenCvRule()

    @Test
    fun theDetectorShowsTheCandidatesAndTheEntryPointsOnTheRectifiedFace() {
        val camera = SyntheticCamera(30.0, 650.0, Vec2(1000.0, 750.0))
        val photo = SyntheticArrows.photograph(camera, 2000, 1500, listOf(SyntheticArrow(Vec2(0.1, -0.05))))
        val request = DetectionRequest(
            FaceLayout.singleSpot(), WaFullZones.RADII, RingTransitions.WA_FULL, 1, camera.intrinsics
        )
        val shown = ArrayList<Triple<String, Int, Int>>()

        val analysis = try {
            OpenCvArrowDetector().analyse(photo, request, DebugSink { stage, image ->
                shown += Triple(stage, image.cols(), image.rows())
            })
        } finally {
            photo.release()
        }

        val edge = FaceWarp.edgeFor((analysis as ArrowAnalysis.Analysed).registration.imageToTarget)
        assertThat(shown.map { it.first }).containsExactly(
            DebugImages.CLASSES, DebugImages.DISCS, DebugImages.RINGS,
            ArrowDebugImages.CANDIDATES, ArrowDebugImages.ENTRY_POINTS
        ).inOrder()
        assertThat(shown.drop(3).map { it.second to it.third }).containsExactly(edge to edge, edge to edge)
    }
}
