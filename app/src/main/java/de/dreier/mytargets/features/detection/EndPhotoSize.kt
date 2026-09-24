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

/**
 * The size of the photo a scan attaches to its end (app integration 8b,
 * EndPhotoStore): long edge exactly [LONG_EDGE] px, smaller photos keep
 * theirs. Pure, so the arithmetic is tested on the JVM.
 */
object EndPhotoSize {
    const val LONG_EDGE = 2048

    /** [width] and [height] in the same lie as the input, i.e. already turned upright. */
    data class Plan(val inSampleSize: Int, val width: Int, val height: Int)

    /**
     * @param width the photo's width after its EXIF orientation is applied
     * @param height its height after the EXIF orientation is applied
     */
    fun plan(width: Int, height: Int): Plan {
        require(width > 0 && height > 0) { "a photo has a positive size, got $width x $height" }
        val longEdge = maxOf(width, height)
        // The largest power of two that still leaves at least LONG_EDGE px to scale down from.
        var sample = 1
        while (longEdge / (sample * 2) >= LONG_EDGE) sample *= 2
        if (longEdge <= LONG_EDGE) return Plan(sample, width, height)
        val scale = LONG_EDGE.toDouble() / longEdge
        return if (width >= height) {
            Plan(sample, LONG_EDGE, Math.round(height * scale).toInt())
        } else {
            Plan(sample, Math.round(width * scale).toInt(), LONG_EDGE)
        }
    }
}
