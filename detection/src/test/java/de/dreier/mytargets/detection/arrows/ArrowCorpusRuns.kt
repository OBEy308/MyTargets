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
import de.dreier.mytargets.detection.geometry.Mat3
import de.dreier.mytargets.detection.geometry.Vec2
import de.dreier.mytargets.detection.metrics.DetectedShotRecord
import de.dreier.mytargets.detection.metrics.EntryOutcome
import de.dreier.mytargets.detection.metrics.ShotMatching
import de.dreier.mytargets.detection.registration.CorpusPhotos
import de.dreier.mytargets.detection.registration.FaceWarp
import de.dreier.mytargets.detection.registration.RingTransitions
import org.junit.Assume.assumeTrue
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File
import kotlin.math.roundToInt

/**
 * What the classical and the learned arrow run share (design 3d, Messung und
 * Tests): where the corpus and the report are, the loop over the photographs
 * in scope, the request of a photograph, the record of a find, and stage
 * image 7. Nothing here knows either detector.
 */
object ArrowCorpusRuns {

    class Dirs(val root: File, val reportDir: File)

    /** One decoded photograph; [image] is valid only while the action of [forEachInScope] runs and is released right after. */
    class Photograph(val entry: CorpusEntry, val image: Mat, val folder: File)

    /** Skips the calling test without DETECTION_CORPUS_DIR; the report dir comes from testDevDebugUnitTest. */
    fun dirs(): Dirs {
        val configured = System.getProperty("detection.corpus.dir")
        assumeTrue("DETECTION_CORPUS_DIR is not configured", configured != null)
        val root = File(configured!!)
        assumeTrue("corpus directory does not exist: $configured", root.isDirectory)
        val reportDir = File(
            checkNotNull(System.getProperty("detection.report.dir")) {
                "detection.report.dir is not set; run this through testDevDebugUnitTest"
            }
        )
        return Dirs(root, reportDir)
    }

    /**
     * Decodes every annotated photograph in scope, hands it to [action] with a
     * fresh folder <reportDir>/[imagesSubdir]/<name>/ for its stage images, and
     * releases it. Returns the photographs out of scope with their reasons.
     */
    fun forEachInScope(dirs: Dirs, imagesSubdir: String, action: (Photograph) -> Unit): List<Pair<String, String>> {
        val entries = CorpusLoader.load(dirs.root).entries
        val files = CorpusPhotos.filesByName(dirs.root, entries)
        val imagesDir = File(dirs.reportDir, imagesSubdir)
        imagesDir.deleteRecursively()
        imagesDir.mkdirs()
        val outOfScope = ArrayList<Pair<String, String>>()
        for (entry in entries) {
            val reason = entry.outOfScope
            if (reason != null) {
                outOfScope += entry.imageName to reason
                continue
            }
            if (!entry.isAnnotated) continue
            val file = CorpusPhotos.imageFileOf(dirs.root, entry.imageName, files[entry.imageName].orEmpty())
            val image = Imgcodecs.imread(file.absolutePath)
            try {
                check(!image.empty()) { "${entry.imageName}: cannot be decoded" }
                CorpusPhotos.checkDecodedSize(entry, image)
                val folder = File(imagesDir, file.nameWithoutExtension).apply { mkdirs() }
                action(Photograph(entry, image, folder))
            } finally {
                image.release()
            }
        }
        return outOfScope
    }

    /** Arrow design, Korpuslauf: the size of the end, not the number of listed hits; the fallback focal length without EXIF. */
    fun requestFor(entry: CorpusEntry, width: Int, height: Int): DetectionRequest {
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

    /** A find as the metrics take it, its ring value from the pure radius like the sidecars'. */
    fun record(c: Candidate) = DetectedShotRecord(
        scoringRing = WaFullZones.zoneOf(c.local.length),
        printedScore = null,
        position = SpotPosition(c.faceIndex, c.local.x, c.local.y),
        confidence = c.confidence
    )

    /**
     * Stage image 7: the rectified face with every listed hit and its budget as
     * a cyan circle, every accepted find as a green dot, and matched pairs joined
     * in white. Only the run knows the truth.
     */
    fun writeTruth(image: Mat, imageToTarget: Mat3, outcome: EntryOutcome, file: File) {
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

    private val CYAN = Scalar(255.0, 255.0, 0.0)
    private val GREEN = Scalar(0.0, 200.0, 0.0)
    private val WHITE = Scalar(255.0, 255.0, 255.0)
}
