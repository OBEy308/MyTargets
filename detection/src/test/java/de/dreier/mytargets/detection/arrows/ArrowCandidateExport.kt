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

import de.dreier.mytargets.detection.DebugSink
import de.dreier.mytargets.detection.DetectionRequest
import de.dreier.mytargets.detection.FaceLayout
import de.dreier.mytargets.detection.corpus.CorpusEntry
import de.dreier.mytargets.detection.corpus.CorpusLoader
import de.dreier.mytargets.detection.geometry.CameraIntrinsics
import de.dreier.mytargets.detection.registration.CorpusPhotos
import de.dreier.mytargets.detection.registration.OpenCvRule
import de.dreier.mytargets.detection.registration.RingTransitions
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.opencv.imgcodecs.Imgcodecs
import java.io.File
import java.util.Locale

/**
 * Writes every candidate the pipeline produces for every annotated
 * photograph, before and after the selection, to `candidates.json` in the
 * report dir -- the raw material for a learned candidate scorer (the PoC in
 * the corpus's `learn/` folder). No truth is attached here on purpose: the
 * PoC matches against the per-view tips of the corpus, which this module's
 * truth does not carry yet.
 *
 * Runs through `testDevDebugUnitTest --tests "*ArrowCandidateExport*"`, like
 * [ArrowCorpusRun], and is skipped without a corpus.
 */
class ArrowCandidateExport {

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
    fun writesEveryCandidate() {
        val entries = CorpusLoader.load(root).entries
        val files = CorpusPhotos.filesByName(root, entries)
        val photos = ArrayList<String>()

        for (entry in entries) {
            if (entry.outOfScope != null || !entry.isAnnotated) continue
            val file = CorpusPhotos.imageFileOf(root, entry.imageName, files[entry.imageName].orEmpty())
            val image = Imgcodecs.imread(file.absolutePath)
            try {
                check(!image.empty()) { "${entry.imageName}: cannot be decoded" }
                CorpusPhotos.checkDecodedSize(entry, image)
                val analysis = detector.analyse(image, requestFor(entry, image.cols(), image.rows()), DebugSink.NONE)
                photos += photoJson(entry, analysis)
            } finally {
                image.release()
            }
        }

        reportDir.mkdirs()
        val out = File(reportDir, "candidates.json")
        out.writeText(photos.joinToString(",\n", "[\n", "\n]\n"))
        println("Candidate export: ${out.absolutePath} (${photos.size} photographs)")
    }

    private fun requestFor(entry: CorpusEntry, width: Int, height: Int): DetectionRequest {
        val focal = entry.camera?.focalLength35mm
        val intrinsics = if (focal != null) {
            CameraIntrinsics.from35mmEquivalent(width, height, focal)
        } else {
            CameraIntrinsics.approximate(width, height)
        }
        return DetectionRequest(
            FaceLayout.singleSpot(), WaFullZones.RADII, RingTransitions.WA_FULL,
            checkNotNull(entry.shotsPerEnd) { "${entry.imageName}: the sidecar names no shotsPerEnd" },
            intrinsics
        )
    }

    private fun photoJson(entry: CorpusEntry, analysis: ArrowAnalysis): String {
        val head = """  {"image": ${str(entry.imageName)}, "angle": ${str(entry.capture?.angle)}, """ +
            """"lighting": ${str(entry.capture?.lighting)}, "shotsPerEnd": ${entry.shotsPerEnd}, """ +
            """"listed": ${entry.shots.size}, """
        return when (analysis) {
            is ArrowAnalysis.NotRegistered ->
                head + """"registered": false, "failure": ${str(analysis.registration.failure.name)}, "candidates": []}"""
            is ArrowAnalysis.Analysed -> {
                val accepted = analysis.selection.accepted
                val foot = analysis.footPoint
                val h = analysis.registration.imageToTarget
                val candidates = analysis.candidates.joinToString(",\n", "[\n", "\n   ]") { c ->
                    val isAccepted = c.located != null && accepted.any { it === c.located }
                    "    {" + listOf(
                        "\"tip\": [${num(c.tip.x)}, ${num(c.tip.y)}]",
                        "\"far\": [${num(c.far.x)}, ${num(c.far.y)}]",
                        "\"contrast\": ${num(c.contrast)}",
                        "\"shaftContrast\": ${num(c.shaftContrast)}",
                        "\"score\": ${num(c.score)}",
                        "\"refinement\": ${str(c.refinement.name)}",
                        "\"offsetFromFoot\": ${num(c.offsetFromFoot)}",
                        "\"confidence\": ${num(c.confidence)}",
                        "\"located\": ${c.located != null}",
                        "\"accepted\": $isAccepted"
                    ).joinToString(", ") + "}"
                }
                head + """"registered": true, "selection": ${str(analysis.selection.reason.name)}, """ +
                    """"foot": ${if (foot == null) "null" else "[${num(foot.x)}, ${num(foot.y)}]"}, """ +
                    """"imageToTarget": ${CorpusPhotos.values(h).joinToString(", ", "[", "]") { num(it) }}, """ +
                    "\"candidates\": $candidates}"
            }
        }
    }

    private fun str(s: String?) = if (s == null) "null" else "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun num(d: Double) = String.format(Locale.ROOT, "%.6f", d)
}
