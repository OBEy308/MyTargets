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

/**
 * The main code compiles against the desktop jar, which carries modules the
 * Android AAR lacks (org.opencv.highgui). core and imgproc are safe on both
 * everywhere; dnn is in the AAR too (design 3d, decision 3) and only the
 * learned finder under arrows/ may use it.
 */
object OpenCvImports {

    private val allowedEverywhere = listOf("org.opencv.core.", "org.opencv.imgproc.")
    private val allowedUnderArrows = listOf("org.opencv.dnn.")
    private val importLine =
        Regex("""^\s*import\s+(org\.opencv\.[A-Za-z0-9_.]*)""", RegexOption.MULTILINE)

    /** "file: imported name" for every import outside the packages allowed for that file. */
    fun forbidden(sources: Map<String, String>): List<String> =
        sources.flatMap { (name, text) ->
            // The sweep over the main sources passes paths relative to src/main/java, so
            // arrows/ sits in the middle of them; the unit tests pass it as the first segment.
            val path = name.replace('\\', '/')
            val underArrows = path.startsWith("arrows/") || path.contains("/arrows/")
            importLine.findAll(text)
                .map { it.groupValues[1] }
                .filter { imported ->
                    allowedEverywhere.none { imported.startsWith(it) } &&
                        !(underArrows && allowedUnderArrows.any { imported.startsWith(it) })
                }
                .map { "$name: $it" }
                .toList()
        }.sorted()
}
