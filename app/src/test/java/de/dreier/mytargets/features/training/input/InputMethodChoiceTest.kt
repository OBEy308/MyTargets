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

package de.dreier.mytargets.features.training.input

import de.dreier.mytargets.shared.models.db.Shot
import de.dreier.mytargets.shared.views.TargetViewBase.EInputMethod.KEYBOARD
import de.dreier.mytargets.shared.views.TargetViewBase.EInputMethod.PLOTTING
import org.junit.Test
import kotlin.test.assertEquals

class InputMethodChoiceTest {

    private val empty = listOf(Shot(0), Shot(1))
    private val set = listOf(Shot(0).apply { scoringRing = 2 }, Shot(1))

    @Test
    fun anEmptyEndFollowsItsExactFlag() {
        for (setting in listOf(KEYBOARD, PLOTTING)) {
            assertEquals(PLOTTING, InputMethodChoice.inputMethodFor(empty, exact = true, setting = setting))
            assertEquals(KEYBOARD, InputMethodChoice.inputMethodFor(empty, exact = false, setting = setting))
        }
    }

    @Test
    fun anExactEndWithShotsIsAlwaysPlotted() {
        // New in 8b (decision 6): a scanned end has positions, whatever the setting says.
        assertEquals(PLOTTING, InputMethodChoice.inputMethodFor(set, exact = true, setting = KEYBOARD))
        assertEquals(PLOTTING, InputMethodChoice.inputMethodFor(set, exact = true, setting = PLOTTING))
    }

    @Test
    fun aKeyboardEndWithShotsFollowsTheSetting() {
        assertEquals(KEYBOARD, InputMethodChoice.inputMethodFor(set, exact = false, setting = KEYBOARD))
        assertEquals(PLOTTING, InputMethodChoice.inputMethodFor(set, exact = false, setting = PLOTTING))
    }
}
