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

package de.dreier.mytargets.detection.registration

import com.google.common.truth.Truth.assertWithMessage
import de.dreier.mytargets.detection.DetectionFailure
import de.dreier.mytargets.detection.FaceLayout
import de.dreier.mytargets.detection.corpus.CorpusEntry
import de.dreier.mytargets.detection.corpus.CorpusLoader
import de.dreier.mytargets.detection.metrics.Homography
import de.dreier.mytargets.detection.metrics.RegistrationError
import de.dreier.mytargets.detection.metrics.RegistrationReport
import de.dreier.mytargets.detection.metrics.RegistrationRow
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.opencv.imgcodecs.Imgcodecs
import java.io.File

/**
 * Registers every photograph of the corpus and writes the report and the stage
 * images (registration design, "Korpuslauf, Bericht und Debug-Bilder"). It
 * measures and does not judge: the only hard check is that the photograph
 * with three faces is a mismatch, which the Haupt-Spec requires.
 */
class RegistrationCorpusRun {

    @get:Rule
    val openCv = OpenCvRule()

    private lateinit var root: File
    private lateinit var reportDir: File

    private val registrar = OpenCvFaceRegistrar()
    private val request = RegistrationRequest(FaceLayout.singleSpot(), RingTransitions.WA_FULL)

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
    fun registersTheCorpusAndWritesTheReport() {
        val entries = CorpusLoader.load(root).entries
        val files = CorpusPhotos.filesByName(root, entries)
        val imagesDir = File(reportDir, "registration")
        imagesDir.deleteRecursively()
        imagesDir.mkdirs()

        val rows = ArrayList<RegistrationRow>()
        val outcomes = HashMap<String, RegistrationOutcome>()
        for (entry in entries) {
            val file = CorpusPhotos.imageFileOf(root, entry.imageName, files[entry.imageName].orEmpty())
            val image = Imgcodecs.imread(file.absolutePath)
            try {
                check(!image.empty()) { "${entry.imageName}: cannot be decoded" }
                CorpusPhotos.checkDecodedSize(entry, image)
                val folder = File(imagesDir, file.nameWithoutExtension).apply { mkdirs() }
                val outcome = registrar.register(image, request, PngDebugSink(folder))
                outcomes[entry.imageName] = outcome
                val reference = referenceFor(entry)
                if (outcome is RegistrationOutcome.Registered && reference != null) {
                    CorpusPhotos.writeRectified(
                        image, outcome.imageToTarget, reference, entry.imageName, File(folder, "4-entzerrt.png")
                    )
                }
                rows += rowFor(entry, outcome, reference, image.cols(), image.rows())
            } finally {
                image.release()
            }
        }

        val report = File(reportDir, "registration.md")
        report.writeText(RegistrationReport.render(rows, "Registration against the corpus"))
        println("Registration report: ${report.absolutePath}")

        // Checked after writing, so a failing run still leaves its report.
        val multiple = outcomes[MULTIPLE_TARGETS] as? RegistrationOutcome.Failed
        assertWithMessage("$MULTIPLE_TARGETS shows three faces and must be a mismatch")
            .that(multiple?.failure).isEqualTo(DetectionFailure.FACE_MISMATCH)
    }

    /**
     * The reference in WAFull units. WA6Ring references are stored in WA6Ring
     * units, whose radius 1.0 lies at 0.6 of the full face. The photograph
     * with three faces gets no error value: a mismatch is its correct result.
     */
    private fun referenceFor(entry: CorpusEntry): List<Double>? {
        if (entry.imageName == MULTIPLE_TARGETS) return null
        val reference = entry.registration?.imageToTarget ?: return null
        return if (entry.target?.model == WA6RING) {
            Homography.scaleTarget(reference, WA6RING_IN_WAFULL_UNITS)
        } else {
            reference
        }
    }

    private fun rowFor(
        entry: CorpusEntry,
        outcome: RegistrationOutcome,
        reference: List<Double>?,
        width: Int,
        height: Int
    ): RegistrationRow = when (outcome) {
        is RegistrationOutcome.Registered -> RegistrationRow(
            imageName = entry.imageName,
            outOfScope = entry.outOfScope,
            outcome = RegistrationRow.REGISTERED,
            detail = null,
            ringsUsed = outcome.rings.map { it.radius },
            worstRingRms = outcome.rings.maxOfOrNull { it.radialRms },
            error = reference?.let {
                RegistrationError.between(it, CorpusPhotos.values(outcome.imageToTarget), width, height)
            }
        )
        is RegistrationOutcome.Failed -> RegistrationRow(
            entry.imageName, entry.outOfScope, outcome.failure.name, outcome.detail,
            emptyList(), null, null
        )
    }

    private companion object {
        const val MULTIPLE_TARGETS = "a6_x99999_multiple_targets.jpg"
        const val WA6RING = "WA6Ring"
        const val WA6RING_IN_WAFULL_UNITS = 0.6
    }
}
