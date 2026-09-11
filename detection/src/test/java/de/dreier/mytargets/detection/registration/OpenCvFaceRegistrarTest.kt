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
import de.dreier.mytargets.detection.DetectionFailure
import de.dreier.mytargets.detection.FaceLayout
import de.dreier.mytargets.detection.geometry.Mat3
import de.dreier.mytargets.detection.geometry.Vec2
import de.dreier.mytargets.detection.metrics.RegistrationError
import org.junit.Rule
import org.junit.Test
import org.opencv.core.CvType
import org.opencv.core.Mat

class OpenCvFaceRegistrarTest {

    @get:Rule
    val openCv = OpenCvRule()

    private val registrar = OpenCvFaceRegistrar()
    private val request = RegistrationRequest(FaceLayout.singleSpot(), RingTransitions.WA_FULL)

    private fun register(photo: Mat): RegistrationOutcome =
        try {
            registrar.register(photo, request)
        } finally {
            photo.release()
        }

    /** Largest registration error against the exact truth, in spot radii, as the corpus run measures it. */
    private fun maxError(targetToPhoto: Mat3, outcome: RegistrationOutcome, width: Int, height: Int): Double {
        assertWithMessage("outcome of the registration")
            .that(outcome).isInstanceOf(RegistrationOutcome.Registered::class.java)
        val registered = outcome as RegistrationOutcome.Registered
        return RegistrationError.between(
            SyntheticFace.values(targetToPhoto.inverse()!!),
            SyntheticFace.values(registered.imageToTarget),
            width, height
        )!!.max
    }

    private fun errorSeenAt(angleDegrees: Double): Double {
        val view = SyntheticFace.view(angleDegrees, 400.0, Vec2(800.0, 600.0))
        return maxError(view, register(SyntheticFace.photograph(1600, 1200, view)), 1600, 1200)
    }

    @Test
    fun registersAFaceHeadOn() {
        assertThat(errorSeenAt(0.0)).isAtMost(0.005)
    }

    @Test
    fun registersAFaceSeenAt30Degrees() {
        assertThat(errorSeenAt(30.0)).isAtMost(0.005)
    }

    @Test
    fun registersAFaceSeenAt45Degrees() {
        assertThat(errorSeenAt(45.0)).isAtMost(0.005)
    }

    @Test
    fun threeFacesAreAMismatch() {
        val outcome = register(SyntheticFace.threeFaces())

        assertThat(outcome).isInstanceOf(RegistrationOutcome.Failed::class.java)
        outcome as RegistrationOutcome.Failed
        assertThat(outcome.failure).isEqualTo(DetectionFailure.FACE_MISMATCH)
        assertThat(outcome.detail).contains("3 yellow discs")
    }

    @Test
    fun aPhotographWithoutAFaceIsFaceNotFound() {
        val outcome = register(SyntheticFace.blank(1600, 1200))

        assertThat(outcome).isInstanceOf(RegistrationOutcome.Failed::class.java)
        assertThat((outcome as RegistrationOutcome.Failed).failure)
            .isEqualTo(DetectionFailure.FACE_NOT_FOUND)
    }

    @Test(expected = IllegalArgumentException::class)
    fun anImageThatIsNotEightBitBgrIsAProgrammingError() {
        register(Mat(100, 100, CvType.CV_8UC1))
    }

    @Test
    fun centreAndRingConicsComeBackInPixelsOfTheOriginal() {
        // The original is twice the working size, so anything left in working
        // pixels would be off by a factor of two.
        val view = SyntheticFace.view(0.0, 800.0, Vec2(1600.0, 1200.0))

        val outcome = register(SyntheticFace.photograph(3200, 2400, view))

        assertThat(outcome).isInstanceOf(RegistrationOutcome.Registered::class.java)
        outcome as RegistrationOutcome.Registered
        assertThat(outcome.imagedCentre.distanceTo(Vec2(1600.0, 1200.0))).isLessThan(1.0)
        val ring = outcome.rings.first { it.radius == 0.4 }
        assertThat(RobustConic.sampsonDistance(ring.conic, Vec2(1600.0 + 0.4 * 800.0, 1200.0)))
            .isLessThan(1.0)
    }
}
