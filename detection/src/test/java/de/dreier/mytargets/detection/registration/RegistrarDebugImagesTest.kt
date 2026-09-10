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

package de.dreier.mytargets.detection.registration

import com.google.common.truth.Truth.assertThat
import de.dreier.mytargets.detection.FaceLayout
import de.dreier.mytargets.detection.geometry.Vec2
import org.junit.Rule
import org.junit.Test
import org.opencv.core.Mat

class RegistrarDebugImagesTest {

    @get:Rule
    val openCv = OpenCvRule()

    private val request = RegistrationRequest(FaceLayout.singleSpot(), RingTransitions.WA_FULL)

    /** Stage name and image width of everything the registrar shows. */
    private fun stagesOf(photo: Mat): List<Pair<String, Int>> {
        val stages = ArrayList<Pair<String, Int>>()
        try {
            OpenCvFaceRegistrar().register(photo, request) { stage, image ->
                stages += stage to image.cols()
            }
        } finally {
            photo.release()
        }
        return stages
    }

    @Test
    fun showsTheFirstThreeStagesInOrderAtTheWorkingSize() {
        val view = SyntheticFace.view(30.0, 800.0, Vec2(1600.0, 1200.0))

        val stages = stagesOf(SyntheticFace.photograph(3200, 2400, view))

        assertThat(stages.map { it.first }).containsExactly(
            DebugImages.CLASSES, DebugImages.DISCS, DebugImages.RINGS
        ).inOrder()
        assertThat(stages.map { it.second }).containsExactly(1600, 1600, 1600)
    }

    @Test
    fun aFailedRegistrationStillShowsWhatItSaw() {
        val stages = stagesOf(SyntheticFace.blank(1600, 1200))

        assertThat(stages.map { it.first })
            .containsExactly(DebugImages.CLASSES, DebugImages.DISCS).inOrder()
    }
}
