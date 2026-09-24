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
import de.dreier.mytargets.detection.corpus.CorpusLoader
import de.dreier.mytargets.detection.registration.CorpusPhotos
import de.dreier.mytargets.detection.registration.OpenCvRule
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.opencv.imgcodecs.Imgcodecs
import java.io.File
import kotlin.math.hypot

/**
 * The reference the device test (app, EndPhotoScannerDeviceTest) is held
 * against: the learned finder on one corpus photo, decoded with imread like
 * the app, through ArrowCorpusRuns.requestFor like the corpus runs. The same
 * list stands in the device test; if this one moves, both move.
 */
class ScanReferenceRun {

    @get:Rule
    val openCv = OpenCvRule()

    private lateinit var root: File
    private lateinit var modelDir: File

    @Before
    fun requireCorpusAndModel() {
        val corpus = System.getProperty("detection.corpus.dir")
        assumeTrue("DETECTION_CORPUS_DIR is not configured", corpus != null)
        root = File(corpus!!)
        assumeTrue("corpus directory does not exist: $corpus", root.isDirectory)
        val model = System.getProperty("detection.model.dir")
        assumeTrue("no model directory", model != null)
        modelDir = File(model!!)
        assumeTrue("model directory does not exist: $model", modelDir.isDirectory)
    }

    @Test
    fun theLearnedFinderFindsTheReferenceShots() {
        val entries = CorpusLoader.load(root).entries
        val entry = entries.single { it.imageName == "$VIEW.jpg" }
        val files = CorpusPhotos.filesByName(root, entries)
        val file = CorpusPhotos.imageFileOf(root, entry.imageName, files[entry.imageName].orEmpty())
        val image = Imgcodecs.imread(file.absolutePath)
        val result = try {
            check(!image.empty()) { "$VIEW: cannot be decoded" }
            LearnedArrowDetector(ArrowModel.load(modelDir))
                .detect(image, ArrowCorpusRuns.requestFor(entry, image.cols(), image.rows()))
        } finally {
            image.release()
        }

        println("$VIEW: face ${result.face?.imageWidth} x ${result.face?.imageHeight}, shots " +
            result.shots.joinToString { "%.4f to %.4f".format(it.x, it.y) })
        assertThat(result.failure).isNull()
        assertThat(result.face!!.imageWidth).isEqualTo(2252)
        assertThat(result.face!!.imageHeight).isEqualTo(4000)
        assertThat(result.shots).hasSize(REFERENCE.size)
        for ((x, y) in REFERENCE) {
            val nearest = result.shots.minOf { hypot(it.x - x, it.y - y) }
            assertWithMessage("a shot near ($x, $y)").that(nearest).isAtMost(1e-3)
        }
    }

    companion object {
        const val VIEW = "2026-08-15_bedeckt_frontal_02"

        /** Pinned from the first run of this test (plan 8a, Task 6); spot-local x, y. */
        val REFERENCE: List<Pair<Double, Double>> = listOf(
            -0.0383 to -0.0848,
            -0.0046 to 0.0931,
            -0.0340 to 0.0122,
            -0.0742 to -0.0321,
            -0.0860 to 0.0411
        )
    }
}
