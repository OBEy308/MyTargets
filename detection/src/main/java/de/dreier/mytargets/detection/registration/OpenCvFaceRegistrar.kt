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

import de.dreier.mytargets.detection.DetectionFailure
import de.dreier.mytargets.detection.geometry.Mat3
import de.dreier.mytargets.detection.geometry.Orientation
import de.dreier.mytargets.detection.geometry.Rectification
import de.dreier.mytargets.detection.geometry.Vec2
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.util.Locale

/**
 * Stages 1 and 2 of the Haupt-Spec with the method of register.py: shrink to
 * the working size, classify the colours, find and count the yellow discs,
 * find the rings, rectify from two of them, refine over all, orient, and map
 * back to pixels of the original.
 */
class OpenCvFaceRegistrar : FaceRegistrar {

    override fun register(
        image: Mat,
        request: RegistrationRequest,
        debug: DebugSink
    ): RegistrationOutcome {
        require(!image.empty()) { "the image is empty" }
        require(image.type() == CvType.CV_8UC3) {
            "the image must be 8-bit BGR, got type ${image.type()}"
        }
        val scale = WorkingScale(image.cols(), image.rows())
        val small = Mat()
        try {
            if (scale.isOriginal) {
                image.copyTo(small)
            } else {
                Imgproc.resize(
                    image, small, Size(scale.width.toDouble(), scale.height.toDouble()),
                    0.0, 0.0, Imgproc.INTER_AREA
                )
            }
            return registerWorkingImage(small, scale, request)
        } finally {
            small.release()
        }
    }

    private fun registerWorkingImage(
        small: Mat,
        scale: WorkingScale,
        request: RegistrationRequest
    ): RegistrationOutcome {
        val hsv = HsvPixels.fromBgr(small)
        var classes = ColourClassifier.classify(hsv)
        var discs = YellowDiscs.find(classes, scale.f)

        // Second pass, with thresholds taken from the disc itself.
        discs.counted.maxByOrNull { it.radius }?.let { first ->
            ColourClassifier.discReference(hsv, classes, first.centre, first.radius)?.let { reference ->
                classes = ColourClassifier.classify(hsv, reference)
                discs = YellowDiscs.find(classes, scale.f)
            }
        }
        if (discs.counted.isEmpty()) {
            return failed(DetectionFailure.FACE_NOT_FOUND, "no yellow disc with red around it")
        }
        if (discs.counted.size != request.layout.faceCount) {
            return failed(
                DetectionFailure.FACE_MISMATCH,
                "${discs.counted.size} yellow discs, expected ${request.layout.faceCount}"
            )
        }

        val disc = discs.counted.maxBy { it.radius }
        val rings = RingSearch.find(classes, disc, request.transitions, scale.f)
        val accepted = rings.filter { it.accepted }
        if (accepted.size < 2) {
            return failed(
                DetectionFailure.FACE_NOT_FOUND,
                "${accepted.size} usable rings: " +
                    rings.joinToString { "${radius(it.transition.radius)} ${it.rejection ?: "ok"}" }
            )
        }

        val start = startingHomography(accepted)
        val rectified = start.result ?: return failed(
            DetectionFailure.FACE_NOT_FOUND,
            "no starting homography: " + start.rejections.joinToString("; ")
        )

        val refined = HomographyRefinement.refineDroppingRings(
            rectified.imageToTarget,
            accepted.map { RingPoints(it.transition.radius, it.fit!!.inliers) }
        )
        val oriented = Orientation.orient(refined.homography, rectified.imagedCentre)
            ?: return failed(DetectionFailure.FACE_NOT_FOUND, "the refined homography cannot be oriented")

        // Back to the original: H_orig = H_small * S; conics follow the point
        // mapping small -> original, which is S^-1.
        val s = scale.smallFromOriginal
        val originalFromSmall = requireNotNull(s.inverse()) { "the working scale is singular" }
        val imageToTarget = normalised(oriented * s)
        val imagedCentre = imageToTarget.inverse()?.mapPoint(Vec2(0.0, 0.0))
            ?: return failed(DetectionFailure.FACE_NOT_FOUND, "the face centre maps to infinity")
        val fits = refined.rings.map { ring ->
            val attempt = accepted.first { it.transition.radius == ring.radius }
            RingFit(
                radius = ring.radius,
                conic = attempt.fit!!.conic.transformedBy(originalFromSmall),
                points = ring.points.size,
                radialRms = HomographyRefinement.radialRms(oriented, ring)
            )
        }
        return RegistrationOutcome.Registered(imageToTarget, imagedCentre, fits)
    }

    private class Start(val result: Rectification.Result?, val rejections: List<String>)

    /**
     * register.py's starting pair: the two best-supported rings whose radius
     * ratio is at most 0.75; when Rectification rejects a pair, the next.
     * Every rejection is kept for the detail.
     */
    private fun startingHomography(accepted: List<RingAttempt>): Start {
        val order = accepted.sortedByDescending { it.fit!!.inliers.size }
        val rejections = ArrayList<String>()
        for (i in order.indices) {
            for (j in i + 1 until order.size) {
                val (inner, outer) = listOf(order[i], order[j]).sortedBy { it.transition.radius }
                if (inner.transition.radius / outer.transition.radius > MAX_PAIR_RATIO) continue
                val attempt = Rectification.attempt(
                    outer.fit!!.conic, outer.transition.radius,
                    inner.fit!!.conic, inner.transition.radius
                )
                when (attempt) {
                    is Rectification.Attempt.Rectified -> return Start(attempt.result, rejections)
                    is Rectification.Attempt.Rejected -> rejections +=
                        "pair ${radius(inner.transition.radius)}/${radius(outer.transition.radius)}: " +
                        attempt.describe()
                }
            }
        }
        if (rejections.isEmpty()) rejections += "no two rings with a radius ratio of at most $MAX_PAIR_RATIO"
        return Start(null, rejections)
    }

    private fun failed(failure: DetectionFailure, detail: String) =
        RegistrationOutcome.Failed(failure, detail)

    private fun normalised(m: Mat3) = m.scaled(1.0 / m[2, 2])

    private fun radius(r: Double) = String.format(Locale.ROOT, "%.1f", r)

    private companion object {
        const val MAX_PAIR_RATIO = 0.75
    }
}
