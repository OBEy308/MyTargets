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
import de.dreier.mytargets.detection.DetectionRequest
import de.dreier.mytargets.detection.FaceLayout
import de.dreier.mytargets.detection.corpus.CorpusEntry
import de.dreier.mytargets.detection.corpus.CorpusLoader
import de.dreier.mytargets.detection.corpus.SpotPosition
import de.dreier.mytargets.detection.geometry.CameraIntrinsics
import de.dreier.mytargets.detection.geometry.CommonPoint
import de.dreier.mytargets.detection.geometry.FootPoint
import de.dreier.mytargets.detection.geometry.Mat3
import de.dreier.mytargets.detection.geometry.Vec2
import de.dreier.mytargets.detection.metrics.ArrowGroups
import de.dreier.mytargets.detection.metrics.ArrowReport
import de.dreier.mytargets.detection.metrics.ArrowRow
import de.dreier.mytargets.detection.metrics.DetectedShotRecord
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
import de.dreier.mytargets.detection.registration.RingTransitions
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Finds the arrows in every annotated photograph in scope and writes the
 * report and the stage images (arrow design, Korpuslauf und Bericht). It
 * measures and does not judge until the bounds are pinned.
 */
class ArrowCorpusRun {

    @get:Rule
    val openCv = OpenCvRule()

    private lateinit var root: File
    private lateinit var reportDir: File

    private val detector = OpenCvArrowDetector()

    @Before
    fun requireCorpus() {
        val configured = System.getProperty("detection.corpus.dir")
        assumeTrue("DETECTION_CORPUS_DIR is not configured", configured != null)
        root = File(configured!!)
        assumeTrue("corpus directory does not exist: $configured", root.isDirectory)
        reportDir = File(
            checkNotNull(System.getProperty("detection.report.dir")) {
                "detection.report.dir is not set; run this through testDevDebugUnitTest"
            }
        )
    }

    @Test
    fun findsTheArrowsAndWritesTheReport() {
        val entries = CorpusLoader.load(root).entries
        val files = CorpusPhotos.filesByName(root, entries)
        val imagesDir = File(reportDir, "arrows")
        imagesDir.deleteRecursively()
        imagesDir.mkdirs()

        val rows = ArrayList<ArrowRow>()
        val truths = ArrayList<TruthDiagnosis>()
        val photos = ArrayList<PhotoDiagnosis>()
        val outcomes = ArrayList<EntryOutcome>()
        val outOfScope = ArrayList<Pair<String, String>>()

        for (entry in entries) {
            val reason = entry.outOfScope
            if (reason != null) {
                outOfScope += entry.imageName to reason
                continue
            }
            if (!entry.isAnnotated) continue
            val file = CorpusPhotos.imageFileOf(root, entry.imageName, files[entry.imageName].orEmpty())
            val image = Imgcodecs.imread(file.absolutePath)
            try {
                check(!image.empty()) { "${entry.imageName}: cannot be decoded" }
                CorpusPhotos.checkDecodedSize(entry, image)
                val folder = File(imagesDir, file.nameWithoutExtension).apply { mkdirs() }
                val request = requestFor(entry, image.cols(), image.rows())
                val analysis = detector.analyse(image, request, PngDebugSink(folder))
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
                            File(folder, "4-entzerrt.png")
                        )
                    }
                    writeTruth(image, analysis, measured.outcome, File(folder, "7-wahrheit.png"))
                }
            } finally {
                image.release()
            }
        }

        val report = File(reportDir, "arrows.md")
        report.writeText(ArrowReport.render("Arrows against the corpus", rows, truths, photos, outcomes, outOfScope))
        println("Arrow report: ${report.absolutePath}")
    }

    private class Measured(
        val row: ArrowRow,
        val truths: List<TruthDiagnosis>,
        val photo: PhotoDiagnosis,
        val outcome: EntryOutcome
    )

    /** Arrow design, Korpuslauf: the size of the end, not the number of listed hits; the fallback focal length without EXIF. */
    private fun requestFor(entry: CorpusEntry, width: Int, height: Int): DetectionRequest {
        val focal = entry.camera?.focalLength35mm
        val intrinsics = if (focal != null) {
            CameraIntrinsics.from35mmEquivalent(width, height, focal)
        } else {
            CameraIntrinsics.approximate(width, height)
        }
        return DetectionRequest(
            FaceLayout.singleSpot(), WaFullZones.RADII, RingTransitions.WA_FULL,
            entry.shotsPerEnd ?: entry.shots.size, intrinsics
        )
    }

    /** A find as the metrics take it, its ring value from the pure radius like the sidecars'. */
    private fun record(c: Candidate) = DetectedShotRecord(
        scoringRing = WaFullZones.zoneOf(c.local.length),
        printedScore = null,
        position = SpotPosition(c.faceIndex, c.local.x, c.local.y),
        confidence = c.confidence
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
        val detected = accepted.map { record(it) }
        val match = ShotMatching.match(entry, detected)
        val outcome = EntryOutcome(entry, match, detected)

        // The matching of the metrics over every candidate on the face: what the
        // search had before the selection. Ranks count all candidates by score.
        val located = analysis.candidates.withIndex().filter { it.value.located != null }
        val all = ShotMatching.match(entry, located.map { record(it.value.located!!) })
        val byTruth = all.pairs.associateBy { it.truthIndex }
        val truths = entry.shots.indices.map { t ->
            val hit = byTruth[t]?.let { located[it.detectedIndex] }
            TruthDiagnosis(
                imageName = entry.imageName,
                group = group,
                truthIndex = t,
                rank = hit?.let { it.index + 1 },
                confidence = hit?.value?.confidence,
                lineOffset = hit?.value?.offsetFromFoot,
                accepted = hit != null && accepted.any { it === hit.value.located }
            )
        }
        val matchedCandidates = all.pairs.map { located[it.detectedIndex].value }
        val bestFalse = all.unmatchedDetected.maxOfOrNull { located[it].value.confidence }
        val listedEntries = entry.shots.mapNotNull { shot -> shot.position?.let { Vec2(it.x, it.y) } }
        val falseCandidates = all.unmatchedDetected.map { located[it].value }
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
                RegistrationError.between(it, CorpusPhotos.values(analysis.registration.imageToTarget), width, height)?.max
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
     * Stage image 7: the rectified face with every listed hit and its budget as
     * a cyan circle, every accepted find as a green dot, and matched pairs joined
     * in white. Only the run knows the truth.
     */
    private fun writeTruth(image: Mat, analysis: ArrowAnalysis.Analysed, outcome: EntryOutcome, file: File) {
        val imageToTarget = analysis.registration.imageToTarget
        val edge = FaceWarp.edgeFor(imageToTarget)
        val warped = FaceWarp.warp(image, imageToTarget, edge)
        try {
            val k = edge / (2.0 * FaceWarp.EXTENT)
            for (shot in outcome.entry.shots) {
                val position = shot.position ?: continue
                val budget = ShotMatching.DEFAULT_POSITION_TOLERANCE + (shot.positionTolerance ?: 0.0)
                Imgproc.circle(warped, point(position, edge), (budget * k).roundToInt(), CYAN, 2)
            }
            for (found in outcome.detected) {
                Imgproc.circle(warped, point(found.position, edge), 6, GREEN, Imgproc.FILLED)
            }
            for (pair in outcome.match.pairs) {
                val truth = outcome.entry.shots[pair.truthIndex].position ?: continue
                Imgproc.line(
                    warped, point(truth, edge), point(outcome.detected[pair.detectedIndex].position, edge), WHITE, 2
                )
            }
            check(Imgcodecs.imwrite(file.absolutePath, warped)) { "cannot write $file" }
        } finally {
            warped.release()
        }
    }

    /** On WAFull, spot-local and target coordinates coincide. */
    private fun point(p: SpotPosition, edge: Int): Point {
        val px = FaceWarp.pixelOf(Vec2(p.x, p.y), edge)
        return Point(px.x, px.y)
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
        val CYAN = Scalar(255.0, 255.0, 0.0)
        val GREEN = Scalar(0.0, 200.0, 0.0)
        val WHITE = Scalar(255.0, 255.0, 255.0)

        /** How close a candidate's line passes a hit for the candidate to count as a piece of its shaft. */
        const val SHAFT_LINE_DISTANCE = 0.02
    }
}
