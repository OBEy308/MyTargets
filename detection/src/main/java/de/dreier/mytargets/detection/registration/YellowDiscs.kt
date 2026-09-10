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
import kotlin.math.sqrt

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
    private const val RAY_LO = 0.3
    private const val RAY_HI = 4.0
    private const val MIN_TRANSITIONS = 36
    private const val MIN_RADIUS_AT_2000 = 4.0
    private const val GAP_AT_2000 = 12.0
    private const val MIN_YELLOW_INSIDE = 0.7
    private const val MAX_ROUNDS = 20
    private const val CONVERGED_PX = 0.1

    /** The red ring around the yellow disc ends at twice its radius. */
    private const val RED_RING_END = 2.0

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
     * closer than the smaller radius. The one with more transition points
     * stays; ties go to the larger radius, as register.py keeps the largest
     * valid disc.
     */
    internal fun merge(discs: List<Disc>): List<Disc> {
        val kept = ArrayList<Disc>()
        val order = compareByDescending<Disc> { it.transitionPoints }.thenByDescending { it.radius }
        for (disc in discs.sortedWith(order)) {
            if (kept.none { it.centre.distanceTo(disc.centre) < min(it.radius, disc.radius) }) {
                kept += disc
            }
        }
        return kept
    }

    /**
     * Rounds of: transitions on 72 rays, radius from their median distance,
     * centre from their mean -- until the centre settles or [MAX_ROUNDS] is
     * reached. register.py's three fixed rounds stop short of convergence:
     * each mean update only about halves the remaining offset from the true
     * centre, so a candidate seeded far off keeps a lingering bias.
     *
     * A disc the frame cuts shows its transition only on the arc inside the
     * image. The mean of that arc lies away from the frame, and the rounds
     * would settle on a smaller disc within the visible part. So once a ray
     * leaves the image on yellow, centre and radius come from the circle
     * through the transitions instead, fitted to those within twice their
     * median distance, where the red ring ends: a transition farther out
     * belongs to something else.
     */
    private fun measure(classes: ClassImage, start: Vec2, window: Double, f: Double): Disc? {
        var centre = start
        var radius = window
        var points: List<Vec2> = emptyList()
        var measuredOnce = false
        for (round in 0 until MAX_ROUNDS) {
            val r = radius
            points = RayTransitions.find(
                classes, centre, { r }, ColourClass.YELLOW, ColourClass.RED,
                RAYS, lo = RAY_LO, hi = RAY_HI, maxGapPx = GAP_AT_2000 * f
            )
            if (points.size < MIN_TRANSITIONS) break
            val distances = points.map { it.distanceTo(centre) }
            val median = RobustConic.median(distances)
            val next: Vec2
            if (cutByTheFrame(classes, centre, (RAY_HI * r).toInt())) {
                val near = points.filterIndexed { i, _ -> distances[i] <= RED_RING_END * median }
                val circle = circleThrough(near, centre) ?: return null
                next = circle.centre
                radius = circle.radius
            } else {
                radius = median
                next = Vec2(points.sumOf { it.x } / points.size, points.sumOf { it.y } / points.size)
            }
            measuredOnce = true
            val moved = next.distanceTo(centre)
            centre = next
            if (moved < CONVERGED_PX) break
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

    /**
     * Whether a ray from [centre] leaves the image on yellow before [reach].
     * That ray cannot show a transition, and the disc is taken to be cut by
     * the frame.
     */
    private fun cutByTheFrame(classes: ClassImage, centre: Vec2, reach: Int): Boolean {
        for (k in 0 until RAYS) {
            val a = 2.0 * PI * k / RAYS
            val c = cos(a)
            val s = sin(a)
            var last: ColourClass? = null
            for (r in 0 until reach) {
                val x = (centre.x + r * c).roundToInt()
                val y = (centre.y + r * s).roundToInt()
                if (classes.isInside(x, y)) {
                    last = classes[x, y]
                } else if (last != null) {
                    if (last == ColourClass.YELLOW) return true
                    break
                }
            }
        }
        return false
    }

    private class Circle(val centre: Vec2, val radius: Double)

    /**
     * The circle x^2 + y^2 + d x + e y + g = 0 nearest to [points] by least
     * squares (Kasa's fit), solved around [origin] to keep the sums small.
     * Null when the points do not determine a circle.
     */
    private fun circleThrough(points: List<Vec2>, origin: Vec2): Circle? {
        val a = Array(3) { DoubleArray(3) }
        val b = DoubleArray(3)
        for (p in points) {
            val row = doubleArrayOf(p.x - origin.x, p.y - origin.y, 1.0)
            val z = row[0] * row[0] + row[1] * row[1]
            for (i in 0 until 3) {
                for (j in 0 until 3) {
                    a[i][j] += row[i] * row[j]
                }
                b[i] -= row[i] * z
            }
        }
        val (d, e, g) = LinearSystem.solve(a, b) ?: return null
        val squared = 0.25 * (d * d + e * e) - g
        if (squared <= 0.0) return null
        return Circle(Vec2(origin.x - 0.5 * d, origin.y - 0.5 * e), sqrt(squared))
    }

    private fun argmax(values: FloatArray): Int {
        var best = 0
        for (i in 1 until values.size) {
            if (values[i] > values[best]) best = i
        }
        return best
    }
}
