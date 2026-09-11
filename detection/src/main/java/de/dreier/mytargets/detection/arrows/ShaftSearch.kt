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

import de.dreier.mytargets.detection.geometry.Vec2
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/** One shaft the search found: [tip] is the end nearer the foot point, [far] the other. */
class SearchRun(val tip: Vec2, val far: Vec2, val contrast: Double, val score: Double) {
    val length: Double
        get() = tip.distanceTo(far)
}

/**
 * radial.py (arrow design, Verfahren step 3). In the rectified image an arrow
 * standing perpendicular in the face lies on a line through the camera's foot
 * point Q, with its entry point at the end nearer Q. The search sweeps lines
 * through Q, with small sideways offsets for arrows that lean a little, and
 * keeps the stretches darker than both flanks -- or lighter, for a grey shaft on
 * the black ring. A shadow does not point at Q and crosses these lines only
 * briefly.
 */
object ShaftSearch {

    const val ANGLE_STEP = 0.25
    const val SAMPLE_STEP = 0.002
    const val MAX_REACH = 3.5
    const val SQUARE = 1.05
    val OFFSETS = DoubleArray(17) { -0.04 + 0.005 * it }
    const val THRESHOLD = 0.15
    const val MAX_GAP = 0.02
    const val DUPLICATE_ANGLE = 6.0
    const val DUPLICATE_DISTANCE = 0.025
    const val DUPLICATE_SLACK = 0.05
    const val MERGE_ANGLE = 2.5
    const val MERGE_DISTANCE = 0.02
    const val MERGE_GAP = 0.3
    const val KEEP = 20

    /** At most KEEP runs, the best scored first. */
    fun find(face: RectifiedFace, foot: Vec2, ringRadii: List<Double>): List<SearchRun> {
        val swept = sweep(face, foot, ringRadii).sortedByDescending { it.score }
        return merge(dropDuplicates(swept), foot).sortedByDescending { it.score }.take(KEEP)
    }

    private fun sweep(face: RectifiedFace, foot: Vec2, ringRadii: List<Double>): List<SearchRun> {
        val maxIndexGap = (MAX_GAP / SAMPLE_STEP).roundToInt()
        val reach = (MAX_REACH / SAMPLE_STEP).roundToInt()
        val directions = (360.0 / ANGLE_STEP).roundToInt()
        val found = ArrayList<SearchRun>()
        for (a in 0 until directions) {
            val theta = Math.toRadians(a * ANGLE_STEP)
            val u = Vec2(cos(theta), sin(theta))
            val normal = Vec2(-u.y, u.x)
            // The stretch of the ray from Q that lies in the square; the square is
            // convex, so it is one piece.
            var first = -1
            var last = -1
            for (i in 0 until reach) {
                val d = i * SAMPLE_STEP
                if (abs(foot.x + d * u.x) < SQUARE && abs(foot.y + d * u.y) < SQUARE) {
                    if (first < 0) first = i
                    last = i
                }
            }
            if (first < 0) continue
            val along = DoubleArray(last - first + 1) { (first + it) * SAMPLE_STEP }
            for (offset in OFFSETS) {
                val contrast = FlankContrast.SEARCH.profile(face, foot, u, offset, along)
                val runs = Runs.find(contrast, THRESHOLD, maxIndexGap)
                if (runs.isEmpty()) continue
                val base = foot + normal * offset
                val radii = DoubleArray(along.size) { hypot(base.x + along[it] * u.x, base.y + along[it] * u.y) }
                for (run in runs) {
                    val length = along[run.last] - along[run.first]
                    if (!RunRules.keep(run, length, radii, ringRadii)) continue
                    val dark = (run.first..run.last)
                        .filter { contrast[it] > THRESHOLD }
                        .map { contrast[it] }
                        .toDoubleArray()
                    val median = Numbers.median(dark)
                    found += SearchRun(
                        tip = base + u * along[run.first],
                        far = base + u * along[run.last],
                        contrast = median,
                        score = length * median
                    )
                }
            }
        }
        return found
    }

    /**
     * radial.py: in order of score, drops a run that repeats a kept one -- within
     * DUPLICATE_ANGLE of its direction, both ends closer than DUPLICATE_DISTANCE
     * to its line, and overlapping it along the line with DUPLICATE_SLACK to spare.
     */
    internal fun dropDuplicates(byScore: List<SearchRun>): List<SearchRun> {
        val cosLimit = cos(Math.toRadians(DUPLICATE_ANGLE))
        val kept = ArrayList<SearchRun>()
        for (run in byScore) {
            val u = unit(run)
            val duplicate = kept.any { other ->
                val u2 = unit(other)
                if (abs(dot(u, u2)) < cosLimit) return@any false
                val n2 = Vec2(-u2.y, u2.x)
                if (abs(dot(run.tip - other.tip, n2)) >= DUPLICATE_DISTANCE) return@any false
                if (abs(dot(run.far - other.tip, n2)) >= DUPLICATE_DISTANCE) return@any false
                val a = dot(run.tip - other.tip, u2)
                val b = dot(run.far - other.tip, u2)
                maxOf(a, b) > -DUPLICATE_SLACK && minOf(a, b) < other.length + DUPLICATE_SLACK
            }
            if (!duplicate) kept += run
        }
        return kept
    }

    /**
     * radial.py: joins collinear pieces of one shaft, split by the black ring or a
     * crossing shaft -- within MERGE_ANGLE, both ends closer than MERGE_DISTANCE to
     * the line, at most MERGE_GAP apart along it. The joined run keeps the end
     * nearest Q as its entry point and the scores add up.
     */
    internal fun merge(runs: List<SearchRun>, foot: Vec2): List<SearchRun> {
        val cosLimit = cos(Math.toRadians(MERGE_ANGLE))
        val merged = ArrayList<SearchRun>()
        for (run in runs) {
            val u = unit(run)
            val index = merged.indexOfFirst { m ->
                val u2 = unit(m)
                if (abs(dot(u, u2)) < cosLimit) return@indexOfFirst false
                val n2 = Vec2(-u2.y, u2.x)
                if (abs(dot(run.tip - m.tip, n2)) > MERGE_DISTANCE) return@indexOfFirst false
                if (abs(dot(run.far - m.tip, n2)) > MERGE_DISTANCE) return@indexOfFirst false
                val a = dot(run.tip - m.tip, u2)
                val b = dot(run.far - m.tip, u2)
                !(b < -MERGE_GAP || a > m.length + MERGE_GAP)
            }
            if (index < 0) {
                merged += run
                continue
            }
            val m = merged[index]
            val u2 = unit(m)
            val ends = listOf(m.tip, m.far, run.tip, run.far)
            merged[index] = SearchRun(
                tip = ends.minBy { dot(it - foot, u2) },
                far = ends.maxBy { dot(it - foot, u2) },
                contrast = m.contrast,
                score = m.score + run.score
            )
        }
        return merged
    }

    private fun unit(run: SearchRun): Vec2 = (run.far - run.tip) * (1.0 / run.length)

    private fun dot(a: Vec2, b: Vec2) = a.x * b.x + a.y * b.y
}
