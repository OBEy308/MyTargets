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
import de.dreier.mytargets.detection.registration.OpenCvRule
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.dnn.Dnn
import java.io.File

/**
 * The desktop jar of the JVM tests is OpenCV 4.9, the app ships 4.14 (design
 * 3d, Risiken): this reads the model of the configured folder with 4.9 and
 * runs it once. Skips itself without DETECTION_MODEL_DIR.
 */
class LearnedModelSmokeTest {

    @get:Rule
    val openCv = OpenCvRule()

    private lateinit var dir: File

    @Before
    fun requireModel() {
        val configured = System.getProperty("detection.model.dir")
        assumeTrue("DETECTION_MODEL_DIR is not configured", configured != null)
        dir = File(configured!!)
        assumeTrue("model directory does not exist: $configured", dir.isDirectory)
        assumeTrue("no ${ArrowModel.ONNX_FILE} in $configured", File(dir, ArrowModel.ONNX_FILE).isFile)
        assumeTrue("no ${ArrowModel.META_FILE} in $configured", File(dir, ArrowModel.META_FILE).isFile)
    }

    @Test
    fun theDesktopJarReadsTheModelAndRunsIt() {
        val model = ArrowModel.load(dir)
        val size = model.meta.inputSize
        val net = Dnn.readNetFromONNX(checkNotNull(model.onnx).absolutePath)
        assertThat(net.empty()).isFalse()

        val input = Mat.zeros(size, size, CvType.CV_8UC3)
        val blob = Dnn.blobFromImage(input, 1.0 / 255.0, Size(size.toDouble(), size.toDouble()), Scalar(0.0, 0.0, 0.0), true, false)
        try {
            net.setInput(blob)
            val out = net.forward()
            try {
                assertThat(out.dims()).isEqualTo(4)
                assertThat(listOf(out.size(0), out.size(1), out.size(2), out.size(3)))
                    .containsExactly(1, 2, model.meta.outputSize, model.meta.outputSize).inOrder()
                assertThat(out.type()).isEqualTo(CvType.CV_32F)
            } finally {
                out.release()
            }
        } finally {
            blob.release()
            input.release()
        }
    }
}
