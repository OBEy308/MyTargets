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

import de.dreier.mytargets.detection.Candidate
import de.dreier.mytargets.detection.FaceLayout
import de.dreier.mytargets.detection.SpotMapping
import de.dreier.mytargets.detection.geometry.Line2
import de.dreier.mytargets.detection.geometry.Vec2
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/** A candidate arrow after the walk (arrow design, Schnittstelle). */
class ArrowCandidate(
    /** The entry point, in target coordinates. */
    val tip: Vec2,
    /** The other end, towards the nock, on the candidate's line. */
    val far: Vec2,
    /** The run's contrast from the search. */
    val contrast: Double,
    /** The shaft contrast from the walk. */
    val shaftContrast: Double,
    /** Length times contrast, as radial.py rates a run. */
    val score: Double,
    val refinement: TipRefinement,
    /** Distance of the line tip-far from the foot point, with the sign of cross(far - tip, Q - tip). */
    val offsetFromFoot: Double,
    /** Arrow design, Verfahren step 5. */
    val confidence: Double,
    /** Spot and spot-local position; null next to the face. */
    val located: Candidate?
) {
    val line: Line2
        get() = Line2.through(tip, far)
}

/**
 * Verfahren step 5, what the annotator did by hand in the tools: one
 * candidate per arrow, and how sure each one is.
 */
object ArrowCandidates {

    const val SAME_TIP = 0.01
    const val SAME_LINE_ANGLE = 2.0
    const val SAME_LINE_DISTANCE = 0.01

    /** Start values (arrow design, Offene Punkte, Zweite Messung); set against the candidate diagnosis. */
    const val LENGTH_SATURATION = 0.3
    const val UNREFINED_FACTOR = 0.5

    /** @param walks one per run, in the same order */
    fun build(runs: List<SearchRun>, walks: List<WalkResult>, foot: Vec2, layout: FaceLayout): List<ArrowCandidate> {
        require(runs.size == walks.size) { "one walk per run" }
        val drafts = runs.indices.mapNotNull { draft(runs[it], walks[it]) }
        val kept = ArrayList<Draft>()
        for (d in drafts.sortedWith(compareBy<Draft>({ preference(it.refinement) }, { -it.score }))) {
            if (kept.none { sameArrow(it, d) }) kept += d
        }
        val raw = kept.map { rawConfidence(it) }
        val best = raw.maxOrNull() ?: 0.0
        return kept.indices.map { i ->
            val d = kept[i]
            val confidence = if (best > 0.0) raw[i] / best else 0.0
            ArrowCandidate(
                tip = d.tip,
                far = d.far,
                contrast = d.contrast,
                shaftContrast = d.shaftContrast,
                score = d.score,
                refinement = d.refinement,
                offsetFromFoot = d.line.signedDistanceTo(foot),
                confidence = confidence,
                located = SpotMapping.locate(d.tip, layout)?.let { Candidate(it.faceIndex, it.local, confidence) }
            )
        }.sortedByDescending { it.score }
    }

    private class Draft(
        val tip: Vec2,
        val far: Vec2,
        val contrast: Double,
        val shaftContrast: Double,
        val score: Double,
        val refinement: TipRefinement,
        val line: Line2
    )

    /** far lies on the walk's line: the refined one, or the search's for NO_SHAFT. */
    private fun draft(run: SearchRun, walk: WalkResult): Draft? {
        val far = walk.line.project(run.far)
        if (far.distanceTo(walk.tip) < 1e-9) return null
        return Draft(walk.tip, far, run.contrast, walk.shaftContrast, run.score, walk.refinement, Line2.through(walk.tip, far))
    }

    /** REFINED before RAN_OUT before NO_SHAFT. */
    private fun preference(refinement: TipRefinement) = when (refinement) {
        TipRefinement.REFINED -> 0
        TipRefinement.RAN_OUT -> 1
        TipRefinement.NO_SHAFT -> 2
    }

    /**
     * The same arrow: entry points closer than SAME_TIP; or, when at least one
     * walk failed, lines that coincide. Two refined candidates on one line stay
     * two: seen from Q, one arrow can stand behind another exactly so.
     */
    private fun sameArrow(a: Draft, b: Draft): Boolean {
        if (a.tip.distanceTo(b.tip) < SAME_TIP) return true
        if (a.refinement == TipRefinement.REFINED && b.refinement == TipRefinement.REFINED) return false
        val cosine = a.line.direction.x * b.line.direction.x + a.line.direction.y * b.line.direction.y
        return abs(cosine) > cos(Math.toRadians(SAME_LINE_ANGLE)) &&
            abs(a.line.signedDistanceTo(b.tip)) < SAME_LINE_DISTANCE &&
            abs(b.line.signedDistanceTo(a.tip)) < SAME_LINE_DISTANCE
    }

    /**
     * Length times contrast, with the length saturating at LENGTH_SATURATION,
     * the walk's shaft contrast, and a failed walk counting UNREFINED_FACTOR.
     * NO_SHAFT has a shaft contrast below 0.08 by definition and ends at the
     * bottom of the scale, as intended.
     */
    private fun rawConfidence(d: Draft): Double {
        val length = min(d.tip.distanceTo(d.far), LENGTH_SATURATION) / LENGTH_SATURATION
        val factor = if (d.refinement == TipRefinement.REFINED) 1.0 else UNREFINED_FACTOR
        return length * max(0.0, d.shaftContrast) * factor
    }
}
