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

import java.io.File

/**
 * Reads a corpus directory into entries.
 *
 * A photograph gets its truth from a sidecar `<image>.json` when one exists,
 * and otherwise from the inherited file name scheme. Anything neither can read
 * is reported in [LoadResult.ignored] rather than aborting the run: a corpus
 * grows by dropping photographs into a folder, and one holiday snap should not
 * stop a measurement.
 *
 * A broken sidecar IS fatal, because it means someone tried to state the truth
 * and got it wrong. Silently ignoring that would quietly drop an annotated
 * photograph out of the metrics.
 */
object CorpusLoader {

    private const val DEFAULTS_FILE = "defaults.json"
    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png")

    class LoadResult(val entries: List<CorpusEntry>, val ignored: List<String>)

    fun load(root: File): LoadResult {
        if (!root.isDirectory) {
            return LoadResult(emptyList(), emptyList())
        }
        val entries = mutableListOf<CorpusEntry>()
        val ignored = mutableListOf<String>()
        loadDirectory(root, entries, ignored)
        return LoadResult(
            entries.sortedBy { it.imageName },
            ignored.sorted()
        )
    }

    private fun loadDirectory(
        directory: File,
        entries: MutableList<CorpusEntry>,
        ignored: MutableList<String>
    ) {
        val children = directory.listFiles() ?: return

        val defaultsFile = File(directory, DEFAULTS_FILE)
        val defaultModel = if (defaultsFile.isFile) {
            SidecarTruth.defaultsTargetModel(directory.name, defaultsFile.readText())
        } else {
            null
        }

        for (child in children.sortedBy { it.name }) {
            if (child.isDirectory) {
                loadDirectory(child, entries, ignored)
                continue
            }
            if (!child.isImage()) {
                continue
            }

            val sidecar = File(directory, child.name + ".json")
            val entry = if (sidecar.isFile) {
                SidecarTruth.parse(child.name, sidecar.readText())
            } else {
                FilenameTruth.parse(child.name)
            }

            if (entry == null) {
                ignored.add(child.name)
            } else {
                entries.add(
                    if (entry.targetModel == null && defaultModel != null) {
                        entry.copy(targetModel = defaultModel)
                    } else {
                        entry
                    }
                )
            }
        }
    }

    private fun File.isImage(): Boolean =
        extension.lowercase() in IMAGE_EXTENSIONS
}
