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
import kotlin.math.cos
import kotlin.math.sin

class ShaftSearchTest {

    private val foot = Vec2(1.3, 0.0)
    private val rings = listOf(0.05, 0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9, 1.0)

    /** The point [distance] from [entry], away from the foot point. */
    private fun away(entry: Vec2, distance: Double): Vec2 {
        val d = entry - foot
        return entry + d * (distance / d.length)
    }

    private fun rotate(v: Vec2, degrees: Double): Vec2 {
        val a = Math.toRadians(degrees)
        return Vec2(v.x * cos(a) - v.y * sin(a), v.x * sin(a) + v.y * cos(a))
    }

    @Test
    fun aShaftThroughTheFootPointIsFoundOnceWithItsEntryNearerTheFootPoint() {
        val entry = Vec2(0.2, 0.1)
        val face = FacePainter(0.9f).stripe(entry, away(entry, 0.5), 0.012, 0.15f).build()

        val runs = ShaftSearch.find(face, foot, rings)

        assertThat(runs).hasSize(1)
        assertThat(runs[0].tip.distanceTo(entry)).isAtMost(0.01)
        assertThat(runs[0].far.distanceTo(away(entry, 0.5))).isAtMost(0.01)
    }

    @Test
    fun aStripeThatDoesNotPointAtTheFootPointIsNotFound() {
        val start = Vec2(0.1, -0.05)
        val turned = rotate((start - foot) * (1.0 / (start - foot).length), 30.0)
        val face = FacePainter(0.9f).stripe(start, start + turned * 0.3, 0.014, 0.25f).build()

        assertThat(ShaftSearch.find(face, foot, rings)).isEmpty()
    }

    @Test
    fun printedRingLinesAreNotShafts() {
        val painter = FacePainter(0.9f)
        for (r in rings) painter.ring(r - 0.0015, r + 0.0015, 0.2f)

        assertThat(ShaftSearch.find(painter.build(), foot, rings)).isEmpty()
    }

    @Test
    fun twoPiecesOfOneShaftAreJoinedWithTheEntryOfTheNearerOne() {
        val entry = Vec2(-0.2, 0.05)
        val face = FacePainter(0.9f)
            .stripe(entry, away(entry, 0.2), 0.012, 0.15f)
            .stripe(away(entry, 0.4), away(entry, 0.7), 0.012, 0.15f)
            .build()

        val runs = ShaftSearch.find(face, foot, rings)

        assertThat(runs).hasSize(1)
        assertThat(runs[0].tip.distanceTo(entry)).isAtMost(0.01)
        assertThat(runs[0].far.distanceTo(away(entry, 0.7))).isAtMost(0.01)
    }

    @Test
    fun aGreyShaftOnTheBlackRingIsFound() {
        val entry = Vec2(-0.65, 0.2)
        val face = FacePainter(0.9f)
            .ring(0.6, 0.8, 0.12f)
            .stripe(entry, away(entry, 0.4), 0.012, 0.59f)
            .build()

        val runs = ShaftSearch.find(face, foot, rings)

        assertThat(runs).hasSize(1)
        assertThat(runs[0].tip.distanceTo(entry)).isAtMost(0.01)
    }

    private fun parallel(y: Double, score: Double) = SearchRun(Vec2(0.0, y), Vec2(-0.5, y), 0.5, score)

    @Test
    fun parallelShaftsThreeHundredthsApartStayTwoAndTwoHundredthsApartBecomeOne() {
        // The limit of the duplicate rule, 0.025 across.
        assertThat(ShaftSearch.dropDuplicates(listOf(parallel(0.0, 0.3), parallel(0.03, 0.2)))).hasSize(2)
        assertThat(ShaftSearch.dropDuplicates(listOf(parallel(0.0, 0.3), parallel(0.02, 0.2)))).hasSize(1)
    }

    @Test
    fun mergingTakesTheEndsNearestAndFarthestFromTheFootPointAndAddsTheScores() {
        val near = SearchRun(Vec2(0.0, 0.0), Vec2(-0.2, 0.0), 0.5, 0.1)
        val beyond = SearchRun(Vec2(-0.45, 0.0), Vec2(-0.8, 0.0), 0.4, 0.14)

        val merged = ShaftSearch.merge(listOf(near, beyond), foot).single()

        assertThat(merged.tip).isEqualTo(Vec2(0.0, 0.0))
        assertThat(merged.far).isEqualTo(Vec2(-0.8, 0.0))
        assertThat(merged.score).isWithin(1e-12).of(0.24)
    }
}
