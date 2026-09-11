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

import com.google.common.truth.Truth.assertThat
import de.dreier.mytargets.detection.geometry.Vec2
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class TipWalkTest {

    private val foot = Vec2(1.3, 0.0)
    private val entry = Vec2(0.2, 0.1)

    /** From the entry towards the nock. */
    private val outward = (entry - foot) * (1.0 / (entry - foot).length)
    private val across = Vec2(-outward.y, outward.x)

    private fun rotate(v: Vec2, degrees: Double): Vec2 {
        val a = Math.toRadians(degrees)
        return Vec2(v.x * cos(a) - v.y * sin(a), v.x * sin(a) + v.y * cos(a))
    }

    /** Towards the entry, three degrees off, as a seed from the search can be. */
    private val seedDirection = rotate(outward * -1.0, 3.0)

    private fun shaft() = FacePainter(0.9f).stripe(entry, entry + outward * 0.4, 0.012, 0.15f).build()

    @Test
    fun findsTheEndOfTheShaft() {
        val seed = entry + outward * 0.01 + across * 0.004

        val result = TipWalk.walk(shaft(), seed, seedDirection)

        assertThat(result.refinement).isEqualTo(TipRefinement.REFINED)
        assertThat(result.tip.distanceTo(entry)).isAtMost(0.002)
        assertThat(abs(result.line.signedDistanceTo(entry + outward * 0.3))).isAtMost(0.002)
    }

    @Test
    fun withoutAShaftTheSeedStays() {
        val result = TipWalk.walk(FacePainter(0.9f).build(), entry, seedDirection)

        assertThat(result.refinement).isEqualTo(TipRefinement.NO_SHAFT)
        assertThat(result.tip).isEqualTo(entry)
    }

    @Test
    fun aShaftRunningPastTheWindowRunsOutOnTheRefinedLine() {
        // The seed sits 0.05 behind the end; the walk looks only 0.035 ahead.
        val seed = entry + outward * 0.05

        val result = TipWalk.walk(shaft(), seed, seedDirection)

        assertThat(result.refinement).isEqualTo(TipRefinement.RAN_OUT)
        assertThat(result.tip.distanceTo(seed)).isAtMost(0.002)
        assertThat(abs(result.line.signedDistanceTo(entry))).isAtMost(0.002)
    }

    @Test
    fun aShortGapReachingTheEndOfTheWindowStillEndsTheShaftBeforeIt() {
        // The end lies 0.03 ahead of the seed. After it only 0.005 of the window
        // remain, less than the 0.012 an end needs, but the gap reaches the end of
        // the window, and tips.py ends the shaft there.
        val seed = entry + outward * 0.03

        val result = TipWalk.walk(shaft(), seed, seedDirection)

        assertThat(result.refinement).isEqualTo(TipRefinement.REFINED)
        assertThat(result.tip.distanceTo(entry)).isAtMost(0.002)
    }
}
