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

package de.dreier.mytargets.detection.corpus

import com.google.gson.Gson

/**
 * Reads the ground truth of a photograph from its JSON sidecar.
 *
 * The schema is the corpus's own, documented in its README, which is the
 * binding authority for it. Unknown fields are ignored on purpose — the corpus
 * grows, and a field added next year must not break a measurement today — while
 * a missing required field is an error, because silently measuring against half
 * a truth is worse than stopping.
 */
object SidecarTruth {

    private val gson = Gson()

    private class ShotJson {
        var faceIndex: Int? = null
        var x: Double? = null
        var y: Double? = null
        var scoringRing: Int? = null
        var printedScore: String? = null
        var positionTolerance: Double? = null
        var nearRingBoundary: Boolean? = null
        var uncertain: Boolean? = null
    }

    private class ImageJson {
        var width: Int? = null
        var height: Int? = null
        var exifOrientation: Int? = null
    }

    private class CameraJson {
        var model: String? = null
        var focalLength35mm: Double? = null
    }

    private class CaptureJson {
        var lighting: String? = null
        var angle: String? = null
        var angleDegrees: Double? = null
    }

    private class TargetJson {
        var model: String? = null
        var diameterCm: Double? = null
        var faceCount: Int? = null
    }

    private class EndJson {
        var shotsPerEnd: Int? = null
    }

    private class AnnotationJson {
        var unresolvedArrows: Int? = null
    }

    private class RegistrationJson {
        var imageToTarget: List<List<Double>>? = null
        var imagedCentre: List<Double>? = null
    }

    private class SidecarJson {
        var image: ImageJson? = null
        var camera: CameraJson? = null
        var capture: CaptureJson? = null
        var target: TargetJson? = null
        var end: EndJson? = null
        var shots: List<ShotJson>? = null
        var annotation: AnnotationJson? = null
        var registration: RegistrationJson? = null
    }

    /**
     * @throws IllegalArgumentException with [imageName] in every message, so a
     *         corpus of a hundred photographs still says which one is broken.
     */
    fun parse(imageName: String, json: String): CorpusEntry {
        val parsed = try {
            gson.fromJson(json, SidecarJson::class.java)
        } catch (e: RuntimeException) {
            // Covers gson's JsonSyntaxException and the bare NumberFormatException
            // it throws for a hand typed decimal comma.
            throw IllegalArgumentException("$imageName: cannot read JSON sidecar", e)
        } ?: throw IllegalArgumentException("$imageName: empty JSON sidecar")

        val shotsJson = parsed.shots
            ?: throw IllegalArgumentException("$imageName: sidecar has no shots array")

        val shots = shotsJson.mapIndexed { index, shot -> readShot(imageName, index, shot) }

        return CorpusEntry(
            imageName = imageName,
            image = parsed.image?.let { readImage(imageName, it) },
            camera = parsed.camera?.let { CameraInfo(it.model, it.focalLength35mm) },
            capture = parsed.capture?.let { CaptureInfo(it.lighting, it.angle, it.angleDegrees) },
            target = parsed.target?.let { readTarget(imageName, it) },
            shotsPerEnd = parsed.end?.shotsPerEnd,
            shots = shots,
            unresolvedArrows = parsed.annotation?.unresolvedArrows ?: 0,
            registration = parsed.registration?.let { readRegistration(imageName, it) }
        )
    }

    private fun readShot(imageName: String, index: Int, shot: ShotJson): TruthShot {
        val hasX = shot.x != null
        val hasY = shot.y != null
        require(hasX == hasY) {
            "$imageName: shot $index gives only one of x and y; a position needs both"
        }
        require(shot.scoringRing != null) {
            "$imageName: shot $index has no scoringRing"
        }

        val position = if (hasX) SpotPosition(shot.faceIndex ?: 0, shot.x!!, shot.y!!) else null
        require(position != null || shot.faceIndex == null) {
            "$imageName: shot $index gives faceIndex without x and y"
        }

        return TruthShot(
            scoringRing = shot.scoringRing,
            // Optional. The annotated inherited photographs carry it so the
            // printed value can be checked against the file name; a detector
            // is matched on the zone index.
            printedScore = shot.printedScore?.let { PrintedScore.of(it) },
            position = position,
            positionTolerance = shot.positionTolerance,
            nearRingBoundary = shot.nearRingBoundary ?: false,
            uncertain = shot.uncertain ?: false
        )
    }

    private fun readImage(imageName: String, image: ImageJson): ImageInfo {
        val width = image.width
            ?: throw IllegalArgumentException("$imageName: image block has no width")
        val height = image.height
            ?: throw IllegalArgumentException("$imageName: image block has no height")
        return ImageInfo(imageName, width, height, image.exifOrientation)
    }

    private fun readTarget(imageName: String, target: TargetJson): TargetInfo {
        val model = target.model
            ?: throw IllegalArgumentException("$imageName: target block has no model")
        return TargetInfo(model, target.diameterCm, target.faceCount ?: 1)
    }

    private fun readRegistration(
        imageName: String,
        registration: RegistrationJson
    ): Registration? {
        val rows = registration.imageToTarget ?: return null
        require(rows.size == 3 && rows.all { it.size == 3 }) {
            "$imageName: imageToTarget must be three rows of three, got " +
                "${rows.size} rows of ${rows.map { it.size }}"
        }
        val centre = registration.imagedCentre
        require(centre == null || centre.size == 2) {
            "$imageName: imagedCentre must be two values, got ${centre?.size}"
        }
        return Registration(
            imageToTarget = rows.flatten(),
            imagedCentre = centre?.let { SpotPosition(0, it[0], it[1]) }
        )
    }
}
