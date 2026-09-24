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
import de.dreier.mytargets.shared.views.TargetViewBase.EInputMethod
import de.dreier.mytargets.shared.views.TargetViewBase.EInputMethod.KEYBOARD
import de.dreier.mytargets.shared.views.TargetViewBase.EInputMethod.PLOTTING

/**
 * How an end is shown in the input (app integration 8b, decision 6). An
 * object of its own, not a member of TargetView: loading TargetView on the JVM
 * creates its android.util.Property constants, which throw "Stub!" there.
 */
object InputMethodChoice {
    fun inputMethodFor(shots: List<Shot>, exact: Boolean, setting: EInputMethod): EInputMethod = when {
        shots.all { it.scoringRing == Shot.NOTHING_SELECTED } -> if (exact) PLOTTING else KEYBOARD
        // Positions are set: show them, also for ends plotted before the keyboard was switched on.
        exact -> PLOTTING
        else -> setting
    }
}
