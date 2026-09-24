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
import de.dreier.mytargets.detection.arrows.WaFullZones
import de.dreier.mytargets.detection.geometry.CameraIntrinsics
import de.dreier.mytargets.detection.registration.RingTransitions
import org.junit.Test

class DetectionRequestsTest {

    @Test
    fun theWaFullRequestIsTheOneTheCorpusRunsUse() {
        val intrinsics = CameraIntrinsics.approximate(4000, 3000)

        val request = DetectionRequests.waFull(6, intrinsics)

        assertThat(request.layout.faceCount).isEqualTo(1)
        assertThat(request.zoneRadii).isEqualTo(WaFullZones.RADII)
        assertThat(request.transitions).isEqualTo(RingTransitions.WA_FULL)
        assertThat(request.expectedShots).isEqualTo(6)
        assertThat(request.intrinsics).isSameInstanceAs(intrinsics)
    }
}
