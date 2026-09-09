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
import com.google.gson.JsonSyntaxException

/**
 * Ground truth read from a JSON sidecar next to the image.
 *
 * The format is deliberately flat, because it is written by hand while
 * annotating photographs:
 *
 *     {
 *       "targetModel": "WAFull",
 *       "tags": ["oblique"],
 *       "shots": [
 *         { "score": "X", "faceIndex": 0, "x": 0.031, "y": -0.017 },
 *         { "score": "M" }
 *       ]
 *     }
 *
 * Coordinates are spot local, so the spot centre is the origin and its
 * outermost ring has radius one. `faceIndex`, `x` and `y` belong together:
 * either all three or none, with `faceIndex` defaulting to 0 for a single spot
 * face.
 */
object SidecarTruth {

    private val gson = Gson()

    private class ShotJson {
        var score: String? = null
        var faceIndex: Int? = null
        var x: Double? = null
        var y: Double? = null
    }

    private class EntryJson {
        var targetModel: String? = null
        var tags: List<String>? = null
        var shots: List<ShotJson>? = null
    }

    private class DefaultsJson {
        var targetModel: String? = null
    }

    /**
     * @throws IllegalArgumentException with the image name in the message, so a
     *         corpus of a hundred photographs still says which one is broken.
     */
    fun parse(imageName: String, json: String): CorpusEntry {
        val parsed = try {
            gson.fromJson(json, EntryJson::class.java)
        } catch (e: JsonSyntaxException) {
            throw IllegalArgumentException("$imageName: malformed JSON sidecar", e)
        } ?: throw IllegalArgumentException("$imageName: empty JSON sidecar")

        val shotsJson = parsed.shots
            ?: throw IllegalArgumentException("$imageName: sidecar has no shots")
        require(shotsJson.isNotEmpty()) { "$imageName: sidecar has an empty shot list" }

        val shots = shotsJson.mapIndexed { index, shot ->
            val scoreText = shot.score
                ?: throw IllegalArgumentException("$imageName: shot $index has no score")
            require(scoreText.isNotBlank()) {
                "$imageName: shot $index has a blank score"
            }

            val hasX = shot.x != null
            val hasY = shot.y != null
            require(hasX == hasY) {
                "$imageName: shot $index gives only one of x and y; " +
                    "a position needs both"
            }

            val position = if (hasX) {
                SpotPosition(shot.faceIndex ?: 0, shot.x!!, shot.y!!)
            } else {
                require(shot.faceIndex == null) {
                    "$imageName: shot $index gives faceIndex without x and y"
                }
                null
            }

            TruthShot(Score.of(scoreText), position)
        }

        return CorpusEntry(
            imageName = imageName,
            targetModel = parsed.targetModel,
            shots = shots,
            tags = parsed.tags?.toSet() ?: emptySet()
        )
    }

    /** The `targetModel` from a directory level defaults file, or null. */
    fun defaultsTargetModel(json: String): String? = try {
        gson.fromJson(json, DefaultsJson::class.java)?.targetModel
    } catch (e: JsonSyntaxException) {
        throw IllegalArgumentException("malformed defaults.json", e)
    }
}
