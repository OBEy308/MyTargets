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

import de.dreier.mytargets.detection.SelectionOutcome
import de.dreier.mytargets.detection.geometry.Vec2
import de.dreier.mytargets.detection.registration.RegistrationOutcome

/** Wall time per stage in milliseconds, for the report (arrow design, Korpuslauf und Bericht). */
class StageTimings(val registrationMs: Long, val warpMs: Long, val searchMs: Long, val walkMs: Long)

/** Everything a detection produced, for the corpus run; the app gets only the DetectionResult. */
sealed interface ArrowAnalysis {
    val timings: StageTimings

    class NotRegistered(
        val registration: RegistrationOutcome.Failed,
        override val timings: StageTimings
    ) : ArrowAnalysis

    class Analysed(
        val registration: RegistrationOutcome.Registered,
        /** The camera's foot point in target coordinates; null only when it cannot be computed. */
        val footPoint: Vec2?,
        /** Highest score first. */
        val candidates: List<ArrowCandidate>,
        val selection: SelectionOutcome,
        override val timings: StageTimings
    ) : ArrowAnalysis
}
