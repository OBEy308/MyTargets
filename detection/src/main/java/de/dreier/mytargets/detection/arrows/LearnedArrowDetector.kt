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
import de.dreier.mytargets.detection.DebugSink
import de.dreier.mytargets.detection.DetectedShot
import de.dreier.mytargets.detection.DetectionRequest
import de.dreier.mytargets.detection.DetectionResult
import de.dreier.mytargets.detection.geometry.Mat3
import de.dreier.mytargets.detection.registration.FaceRegistrar
import de.dreier.mytargets.detection.registration.FaceWarp
import de.dreier.mytargets.detection.registration.OpenCvFaceRegistrar
import de.dreier.mytargets.detection.registration.RegistrationOutcome
import de.dreier.mytargets.detection.registration.RegistrationRequest
import de.dreier.mytargets.detection.show
import org.opencv.core.CvException
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.dnn.Dnn
import org.opencv.dnn.Net

/**
 * The learned arrow finder (design 3d): register like the classical finder,
 * shrink and rectify onto the model's input square, run the heatmap network
 * through OpenCV DNN, take the local maxima, place them on spots. No shaft
 * search, no CandidateSelection: the corpus number of the app stays the one
 * of the PoC.
 *
 * The network is read once and lives as long as the instance; its native
 * memory is freed by Net's finalizer, so hold one instance per process, not
 * per photograph. Not thread-safe: one detection at a time.
 */
class LearnedArrowDetector(
    private val model: ArrowModel,
    private val registrar: FaceRegistrar = OpenCvFaceRegistrar(),
    winograd: Boolean = true
) : ArrowDetector {

    private val net: Net = try {
        Dnn.readNetFromONNX(model.onnx.absolutePath)
    } catch (e: CvException) {
        throw IllegalStateException("cannot read ${model.onnx.path}: ${e.message}", e)
    }

    init {
        check(!net.empty()) { "${model.onnx.path}: OpenCV read an empty network" }
        // Winograd trades roughly 300 MB for a 2.3 times faster pass (app-path findings of 2026-09-17).
        net.enableWinograd(winograd)
    }

    fun analyse(image: Mat, request: DetectionRequest, debug: DebugSink = DebugSink.NONE): LearnedAnalysis {
        val started = System.nanoTime()
        val outcome = registrar.register(image, RegistrationRequest(request.layout, request.transitions), debug)
        val registered = System.nanoTime()
        val registration = when (outcome) {
            is RegistrationOutcome.Failed ->
                return LearnedAnalysis.NotRegistered(outcome, LearnedTimings(millis(started, registered), 0, 0, 0))
            is RegistrationOutcome.Registered -> outcome
        }
        val meta = model.meta
        val warped = rectify(image, registration.imageToTarget)
        try {
            val rectified = System.nanoTime()
            val heat = forward(warped)
            val forwarded = System.nanoTime()
            val peaks = HeatmapPeaks.find(heat, meta.stride, meta.kernel, meta.threshold)
            val kept = peaks.take(request.expectedShots)
            val placement = LearnedSelection.place(
                kept, meta.inputSize, request.layout, request.expectedShots, request.maxArrowsPerSpot
            )
            val placed = System.nanoTime()
            debug.show(LearnedDebugImages.HEATMAP) { LearnedDebugImages.heatmap(warped, heat, meta.stride) }
            debug.show(LearnedDebugImages.PEAKS) {
                LearnedDebugImages.peaks(warped, peaks, placement.accepted.map { it.peak }, placement.outsideFace)
            }
            return LearnedAnalysis.Analysed(
                registration, peaks, kept, placement.onFace, placement.outsideFace, placement.accepted, placement.reason,
                LearnedTimings(
                    millis(started, registered), millis(registered, rectified),
                    millis(rectified, forwarded), millis(forwarded, placed)
                )
            )
        } finally {
            warped.release()
        }
    }

    override fun detect(image: Mat, request: DetectionRequest, debug: DebugSink): DetectionResult =
        when (val analysis = analyse(image, request, debug)) {
            is LearnedAnalysis.NotRegistered -> DetectionResult.failed(analysis.registration.failure)
            is LearnedAnalysis.Analysed -> DetectionResult(
                shots = analysis.accepted.map {
                    val c = it.candidate
                    DetectedShot(c.faceIndex, c.local.x.toFloat(), c.local.y.toFloat(), c.confidence.toFloat())
                },
                faceConfidence = OpenCvArrowDetector.faceConfidence(analysis.registration).toFloat(),
                reason = analysis.reason,
                failure = null
            )
        }

    /** Step 2 of the design: shrink like prepare.py, then warp onto the model's input square. */
    private fun rectify(image: Mat, imageToTarget: Mat3): Mat {
        val size = model.meta.inputSize
        val shrink = PreShrink.of(image.cols(), image.rows(), model.meta.preShrinkMaxSide)
        if (shrink.isIdentity) return FaceWarp.warp(image, imageToTarget, size)
        val small = shrink.shrink(image)
        try {
            return FaceWarp.warp(small, shrink.imageToTarget(imageToTarget), size)
        } finally {
            small.release()
        }
    }

    /** Steps 3 and 4: RGB in [0, 1] into the graph, channel 0 of the probabilities out. */
    private fun forward(warped: Mat): Heatmap {
        val size = model.meta.inputSize
        val blob = Dnn.blobFromImage(
            warped, 1.0 / 255.0, Size(size.toDouble(), size.toDouble()), Scalar(0.0, 0.0, 0.0), true, false
        )
        try {
            net.setInput(blob)
            val out = try {
                net.forward()
            } catch (e: CvException) {
                throw IllegalStateException(
                    "${model.onnx.path}: the forward pass failed at inputSize $size; the export is fixed to one " +
                        "input size, check model.json against the export: ${e.message}", e
                )
            }
            try {
                val expected = model.meta.outputSize
                check(
                    out.dims() == 4 && out.size(0) == 1 && out.size(1) >= 1 &&
                        out.size(2) == expected && out.size(3) == expected && out.type() == CvType.CV_32F
                ) {
                    "${model.onnx.path}: expected a float output of 1 x C x $expected x $expected (C >= 1) for " +
                        "inputSize $size and stride ${model.meta.stride}, got ${shapeOf(out)}"
                }
                val height = out.size(2)
                val width = out.size(3)
                // The output is continuous; as one 2D matrix its first height rows are channel 0.
                check(out.isContinuous()) { "${model.onnx.path}: the network output is not continuous, cannot read channel 0" }
                val values = FloatArray(width * height)
                val flat = out.reshape(1, out.size(1) * height)
                try {
                    flat.get(0, 0, values)
                } finally {
                    flat.release()
                }
                return Heatmap(width, height, values)
            } finally {
                out.release()
            }
        } finally {
            blob.release()
        }
    }

    private fun shapeOf(m: Mat) = (0 until m.dims()).joinToString(" x ") { m.size(it).toString() } + " of type ${m.type()}"

    private companion object {
        fun millis(from: Long, to: Long) = (to - from) / 1_000_000
    }
}
