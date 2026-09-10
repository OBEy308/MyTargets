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

import de.dreier.mytargets.detection.geometry.Vec2
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** A yellow disc with red around it, measured on its yellow-to-red transition. */
class Disc(val centre: Vec2, val radius: Double, val transitionPoints: Int)

/** Every disc the search measured, and the ones it counts after merging and the size rule. */
class DiscSearch(val measured: List<Disc>, val counted: List<Disc>)

/**
 * register.py's find_yellow_disc, extended to count. Candidates are density
 * peaks of the yellow class at six scales where red is near; each is measured
 * on 72 rays at the yellow-to-red transition. register.py suppresses peaks
 * only within one scale and keeps the largest disc; to count, the same disc
 * found at several scales has to be merged first (registration design,
 * stage 3 of "Verfahren").
 */
object YellowDiscs {

    private val WINDOWS_AT_2000 = intArrayOf(6, 12, 24, 48, 96, 160)
    private const val PEAKS_PER_WINDOW = 12
    private const val MIN_DENSITY = 0.6f
    private const val MIN_RED_DENSITY = 0.08f
    private const val RAYS = 72
    private const val MIN_TRANSITIONS = 36
    private const val MIN_RADIUS_AT_2000 = 4.0
    private const val GAP_AT_2000 = 12.0
    private const val MIN_YELLOW_INSIDE = 0.7

    fun find(classes: ClassImage, f: Double): DiscSearch {
        val width = classes.width
        val height = classes.height
        val yellow = classes.mask(ColourClass.YELLOW)
        val red = classes.mask(ColourClass.RED)

        val measured = ArrayList<Disc>()
        for (windowAt2000 in WINDOWS_AT_2000) {
            val window = max(1, (windowAt2000 * f).roundToInt())
            val density = density(yellow, width, height, window)
            val redNear = density(red, width, height, 2 * window)
            for (i in density.indices) {
                if (redNear[i] <= MIN_RED_DENSITY) density[i] = 0f
            }
            for (peak in 0 until PEAKS_PER_WINDOW) {
                val i = argmax(density)
                if (density[i] < MIN_DENSITY) break
                val px = i % width
                val py = i / width
                measure(classes, Vec2(px.toDouble(), py.toDouble()), window.toDouble(), f)
                    ?.let { measured += it }
                // Suppress this peak's neighbourhood, as register.py does.
                for (y in max(0, py - 3 * window) until min(height, py + 3 * window)) {
                    for (x in max(0, px - 3 * window) until min(width, px + 3 * window)) {
                        density[y * width + x] = 0f
                    }
                }
            }
        }

        val merged = merge(measured)
        val largest = merged.maxOfOrNull { it.radius } ?: return DiscSearch(measured, emptyList())
        return DiscSearch(measured, merged.filter { it.radius >= 0.5 * largest })
    }

    /**
     * Share of the (2 [window] + 1)^2 box around each pixel that is 1 in
     * [mask], counting only the part of the box inside the image, as
     * register.py's box_density. The sum is OpenCV's; the division by the
     * in-image area is done here, because boxFilter can only divide by the
     * whole box.
     */
    internal fun density(mask: FloatArray, width: Int, height: Int, window: Int): FloatArray {
        val source = Mat(height, width, CvType.CV_32F)
        val sum = Mat()
        try {
            source.put(0, 0, mask)
            val side = (2 * window + 1).toDouble()
            Imgproc.boxFilter(
                source, sum, -1, Size(side, side), Point(-1.0, -1.0), false, Core.BORDER_CONSTANT
            )
            val out = FloatArray(width * height)
            sum.get(0, 0, out)
            for (y in 0 until height) {
                val rows = min(height, y + window + 1) - max(0, y - window)
                for (x in 0 until width) {
                    val cols = min(width, x + window + 1) - max(0, x - window)
                    out[y * width + x] /= (rows * cols).toFloat()
                }
            }
            return out
        } finally {
            source.release()
            sum.release()
        }
    }

    /**
     * The same disc found at several scales: two discs whose centres are
     * closer than the smaller radius. The one with more transition points stays.
     */
    internal fun merge(discs: List<Disc>): List<Disc> {
        val kept = ArrayList<Disc>()
        for (disc in discs.sortedByDescending { it.transitionPoints }) {
            if (kept.none { it.centre.distanceTo(disc.centre) < min(it.radius, disc.radius) }) {
                kept += disc
            }
        }
        return kept
    }

    /** Three rounds of: transitions on 72 rays, radius from their median distance, centre from their mean. */
    private fun measure(classes: ClassImage, start: Vec2, window: Double, f: Double): Disc? {
        var centre = start
        var radius = window
        var points: List<Vec2> = emptyList()
        var measuredOnce = false
        for (round in 0 until 3) {
            val r = radius
            points = RayTransitions.find(
                classes, centre, { r }, ColourClass.YELLOW, ColourClass.RED,
                RAYS, lo = 0.3, hi = 4.0, maxGapPx = GAP_AT_2000 * f
            )
            if (points.size < MIN_TRANSITIONS) break
            radius = RobustConic.median(points.map { it.distanceTo(centre) })
            centre = Vec2(points.sumOf { it.x } / points.size, points.sumOf { it.y } / points.size)
            measuredOnce = true
        }
        if (!measuredOnce || radius < MIN_RADIUS_AT_2000 * f || points.size < MIN_TRANSITIONS) {
            return null
        }

        // The disc has to be yellow inside: sample a circle at 0.6 of the radius.
        var inside = 0
        var yellow = 0
        for (k in 0 until RAYS) {
            val a = 2.0 * PI * k / RAYS
            val x = (centre.x + 0.6 * radius * cos(a)).roundToInt()
            val y = (centre.y + 0.6 * radius * sin(a)).roundToInt()
            if (classes.isInside(x, y)) {
                inside++
                if (classes[x, y] == ColourClass.YELLOW) yellow++
            }
        }
        if (inside <= RAYS / 2 || yellow.toDouble() / inside <= MIN_YELLOW_INSIDE) return null
        return Disc(centre, radius, points.size)
    }

    private fun argmax(values: FloatArray): Int {
        var best = 0
        for (i in 1 until values.size) {
            if (values[i] > values[best]) best = i
        }
        return best
    }
}
