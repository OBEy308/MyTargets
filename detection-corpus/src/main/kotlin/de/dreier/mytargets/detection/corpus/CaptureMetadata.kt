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

/**
 * A point in pixels of the EXIF-rotated original, x to the right and y
 * downwards. Not a [SpotPosition]: that one is spot-local, with the outermost
 * ring at radius one, and the two must not be compared by accident.
 */
data class ImagePoint(val x: Double, val y: Double)

/** The photograph itself, as the sidecar records it. */
data class ImageInfo(
    val fileName: String,
    val width: Int,
    val height: Int,
    val exifOrientation: Int?
) {
    init {
        require(width > 0 && height > 0) { "an image has a positive size" }
    }

    /** The long edge, which is what a focal length in 35 mm terms scales against. */
    val longEdge: Int
        get() = maxOf(width, height)

    /**
     * Whether a decoder that applies the EXIF orientation produced [width] x
     * [height]. The sidecar records the raw size of the file; orientations 5
     * to 8 turn the image by 90 degrees and swap the sides.
     */
    fun matchesDecodedSize(width: Int, height: Int): Boolean {
        val swapped = (exifOrientation ?: 1) in 5..8
        return if (swapped) {
            width == this.height && height == this.width
        } else {
            width == this.width && height == this.height
        }
    }
}

/**
 * What is known about the camera. [focalLength35mm] is the value the detection
 * pipeline needs to build its intrinsics; without it a fallback is used.
 */
data class CameraInfo(val model: String?, val focalLength35mm: Double?)

/** The conditions the photograph was taken under, which is how the metrics are
 *  broken down by difficulty. */
data class CaptureInfo(
    val lighting: String?,
    val angle: String?,
    val angleDegrees: Double?
)

/** Which target face is in the photograph. [model] names a class in
 *  `de.dreier.mytargets.shared.targets.models`, for example "WAFull". */
data class TargetInfo(val model: String, val diameterCm: Double?, val faceCount: Int) {
    init {
        require(model.isNotBlank()) { "a target needs a model name" }
        require(faceCount > 0) { "a face has at least one spot" }
    }
}

/**
 * The registration the annotator arrived at, kept so the pipeline's own
 * registration can be checked against it independently of whether it finds any
 * arrows.
 *
 * [imageToTarget] holds the nine values of a 3x3 homography row by row, mapping
 * pixels of the EXIF-rotated original into target coordinates. [imagedCentre] is
 * where the face's centre lies in that image.
 *
 * [cameraPositionFaceUnits] is where register.py put the camera, in target units:
 * x right, y down, z into the face, so the camera sits at z < 0. Its x and y are
 * the camera's foot point on the face (arrow design, Verfahren step 2).
 */
data class Registration(
    val imageToTarget: List<Double>,
    val imagedCentre: ImagePoint?,
    val cameraPositionFaceUnits: List<Double>? = null
) {
    init {
        require(imageToTarget.size == 9) {
            "a homography has nine values, got ${imageToTarget.size}"
        }
    }
}
