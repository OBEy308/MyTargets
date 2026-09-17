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

import org.opencv.core.Mat

object LearnedDebugImages {
    const val HEATMAP = "5-heatmap"
    const val PEAKS = "6-spitzen"

    fun heatmap(warped: Mat, heat: Heatmap, stride: Int): Mat = warped.clone()

    fun peaks(warped: Mat, peaks: List<Peak>, accepted: List<Peak>, outsideFace: List<Peak>): Mat = warped.clone()
}
