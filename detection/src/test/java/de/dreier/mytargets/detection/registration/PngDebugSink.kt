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

import de.dreier.mytargets.detection.DebugSink
import org.opencv.core.Mat
import org.opencv.imgcodecs.Imgcodecs
import java.io.File

/** Writes each stage image as <stage>.png into [folder], synchronously as DebugSink requires. */
class PngDebugSink(private val folder: File) : DebugSink {

    override fun image(stage: String, image: Mat) {
        val file = File(folder, "$stage.png")
        check(Imgcodecs.imwrite(file.absolutePath, image)) { "cannot write $file" }
    }
}
