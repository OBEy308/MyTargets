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
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

enum class TipRefinement {
    /** The walk found the shaft and where it ends. */
    REFINED,

    /** The walk saw no shaft behind the seed; the entry point is the search's. */
    NO_SHAFT,

    /** The walk found the shaft but no end within its reach; the entry point is the search's, on the refined line. */
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
 * lighter) than its flanks. It deviates from tips.py where the pipeline has no
 * annotator (arrow design, Abweichungen von den Werkzeugen): of each grid it
 * keeps the middle of the near-best lines; it looks up to REACH ahead and sets
 * out again with a fresh fit from every end at least RESEED ahead of its seed;
 * and it walks across a black zone on which the shaft has no contrast.
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
    const val REACH = 0.8
    const val RESEED = 0.05
    const val SHAFT_FROM = -0.03
    const val END_FRACTION = 0.35
    const val END_GAP = 0.012
    const val NO_SHAFT_BELOW = 0.08

    /** Lines whose goodness lies this close to the best count as equally good. */
    const val TIE = 0.01

    /**
     * @param direction from the nock towards the tip; any length
     * @param blackZones rings, inner..outer radius about the face centre, on
     *        which a dark shaft has no contrast ([BlackZones.of])
     * @param reach how far ahead of [seed] the walk looks for the end
     */
    fun walk(
        face: RectifiedFace,
        seed: Vec2,
        direction: Vec2,
        blackZones: List<ClosedFloatingPointRange<Double>> = emptyList(),
        reach: Double = REACH
    ): WalkResult {
        val first = fit(face, seed, Math.toDegrees(atan2(direction.y, direction.x)), blackZones)
        val shaft = shaftContrast(face, seed, first, blackZones)
        if (shaft < NO_SHAFT_BELOW) {
            return WalkResult(seed, Line2(seed, direction), shaft, TipRefinement.NO_SHAFT)
        }
        val firstLine = first.line(seed)
        var from = seed
        var fitted = first
        var used = 0.0
        while (true) {
            val line = fitted.line(from)
            val end = endAhead(face, from, fitted, shaft, blackZones, reach - used)
                ?: return WalkResult(firstLine.project(seed), firstLine, shaft, TipRefinement.RAN_OUT)
            val advance = (end.x - from.x) * line.direction.x + (end.y - from.y) * line.direction.y
            if (advance < RESEED) return WalkResult(end, line, shaft, TipRefinement.REFINED)
            // A line extended from a fit behind the seed leaves the shaft after a
            // few tenths when its direction is a degree off. Fit again at the end
            // found; a real end confirms itself, the next walk ends at once.
            used += advance
            from = end
            fitted = fit(face, from, fitted.angle, blackZones)
        }
    }

    /** A line fitted at a seed: [angle] in degrees, [direction] its unit vector, [offset] along the left normal. */
    private class Fit(val angle: Double, val offset: Double, val direction: Vec2) {
        fun line(seed: Vec2) = Line2(seed + Vec2(-direction.y, direction.x) * offset, direction)
    }

    /** tips.py's fit of the line behind [seed], around [angle0] (arrow design, Nachstellen). */
    private fun fit(
        face: RectifiedFace,
        seed: Vec2,
        angle0: Double,
        blackZones: List<ClosedFloatingPointRange<Double>>
    ): Fit {
        val behind = Numbers.steps(BEHIND_FROM, BEHIND_TO, BEHIND_STEP)

        // Robust mean of the contrast behind the seed: the lowest quarter is
        // dropped so gaps and crossing shafts do not pull the line away. A sample
        // on a black zone without contrast says nothing about the line.
        fun goodness(angle: Double, offset: Double): Double {
            val u = unit(angle)
            val v = FlankContrast.WALK.profile(face, seed, u, offset, behind)
            val kept = v.indices
                .filter { v[it] >= NO_SHAFT_BELOW || !onBlack(seed, u, offset, behind[it], blackZones) }
                .map { v[it] }
                .sorted()
            if (kept.isEmpty()) return 0.0
            return kept.subList(kept.size / 4, kept.size).average()
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

        var (angle, offset) = middleOfBest(
            (-ANGLE_WINDOW..ANGLE_WINDOW).flatMap { a ->
                (-OFFSET_STEPS..OFFSET_STEPS).map { o -> Pair(angle0 + a, o * OFFSET_STEP) }
            }
        )
        repeat(2) {
            val angleBefore = angle
            val offsetBefore = offset
            val fine = middleOfBest(
                (-FINE_STEPS..FINE_STEPS).flatMap { a ->
                    (-FINE_STEPS..FINE_STEPS).map { o ->
                        Pair(angleBefore + a * FINE_ANGLE_STEP, offsetBefore + o * FINE_OFFSET_STEP)
                    }
                }
            )
            angle = fine.first
            offset = fine.second
        }
        return Fit(angle, offset, unit(angle))
    }

    /** The median contrast more than 0.03 behind [seed], without black samples lacking contrast; 0 when none is left. */
    private fun shaftContrast(
        face: RectifiedFace,
        seed: Vec2,
        fit: Fit,
        blackZones: List<ClosedFloatingPointRange<Double>>
    ): Double {
        val s = Numbers.steps(BEHIND_FROM, SHAFT_FROM, WALK_STEP)
        val v = FlankContrast.WALK.profile(face, seed, fit.direction, fit.offset, s)
        val kept = v.indices
            .filter { v[it] >= NO_SHAFT_BELOW || !onBlack(seed, fit.direction, fit.offset, s[it], blackZones) }
            .map { v[it] }
            .toDoubleArray()
        return if (kept.isEmpty()) 0.0 else Numbers.median(kept)
    }

    /**
     * Walks from 0.03 behind [from] along [fit] up to [reach] ahead and returns
     * the last sample of shaft before an end, or null when the shaft goes on.
     * An end is a gap of END_GAP below END_FRACTION of [shaft] -- or a shorter
     * one that reaches the end of the reach, as tips.py does at the end of its
     * window. A sample on a black zone without contrast is neither shaft nor gap.
     */
    private fun endAhead(
        face: RectifiedFace,
        from: Vec2,
        fit: Fit,
        shaft: Double,
        blackZones: List<ClosedFloatingPointRange<Double>>,
        reach: Double
    ): Vec2? {
        val u = fit.direction
        val s = Numbers.steps(BEHIND_FROM, reach, WALK_STEP)
        val v = FlankContrast.WALK.profile(face, from, u, fit.offset, s)
        val start = ((SHAFT_FROM - BEHIND_FROM) / WALK_STEP).roundToInt()
        val threshold = END_FRACTION * shaft
        val counted = (start until s.size).filter {
            v[it] > threshold || !onBlack(from, u, fit.offset, s[it], blackZones)
        }
        val gap = (END_GAP / WALK_STEP).roundToInt()
        var lastShaft = start - 1
        var i = 0
        while (i < counted.size) {
            if (v[counted[i]] > threshold) {
                lastShaft = counted[i]
                i++
                continue
            }
            var j = i
            while (j < counted.size && v[counted[j]] <= threshold) j++
            if (j - i >= gap || j >= counted.size) return fit.line(from).point + u * s[lastShaft]
            i = j
        }
        return null
    }

    /** Whether the sample [along] on the line through [seed], or one of its flanks, lies on a black zone. */
    private fun onBlack(
        seed: Vec2,
        u: Vec2,
        offset: Double,
        along: Double,
        blackZones: List<ClosedFloatingPointRange<Double>>
    ): Boolean {
        if (blackZones.isEmpty()) return false
        val nx = -u.y
        val ny = u.x
        val x = seed.x + offset * nx + along * u.x
        val y = seed.y + offset * ny + along * u.y
        val flank = FlankContrast.WALK.flank
        return inBlack(hypot(x, y), blackZones) ||
            inBlack(hypot(x - flank * nx, y - flank * ny), blackZones) ||
            inBlack(hypot(x + flank * nx, y + flank * ny), blackZones)
    }

    private fun inBlack(radius: Double, blackZones: List<ClosedFloatingPointRange<Double>>) =
        blackZones.any { radius in it }

    private fun unit(degrees: Double): Vec2 {
        val r = Math.toRadians(degrees)
        return Vec2(cos(r), sin(r))
    }
}
