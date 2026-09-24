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

package de.dreier.mytargets.features.detection

import de.dreier.mytargets.detection.DetectionFailure
import de.dreier.mytargets.detection.DetectionResult
import de.dreier.mytargets.detection.SelectionReason
import de.dreier.mytargets.shared.models.db.Shot

/** What the input tells the user after a scan (app integration 8b, Meldungen). */
sealed class ScanMessage {
    data class Complete(val found: Int) : ScanMessage()
    data class Fewer(val found: Int, val expected: Int) : ScanMessage()
    /** Only the sure ones were entered; [reason] is AMBIGUOUS_SURPLUS or SPOT_OVERFLOW. */
    data class Uncertain(val sure: Int, val reason: SelectionReason) : ScanMessage()
    data object NoArrows : ScanMessage()
    data object FaceNotFound : ScanMessage()
    data object FaceMismatch : ScanMessage()
    data object PhotoUnreadable : ScanMessage()
    data class Failed(val cause: Throwable) : ScanMessage()
    /** Cannot happen, the menu item is hidden; logged, not shown. */
    data object Unsupported : ScanMessage()
}

/** [shots] is null when nothing is entered and the end keeps what it has. */
data class ScanReport(val shots: List<Shot>?, val message: ScanMessage)

/**
 * The table of the 8b design as a pure function: the activity only sets text
 * and action. Leitsatz: rather enter nothing than something wrong.
 */
object ScanApplication {

    /**
     * @param current the end's shots as loaded; the result copies them so ids,
     *   indices and the end stay (EndDAO.updateShots updates by primary key)
     * @param zoneAt the ring for a position, from the target view
     */
    fun apply(outcome: ScanOutcome, current: List<Shot>, zoneAt: (Float, Float) -> Int): ScanReport = when (outcome) {
        is ScanOutcome.Detected -> applyDetected(outcome.result, current, zoneAt)
        is ScanOutcome.PhotoUnreadable -> ScanReport(null, ScanMessage.PhotoUnreadable)
        is ScanOutcome.ModelUnavailable -> ScanReport(null, ScanMessage.Failed(outcome.cause))
        is ScanOutcome.ScanFailed -> ScanReport(null, ScanMessage.Failed(outcome.cause))
        ScanOutcome.Unsupported -> ScanReport(null, ScanMessage.Unsupported)
    }

    private fun applyDetected(result: DetectionResult, current: List<Shot>, zoneAt: (Float, Float) -> Int): ScanReport {
        when (result.failure) {
            DetectionFailure.FACE_NOT_FOUND -> return ScanReport(null, ScanMessage.FaceNotFound)
            DetectionFailure.FACE_MISMATCH -> return ScanReport(null, ScanMessage.FaceMismatch)
            null -> Unit
        }
        // "No arrows" is no reason of its own: FEWER_THAN_EXPECTED with an empty list.
        if (result.shots.isEmpty()) return ScanReport(null, ScanMessage.NoArrows)
        val placed = result.shots.take(current.size)
        val shots = current.mapIndexed { i, shot ->
            val detected = placed.getOrNull(i)
            if (detected == null) {
                shot.copy(x = 0f, y = 0f, scoringRing = Shot.NOTHING_SELECTED, arrowNumber = null)
            } else {
                shot.copy(x = detected.x, y = detected.y, scoringRing = zoneAt(detected.x, detected.y), arrowNumber = null)
            }
        }
        val uncertain = result.reason?.takeIf {
            it == SelectionReason.AMBIGUOUS_SURPLUS || it == SelectionReason.SPOT_OVERFLOW
        }
        val message = when {
            uncertain != null -> ScanMessage.Uncertain(placed.size, uncertain)
            placed.size == current.size -> ScanMessage.Complete(placed.size)
            else -> ScanMessage.Fewer(placed.size, current.size)
        }
        return ScanReport(shots, message)
    }
}
