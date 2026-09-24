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

import de.dreier.mytargets.shared.targets.models.WAField
import de.dreier.mytargets.shared.targets.models.WAFull
import de.dreier.mytargets.shared.targets.models.WAVertical3Spot
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScanSupportTest {
    @Test
    fun theWaFullFaceIsSupported() = assertTrue(ScanSupport.supports(WAFull.ID))

    @Test
    fun otherFacesAreNot() {
        assertFalse(ScanSupport.supports(WAVertical3Spot.ID))
        assertFalse(ScanSupport.supports(WAField.ID))
    }
}
