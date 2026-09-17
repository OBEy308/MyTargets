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

import de.dreier.mytargets.detection.Candidate
import de.dreier.mytargets.detection.FaceLayout
import de.dreier.mytargets.detection.SpotMapping
import de.dreier.mytargets.detection.corpus.CorpusEntry
import de.dreier.mytargets.detection.geometry.Vec2
import de.dreier.mytargets.detection.metrics.ArrowBounds
import de.dreier.mytargets.detection.metrics.ArrowGroups
import de.dreier.mytargets.detection.metrics.ArrowMeasurement
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
import de.dreier.mytargets.detection.registration.FaceWarp
import de.dreier.mytargets.detection.registration.OpenCvRule
import de.dreier.mytargets.detection.registration.PngDebugSink
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * Finds the arrows with the learned finder in every annotated photograph in
 * scope and writes the report and the stage images (design 3d, Messung und
 * Tests). The model was trained on these photographs: the numbers are an
 * upper bound and a regression guard, the estimate on new photographs is the
 * PoC's cross-validation, both named in the report.
 */
class LearnedArrowCorpusRun {

    @get:Rule
    val openCv = OpenCvRule()

    private lateinit var dirs: ArrowCorpusRuns.Dirs
    private lateinit var modelDir: File
    private lateinit var model: ArrowModel
    private lateinit var detector: LearnedArrowDetector

    @Before
    fun requireCorpusAndModel() {
        dirs = ArrowCorpusRuns.dirs()
        val configured = System.getProperty("detection.model.dir")
        assumeTrue("DETECTION_MODEL_DIR is not configured", configured != null)
        modelDir = File(configured!!)
        assumeTrue("model directory does not exist: $configured", modelDir.isDirectory)
        model = ArrowModel.load(modelDir)
        detector = LearnedArrowDetector(model)
    }

    @Test
    fun findsTheArrowsAndWritesTheReport() {
        val rows = ArrayList<ArrowRow>()
        val truths = ArrayList<TruthDiagnosis>()
        val photos = ArrayList<PhotoDiagnosis>()
        val outcomes = ArrayList<EntryOutcome>()

        val outOfScope = ArrowCorpusRuns.forEachInScope(dirs, "learned-arrows") { photo ->
            val entry = photo.entry
            val image = photo.image
            val request = ArrowCorpusRuns.requestFor(entry, image.cols(), image.rows())
            val analysis = detector.analyse(image, request, PngDebugSink(photo.folder))
            val measured = measure(entry, analysis, request.layout, image.cols(), image.rows())
            rows += measured.row
            truths += measured.truths
            photos += measured.photo
            outcomes += measured.outcome
            if (analysis is LearnedAnalysis.Analysed) {
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
        val worstBudget = ShotMatching.DEFAULT_POSITION_TOLERANCE +
            (oblique.flatMap { it.entry.shots }.mapNotNull { it.positionTolerance }.maxOrNull() ?: 0.0)
        val listedInScope = outcomes.flatMap { it.entry.shots }
        val ownTips = listedInScope.count { it.tipPixel != null }
        val report = File(dirs.reportDir, "learned-arrows.md")
        report.writeText(
            ArrowReport.render(
                "Learned finder against the corpus", rows, truths, photos, outcomes, outOfScope, preface(rows.size)
            ) +
                "\n## Truth per view\n\n$ownTips of ${listedInScope.size} listed hits in scope " +
                "carry a clicked tip of their own view (`shots[i].tipPx`) and are matched where this run's " +
                "homography puts that tip; the rest are matched against the truth of the end, carried from " +
                "the steepest view. See docs/design/2026-09-16-roll-in-the-measurement.md.\n" +
                ArrowReport.pinsSection(ArrowBounds.pinsFor(measurement, worstBudget))
        )
        println("Learned arrow report: ${report.absolutePath}")
    }

    /** The preface of the report: what this number is and is not (design 3d, Guete und Messung sind getrennt). */
    private fun preface(photographs: Int): String {
        val m = model.meta
        val cv = m.crossValidation.entries.joinToString("; ") { "${it.key}: ${it.value}" }
        return "Model `${modelDir.name}` (training `${m.training}`, corpus `${m.corpus}`, ${m.inputSize} px, " +
            "threshold ${m.threshold}, ${m.thresholdFrom}).\n\n" +
            "**The model was trained on these photographs.** Every number below is an upper bound and a " +
            "regression guard for the chain warp, network, peaks; it is no estimate of the quality on new " +
            "photographs. That estimate is the PoC's cross-validation: $cv.\n\n" +
            "Two differences to the PoC's metric: the PoC leaves out frontal views whose truth is inherited " +
            "from a sibling without a clicked tip, this run measures all $photographs annotated photographs " +
            "in scope against the truth of the end; and a peak outside the face counted as a false positive " +
            "in the PoC, while here it takes one of the expected places and never reaches the metrics, so the " +
            "false positive count can be lower than the PoC's.\n\n" +
            "Columns: Candidates are the peaks at or above the threshold, Accepted the ones kept by count and " +
            "placed on a spot. Q, registration Q shift, line offsets, common point, NO_SHAFT and RAN_OUT do not " +
            "apply to this finder. The times are registration / warp (with pre-shrink) / network / peaks."
    }

    private class Measured(
        val row: ArrowRow,
        val truths: List<TruthDiagnosis>,
        val photo: PhotoDiagnosis,
        val outcome: EntryOutcome
    )

    private fun measure(
        entry: CorpusEntry,
        analysis: LearnedAnalysis,
        layout: FaceLayout,
        width: Int,
        height: Int
    ): Measured {
        val group = ArrowGroups.of(entry)
        val millis = with(analysis.timings) { StageMillis(registrationMs, warpMs, networkMs, peaksMs) }
        return when (analysis) {
            is LearnedAnalysis.NotRegistered -> notRegistered(entry, group, analysis, millis)
            is LearnedAnalysis.Analysed -> analysed(entry, group, analysis, layout, width, height, millis)
        }
    }

    private fun notRegistered(
        entry: CorpusEntry,
        group: String,
        analysis: LearnedAnalysis.NotRegistered,
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
     * Like the classical run's diagnosis: step 1 pairs the metrics made with
     * accepted finds; step 2 matches the remaining hits against the peaks the
     * finder had but did not accept (dropped by the count, by the spot cap);
     * step 3 counts the rest as false. A peak outside the face is neither.
     */
    private fun analysed(
        entry: CorpusEntry,
        group: String,
        analysis: LearnedAnalysis.Analysed,
        layout: FaceLayout,
        width: Int,
        height: Int,
        millis: StageMillis
    ): Measured {
        val accepted = analysis.accepted
        val detected = accepted.map { ArrowCorpusRuns.record(it.candidate) }
        val entry = entry.truthInView(CorpusPhotos.values(analysis.registration.imageToTarget).toDoubleArray())
        val match = ShotMatching.match(entry, detected)
        val outcome = EntryOutcome(entry, match, detected)

        // Step 1.
        val foundAndAccepted = match.pairs.associate { pair -> pair.truthIndex to (accepted[pair.detectedIndex] to true) }

        // Step 2: the peaks on the face the finder did not accept -- kept but over
        // the spot cap, or beyond expectedShots by value -- placed like step 6 would.
        val notAccepted = analysis.onFace.filter { f -> accepted.none { it === f } } +
            analysis.peaks.drop(analysis.kept.size).mapNotNull { peak ->
                val located = SpotMapping.locate(FaceWarp.targetOf(Vec2(peak.u, peak.v), model.meta.inputSize), layout)
                located?.let { LearnedFind(peak, Candidate(it.faceIndex, it.local, peak.value)) }
            }
        val remainingIndices = match.unmatchedTruth
        val remainingEntry = entry.copy(shots = remainingIndices.map { entry.shots[it] })
        val remainingMatch = ShotMatching.match(remainingEntry, notAccepted.map { ArrowCorpusRuns.record(it.candidate) })
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
                rank = hit?.let { (find, _) -> analysis.peaks.indexOfFirst { it === find.peak } + 1 },
                confidence = hit?.first?.peak?.value,
                lineOffset = null,
                accepted = hit?.second ?: false
            )
        }

        // Step 3.
        val falseFinds = match.unmatchedDetected.map { accepted[it] } +
            remainingMatch.unmatchedDetected.map { notAccepted[it] }
        val reference = entry.registration?.imageToTarget
        val row = ArrowRow(
            imageName = entry.imageName,
            group = group,
            angleDegrees = entry.capture?.angleDegrees,
            outcome = ArrowRow.REGISTERED,
            detail = null,
            footPointFromCentre = null,
            registrationError = reference?.let {
                RegistrationError.between(it, CorpusPhotos.values(analysis.registration.imageToTarget), width, height)?.max
            },
            footPointShift = null,
            largestLineOffset = null,
            commonPointFromFoot = null,
            listed = entry.shots.size,
            unresolved = entry.unresolvedArrows,
            candidates = analysis.peaks.size,
            accepted = accepted.size,
            matched = match.pairs.size,
            falsePositives = Metrics.over(listOf(outcome)).falsePositives,
            selection = analysis.reason.name,
            largestPositionError = match.pairs.mapNotNull { it.distance }.maxOrNull(),
            noShaft = 0,
            ranOut = 0,
            millis = millis
        )
        return Measured(
            row, truths,
            PhotoDiagnosis(entry.imageName, group, falseFinds.maxOfOrNull { it.peak.value }, 0, falseFinds.size),
            outcome
        )
    }
}
