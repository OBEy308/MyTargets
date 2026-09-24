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
import kotlin.test.assertFailsWith

class DecodeReductionTest {

    @Test
    fun halvesWhileTheLongEdgeIsAbove4200() {
        val cases = mapOf(
            1280 to 1, 4000 to 1, 4160 to 1, 4200 to 1, 4201 to 2,
            8160 to 2, 8400 to 2, 8401 to 4, 16320 to 4, 40000 to 8
        )
        for ((longEdge, factor) in cases) {
            assertEquals(factor, DecodeReduction.factorFor(longEdge), "long edge $longEdge")
        }
    }

    @Test
    fun noEdgeIsNoImage() {
        // BitmapFactory reports -1 for a file it cannot read; that is PhotoUnreadable, not a factor.
        assertFailsWith<IllegalArgumentException> { DecodeReduction.factorFor(0) }
        assertFailsWith<IllegalArgumentException> { DecodeReduction.factorFor(-1) }
    }
}
