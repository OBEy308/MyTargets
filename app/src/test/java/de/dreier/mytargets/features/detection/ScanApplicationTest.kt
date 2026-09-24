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

import de.dreier.mytargets.detection.DetectedFace
import de.dreier.mytargets.detection.DetectedShot
import de.dreier.mytargets.detection.DetectionFailure
import de.dreier.mytargets.detection.DetectionResult
import de.dreier.mytargets.detection.SelectionReason
import de.dreier.mytargets.detection.geometry.Mat3
import de.dreier.mytargets.detection.registration.Roll
import de.dreier.mytargets.shared.models.db.Shot
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ScanApplicationTest {

    /** Six places as the database hands them out: ids, indices, the end, one arrow number. */
    private fun current() = (0 until 6).map { Shot(id = 100L + it, index = it, endId = 7L, arrowNumber = "3") }

    /** The ring is whatever the target view says; here a marker that shows it was asked. */
    private val zoneAt: (Float, Float) -> Int = { x, _ -> if (x > 0f) 9 else 4 }

    private fun detected(reason: SelectionReason, vararg xs: Float) = ScanOutcome.Detected(
        DetectionResult(
            shots = xs.map { DetectedShot(0, it, 0.1f, 0.9f) },
            faceConfidence = 0.9f,
            reason = reason,
            failure = null,
            face = DetectedFace(Mat3.identity(), 2252, 4000, Roll.NOT_MEASURED)
        ),
        "r4-all-2026-09"
    )

    @Test
    fun aCompleteEndFillsEveryPlace() {
        val report = ScanApplication.apply(detected(SelectionReason.COMPLETE, 0.1f, -0.1f, 0.2f, -0.2f, 0.3f, -0.3f), current(), zoneAt)

        assertEquals(ScanMessage.Complete(6), report.message)
        assertEquals(listOf(9, 4, 9, 4, 9, 4), report.shots!!.map { it.scoringRing })
        assertEquals(0.3f, report.shots!![4].x)
        assertEquals(0.1f, report.shots!![4].y)
    }

    @Test
    fun theShotsKeepTheirIdentity() {
        val report = ScanApplication.apply(detected(SelectionReason.FEWER_THAN_EXPECTED, 0.1f, 0.2f), current(), zoneAt)

        val shots = report.shots!!
        // EndDAO.updateShots updates by primary key; new ids would never reach the database.
        assertEquals((100L until 106L).toList(), shots.map { it.id })
        assertEquals((0 until 6).toList(), shots.map { it.index })
        assertEquals(List(6) { 7L }, shots.map { it.endId })
        assertEquals(List(6) { null }, shots.map { it.arrowNumber })
    }

    @Test
    fun fewerArrowsLeaveTheRestOpen() {
        val report = ScanApplication.apply(detected(SelectionReason.FEWER_THAN_EXPECTED, 0.1f, 0.2f, 0.3f, 0.4f), current(), zoneAt)

        assertEquals(ScanMessage.Fewer(4, 6), report.message)
        assertEquals(6, report.shots!!.size)
        assertEquals(List(2) { Shot.NOTHING_SELECTED }, report.shots!!.drop(4).map { it.scoringRing })
        assertEquals(List(2) { 0f }, report.shots!!.drop(4).map { it.x })
    }

    @Test
    fun noArrowsEntersNothing() {
        // The detector says FEWER_THAN_EXPECTED with an empty list; the count decides before the reason.
        val report = ScanApplication.apply(detected(SelectionReason.FEWER_THAN_EXPECTED), current(), zoneAt)

        assertNull(report.shots)
        assertEquals(ScanMessage.NoArrows, report.message)
    }

    @Test
    fun anAmbiguousSurplusEntersOnlyTheSureOnes() {
        val report = ScanApplication.apply(detected(SelectionReason.AMBIGUOUS_SURPLUS, 0.1f, 0.2f, 0.3f, 0.4f), current(), zoneAt)

        assertEquals(ScanMessage.Uncertain(4, SelectionReason.AMBIGUOUS_SURPLUS), report.message)
        assertEquals(4, report.shots!!.count { it.scoringRing != Shot.NOTHING_SELECTED })
    }

    @Test
    fun aSpotOverflowCarriesItsReason() {
        val report = ScanApplication.apply(detected(SelectionReason.SPOT_OVERFLOW, 0.1f, 0.2f, 0.3f), current(), zoneAt)

        assertEquals(ScanMessage.Uncertain(3, SelectionReason.SPOT_OVERFLOW), report.message)
        assertEquals(3, report.shots!!.count { it.scoringRing != Shot.NOTHING_SELECTED })
    }

    @Test
    fun neverMorePlacesThanTheEndHas() {
        val report = ScanApplication.apply(detected(SelectionReason.COMPLETE, 0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.6f, 0.7f), current(), zoneAt)

        assertEquals(6, report.shots!!.size)
        assertEquals(ScanMessage.Complete(6), report.message)
    }

    @Test
    fun aFaceThatIsNotFoundOrDoesNotMatchEntersNothing() {
        val notFound = ScanApplication.apply(ScanOutcome.Detected(DetectionResult.failed(DetectionFailure.FACE_NOT_FOUND), "m"), current(), zoneAt)
        val mismatch = ScanApplication.apply(ScanOutcome.Detected(DetectionResult.failed(DetectionFailure.FACE_MISMATCH), "m"), current(), zoneAt)

        assertEquals(ScanReport(null, ScanMessage.FaceNotFound), notFound)
        assertEquals(ScanReport(null, ScanMessage.FaceMismatch), mismatch)
    }

    @Test
    fun theOtherOutcomesEnterNothing() {
        val cause = IllegalStateException("boom")

        assertEquals(ScanReport(null, ScanMessage.PhotoUnreadable), ScanApplication.apply(ScanOutcome.PhotoUnreadable(cause), current(), zoneAt))
        assertEquals(ScanReport(null, ScanMessage.Failed(cause)), ScanApplication.apply(ScanOutcome.ModelUnavailable(cause), current(), zoneAt))
        assertEquals(ScanReport(null, ScanMessage.Failed(cause)), ScanApplication.apply(ScanOutcome.ScanFailed(cause), current(), zoneAt))
        assertEquals(ScanReport(null, ScanMessage.Unsupported), ScanApplication.apply(ScanOutcome.Unsupported, current(), zoneAt))
    }

    @Test
    fun theCurrentShotsAreNotChanged() {
        val shots = current()

        ScanApplication.apply(detected(SelectionReason.COMPLETE, 0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.6f), shots, zoneAt)

        // They are the undo copy's source and the target view's list.
        assertEquals(List(6) { Shot.NOTHING_SELECTED }, shots.map { it.scoringRing })
    }
}
