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
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Reads the real corpus, when there is one.
 *
 * Skips itself when `DETECTION_CORPUS_DIR` is not set, so a fresh clone builds
 * green without a forty megabyte download. See BUILDING.md.
 */
class InheritedCorpusTest {

    private lateinit var root: File

    @Before
    fun requireCorpus() {
        val configured = System.getProperty("detection.corpus.dir")
        assumeTrue("DETECTION_CORPUS_DIR is not configured", configured != null)
        root = File(configured!!)
        assumeTrue("corpus directory does not exist: $configured", root.isDirectory)
    }

    @Test
    fun everyInheritedPhotographIsUnderstood() {
        val inherited = File(root, "inherited-249")
        assumeTrue("inherited-249 is not present", inherited.isDirectory)

        val result = CorpusLoader.load(inherited)

        assertThat(result.ignored).isEmpty()
        assertThat(result.entries).hasSize(16)
        assertThat(result.entries.all { it.expectedShots in 6..8 }).isTrue()
        assertThat(result.entries.none { it.hasPositions }).isTrue()
    }

    @Test
    fun theInheritedTagsAreTheExpectedOnes() {
        val inherited = File(root, "inherited-249")
        assumeTrue("inherited-249 is not present", inherited.isDirectory)

        val tags = CorpusLoader.load(inherited).entries.flatMap { it.tags }.toSet()

        assertThat(tags).containsExactly(
            "dark", "noise", "overlap", "front", "multiple_targets"
        )
    }

    @Test
    fun aKnownEntryHasTheScoresItsNamePromises() {
        val inherited = File(root, "inherited-249")
        assumeTrue("inherited-249 is not present", inherited.isDirectory)

        val entry = CorpusLoader.load(inherited).entries
            .single { it.imageName == "a8_xxx99988_front.jpg" }

        assertThat(entry.shots.map { it.score.text })
            .containsExactly("X", "X", "X", "9", "9", "9", "8", "8").inOrder()
        assertThat(entry.tags).containsExactly("front")
    }
}
