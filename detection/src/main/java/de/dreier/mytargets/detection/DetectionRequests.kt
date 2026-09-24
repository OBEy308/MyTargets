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

import de.dreier.mytargets.detection.arrows.WaFullZones
import de.dreier.mytargets.detection.geometry.CameraIntrinsics
import de.dreier.mytargets.detection.registration.RingTransitions

/**
 * The requests the detector is measured with. One place, so the app asks
 * exactly what the corpus runs pin (app integration 8a): only the WA full
 * face, which is the only face with colour transitions and the only one the
 * model has seen.
 */
object DetectionRequests {
    fun waFull(shotsPerEnd: Int, intrinsics: CameraIntrinsics) = DetectionRequest(
        FaceLayout.singleSpot(), WaFullZones.RADII, RingTransitions.WA_FULL, shotsPerEnd, intrinsics
    )
}
