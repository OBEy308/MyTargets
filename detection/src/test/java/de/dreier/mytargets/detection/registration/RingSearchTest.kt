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
import de.dreier.mytargets.detection.geometry.Conic
import de.dreier.mytargets.detection.geometry.Vec2
import org.junit.Test
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

class RingSearchTest {

    private val centre = Vec2(450.0, 450.0)
    private val disc = Disc(centre, 80.0, 72)

    /** Head on, 400 px per spot radius; [band] may replace part of a ring. */
    private fun face(band: (d: Double, angle: Double) -> ColourClass? = { _, _ -> null }) =
        ClassImage.of(900, 900) { x, y ->
            val d = hypot(x - centre.x, y - centre.y)
            val angle = atan2(y - centre.y, x - centre.x)
            band(d, angle) ?: when {
                d < 80 -> ColourClass.YELLOW
                d < 160 -> ColourClass.RED
                d < 240 -> ColourClass.BLUE
                d < 320 -> ColourClass.BLACK
                d < 400 -> ColourClass.WHITE
                else -> ColourClass.OTHER
            }
        }

    @Test
    fun findsAllFourRingsOfAFaceHeadOn() {
        val attempts = RingSearch.find(face(), disc, RingTransitions.WA_FULL, f = 0.8)

        assertThat(attempts.map { it.accepted }).containsExactly(true, true, true, true).inOrder()
        attempts.zip(listOf(80.0, 160.0, 240.0, 320.0)).forEach { (attempt, radius) ->
            assertThat(RingSearch.radiusAlong(attempt.fit!!.conic, centre, 0.0)!!)
                .isWithin(1.0).of(radius)
        }
    }

    @Test
    fun aRejectedRingDoesNotHideTheNextOne() {
        // Blue and black are 15 px apart except within 5 degrees of the x
        // axis, so 0.6 has too few points. 0.8 must still be found: its window
        // follows ring 0.4, the last one accepted. Following the table's
        // neighbour 0.6, as register.py does, it would end at 277 px, short
        // of 320.
        val attempts = RingSearch.find(
            face { d, angle ->
                if (d >= 240 && d < 255 && abs(angle) > Math.toRadians(5.0)) ColourClass.OTHER else null
            },
            disc, RingTransitions.WA_FULL, f = 0.8
        )

        assertThat(attempts.map { it.accepted }).containsExactly(true, true, false, true).inOrder()
        assertThat(RingSearch.radiusAlong(attempts[3].fit!!.conic, centre, 0.0)!!)
            .isWithin(1.0).of(320.0)
    }

    @Test
    fun theRadiusAlongARayOfACircleIsItsDistanceToTheEdge() {
        val circle = Conic.circle(Vec2(0.0, 0.0), 100.0)

        assertThat(RingSearch.radiusAlong(circle, Vec2(0.0, 0.0), 1.0)!!).isWithin(1e-9).of(100.0)
        assertThat(RingSearch.radiusAlong(circle, Vec2(30.0, 0.0), 0.0)!!).isWithin(1e-9).of(70.0)
    }
}
