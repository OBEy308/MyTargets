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

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

class OpenCvImportRuleTest {

    @Test
    fun reportsAnImportOutsideCoreAndImgproc() {
        val sources = mapOf(
            "Ok.kt" to "import org.opencv.core.Mat\nimport org.opencv.imgproc.Imgproc\n",
            "Bad.kt" to "import org.opencv.core.Mat\nimport org.opencv.highgui.HighGui\n"
        )

        assertThat(OpenCvImports.forbidden(sources))
            .containsExactly("Bad.kt: org.opencv.highgui.HighGui")
    }

    @Test
    fun aWildcardImportOfCoreIsAllowed() {
        assertThat(OpenCvImports.forbidden(mapOf("W.kt" to "import org.opencv.core.*\n")))
            .isEmpty()
    }

    @Test
    fun theMainSourcesImportOnlyCoreAndImgproc() {
        // Gradle runs unit tests with the module directory as working directory.
        val root = File("src/main/java")
        assertThat(root.isDirectory).isTrue()
        val sources = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .associate { it.relativeTo(root).path to it.readText() }
        assertThat(sources).isNotEmpty()

        assertThat(OpenCvImports.forbidden(sources)).isEmpty()
    }
}
