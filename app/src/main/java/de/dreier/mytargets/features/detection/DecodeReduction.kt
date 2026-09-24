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
 * How much the decoder shrinks a photo (app integration 8a, Foto lesen):
 * halve while the long edge is above [MAX_LONG_EDGE], at most [MAX_FACTOR]
 * times. Nothing in the pipeline uses more than 2000 px; the bound only has to
 * leave every corpus photo (up to 4160 px) untouched, so the app sees the
 * pixels the pins were measured on.
 */
object DecodeReduction {
    const val MAX_LONG_EDGE = 4200
    const val MAX_FACTOR = 8

    fun factorFor(longEdge: Int): Int {
        require(longEdge > 0) { "an image has a positive size, got a long edge of $longEdge" }
        var factor = 1
        while (longEdge > MAX_LONG_EDGE * factor && factor < MAX_FACTOR) factor *= 2
        return factor
    }
}
