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

import android.graphics.BitmapFactory
import android.os.Build
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.dnn.Dnn
import org.opencv.imgproc.Imgproc
import java.io.File

/**
 * Times the learned arrow finder of the PoC on the device it runs on: loads the
 * fp16 ONNX export of run r4 (corpus repo, `learn/export_onnx.py`) with the
 * OpenCV `dnn` module the app ships anyway, runs it on a rectified face from the
 * corpus and reports seconds per forward pass and memory, at 768 and 512 px, on
 * one thread and on all. This is a measurement, not a regression test: the
 * assertions only guard that the model loads and answers in the expected shape.
 *
 * Results go to logcat under the tag [TAG] and to
 * `<external files dir>/learned-finder-timing.txt`. Assets are not in git, see
 * `androidTest/README.md`. Design: `docs/design/2026-09-17-learned-finder-app-path.md`.
 */
@RunWith(AndroidJUnit4::class)
class LearnedFinderTimingTest {

    companion object {
        const val TAG = "LearnedFinderTiming"
        private const val REPEATS = 3

        @BeforeClass
        @JvmStatic
        fun loadOpenCv() {
            check(OpenCVLoader.initLocal()) { "OpenCV native library did not load" }
        }
    }

    private val lines = mutableListOf<String>()

    @Test
    fun timeForwardPassAt768And512() {
        report("device ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}, " +
            "ABI ${Build.SUPPORTED_ABIS.joinToString("/")}, cores ${Runtime.getRuntime().availableProcessors()}, " +
            "OpenCV ${Core.VERSION}")
        for (res in intArrayOf(768, 512)) {
            measure(res)
        }
        writeReport()
    }

    private fun measure(res: Int) {
        val ctx = InstrumentationRegistry.getInstrumentation().context
        val cacheDir = ctx.cacheDir
        val modelName = "model_fold3_${res}_fp16.onnx"
        val modelFile = File(cacheDir, modelName)
        ctx.assets.open(modelName).use { input -> modelFile.outputStream().use { input.copyTo(it) } }

        val blob = inputBlob(res)
        System.gc()
        val pssBefore = pssMb()
        val nativeBefore = Debug.getNativeHeapAllocatedSize() / 1e6

        val tLoad = SystemClock.elapsedRealtime()
        val net = Dnn.readNetFromONNX(modelFile.absolutePath)
        val loadMs = SystemClock.elapsedRealtime() - tLoad
        report("$res px: model ${modelFile.length() / 1e6} MB read in ${loadMs / 1000.0} s")

        for (threads in intArrayOf(1, 0)) {
            Core.setNumThreads(threads)
            val label = if (threads == 0) "all threads" else "1 thread"
            net.setInput(blob)
            val tFirst = SystemClock.elapsedRealtime()
            val out = net.forward()
            val firstMs = SystemClock.elapsedRealtime() - tFirst
            assertThat(out.dims()).isEqualTo(4)
            assertThat(out.size(2)).isEqualTo(res / 2)
            assertThat(out.size(3)).isEqualTo(res / 2)
            val tRep = SystemClock.elapsedRealtime()
            repeat(REPEATS) {
                net.setInput(blob)
                net.forward()
            }
            val perPass = (SystemClock.elapsedRealtime() - tRep) / 1000.0 / REPEATS
            val maxLogit = Core.minMaxLoc(out.reshape(1, 1)).maxVal
            report("$res px, $label: first forward ${firstMs / 1000.0} s, then ${"%.2f".format(perPass)} s per pass, " +
                "output ${out.size(1)}x${out.size(2)}x${out.size(3)}, max logit ${"%.2f".format(maxLogit)}")
            out.release()
        }
        val pssAfter = pssMb()
        val nativeAfter = Debug.getNativeHeapAllocatedSize() / 1e6
        report("$res px: PSS ${"%.0f".format(pssBefore)} -> ${"%.0f".format(pssAfter)} MB (+${"%.0f".format(pssAfter - pssBefore)}), " +
            "native heap ${"%.0f".format(nativeBefore)} -> ${"%.0f".format(nativeAfter)} MB")
        blob.release()
        modelFile.delete()
    }

    /** The corpus face rendered at 768 px, resized to [res], ImageNet-normalised like train.py. */
    private fun inputBlob(res: Int): Mat {
        val ctx = InstrumentationRegistry.getInstrumentation().context
        val bitmap = ctx.assets.open("face_768.png").use { BitmapFactory.decodeStream(it) }
        val rgba = Mat()
        Utils.bitmapToMat(bitmap, rgba)
        val rgb = Mat()
        Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
        val resized = Mat()
        Imgproc.resize(rgb, resized, Size(res.toDouble(), res.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
        // blob = (pixel - mean) * scale. One scale for all channels is close
        // enough for timing (std 0.229/0.224/0.225); the real detector divides per channel.
        val mean = Scalar(0.485 * 255, 0.456 * 255, 0.406 * 255)
        val blob = Dnn.blobFromImage(resized, 1.0 / (255 * 0.226), Size(res.toDouble(), res.toDouble()), mean, false, false)
        rgba.release(); rgb.release(); resized.release()
        return blob
    }

    private fun pssMb(): Double {
        val info = Debug.MemoryInfo()
        Debug.getMemoryInfo(info)
        return info.totalPss / 1024.0
    }

    private fun report(line: String) {
        Log.i(TAG, line)
        lines += line
    }

    private fun writeReport() {
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null) ?: return
        File(dir, "learned-finder-timing.txt").writeText(lines.joinToString("\n") + "\n")
    }
}
