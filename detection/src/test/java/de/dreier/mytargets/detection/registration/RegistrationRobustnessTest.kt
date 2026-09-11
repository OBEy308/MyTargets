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
import com.google.common.truth.Truth.assertWithMessage
import de.dreier.mytargets.detection.FaceLayout
import de.dreier.mytargets.detection.geometry.Mat3
import de.dreier.mytargets.detection.geometry.Vec2
import de.dreier.mytargets.detection.metrics.RegistrationError
import org.junit.Rule
import org.junit.Test
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import kotlin.math.cos
import kotlin.math.sin

/**
 * The harder cases of the registration design, each against exact truth. A
 * failure here is reported, not tuned away: the thresholds are register.py's
 * and are adjusted against the corpus report.
 */
class RegistrationRobustnessTest {

    @get:Rule
    val openCv = OpenCvRule()

    private val registrar = OpenCvFaceRegistrar()
    private val request = RegistrationRequest(FaceLayout.singleSpot(), RingTransitions.WA_FULL)
    private val tilted = SyntheticFace.view(30.0, 400.0, Vec2(800.0, 600.0))

    private fun maxError(targetToPhoto: Mat3, photo: Mat): Double {
        val outcome = try {
            registrar.register(photo, request)
        } finally {
            photo.release()
        }
        assertWithMessage("outcome of the registration")
            .that(outcome).isInstanceOf(RegistrationOutcome.Registered::class.java)
        return RegistrationError.between(
            SyntheticFace.values(targetToPhoto.inverse()!!),
            SyntheticFace.values((outcome as RegistrationOutcome.Registered).imageToTarget),
            1600, 1200
        )!!.max
    }

    @Test
    fun aCroppedFaceRegistersFromItsInnerTwoTransitions() {
        // 1700 px per spot radius: ring 0.6 (1020 px) lies wholly outside the
        // 1600 x 1200 photograph, ring 0.4 (680 px) is cut at top and bottom.
        val view = SyntheticFace.view(0.0, 1700.0, Vec2(800.0, 600.0))

        assertThat(maxError(view, SyntheticFace.photograph(1600, 1200, view))).isAtMost(0.005)
    }

    @Test
    fun aDarkPhotographRegisters() {
        val photo = SyntheticFace.photograph(1600, 1200, tilted)
        Core.multiply(photo, Scalar(0.35, 0.35, 0.35), photo)

        assertThat(maxError(tilted, photo)).isAtMost(0.005)
    }

    @Test
    fun aBlueColourCastRegisters() {
        val photo = SyntheticFace.photograph(1600, 1200, tilted)
        Core.add(photo, Scalar(40.0, 0.0, -20.0), photo)

        assertThat(maxError(tilted, photo)).isAtMost(0.005)
    }

    @Test
    fun shaftsAcrossTheRingsDoNotPullTheFit() {
        val photo = SyntheticFace.photograph(1600, 1200, tilted)
        // Six dark shafts, slanted rather than radial, from near the centre to
        // the black ring -- across every transition up to 0.8.
        for (k in 0 until 6) {
            val a = 0.3 + k * 1.0
            val from = tilted.mapPoint(Vec2(0.1 * cos(a), 0.1 * sin(a)))!!
            val to = tilted.mapPoint(Vec2(0.9 * cos(a + 0.3), 0.9 * sin(a + 0.3)))!!
            Imgproc.line(photo, Point(from.x, from.y), Point(to.x, to.y), Scalar(25.0, 25.0, 25.0), 5)
        }

        assertThat(maxError(tilted, photo)).isAtMost(0.005)
    }
}
