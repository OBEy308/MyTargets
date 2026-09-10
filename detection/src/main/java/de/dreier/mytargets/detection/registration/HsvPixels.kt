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

import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc

/**
 * The working image in HSV, fetched from OpenCV in one call. OpenCV's 8-bit HSV
 * halves the hue to fit a byte; [hue] gives it back in degrees, [saturation]
 * and [value] in 0..1 -- the definitions of register.py's rgb_to_hsv.
 */
class HsvPixels(val width: Int, val height: Int, private val data: ByteArray) {

    init {
        require(data.size == width * height * 3) { "expected ${width * height * 3} bytes" }
    }

    val size: Int
        get() = width * height

    fun hue(i: Int): Double = (data[3 * i].toInt() and 0xFF) * 2.0

    fun saturation(i: Int): Double = (data[3 * i + 1].toInt() and 0xFF) / 255.0

    fun value(i: Int): Double = (data[3 * i + 2].toInt() and 0xFF) / 255.0

    /** The value below which [fraction] of all pixels lie, from a 256-bin histogram. */
    fun valuePercentile(fraction: Double): Double {
        val counts = IntArray(256)
        for (i in 0 until size) {
            counts[data[3 * i + 2].toInt() and 0xFF]++
        }
        val target = fraction * size
        var cumulative = 0
        for (bin in 0 until 256) {
            cumulative += counts[bin]
            if (cumulative >= target) return bin / 255.0
        }
        return 1.0
    }

    companion object {
        fun fromBgr(bgr: Mat): HsvPixels {
            require(bgr.type() == CvType.CV_8UC3) { "expected 8-bit BGR, got type ${bgr.type()}" }
            val hsv = Mat()
            try {
                Imgproc.cvtColor(bgr, hsv, Imgproc.COLOR_BGR2HSV)
                val data = ByteArray(hsv.rows() * hsv.cols() * 3)
                hsv.get(0, 0, data)
                return HsvPixels(hsv.cols(), hsv.rows(), data)
            } finally {
                hsv.release()
            }
        }
    }
}
