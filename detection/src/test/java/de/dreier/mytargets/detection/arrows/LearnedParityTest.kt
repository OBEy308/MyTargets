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
import com.google.gson.Gson
import de.dreier.mytargets.detection.DetectionRequest
import de.dreier.mytargets.detection.FaceLayout
import de.dreier.mytargets.detection.corpus.CorpusLoader
import de.dreier.mytargets.detection.geometry.CameraIntrinsics
import de.dreier.mytargets.detection.geometry.Mat3
import de.dreier.mytargets.detection.geometry.Vec2
import de.dreier.mytargets.detection.registration.CorpusPhotos
import de.dreier.mytargets.detection.registration.FaceWarp
import de.dreier.mytargets.detection.registration.OpenCvRule
import de.dreier.mytargets.detection.registration.RegistrationOutcome
import de.dreier.mytargets.detection.registration.RingTransitions
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.opencv.core.Core
import org.opencv.imgcodecs.Imgcodecs
import java.io.File
import kotlin.math.abs
import kotlin.math.hypot

/**
 * The Kotlin path against the PoC (design 3d, Paritaetstest): with the
 * homography of the sidecar fed through a stub registrar, pre-shrink, warp,
 * network and maxima must give the peaks export_onnx.py --peaks wrote for the
 * same model, to 0.5 input pixels and 0.01 in value. The registration itself
 * is measured by the corpus run, not here.
 */
class LearnedParityTest {

    @get:Rule
    val openCv = OpenCvRule()

    private lateinit var root: File
    private lateinit var modelDir: File
    private lateinit var references: List<File>

    /** parity/<view>.json as export_onnx.py writes it. */
    private class Reference {
        var view: String? = null
        var inputSize: Int? = null
        var threshold: Double? = null
        var maxCount: Int? = null
        var imageToTarget: List<List<Double>>? = null
        var imageMeanBgr: List<Double>? = null
        var peaks: List<RefPeak>? = null
    }

    private class RefPeak {
        var u = 0.0
        var v = 0.0
        var value = 0.0
    }

    @Before
    fun requireCorpusAndModel() {
        val corpus = System.getProperty("detection.corpus.dir")
        assumeTrue("DETECTION_CORPUS_DIR is not configured", corpus != null)
        root = File(corpus!!)
        assumeTrue("corpus directory does not exist: $corpus", root.isDirectory)
        val configured = System.getProperty("detection.model.dir")
        assumeTrue("DETECTION_MODEL_DIR is not configured", configured != null)
        modelDir = File(configured!!)
        assumeTrue("model directory does not exist: $configured", modelDir.isDirectory)
        references = File(modelDir, "parity").listFiles { f -> f.extension == "json" }?.sortedBy { it.name }.orEmpty()
        assumeTrue("no parity/*.json in $configured", references.isNotEmpty())
    }

    private fun read(file: File): Reference = Gson().fromJson(file.readText(), Reference::class.java)

    private fun homography(ref: Reference): Mat3 {
        val rows = checkNotNull(ref.imageToTarget) { "${ref.view}: no imageToTarget" }
        return Mat3.of(*rows.flatten().toDoubleArray())
    }

    private fun photograph(view: String): File {
        val entries = CorpusLoader.load(root).entries
        val files = CorpusPhotos.filesByName(root, entries)
        return CorpusPhotos.imageFileOf(root, "$view.jpg", files["$view.jpg"].orEmpty())
    }

    @Test
    fun theRectifiedInputMatchesPreparePy() {
        val model = ArrowModel.load(modelDir)
        for (file in references) {
            val ref = read(file)
            val image = Imgcodecs.imread(photograph(ref.view!!).absolutePath)
            try {
                check(!image.empty()) { "${ref.view}: cannot be decoded" }
                val shrink = PreShrink.of(image.cols(), image.rows(), model.meta.preShrinkMaxSide)
                val small = shrink.shrink(image)
                val warped = try {
                    FaceWarp.warp(small, shrink.imageToTarget(homography(ref)), model.meta.inputSize)
                } finally {
                    small.release()
                }
                try {
                    val mean = Core.mean(warped).`val`
                    val expected = ref.imageMeanBgr!!
                    for (c in 0 until 3) {
                        assertWithMessage("${ref.view}: mean of channel $c (BGR) of the rectified input")
                            .that(mean[c]).isWithin(0.5).of(expected[c])
                    }
                } finally {
                    warped.release()
                }
            } finally {
                image.release()
            }
        }
    }

    @Test
    fun theKotlinPathReproducesThePeaksOfThePoC() {
        val model = ArrowModel.load(modelDir)
        for (file in references) {
            val ref = read(file)
            assertWithMessage("${file.name}: inputSize of the reference").that(ref.inputSize).isEqualTo(model.meta.inputSize)
            assertWithMessage("${file.name}: threshold of the reference").that(ref.threshold).isWithin(1e-9).of(model.meta.threshold)
            val h = homography(ref)
            val stub = StubRegistrar(RegistrationOutcome.Registered(h, Vec2(0.0, 0.0), emptyList()))
            val detector = LearnedArrowDetector(model, registrar = stub)
            val image = Imgcodecs.imread(photograph(ref.view!!).absolutePath)
            val analysis = try {
                val request = DetectionRequest(
                    FaceLayout.singleSpot(), WaFullZones.RADII, RingTransitions.WA_FULL, ref.maxCount!!,
                    CameraIntrinsics.approximate(image.cols(), image.rows())
                )
                detector.analyse(image, request) as LearnedAnalysis.Analysed
            } finally {
                image.release()
            }

            val expected = ref.peaks!!
            assertWithMessage("${ref.view}: number of peaks after threshold and count").that(analysis.kept).hasSize(expected.size)
            val unmatched = analysis.kept.toMutableList()
            for (e in expected) {
                val nearest = unmatched.minByOrNull { hypot(it.u - e.u, it.v - e.v) }
                assertWithMessage("${ref.view}: a peak near (${e.u}, ${e.v}) with value ${e.value}").that(nearest).isNotNull()
                val d = hypot(nearest!!.u - e.u, nearest.v - e.v)
                assertWithMessage("${ref.view}: distance of the peak at (${e.u}, ${e.v})").that(d).isAtMost(0.5)
                assertWithMessage("${ref.view}: value of the peak at (${e.u}, ${e.v})").that(abs(nearest.value - e.value)).isAtMost(0.01)
                unmatched.remove(nearest)
            }
        }
    }
}
