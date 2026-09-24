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

package de.dreier.mytargets.features.detection

import android.app.ActivityManager
import android.content.Context
import de.dreier.mytargets.detection.ArrowDetector
import de.dreier.mytargets.detection.arrows.ArrowModel
import de.dreier.mytargets.detection.arrows.LearnedArrowDetector
import org.opencv.android.OpenCVLoader
import timber.log.Timber
import java.io.InputStream

class LoadedDetector(val detector: ArrowDetector, val modelName: String)

/**
 * Builds the one detector of the process: OpenCV's native library, the model
 * read from the asset into memory (never copied to disk), Winograd by the
 * device's memory. Throws on any failure; DetectorHolder turns that into
 * ModelUnavailable and retries next time.
 */
object ModelLoading {
    const val MODEL_NAME = "r4-all-2026-09"
    private const val MODEL_DIR = "arrows/$MODEL_NAME"

    fun load(context: Context): LoadedDetector {
        check(OpenCVLoader.initLocal()) { "OpenCV's native library did not load" }
        val assets = context.assets
        // open(), not openFd(): openFd fails on an asset stored compressed.
        val onnx = assets.open("$MODEL_DIR/${ArrowModel.ONNX_FILE}").use { readAllBytes(it) }
        val meta = assets.open("$MODEL_DIR/${ArrowModel.META_FILE}").use { readAllBytes(it).toString(Charsets.UTF_8) }
        val model = ArrowModel.fromBytes(onnx, meta, "assets/$MODEL_DIR")

        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memory = ActivityManager.MemoryInfo().also { activityManager.getMemoryInfo(it) }
        val winograd = WinogradPolicy.enabled(activityManager.isLowRamDevice, memory.totalMem)
        Timber.i("arrow model %s, winograd %s (total memory %d MB)", MODEL_NAME, winograd, memory.totalMem shr 20)

        return LoadedDetector(LearnedArrowDetector(model, winograd = winograd), MODEL_NAME)
    }

    /**
     * Reads [stream] fully into a freshly, exactly sized array. An asset's `available()` is the
     * whole remaining uncompressed length, not a bounded guess, so this avoids `readBytes()`'s
     * doubling `ByteArrayOutputStream` and its final copy, which would otherwise briefly double
     * the largest asset (the ~29 MB weights) in the Java heap.
     */
    private fun readAllBytes(stream: InputStream): ByteArray {
        val size = stream.available()
        val bytes = ByteArray(size)
        var read = 0
        while (read < size) {
            val n = stream.read(bytes, read, size - read)
            check(n >= 0) { "asset ended after $read of $size bytes" }
            read += n
        }
        check(stream.read() == -1) { "asset has more than the advertised $size bytes" }
        return bytes
    }
}
