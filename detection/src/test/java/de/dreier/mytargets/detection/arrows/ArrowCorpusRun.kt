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

import com.google.common.truth.Truth.assertWithMessage
import de.dreier.mytargets.detection.Candidate
import de.dreier.mytargets.detection.DetectionRequest
import de.dreier.mytargets.detection.corpus.CorpusEntry
import de.dreier.mytargets.detection.geometry.CommonPoint
import de.dreier.mytargets.detection.geometry.FootPoint
import de.dreier.mytargets.detection.geometry.Mat3
import de.dreier.mytargets.detection.geometry.Vec2
import de.dreier.mytargets.detection.metrics.ArrowBounds
import de.dreier.mytargets.detection.metrics.ArrowGroups
import de.dreier.mytargets.detection.metrics.ArrowMeasurement
import de.dreier.mytargets.detection.metrics.ArrowPins
import de.dreier.mytargets.detection.metrics.ArrowReport
import de.dreier.mytargets.detection.metrics.ArrowRow
import de.dreier.mytargets.detection.metrics.EntryOutcome
import de.dreier.mytargets.detection.metrics.Metrics
import de.dreier.mytargets.detection.metrics.PhotoDiagnosis
import de.dreier.mytargets.detection.metrics.RegistrationError
import de.dreier.mytargets.detection.metrics.ShotMatching
import de.dreier.mytargets.detection.metrics.StageMillis
import de.dreier.mytargets.detection.metrics.TruthDiagnosis
import de.dreier.mytargets.detection.registration.CorpusPhotos
import de.dreier.mytargets.detection.registration.OpenCvRule
import de.dreier.mytargets.detection.registration.PngDebugSink
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * Finds the arrows in every annotated photograph in scope and writes the
 * report and the stage images (arrow design, Korpuslauf und Bericht). It
 * fails when a metric of the oblique photographs gets worse than its bound.
 */
class ArrowCorpusRun {

    @get:Rule
    val openCv = OpenCvRule()

    private lateinit var dirs: ArrowCorpusRuns.Dirs

    private val detector = OpenCvArrowDetector()

    @Before
    fun requireCorpus() {
        dirs = ArrowCorpusRuns.dirs()
    }

    @Test
    fun findsTheArrowsAndWritesTheReport() {
        val rows = ArrayList<ArrowRow>()
        val truths = ArrayList<TruthDiagnosis>()
        val photos = ArrayList<PhotoDiagnosis>()
        val outcomes = ArrayList<EntryOutcome>()

        val outOfScope = ArrowCorpusRuns.forEachInScope(dirs, "arrows") { photo ->
            val entry = photo.entry
            val image = photo.image
            val request = ArrowCorpusRuns.requestFor(entry, image.cols(), image.rows())
            val analysis = detector.analyse(image, request, PngDebugSink(photo.folder))
            val measured = measure(entry, analysis, request, image.cols(), image.rows())
            rows += measured.row
            truths += measured.truths
            photos += measured.photo
            outcomes += measured.outcome
            if (analysis is ArrowAnalysis.Analysed) {
                val reference = entry.registration?.imageToTarget
                if (reference != null) {
                    CorpusPhotos.writeRectified(
                        image, analysis.registration.imageToTarget, reference, entry.imageName,
                        File(photo.folder, "4-entzerrt.png")
                    )
                }
                ArrowCorpusRuns.writeTruth(
                    image, analysis.registration.imageToTarget, measured.outcome, File(photo.folder, "7-wahrheit.png")
                )
            }
        }

        val oblique = outcomes.filter { ArrowGroups.of(it.entry) == ArrowGroups.OBLIQUE }
        val measurement = ArrowMeasurement.of(rows.count { it.group == ArrowGroups.OBLIQUE }, Metrics.over(oblique))
        // The worst a matched error can be: the detector's budget plus the largest annotation tolerance.
        val worstBudget = ShotMatching.DEFAULT_POSITION_TOLERANCE +
            (oblique.flatMap { it.entry.shots }.mapNotNull { it.positionTolerance }.maxOrNull() ?: 0.0)
        val listedInScope = outcomes.flatMap { it.entry.shots }
        val ownTips = listedInScope.count { it.tipPixel != null }
        val report = File(dirs.reportDir, "arrows.md")
        report.writeText(
            ArrowReport.render("Arrows against the corpus", rows, truths, photos, outcomes, outOfScope) +
                "\n## Truth per view\n\n$ownTips of ${listedInScope.size} listed hits in scope " +
                "carry a clicked tip of their own view (`shots[i].tipPx`) and are matched where this run's " +
                "homography puts that tip; the rest are matched against the truth of the end, carried from " +
                "the steepest view. See docs/design/2026-09-16-roll-in-the-measurement.md.\n" +
                ArrowReport.pinsSection(ArrowBounds.pinsFor(measurement, worstBudget))
        )
        println("Arrow report: ${report.absolutePath}")

        // Checked after writing, so a failing run still leaves its report.
        val broken = ArrowBounds.violations(measurement, PINS)
        assertWithMessage("bounds of the oblique photographs:\n" + broken.joinToString("\n"))
            .that(broken).isEmpty()
    }

    private class Measured(
        val row: ArrowRow,
        val truths: List<TruthDiagnosis>,
        val photo: PhotoDiagnosis,
        val outcome: EntryOutcome
    )

    private fun measure(
        entry: CorpusEntry,
        analysis: ArrowAnalysis,
        request: DetectionRequest,
        width: Int,
        height: Int
    ): Measured {
        val group = ArrowGroups.of(entry)
        val millis = with(analysis.timings) { StageMillis(registrationMs, warpMs, searchMs, walkMs) }
        return when (analysis) {
            is ArrowAnalysis.NotRegistered -> notRegistered(entry, group, analysis, millis)
            is ArrowAnalysis.Analysed -> analysed(entry, group, analysis, request, width, height, millis)
        }
    }

    private fun notRegistered(
        entry: CorpusEntry,
        group: String,
        analysis: ArrowAnalysis.NotRegistered,
        millis: StageMillis
    ): Measured {
        val row = ArrowRow(
            imageName = entry.imageName, group = group, angleDegrees = entry.capture?.angleDegrees,
            outcome = analysis.registration.failure.name, detail = analysis.registration.detail,
            footPointFromCentre = null, registrationError = null, footPointShift = null,
            largestLineOffset = null, commonPointFromFoot = null,
            listed = entry.shots.size, unresolved = entry.unresolvedArrows,
            candidates = 0, accepted = 0, matched = 0, falsePositives = 0, selection = null,
            largestPositionError = null, noShaft = 0, ranOut = 0, millis = millis
        )
        val truths = entry.shots.indices.map { TruthDiagnosis(entry.imageName, group, it, null, null, null, false) }
        val outcome = EntryOutcome(entry, ShotMatching.match(entry, emptyList()), emptyList())
        return Measured(row, truths, PhotoDiagnosis(entry.imageName, group, null), outcome)
    }

    /**
     * The candidate diagnosis starts from the metrics' own pairs, so found
     * minus lost always equals what the metrics matched: step 1 takes the
     * hits `match` already paired with an accepted find; step 2 matches the
     * remaining hits against the located candidates the selection did not
     * accept. Only step 2 involves a fresh `ShotMatching.match` call, and only
     * over candidates step 1 could not have claimed. Up to the roll: the
     * registrar anchors it, the sidecar reference assumes up in the image.
     */
    private fun analysed(
        entry: CorpusEntry,
        group: String,
        analysis: ArrowAnalysis.Analysed,
        request: DetectionRequest,
        width: Int,
        height: Int,
        millis: StageMillis
    ): Measured {
        val accepted = analysis.selection.accepted
        val detected = accepted.map { ArrowCorpusRuns.record(it) }
        // The truth as this view shows it: a hit with a clicked tip of its
        // own is matched where THIS run's homography puts that tip, not where
        // the truth of the end (measured in the steepest view) lands after a
        // registration turned by the camera's roll. See
        // docs/design/2026-09-16-roll-in-the-measurement.md.
        val entry = entry.truthInView(CorpusPhotos.values(analysis.registration.imageToTarget).toDoubleArray())
        val match = ShotMatching.match(entry, detected)
        val outcome = EntryOutcome(entry, match, detected)

        fun candidateOf(located: Candidate) = analysis.candidates.first { it.located === located }

        // Step 1: hits the metrics matched to an accepted find. `detected[k]`
        // is `accepted[k]`'s record, so `match.pairs[*].detectedIndex` indexes
        // `accepted` directly.
        val foundAndAccepted = match.pairs.associate { pair ->
            pair.truthIndex to (candidateOf(accepted[pair.detectedIndex]) to true)
        }

        // Step 2: the remaining hits, matched against the located candidates
        // the selection did not accept -- on a copy of the entry that lists
        // only those hits, with a map back to their original index.
        val remainingIndices = match.unmatchedTruth
        val remainingEntry = entry.copy(shots = remainingIndices.map { entry.shots[it] })
        val notAccepted = analysis.candidates.filter { c ->
            c.located != null && accepted.none { it === c.located }
        }
        val notAcceptedRecords = notAccepted.map { ArrowCorpusRuns.record(it.located!!) }
        val remainingMatch = ShotMatching.match(remainingEntry, notAcceptedRecords)
        val foundButLost = remainingMatch.pairs.associate { pair ->
            remainingIndices[pair.truthIndex] to (notAccepted[pair.detectedIndex] to false)
        }

        val hits = foundAndAccepted + foundButLost
        val truths = entry.shots.indices.map { t ->
            val hit = hits[t]
            TruthDiagnosis(
                imageName = entry.imageName,
                group = group,
                truthIndex = t,
                rank = hit?.let { (candidate, _) -> analysis.candidates.indexOfFirst { it === candidate } + 1 },
                confidence = hit?.first?.confidence,
                lineOffset = hit?.first?.offsetFromFoot,
                accepted = hit?.second ?: false
            )
        }

        // Step 3: false candidates are accepted candidates `match` left
        // unmatched, plus non-accepted located candidates step 2 left
        // unmatched.
        val falseCandidates = match.unmatchedDetected.map { candidateOf(accepted[it]) } +
            remainingMatch.unmatchedDetected.map { notAccepted[it] }
        val matchedCandidates = hits.values.map { it.first }
        val bestFalse = falseCandidates.maxOfOrNull { it.confidence }
        val listedEntries = entry.shots.mapNotNull { shot -> shot.position?.let { Vec2(it.x, it.y) } }
        val falseOnShafts = falseCandidates.count { c -> listedEntries.any { onShaftOf(c, it) } }

        val foot = analysis.footPoint
        val reference = entry.registration?.imageToTarget
        val referenceFoot = reference?.let { FootPoint.of(Mat3.of(*it.toDoubleArray()), request.intrinsics) }
        val common = CommonPoint.of(
            matchedCandidates.filter { it.refinement != TipRefinement.NO_SHAFT }.map { it.line }
        )
        val row = ArrowRow(
            imageName = entry.imageName,
            group = group,
            angleDegrees = entry.capture?.angleDegrees,
            outcome = ArrowRow.REGISTERED,
            detail = null,
            footPointFromCentre = foot?.length,
            registrationError = reference?.let {
                RegistrationError.betweenUpToRoll(it, CorpusPhotos.values(analysis.registration.imageToTarget), width, height)?.max
            },
            footPointShift = if (foot != null && referenceFoot != null) foot.distanceTo(referenceFoot) else null,
            largestLineOffset = matchedCandidates.maxOfOrNull { abs(it.offsetFromFoot) },
            commonPointFromFoot = if (foot != null && common != null) common.distanceTo(foot) else null,
            listed = entry.shots.size,
            unresolved = entry.unresolvedArrows,
            candidates = analysis.candidates.size,
            accepted = accepted.size,
            matched = match.pairs.size,
            falsePositives = Metrics.over(listOf(outcome)).falsePositives,
            selection = analysis.selection.reason.name,
            largestPositionError = match.pairs.mapNotNull { it.distance }.maxOrNull(),
            noShaft = analysis.candidates.count { it.refinement == TipRefinement.NO_SHAFT },
            ranOut = analysis.candidates.count { it.refinement == TipRefinement.RAN_OUT },
            millis = millis
        )
        return Measured(
            row, truths,
            PhotoDiagnosis(entry.imageName, group, bestFalse, falseOnShafts, falseCandidates.size - falseOnShafts),
            outcome
        )
    }

    /**
     * Whether [candidate] lies on the shaft of the hit at [entryPoint]: its line
     * passes within SHAFT_LINE_DISTANCE of the hit, and the hit lies ahead of its
     * tip, towards Q (arrow design, Korpuslauf und Bericht, point 3). Spot-local
     * and target coordinates coincide on WAFull.
     */
    private fun onShaftOf(candidate: ArrowCandidate, entryPoint: Vec2): Boolean {
        if (abs(candidate.line.signedDistanceTo(entryPoint)) >= SHAFT_LINE_DISTANCE) return false
        val towardsQ = candidate.tip - candidate.far
        return (entryPoint.x - candidate.tip.x) * towardsQ.x + (entryPoint.y - candidate.tip.y) * towardsQ.y > 0.0
    }

    private companion object {
        /**
         * Pinned from the report of 2026-09-16 (second run of that day): the
         * block under "Bounds, oblique photographs". When the corpus changes
         * the run fails and says so; set them again against a new report.
         *
         * Since that second run the hits are matched against each view's own
         * clicked tip where the sidecar has one (`truthInView`), so a
         * registration turned by the camera's roll no longer counts a
         * correctly found arrow as a miss plus a false positive. On the same
         * corpus that took the oblique group from 85 matched and 62 false
         * positives to 95 and 53, and the search ceiling from 62 % to 71 %;
         * see docs/design/2026-09-16-roll-in-the-measurement.md.
         *
         * The series of 15.9. (49 photographs of 16 ends, overcast, 10 m and
         * 25 m, 30 of them oblique with 174 listed hits) brought the oblique
         * group from 40 photographs and 227 hits to 70 and 401. The rate
         * moved from 22.9 % (52 of 227) to 21.2 % (85 of 401): the new views
         * alone come to 19 % (33 of 174), and they are the corpus's first
         * photographs in the 15 to 30 degree band, on 10 m with six shafts
         * packed into the gold. False positives grew from 26 to 62.
         *
         * The pins of 2026-09-14 replaced the pins of 2026-09-11, which stood on 15 oblique
         * photographs with 86 listed hits, 30 of them matched -- a detection
         * rate of 34.9 %. The nine ends of 14.9. brought the oblique group to
         * 40 photographs and 227 hits, and the rate fell to 22.9 % (52 of 227).
         * The detector did not change; the measuring stick did. The old
         * photographs were 15 views of 15 ends in flat evening light, and the
         * bounds set on them were, as the corpus's Fotoliste put it, fitted to
         * those photographs. The new ones are steeper (31 to 54 degrees rather
         * than mostly under 35), shot into a low sun, and show the same end
         * from several sides, so a weakness that one lucky view hid now shows
         * in the others. Read the rate as the honest one and the old as
         * optimistic, not as a regression of the finder.
         *
         * Set again on 2026-09-21 (corpus ac64539): the ten ends of 17.9. add
         * eight oblique views of 16 to 22 degrees with 44 hits, 12 of them
         * matched (27.3 %) at three false positives; the older 70 stay at 95
         * of 401. One of the eight, leicht-schraeg_07, is not registered.
         */
        val PINS = ArrowPins(photographs = 78, listed = 445, matched = 107, falsePositives = 56, correctScores = 54, comparableScores = 57, medianErrorBound = 0.012174, p95ErrorBound = 0.056333)

        /** How close a candidate's line passes a hit for the candidate to count as a piece of its shaft. */
        const val SHAFT_LINE_DISTANCE = 0.02
    }
}
