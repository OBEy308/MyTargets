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
import de.dreier.mytargets.detection.registration.ColourClass
import de.dreier.mytargets.detection.registration.RingTransition
import de.dreier.mytargets.detection.registration.RingTransitions
import org.junit.Test

class BlackZonesTest {

    @Test
    fun theBlackRingOfTheFullFaceLiesBetweenBlueAndWhite() {
        assertThat(BlackZones.of(RingTransitions.WA_FULL)).containsExactly(0.6..0.8)
    }

    @Test
    fun theOrderOfTheTransitionsDoesNotMatter() {
        assertThat(BlackZones.of(RingTransitions.WA_FULL.reversed())).containsExactly(0.6..0.8)
    }

    @Test
    fun aFaceWithoutBlackHasNoBlackZone() {
        val transitions = listOf(
            RingTransition(0.2, ColourClass.YELLOW, ColourClass.RED),
            RingTransition(0.4, ColourClass.RED, ColourClass.BLUE)
        )

        assertThat(BlackZones.of(transitions)).isEmpty()
    }
}
