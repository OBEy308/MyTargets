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

package de.dreier.mytargets.detection

import de.dreier.mytargets.detection.geometry.Vec2

/**
 * How the spots of a target face are arranged, in face coordinates.
 *
 * This deliberately mirrors `TargetModelBase.facePositions` and
 * `TargetModelBase.faceRadius` instead of using that class: those fields are
 * `List<PointF>`, and `PointF` is an Android type whose stub throws in plain
 * JVM unit tests. The conversion lives in the app module, where Android is
 * available anyway.
 */
data class FaceLayout(
    val facePositions: List<Vec2>,
    val faceRadius: Double
) {
    init {
        require(facePositions.isNotEmpty()) { "a face needs at least one spot" }
        require(faceRadius > 0.0) { "face radius must be positive" }
    }

    val faceCount: Int
        get() = facePositions.size

    companion object {
        /** A full face: one spot at the origin filling the whole face. */
        fun singleSpot() = FaceLayout(listOf(Vec2(0.0, 0.0)), 1.0)
    }
}
