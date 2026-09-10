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

/**
 * Ground truth read straight out of a file name.
 *
 * The scheme comes from the 2017 prototype branch
 * `feature/249_auto_detect_arrows` and looks like
 *
 *     a<arrow count>_<one character per score>[_<tag>].<extension>
 *
 * so `a6_x99765_noise.jpg` is six arrows scoring X, 9, 9, 7, 6, 5 on a noisy
 * photograph. The scheme carries no positions and no target model; both have to
 * come from a sidecar if they are wanted.
 */
object FilenameTruth {

    private val PATTERN = Regex(
        "^a(\\d+)_([A-Za-z0-9]+?)(?:_([A-Za-z0-9_]+))?\\.[A-Za-z0-9]+$"
    )

    /** Null when [fileName] does not follow the scheme, or contradicts itself. */
    fun parse(fileName: String): CorpusEntry? {
        val match = PATTERN.matchEntire(fileName) ?: return null

        val declaredCount = match.groupValues[1].toIntOrNull() ?: return null
        if (declaredCount <= 0) return null

        val scoreText = match.groupValues[2]
        if (scoreText.length != declaredCount) return null

        val shots = scoreText.map { c ->
            val score = PrintedScore.parseFilenameChar(c) ?: return null
            TruthShot(printedScore = score)
        }

        return CorpusEntry(
            imageName = fileName,
            image = null,
            camera = null,
            capture = null,
            target = null,
            shotsPerEnd = declaredCount,
            shots = shots,
            unresolvedArrows = 0,
            registration = null
        )
    }

    /**
     * The difficulty marker the inherited scheme appends, such as "dark" or
     * "overlap", or null when there is none.
     *
     * It is not a capture condition in the sense the annotated photographs use,
     * so it does not become a [CorpusEntry] tag automatically; the loader
     * decides what to do with it.
     */
    fun tagOf(fileName: String): String? {
        val match = PATTERN.matchEntire(fileName) ?: return null
        val tag = match.groupValues[3]
        return tag.ifEmpty { null }
    }
}
