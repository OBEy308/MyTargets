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

import de.dreier.mytargets.detection.geometry.CameraIntrinsics

/**
 * The camera matrix from EXIF's FocalLengthIn35mmFilm for an image of the
 * decoded size. A value of 0 or less means the tag is missing
 * (ExifInterface's default) and falls back to 0.75 of the long edge.
 */
object ExifIntrinsics {
    fun of(width: Int, height: Int, focalLength35mm: Int): CameraIntrinsics =
        if (focalLength35mm > 0) {
            CameraIntrinsics.from35mmEquivalent(width, height, focalLength35mm.toDouble())
        } else {
            CameraIntrinsics.approximate(width, height)
        }
}
