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

import de.dreier.mytargets.detection.SelectionReason
import de.dreier.mytargets.detection.registration.RegistrationOutcome

/**
 * Wall time per stage in milliseconds: registration, warp (with pre-shrink),
 * the network's forward pass, and peak finding together with the placement
 * on spots; the corpus report shows them in its registration / warp / search
 * / walk columns.
 */
class LearnedTimings(val registrationMs: Long, val warpMs: Long, val networkMs: Long, val peaksMs: Long)

/** Everything the learned detector produced, for the corpus run and the debug screen; the app gets only the DetectionResult. */
sealed interface LearnedAnalysis {
    val timings: LearnedTimings

    class NotRegistered(
        val registration: RegistrationOutcome.Failed,
        override val timings: LearnedTimings
    ) : LearnedAnalysis

    class Analysed(
        val registration: RegistrationOutcome.Registered,
        /** Every peak at or above the threshold, highest first. */
        val peaks: List<Peak>,
        /** The first expectedShots of [peaks]: what the PoC measured (decision 4). */
        val kept: List<Peak>,
        /** Of [kept], those on a spot. */
        val onFace: List<LearnedFind>,
        /** Of [kept], those outside every spot: they took a place and count for nothing. */
        val outsideFace: List<Peak>,
        /** [onFace] after the per-spot cap: the shots. */
        val accepted: List<LearnedFind>,
        val reason: SelectionReason,
        override val timings: LearnedTimings
    ) : LearnedAnalysis
}
