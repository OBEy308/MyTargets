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

import de.dreier.mytargets.detection.geometry.Line2
import de.dreier.mytargets.detection.geometry.Vec2
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

enum class TipRefinement {
    /** The walk found the shaft and where it ends. */
    REFINED,

    /** The walk saw no shaft behind the seed; the entry point is the search's. */
    NO_SHAFT,

    /** The walk found the shaft but no end within its window; the entry point is the search's, on the refined line. */
    RAN_OUT
}

/**
 * One walked shaft. [line] is the refined line when the walk saw a shaft
 * (REFINED, RAN_OUT) and the search's line for NO_SHAFT; [tip] lies on it.
 */
class WalkResult(val tip: Vec2, val line: Line2, val shaftContrast: Double, val refinement: TipRefinement)

/**
 * tips.py's refine (arrow design, Verfahren step 4): fit the line of the shaft
 * behind the seed, then walk along it until the shaft stops being darker (or
 * lighter) than its flanks. Of each grid it keeps the middle of the near-best
 * lines rather than tips.py's first best one (arrow design, Abweichungen von
 * den Werkzeugen).
 */
object TipWalk {

    const val BEHIND_FROM = -0.10
    const val BEHIND_TO = -0.012
    const val BEHIND_STEP = 0.0015
    const val ANGLE_WINDOW = 12
    const val OFFSET_STEPS = 6
    const val OFFSET_STEP = 0.002
    const val FINE_STEPS = 4
    const val FINE_ANGLE_STEP = 0.25
    const val FINE_OFFSET_STEP = 0.0005
    const val WALK_STEP = 0.0005
    const val AHEAD = 0.035
    const val SHAFT_FROM = -0.03
    const val END_FRACTION = 0.35
    const val END_GAP = 0.012
    const val NO_SHAFT_BELOW = 0.08

    /** Lines whose goodness lies this close to the best count as equally good. */
    const val TIE = 0.01

    /** @param direction from the nock towards the tip; any length */
    fun walk(face: RectifiedFace, seed: Vec2, direction: Vec2): WalkResult {
        val seedAngle = Math.toDegrees(atan2(direction.y, direction.x))
        val behind = Numbers.steps(BEHIND_FROM, BEHIND_TO, BEHIND_STEP)

        // Robust mean of the contrast behind the seed: the lowest quarter is
        // dropped so gaps and crossing shafts do not pull the line away.
        fun goodness(angle: Double, offset: Double): Double {
            val v = FlankContrast.WALK.profile(face, seed, unit(angle), offset, behind)
            v.sort()
            return v.copyOfRange(v.size / 4, v.size).average()
        }

        // tips.py takes the first best line of each grid. On a shaft of even
        // brightness the goodness is flat across many degrees, because the lowest
        // quarter drops out, and the first best line lies at the edge of that
        // plateau. The middle of the near-best lines is the shaft's axis.
        fun middleOfBest(grid: List<Pair<Double, Double>>): Pair<Double, Double> {
            val scores = DoubleArray(grid.size) { goodness(grid[it].first, grid[it].second) }
            val top = scores.max()
            var angle = 0.0
            var offset = 0.0
            var count = 0
            for (k in grid.indices) {
                if (scores[k] >= top - TIE) {
                    angle += grid[k].first
                    offset += grid[k].second
                    count++
                }
            }
            val middle = Pair(angle / count, offset / count)
            if (goodness(middle.first, middle.second) >= top - TIE) return middle
            // The near-best lines fall apart into two groups, as beside a second
            // shaft; their middle lies between them. Keep the first best.
            return grid[scores.indices.maxBy { scores[it] }]
        }

        var (bestAngle, bestOffset) = middleOfBest(
            (-ANGLE_WINDOW..ANGLE_WINDOW).flatMap { a ->
                (-OFFSET_STEPS..OFFSET_STEPS).map { o -> Pair(seedAngle + a, o * OFFSET_STEP) }
            }
        )
        repeat(2) {
            val angle0 = bestAngle
            val offset0 = bestOffset
            val fine = middleOfBest(
                (-FINE_STEPS..FINE_STEPS).flatMap { a ->
                    (-FINE_STEPS..FINE_STEPS).map { o ->
                        Pair(angle0 + a * FINE_ANGLE_STEP, offset0 + o * FINE_OFFSET_STEP)
                    }
                }
            )
            bestAngle = fine.first
            bestOffset = fine.second
        }

        val u = unit(bestAngle)
        val s = Numbers.steps(BEHIND_FROM, AHEAD, WALK_STEP)
        val v = FlankContrast.WALK.profile(face, seed, u, bestOffset, s)
        val start = ((SHAFT_FROM - BEHIND_FROM) / WALK_STEP).roundToInt()
        val shaft = Numbers.median(v.copyOfRange(0, start))
        if (shaft < NO_SHAFT_BELOW) {
            return WalkResult(seed, Line2(seed, direction), shaft, TipRefinement.NO_SHAFT)
        }

        val origin = seed + Vec2(-u.y, u.x) * bestOffset
        val line = Line2(origin, u)
        val threshold = END_FRACTION * shaft
        val gap = (END_GAP / WALK_STEP).roundToInt()
        var tipIndex = -1
        var i = start
        while (i < s.size) {
            if (v[i] > threshold) {
                i++
                continue
            }
            var j = i
            while (j < s.size && v[j] <= threshold) j++
            // An end needs a gap of END_GAP -- or a shorter one that reaches the
            // end of the window, as in tips.py.
            if (j - i >= gap || j >= s.size) {
                tipIndex = i - 1
                break
            }
            i = j
        }
        if (tipIndex < 0) {
            return WalkResult(line.project(seed), line, shaft, TipRefinement.RAN_OUT)
        }
        return WalkResult(origin + u * s[tipIndex], line, shaft, TipRefinement.REFINED)
    }

    private fun unit(degrees: Double): Vec2 {
        val r = Math.toRadians(degrees)
        return Vec2(cos(r), sin(r))
    }
}
