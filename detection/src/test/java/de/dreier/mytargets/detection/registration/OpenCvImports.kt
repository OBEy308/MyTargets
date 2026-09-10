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
 * Android AAR lacks (org.opencv.highgui). Only these two packages are safe on
 * both.
 */
object OpenCvImports {

    private val allowed = listOf("org.opencv.core.", "org.opencv.imgproc.")
    private val importLine =
        Regex("""^\s*import\s+(org\.opencv\.[A-Za-z0-9_.]*)""", RegexOption.MULTILINE)

    /** "file: imported name" for every import outside the allowed packages. */
    fun forbidden(sources: Map<String, String>): List<String> =
        sources.flatMap { (name, text) ->
            importLine.findAll(text)
                .map { it.groupValues[1] }
                .filter { imported -> allowed.none { imported.startsWith(it) } }
                .map { "$name: $it" }
                .toList()
        }.sorted()
}
