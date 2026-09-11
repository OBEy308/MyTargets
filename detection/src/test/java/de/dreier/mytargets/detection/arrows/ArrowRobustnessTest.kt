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
import com.google.common.truth.Truth.assertWithMessage
import de.dreier.mytargets.detection.DetectionRequest
import de.dreier.mytargets.detection.FaceLayout
import de.dreier.mytargets.detection.geometry.CommonPoint
import de.dreier.mytargets.detection.geometry.Line2
import de.dreier.mytargets.detection.geometry.Vec2
import de.dreier.mytargets.detection.registration.OpenCvRule
import de.dreier.mytargets.detection.registration.RingTransitions
import de.dreier.mytargets.detection.registration.SyntheticFace
import de.dreier.mytargets.detection.registration.releaseIfThrows
import org.junit.Rule
import org.junit.Test
import org.opencv.core.Mat
import org.opencv.core.Scalar
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * The harder cases of the arrow design, each against exact truth. A failure
 * here is reported, not tuned away: the thresholds are the tools' and are set
 * against the corpus report.
 */
class ArrowRobustnessTest {

    @get:Rule
    val openCv = OpenCvRule()

    private val detector = OpenCvArrowDetector()

    /** 2.0 radii above the face, as the design's leaning cases assume. */
    private val camera = SyntheticCamera(30.0, 650.0, Vec2(1000.0, 750.0))

    private fun analyse(photo: Mat, shots: Int, seenBy: SyntheticCamera = camera): ArrowAnalysis.Analysed {
        val request = DetectionRequest(
            FaceLayout.singleSpot(), WaFullZones.RADII, RingTransitions.WA_FULL, shots, seenBy.intrinsics
        )
        val analysis = try {
            detector.analyse(photo, request)
        } finally {
            photo.release()
        }
        assertWithMessage("outcome of the registration").that(analysis).isInstanceOf(ArrowAnalysis.Analysed::class.java)
        return analysis as ArrowAnalysis.Analysed
    }

    private fun photograph(vararg arrows: SyntheticArrow) =
        SyntheticArrows.photograph(camera, 2000, 1500, arrows.toList())

    /** A lean of [degrees] across the direction from the foot point to [entry]. */
    private fun leanAcross(entry: Vec2, degrees: Double): Vec2 {
        val away = (entry - camera.footPoint) * (1.0 / (entry - camera.footPoint).length)
        return Vec2(-away.y, away.x) * tan(Math.toRadians(degrees))
    }

    /** The shaft's line in the rectified image, from the entry towards the nock. */
    private fun shaftLine(arrow: SyntheticArrow, seenBy: SyntheticCamera = camera) =
        Line2.through(arrow.entry, seenBy.onFace(arrow.nock))

    /** The candidates lying along [arrow]'s shaft: both ends within 0.01 of its line. */
    private fun alongShaft(analysis: ArrowAnalysis.Analysed, arrow: SyntheticArrow): List<ArrowCandidate> {
        val line = shaftLine(arrow)
        return analysis.candidates.filter {
            abs(line.signedDistanceTo(it.tip)) <= 0.01 && abs(line.signedDistanceTo(it.far)) <= 0.01
        }
    }

    private fun rotate(v: Vec2, degrees: Double): Vec2 {
        val a = Math.toRadians(degrees)
        return Vec2(v.x * cos(a) - v.y * sin(a), v.x * sin(a) + v.y * cos(a))
    }

    @Test
    fun anArrowLeaningOneDegreeIsFoundWhole() {
        // At a height of 2.0 a lean of 1 degree moves the line at most 0.035 from Q,
        // inside the search's window of 0.04 in any direction.
        val arrow = SyntheticArrow(Vec2(0.1, -0.05), tilt = leanAcross(Vec2(0.1, -0.05), 1.0))

        val analysis = analyse(photograph(arrow), 1)

        assertThat(analysis.selection.accepted.single().local.distanceTo(arrow.entry)).isAtMost(0.01)
    }

    @Test
    fun anArrowLeaningThreeDegreesShowsItsOffset() {
        val arrow = SyntheticArrow(Vec2(0.1, -0.05), tilt = leanAcross(Vec2(0.1, -0.05), 3.0))

        val analysis = analyse(photograph(arrow), 1)
        // From the projection of the shaft, not d tan(alpha): that holds only for a lean across.
        val expected = shaftLine(arrow).signedDistanceTo(analysis.footPoint!!)

        assertWithMessage("the case lies outside the window, as it claims").that(abs(expected)).isGreaterThan(0.04)
        val seen = alongShaft(analysis, arrow).filter { it.refinement != TipRefinement.NO_SHAFT }
        assertWithMessage("a candidate with a refined line along the leaning shaft").that(seen).isNotEmpty()
        assertThat(seen.minOf { abs(it.offsetFromFoot - expected) }).isAtMost(0.01)
    }

    @Test
    fun threeArrowsLeaningAlikeMeetInOnePoint() {
        val tilt = Vec2(0.0, tan(Math.toRadians(3.0)))
        // Off the black ring, like the entries of OpenCvArrowDetectorTest.
        val arrows = listOf(Vec2(0.3, 0.35), Vec2(0.1, -0.05), Vec2(0.25, -0.42)).map { SyntheticArrow(it, tilt = tilt) }

        val analysis = analyse(photograph(*arrows.toTypedArray()), 3)
        val lines = arrows.map { arrow ->
            val seen = alongShaft(analysis, arrow).filter { it.refinement != TipRefinement.NO_SHAFT }
            assertWithMessage("a refined candidate along the arrow at ${arrow.entry}").that(seen).isNotEmpty()
            seen.maxBy { it.score }.line
        }

        // Q - d t with d the camera's height (arrow design, Verfahren step 3).
        val expected = camera.footPoint - tilt * camera.height
        assertThat(CommonPoint.of(lines)!!.distanceTo(expected)).isAtMost(0.01)
    }

    @Test
    fun anArrowLeaningFourDegreesIsFoundOnceAtItsEntry() {
        // At a height of 2.0 a lean of 4 degrees across puts the shaft's line
        // about 0.14 from Q, far outside the search's window of 0.04: the search
        // meets the shaft only in pieces, and every piece walks to the entry
        // (arrow design, Verfahren step 4, Neu ansetzen).
        val arrow = SyntheticArrow(Vec2(0.1, -0.05), tilt = leanAcross(Vec2(0.1, -0.05), 4.0))

        val analysis = analyse(photograph(arrow), 1)

        assertThat(analysis.candidates.filter { it.tip.distanceTo(arrow.entry) <= 0.02 }).hasSize(1)
        assertThat(analysis.selection.accepted.single().local.distanceTo(arrow.entry)).isAtMost(0.01)
    }

    @Test
    fun aShadowThatDoesNotPointAtTheFootPointIsNotFound() {
        val start = Vec2(0.1, -0.05)
        val turned = rotate((start - camera.footPoint) * (1.0 / (start - camera.footPoint).length), 30.0)
        val photo = SyntheticFace.photograph(2000, 1500, camera.targetToPhoto).releaseIfThrows {
            SyntheticArrows.stripeOnFace(it, camera, start, start + turned * 0.3, 0.014, Scalar(60.0, 60.0, 60.0))
        }

        assertThat(analyse(photo, 1).candidates).isEmpty()
    }

    @Test
    fun aGreyShaftOnTheBlackRingIsFound() {
        val arrow = SyntheticArrow(Vec2(-0.65, 0.2), shaft = SyntheticArrow.GREY)

        val analysis = analyse(photograph(arrow), 1)

        assertThat(analysis.selection.accepted.single().local.distanceTo(arrow.entry)).isAtMost(0.01)
    }

    @Test
    fun aShaftAcrossTheBlackRingIsFoundOnce() {
        // A dark shaft vanishes on the black ring and splits into two pieces.
        val arrow = SyntheticArrow(Vec2(-0.3, -0.3))

        val along = alongShaft(analyse(photograph(arrow), 1), arrow)

        assertThat(along).hasSize(1)
        assertThat(along.single().tip.distanceTo(arrow.entry)).isAtMost(0.01)
    }

    @Test
    fun aShaftLeavingACutPhotographIsFoundAndTheEdgeIsNot() {
        // The face centre sits at x = 400 of a 1600 px wide photograph: its left
        // edge cuts the face near x = -0.84, and the shaft runs out of the image.
        val cut = SyntheticCamera(30.0, 650.0, Vec2(400.0, 750.0))
        val arrow = SyntheticArrow(Vec2(-0.3, -0.3))

        val analysis = analyse(SyntheticArrows.photograph(cut, 1600, 1500, listOf(arrow)), 1, cut)

        assertThat(analysis.selection.accepted.single().local.distanceTo(arrow.entry)).isAtMost(0.01)
        val toTarget = requireNotNull(cut.targetToPhoto.inverse())
        val edge = Line2.through(toTarget.mapPoint(Vec2(0.0, 0.0))!!, toTarget.mapPoint(Vec2(0.0, 1499.0))!!)
        val alongEdge = analysis.candidates.filter {
            abs(edge.signedDistanceTo(it.tip)) < 0.02 && abs(edge.signedDistanceTo(it.far)) < 0.02
        }
        assertThat(alongEdge).isEmpty()
    }
}
