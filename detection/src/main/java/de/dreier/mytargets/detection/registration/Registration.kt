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

import de.dreier.mytargets.detection.DebugSink
import de.dreier.mytargets.detection.DetectionFailure
import de.dreier.mytargets.detection.FaceLayout
import de.dreier.mytargets.detection.geometry.Conic
import de.dreier.mytargets.detection.geometry.Mat3
import de.dreier.mytargets.detection.geometry.Vec2
import org.opencv.core.Mat

/** A colour transition at a known spot-local radius. */
class RingTransition(
    val radius: Double,
    val inside: ColourClass,
    val outside: ColourClass
)

object RingTransitions {
    /**
     * Haupt-Spec, stage 2. The transition at 1.0, white to boss, is left out:
     * on a light boss it is unreliable.
     */
    val WA_FULL = listOf(
        RingTransition(0.2, ColourClass.YELLOW, ColourClass.RED),
        RingTransition(0.4, ColourClass.RED, ColourClass.BLUE),
        RingTransition(0.6, ColourClass.BLUE, ColourClass.BLACK),
        RingTransition(0.8, ColourClass.BLACK, ColourClass.WHITE)
    )
}

class RegistrationRequest(
    val layout: FaceLayout,
    val transitions: List<RingTransition>
)

/** A ring in the final fit. [conic] is in pixels of the original, like everything a registrar returns. */
class RingFit(
    val radius: Double,
    val conic: Conic,
    val points: Int,
    val radialRms: Double
)

sealed interface RegistrationOutcome {
    /**
     * [imageToTarget] maps pixels of the original to spot-local target
     * coordinates, the convention of registration.imageToTarget in the
     * sidecars; [imagedCentre] is the face centre in the same pixels.
     */
    class Registered(
        val imageToTarget: Mat3,
        val imagedCentre: Vec2,
        val rings: List<RingFit>
    ) : RegistrationOutcome

    /** [detail] is for reports and debug images, never for the user. */
    class Failed(
        val failure: DetectionFailure,
        val detail: String
    ) : RegistrationOutcome
}

interface FaceRegistrar {
    /** @param image the original as 8-bit BGR, already turned by its EXIF orientation */
    fun register(
        image: Mat,
        request: RegistrationRequest,
        debug: DebugSink = DebugSink.NONE
    ): RegistrationOutcome
}
