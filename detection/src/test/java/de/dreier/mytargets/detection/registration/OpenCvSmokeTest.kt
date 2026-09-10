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
import org.junit.Rule
import org.junit.Test
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat

class OpenCvSmokeTest {

    @get:Rule
    val openCv = OpenCvRule()

    @Test
    fun theDesktopLibraryIsVersion49() {
        // The main code compiles against 4.9 and the app ships 4.14; this pins
        // which side of that gap the tests run on.
        assertThat(Core.VERSION).startsWith("4.9")
    }

    @Test
    fun aMatRoundTripsThroughOneBulkByteArray() {
        val mat = Mat(2, 3, CvType.CV_8UC3)
        try {
            val data = ByteArray(2 * 3 * 3) { it.toByte() }
            mat.put(0, 0, data)
            val back = ByteArray(data.size)
            mat.get(0, 0, back)
            assertThat(back.toList()).isEqualTo(data.toList())
        } finally {
            mat.release()
        }
    }
}
