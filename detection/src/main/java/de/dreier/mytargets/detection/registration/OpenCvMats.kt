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

import de.dreier.mytargets.detection.geometry.Mat3
import org.opencv.core.CvType
import org.opencv.core.Mat

object OpenCvMats {
    /** A new 3x3 CV_64F matrix holding [m]; the caller releases it. */
    fun of(m: Mat3): Mat {
        val mat = Mat(3, 3, CvType.CV_64F)
        mat.put(0, 0, *DoubleArray(9) { m[it / 3, it % 3] })
        return mat
    }
}
