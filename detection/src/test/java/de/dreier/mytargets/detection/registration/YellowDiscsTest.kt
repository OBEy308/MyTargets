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

import com.google.common.truth.Truth.assertThat
import de.dreier.mytargets.detection.geometry.Vec2
import org.junit.Rule
import org.junit.Test
import kotlin.math.hypot

class YellowDiscsTest {

    @get:Rule
    val openCv = OpenCvRule()

    /** Faces of yellow up to r and red up to 2r, on white. */
    private fun faces(width: Int, height: Int, vararg faces: Pair<Vec2, Double>) =
        ClassImage.of(width, height) { x, y ->
            var c = ColourClass.WHITE
            for ((centre, r) in faces) {
                val d = hypot(x - centre.x, y - centre.y)
                if (d < r) c = ColourClass.YELLOW else if (d < 2 * r && c == ColourClass.WHITE) c = ColourClass.RED
            }
            c
        }

    @Test
    fun theDensityIsTheShareOfTheWindowThatLiesInsideTheImage() {
        // A zero-padded box sum over the full window would read 36/121 = 0.30
        // in a corner; register.py divides by the part of the window in the image.
        val d = YellowDiscs.density(FloatArray(20 * 20) { 1f }, 20, 20, 5)

        assertThat(d[0]).isWithin(1e-6f).of(1f)
        assertThat(d[20 * 20 - 1]).isWithin(1e-6f).of(1f)
    }

    @Test
    fun oneDiscFoundAtSeveralScalesCountsOnce() {
        val search = YellowDiscs.find(faces(400, 400, Vec2(200.0, 200.0) to 40.0), f = 1.0)

        // Without merging, each of these would count as a disc of its own.
        assertThat(search.measured.size).isAtLeast(2)
        assertThat(search.counted).hasSize(1)
        val disc = search.counted.single()
        assertThat(disc.centre.distanceTo(Vec2(200.0, 200.0))).isLessThan(1.0)
        assertThat(disc.radius).isWithin(1.5).of(40.0)
    }

    @Test
    fun twoSeparateDiscsCountTwice() {
        val search = YellowDiscs.find(
            faces(800, 400, Vec2(200.0, 200.0) to 40.0, Vec2(600.0, 200.0) to 40.0), f = 1.0
        )

        assertThat(search.counted).hasSize(2)
    }

    @Test
    fun aDiscLessThanHalfAsLargeAsTheLargestDoesNotCount() {
        val search = YellowDiscs.find(
            faces(800, 400, Vec2(200.0, 200.0) to 40.0, Vec2(600.0, 200.0) to 15.0), f = 1.0
        )

        assertThat(search.counted).hasSize(1)
        assertThat(search.counted.single().radius).isWithin(1.5).of(40.0)
    }

    @Test
    fun yellowWithoutRedAroundItIsNoDisc() {
        val classes = ClassImage.of(300, 300) { x, y ->
            if (hypot(x - 150.0, y - 150.0) < 40) ColourClass.YELLOW else ColourClass.WHITE
        }

        assertThat(YellowDiscs.find(classes, f = 1.0).counted).isEmpty()
    }

    @Test
    fun mergingKeepsTheDiscWithMoreTransitionPoints() {
        val a = Disc(Vec2(100.0, 100.0), 40.0, 60)
        val b = Disc(Vec2(103.0, 101.0), 38.0, 70)
        val c = Disc(Vec2(300.0, 100.0), 40.0, 50)

        assertThat(YellowDiscs.merge(listOf(a, b, c))).containsExactly(b, c).inOrder()
    }

    @Test
    fun onEqualTransitionPointsMergingKeepsTheLargerDisc() {
        val a = Disc(Vec2(100.0, 100.0), 37.5, 72)
        val b = Disc(Vec2(101.0, 100.0), 39.5, 72)

        assertThat(YellowDiscs.merge(listOf(a, b))).containsExactly(b)
    }
}
