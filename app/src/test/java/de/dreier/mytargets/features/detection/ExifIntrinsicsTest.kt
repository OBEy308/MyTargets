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

package de.dreier.mytargets.features.detection

import org.junit.Test
import kotlin.test.assertEquals

class ExifIntrinsicsTest {

    @Test
    fun theEquivalentFocalLengthSpansTheLongEdge() {
        // 26 mm on 36 mm film across 4000 px.
        val k = ExifIntrinsics.of(2252, 4000, 26)
        assertEquals(26.0 / 36.0 * 4000.0, k.focalLengthPx, 1e-9)
        assertEquals(1126.0, k.principalPoint.x, 1e-9)
        assertEquals(2000.0, k.principalPoint.y, 1e-9)
    }

    @Test
    fun zeroMeansTheTagIsMissing() {
        // getAttributeInt returns the default 0 without the tag, and some cameras write 0.
        assertEquals(0.75 * 4000.0, ExifIntrinsics.of(4000, 3000, 0).focalLengthPx, 1e-9)
    }

    @Test
    fun aNegativeValueIsTreatedAsMissing() {
        assertEquals(0.75 * 4000.0, ExifIntrinsics.of(4000, 3000, -5).focalLengthPx, 1e-9)
    }
}
