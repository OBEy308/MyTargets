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

class EndPhotoSizeTest {

    @Test
    fun aSmallPhotoKeepsItsSize() =
        assertEquals(EndPhotoSize.Plan(1, 1280, 720), EndPhotoSize.plan(1280, 720))

    @Test
    fun aPhotoOfExactly2048IsLeftAlone() =
        assertEquals(EndPhotoSize.Plan(1, 2048, 1152), EndPhotoSize.plan(2048, 1152))

    @Test
    fun onePixelMoreIsScaledDownWithTheShortEdgeRounded() =
        // 1153 * 2048 / 2049 = 1152.44
        assertEquals(EndPhotoSize.Plan(1, 2048, 1152), EndPhotoSize.plan(2049, 1153))

    @Test
    fun theCorpusPhotoTurnedUprightBecomes1153By2048() =
        // 2252 * 2048 / 4000 = 1153.02; 4000 < 4096, so no subsampling
        assertEquals(EndPhotoSize.Plan(1, 1153, 2048), EndPhotoSize.plan(2252, 4000))

    @Test
    fun subsamplingStartsAt4096() {
        assertEquals(1, EndPhotoSize.plan(4095, 3071).inSampleSize)
        assertEquals(EndPhotoSize.Plan(2, 2048, 1536), EndPhotoSize.plan(4096, 3072))
    }

    @Test
    fun aFiftyMegapixelPhotoIsHalvedOnce() =
        // 8160 / 2 = 4080 >= 2048, 8160 / 4 = 2040 < 2048
        assertEquals(EndPhotoSize.Plan(2, 2048, 1536), EndPhotoSize.plan(8160, 6120))

    @Test
    fun anEmptySizeIsRejected() {
        assertFailsWith<IllegalArgumentException> { EndPhotoSize.plan(0, 100) }
    }
}
