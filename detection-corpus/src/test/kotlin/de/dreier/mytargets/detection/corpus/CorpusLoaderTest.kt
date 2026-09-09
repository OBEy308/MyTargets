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

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CorpusLoaderTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun write(relative: String, content: String = "") {
        val file = File(folder.root, relative)
        file.parentFile.mkdirs()
        file.writeText(content)
    }

    @Test
    fun readsTruthFromFileNames() {
        write("a6_998877.jpg")
        write("a8_xxx99988_front.jpg")

        val result = CorpusLoader.load(folder.root)

        assertThat(result.entries.map { it.imageName })
            .containsExactly("a6_998877.jpg", "a8_xxx99988_front.jpg")
        assertThat(result.ignored).isEmpty()
    }

    @Test
    fun aSidecarWinsOverTheFileName() {
        write("a6_998877.jpg")
        write(
            "a6_998877.jpg.json",
            """{ "targetModel": "WAFull",
                 "shots": [ { "score": "X", "x": 0.0, "y": 0.0 } ] }"""
        )

        val result = CorpusLoader.load(folder.root)
        val entry = result.entries.single()

        assertThat(entry.expectedShots).isEqualTo(1)
        assertThat(entry.shots[0].score.text).isEqualTo("X")
        assertThat(entry.hasPositions).isTrue()
        assertThat(entry.targetModel).isEqualTo("WAFull")
    }

    @Test
    fun theDirectoryDefaultFillsAMissingTargetModel() {
        write("defaults.json", """{ "targetModel": "WA6Ring" }""")
        write("a6_998877.jpg")
        write("a6_x99976.jpg")
        write(
            "a6_x99976.jpg.json",
            """{ "targetModel": "WAFull", "shots": [ { "score": "X" } ] }"""
        )

        val entries = CorpusLoader.load(folder.root).entries.associateBy { it.imageName }

        // Taken from the directory default.
        assertThat(entries["a6_998877.jpg"]!!.targetModel).isEqualTo("WA6Ring")
        // The sidecar states its own and keeps it.
        assertThat(entries["a6_x99976.jpg"]!!.targetModel).isEqualTo("WAFull")
    }

    @Test
    fun unrecognisedFilesAreReportedRatherThanFatal() {
        write("a6_998877.jpg")
        write("holiday.jpg")
        write("notes.txt")

        val result = CorpusLoader.load(folder.root)

        assertThat(result.entries).hasSize(1)
        assertThat(result.ignored).containsExactly("holiday.jpg")
    }

    @Test
    fun subdirectoriesAreReadAndKeepTheirOwnDefaults() {
        write("defaults.json", """{ "targetModel": "WAFull" }""")
        write("a6_998877.jpg")
        write("inherited-249/defaults.json", """{ "targetModel": "WA6Ring" }""")
        write("inherited-249/a6_x99976.jpg")

        val entries = CorpusLoader.load(folder.root).entries.associateBy { it.imageName }

        assertThat(entries).hasSize(2)
        assertThat(entries["a6_998877.jpg"]!!.targetModel).isEqualTo("WAFull")
        assertThat(entries["a6_x99976.jpg"]!!.targetModel).isEqualTo("WA6Ring")
    }

    @Test
    fun aBrokenSidecarNamesItsFile() {
        write("a6_998877.jpg")
        write("a6_998877.jpg.json", "{ not json")

        val error = runCatching { CorpusLoader.load(folder.root) }.exceptionOrNull()
        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(error!!).hasMessageThat().contains("a6_998877.jpg")
    }

    @Test
    fun aMissingRootYieldsAnEmptyResultRatherThanAnError() {
        val absent = File(folder.root, "nope")
        val result = CorpusLoader.load(absent)
        assertThat(result.entries).isEmpty()
        assertThat(result.ignored).isEmpty()
    }

    @Test
    fun entriesComeBackInAStableOrder() {
        write("a6_x99976.jpg")
        write("a6_998877.jpg")
        write("a8_xxx99988_front.jpg")

        val names = CorpusLoader.load(folder.root).entries.map { it.imageName }
        assertThat(names).isInOrder()
    }
}
