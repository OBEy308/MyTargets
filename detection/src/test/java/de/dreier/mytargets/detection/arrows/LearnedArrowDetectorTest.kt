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

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import de.dreier.mytargets.detection.DebugSink
import de.dreier.mytargets.detection.DetectionFailure
import de.dreier.mytargets.detection.DetectionRequest
import de.dreier.mytargets.detection.FaceLayout
import de.dreier.mytargets.detection.geometry.Vec2
import de.dreier.mytargets.detection.registration.DebugImages
import de.dreier.mytargets.detection.registration.OpenCvRule
import de.dreier.mytargets.detection.registration.RegistrationOutcome
import de.dreier.mytargets.detection.registration.RingTransitions
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * The learned detector's plumbing on a synthetic photograph: the model of the
 * configured folder runs, the stages report, detect() maps the analysis. What
 * the network makes of a drawn face is not asserted; the parity test checks
 * its numbers on corpus photographs.
 */
class LearnedArrowDetectorTest {

    @get:Rule
    val openCv = OpenCvRule()

    private lateinit var model: ArrowModel

    @Before
    fun requireModel() {
        val configured = System.getProperty("detection.model.dir")
        assumeTrue("DETECTION_MODEL_DIR is not configured", configured != null)
        val dir = File(configured!!)
        assumeTrue("model directory does not exist: $configured", dir.isDirectory)
        model = ArrowModel.load(dir)
    }

    private val camera = SyntheticCamera(30.0, 650.0, Vec2(1000.0, 750.0))
    private val entries = listOf(Vec2(0.3, 0.35), Vec2(0.1, -0.05), Vec2(-0.3, -0.3))

    private fun request(shots: Int) = DetectionRequest(
        FaceLayout.singleSpot(), WaFullZones.RADII, RingTransitions.WA_FULL, shots, camera.intrinsics
    )

    @Test
    fun analysesASyntheticPhotographThroughEveryStage() {
        val detector = LearnedArrowDetector(model)
        val photo = SyntheticArrows.photograph(camera, 2000, 1500, entries.map { SyntheticArrow(it) })
        val analysis = try {
            detector.analyse(photo, request(entries.size))
        } finally {
            photo.release()
        }

        assertWithMessage("outcome of the registration").that(analysis).isInstanceOf(LearnedAnalysis.Analysed::class.java)
        analysis as LearnedAnalysis.Analysed
        assertThat(analysis.peaks.map { it.value }).isInOrder(Comparator.reverseOrder<Double>())
        assertThat(analysis.peaks.all { it.value.toFloat() >= model.meta.threshold.toFloat() }).isTrue()
        assertThat(analysis.kept.size).isAtMost(entries.size)
        assertThat(analysis.kept).containsExactlyElementsIn(analysis.peaks.take(analysis.kept.size)).inOrder()
        assertThat(analysis.onFace.size + analysis.outsideFace.size).isEqualTo(analysis.kept.size)
        assertThat(analysis.accepted.all { it.candidate.local.length <= 1.0 }).isTrue()
        for (peak in analysis.peaks) {
            assertThat(peak.u).isAtLeast(0.0)
            assertThat(peak.u).isAtMost(model.meta.inputSize.toDouble())
        }
        with(analysis.timings) {
            assertThat(registrationMs).isAtLeast(0L)
            assertThat(warpMs).isAtLeast(0L)
            assertThat(networkMs).isGreaterThan(0L)
            assertThat(peaksMs).isAtLeast(0L)
        }
    }

    @Test
    fun detectMapsTheAcceptedFindsAndTheFaceConfidence() {
        val detector = LearnedArrowDetector(model)
        val photo = SyntheticArrows.photograph(camera, 2000, 1500, entries.map { SyntheticArrow(it) })
        val (analysis, result) = try {
            detector.analyse(photo, request(entries.size)) to detector.detect(photo, request(entries.size))
        } finally {
            photo.release()
        }
        analysis as LearnedAnalysis.Analysed

        assertThat(result.failure).isNull()
        assertThat(result.reason).isEqualTo(analysis.reason)
        assertThat(result.shots).hasSize(analysis.accepted.size)
        for ((shot, find) in result.shots.zip(analysis.accepted)) {
            assertThat(shot.faceIndex).isEqualTo(find.candidate.faceIndex)
            assertThat(shot.x).isWithin(1e-6f).of(find.candidate.local.x.toFloat())
            assertThat(shot.y).isWithin(1e-6f).of(find.candidate.local.y.toFloat())
            assertThat(shot.confidence).isWithin(1e-6f).of(find.candidate.confidence.toFloat())
        }
        assertThat(result.faceConfidence)
            .isWithin(1e-6f).of(OpenCvArrowDetector.faceConfidence(analysis.registration).toFloat())
    }

    @Test
    fun aFailedRegistrationFailsTheDetectionLikeTheClassicalFinder() {
        val failing = StubRegistrar(RegistrationOutcome.Failed(DetectionFailure.FACE_NOT_FOUND, "test"))
        val detector = LearnedArrowDetector(model, registrar = failing)
        val photo = SyntheticArrows.photograph(camera, 2000, 1500, emptyList())
        val (analysis, result) = try {
            detector.analyse(photo, request(3)) to detector.detect(photo, request(3))
        } finally {
            photo.release()
        }

        assertThat(analysis).isInstanceOf(LearnedAnalysis.NotRegistered::class.java)
        assertThat(result.failure).isEqualTo(DetectionFailure.FACE_NOT_FOUND)
        assertThat(result.shots).isEmpty()
    }

    @Test
    fun aSidecarWithTheWrongInputSizeIsRejectedByName() {
        // The export is fixed to one size (design 3d, Ablageformat): a 768 model with 512 in the sidecar fails at the forward pass.
        val wrong = ArrowModel(
            model.onnx,
            ArrowModelMeta(512, model.meta.stride, model.meta.kernel, model.meta.preShrinkMaxSide, model.meta.threshold, null, null, null, emptyMap())
        )
        val detector = LearnedArrowDetector(wrong)
        val photo = SyntheticArrows.photograph(camera, 2000, 1500, entries.map { SyntheticArrow(it) })
        try {
            detector.analyse(photo, request(3))
            throw AssertionError("expected an IllegalStateException")
        } catch (e: IllegalStateException) {
            assertThat(e).hasMessageThat().contains("inputSize 512")
        } finally {
            photo.release()
        }
    }

    @Test
    fun anUnreadableModelFailsAtConstruction() {
        val broken = ArrowModel(File("does-not-exist.onnx"), model.meta)
        try {
            LearnedArrowDetector(broken)
            throw AssertionError("expected an IllegalStateException")
        } catch (e: IllegalStateException) {
            assertThat(e).hasMessageThat().contains("does-not-exist.onnx")
        }
    }

    @Test
    fun theDetectorShowsTheHeatmapAndThePeaksOnTheRectifiedInput() {
        val shown = ArrayList<Triple<String, Int, Int>>()
        val photo = SyntheticArrows.photograph(camera, 2000, 1500, entries.map { SyntheticArrow(it) })
        try {
            LearnedArrowDetector(model).analyse(photo, request(entries.size), DebugSink { stage, image ->
                shown += Triple(stage, image.cols(), image.rows())
            })
        } finally {
            photo.release()
        }

        val size = model.meta.inputSize
        assertThat(shown.map { it.first }).containsExactly(
            DebugImages.CLASSES, DebugImages.DISCS, DebugImages.RINGS,
            LearnedDebugImages.HEATMAP, LearnedDebugImages.PEAKS
        ).inOrder()
        assertThat(shown.drop(3).map { it.second to it.third }).containsExactly(size to size, size to size)
    }
}
