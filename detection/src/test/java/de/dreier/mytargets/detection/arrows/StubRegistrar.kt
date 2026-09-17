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

import de.dreier.mytargets.detection.DebugSink
import de.dreier.mytargets.detection.registration.FaceRegistrar
import de.dreier.mytargets.detection.registration.RegistrationOutcome
import de.dreier.mytargets.detection.registration.RegistrationRequest
import org.opencv.core.Mat

/** Answers every photograph with the same outcome: a failure, or the homography a sidecar carries. */
class StubRegistrar(private val outcome: RegistrationOutcome) : FaceRegistrar {
    override fun register(image: Mat, request: RegistrationRequest, debug: DebugSink): RegistrationOutcome = outcome
}
