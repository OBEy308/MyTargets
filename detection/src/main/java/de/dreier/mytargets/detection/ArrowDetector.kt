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

import de.dreier.mytargets.detection.geometry.CameraIntrinsics

/**
 * One detected arrow, in the coordinates `Shot` stores: spot local, centre at
 * the origin, outermost ring at radius one.
 */
data class DetectedShot(
    val faceIndex: Int,
    val x: Float,
    val y: Float,
    val confidence: Float
)

enum class DetectionFailure {
    /** No target face could be located in the photo. */
    FACE_NOT_FOUND,

    /** A face was found, but its spot count does not match the configured target. */
    FACE_MISMATCH
}

class DetectionResult(
    val shots: List<DetectedShot>,
    val faceConfidence: Float,
    val reason: SelectionReason?,
    val failure: DetectionFailure?
) {
    companion object {
        fun failed(failure: DetectionFailure) = DetectionResult(
            shots = emptyList(),
            faceConfidence = 0f,
            reason = null,
            failure = failure
        )
    }
}

/**
 * Everything the detector needs that is not the image itself.
 *
 * Deliberately free of Android types so the contract can be exercised in plain
 * JVM tests. The image is supplied by the platform specific sub interface that
 * plan 3 adds.
 */
data class DetectionRequest(
    val layout: FaceLayout,
    val zoneRadii: List<Double>,
    val expectedShots: Int,
    val intrinsics: CameraIntrinsics
) {
    init {
        require(zoneRadii.isNotEmpty()) { "at least one zone radius is required" }
        require(zoneRadii.all { it > 0.0 }) { "zone radii must be positive" }
        require(expectedShots > 0) { "an end has at least one shot" }
    }

    /**
     * How many arrows one spot can hold in this end. The app derives the spot
     * of a shot from `index % faceCount`, so with six shots on three spots the
     * indices 0 and 3 share spot 0. Rounded up: four shots on three spots put
     * two on one of them.
     */
    val maxArrowsPerSpot: Int
        get() = (expectedShots + layout.faceCount - 1) / layout.faceCount
}

/**
 * Finds arrows in a photograph of a target face.
 *
 * The interface is narrow on purpose: it is the seam along which the classical
 * pipeline can later be replaced by a learned detector without touching the
 * app.
 */
interface ArrowDetector {
    fun detect(request: DetectionRequest): DetectionResult
}
