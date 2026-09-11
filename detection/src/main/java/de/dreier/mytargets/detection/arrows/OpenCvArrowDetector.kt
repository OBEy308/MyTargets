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

import de.dreier.mytargets.detection.ArrowDetector
import de.dreier.mytargets.detection.CandidateSelection
import de.dreier.mytargets.detection.DebugSink
import de.dreier.mytargets.detection.DetectedShot
import de.dreier.mytargets.detection.DetectionRequest
import de.dreier.mytargets.detection.DetectionResult
import de.dreier.mytargets.detection.geometry.FootPoint
import de.dreier.mytargets.detection.registration.FaceRegistrar
import de.dreier.mytargets.detection.registration.FaceWarp
import de.dreier.mytargets.detection.registration.OpenCvFaceRegistrar
import de.dreier.mytargets.detection.registration.RegistrationOutcome
import de.dreier.mytargets.detection.registration.RegistrationRequest
import de.dreier.mytargets.detection.show
import org.opencv.core.Mat

/**
 * The pipeline for a face the registration knows (arrow design): register,
 * rectify, compute the camera's foot point, search the shafts, walk each one to
 * its end, merge and rate the candidates, place them on spots, select. The
 * search frame, the black zones and the ring filter assume a single-spot face
 * (the WAFull faces of v1); other layouts are not supported yet.
 */
class OpenCvArrowDetector(
    private val registrar: FaceRegistrar = OpenCvFaceRegistrar()
) : ArrowDetector {

    fun analyse(image: Mat, request: DetectionRequest, debug: DebugSink = DebugSink.NONE): ArrowAnalysis {
        val started = System.nanoTime()
        val outcome = registrar.register(image, RegistrationRequest(request.layout, request.transitions), debug)
        val registered = System.nanoTime()
        val registration = when (outcome) {
            is RegistrationOutcome.Failed ->
                return ArrowAnalysis.NotRegistered(outcome, StageTimings(millis(started, registered), 0, 0, 0))
            is RegistrationOutcome.Registered -> outcome
        }
        val foot = FootPoint.of(registration.imageToTarget, request.intrinsics)
        val warped = FaceWarp.warp(image, registration.imageToTarget)
        try {
            val face = RectifiedFace.fromWarped(warped, registration.imageToTarget, image.cols(), image.rows())
            val rectified = System.nanoTime()
            val runs = if (foot == null) emptyList() else ShaftSearch.find(face, foot, request.zoneRadii)
            val searched = System.nanoTime()
            val blackZones = BlackZones.of(request.transitions)
            val walks = runs.map { TipWalk.walk(face, it.tip, it.tip - it.far, blackZones) }
            val candidates = if (foot == null) emptyList() else ArrowCandidates.build(runs, walks, foot, request.layout)
            val selection = CandidateSelection.select(
                candidates.mapNotNull { it.located }, request.expectedShots, request.maxArrowsPerSpot
            )
            val walked = System.nanoTime()
            debug.show(ArrowDebugImages.CANDIDATES) { ArrowDebugImages.candidates(warped, foot, candidates) }
            debug.show(ArrowDebugImages.ENTRY_POINTS) {
                ArrowDebugImages.entryPoints(warped, candidates, selection.accepted)
            }
            return ArrowAnalysis.Analysed(
                registration, foot, candidates, selection,
                StageTimings(
                    millis(started, registered), millis(registered, rectified),
                    millis(rectified, searched), millis(searched, walked)
                )
            )
        } finally {
            warped.release()
        }
    }

    override fun detect(image: Mat, request: DetectionRequest, debug: DebugSink): DetectionResult =
        when (val analysis = analyse(image, request, debug)) {
            is ArrowAnalysis.NotRegistered -> DetectionResult.failed(analysis.registration.failure)
            is ArrowAnalysis.Analysed -> DetectionResult(
                shots = analysis.selection.accepted.map {
                    DetectedShot(it.faceIndex, it.local.x.toFloat(), it.local.y.toFloat(), it.confidence.toFloat())
                },
                faceConfidence = faceConfidence(analysis.registration).toFloat(),
                reason = analysis.selection.reason,
                failure = null
            )
        }

    companion object {
        /** Above this radial RMS the registration drops a ring. */
        const val RING_RMS_LIMIT = 0.012

        /**
         * Arrow design, Schnittstelle: 1 minus the worst radial RMS over
         * RING_RMS_LIMIT, clamped to 0..1. A definition, not a calibration.
         */
        fun faceConfidence(registered: RegistrationOutcome.Registered): Double {
            val worst = registered.rings.maxOfOrNull { it.radialRms } ?: return 0.0
            return (1.0 - worst / RING_RMS_LIMIT).coerceIn(0.0, 1.0)
        }

        private fun millis(from: Long, to: Long) = (to - from) / 1_000_000
    }
}
