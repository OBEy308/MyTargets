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

package de.dreier.mytargets.detection.corpus

import kotlin.math.hypot

/**
 * A hit in spot local coordinates: the spot's centre is the origin and its
 * outermost ring has radius one, matching what `Shot.x` and `Shot.y` store.
 */
data class SpotPosition(val faceIndex: Int, val x: Double, val y: Double) {

    /** Null when the two positions sit on different spots and are incomparable. */
    fun distanceTo(other: SpotPosition): Double? =
        if (faceIndex != other.faceIndex) null else hypot(x - other.x, y - other.y)
}

/** One arrow of the ground truth, with its position when it is known. */
data class TruthShot(val score: Score, val position: SpotPosition? = null)

/**
 * One photograph and everything known to be true about it.
 *
 * @param targetModel the target model's class name, for example "WAFull".
 *        Null when it has not been recorded; the metrics do not need it, but a
 *        detector does, so an entry without one cannot be run.
 * @param tags free form markers from the file name, such as "dark" or "overlap",
 *        used to break the metrics down by difficulty.
 */
data class CorpusEntry(
    val imageName: String,
    val targetModel: String?,
    val shots: List<TruthShot>,
    val tags: Set<String>
) {
    init {
        require(imageName.isNotBlank()) { "an entry needs an image name" }
        require(shots.isNotEmpty()) { "an entry needs at least one shot" }
    }

    val expectedShots: Int
        get() = shots.size

    /**
     * Whether every shot carries a position. Partial annotation counts as none:
     * otherwise the position error would depend on which arrows happened to be
     * annotated, which is not a property of the detector.
     */
    val hasPositions: Boolean
        get() = shots.all { it.position != null }
}
